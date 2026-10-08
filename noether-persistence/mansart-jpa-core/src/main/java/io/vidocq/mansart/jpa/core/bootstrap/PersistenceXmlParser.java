/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.bootstrap;

import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * StAX parser of {@code META-INF/persistence.xml}, schemas 1.0 to 3.2 (Jakarta Persistence 3.2, §8.2). No JAXB, no
 * schema validation: the structure is checked while reading. DTDs and external entities are refused.
 */
public final class PersistenceXmlParser {

    private static final String SUN = "http://java.sun.com/xml/ns/persistence";
    private static final String JCP = "http://xmlns.jcp.org/xml/ns/persistence";
    private static final String JAKARTA = "https://jakarta.ee/xml/ns/persistence";
    private static final String RESOURCE = "META-INF/persistence.xml";

    private PersistenceXmlParser() {
    }

    /**
     * Parses one {@code persistence.xml}.
     *
     * @param rootUrl the root of the persistence units it declares, see {@link #rootOf(URL)}
     * @param classLoader the class loader of the units
     */
    public static List<PersistenceUnitDefinition> parse(InputStream in, URL rootUrl, ClassLoader classLoader) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                return read(reader, rootUrl, classLoader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new PersistenceException("Unable to parse persistence.xml at " + rootUrl + ": " + e.getMessage(), e);
        }
    }

    /** The root of a persistence unit: the jar, or the directory, that holds {@code META-INF/persistence.xml}. */
    public static URL rootOf(URL persistenceXml) {
        String external = persistenceXml.toExternalForm();
        try {
            if ("jar".equals(persistenceXml.getProtocol())) {
                int separator = external.indexOf("!/");
                return new java.net.URI(external.substring("jar:".length(), separator)).toURL();
            }
            if (external.endsWith(RESOURCE)) {
                return new java.net.URI(external.substring(0, external.length() - RESOURCE.length())).toURL();
            }
            return persistenceXml;
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new PersistenceException("Unable to compute the persistence unit root of " + persistenceXml, e);
        }
    }

    private static List<PersistenceUnitDefinition> read(XMLStreamReader r, URL rootUrl, ClassLoader classLoader)
            throws XMLStreamException {
        nextElement(r);
        if (!"persistence".equals(r.getLocalName())) {
            throw new PersistenceException("persistence.xml at " + rootUrl + " must have a <persistence> root element");
        }
        String version = checkVersion(r.getNamespaceURI(), r.getAttributeValue(null, "version"), rootUrl);
        List<PersistenceUnitDefinition> units = new ArrayList<>();
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.DTD) {
                throw new PersistenceException("A DTD is not allowed in persistence.xml");
            }
            if (event == XMLStreamConstants.START_ELEMENT && "persistence-unit".equals(r.getLocalName())) {
                units.add(readUnit(r, rootUrl, version, classLoader));
            }
        }
        return units;
    }

    private static void nextElement(XMLStreamReader r) throws XMLStreamException {
        while (r.hasNext() && r.getEventType() != XMLStreamConstants.START_ELEMENT) {
            if (r.next() == XMLStreamConstants.DTD) {
                throw new PersistenceException("A DTD is not allowed in persistence.xml");
            }
        }
    }

    private static String checkVersion(String namespace, String version, URL rootUrl) {
        String declared = version == null ? "" : version.strip();
        String expected = switch (declared) {
            case "1.0", "2.0" -> SUN;
            case "2.1", "2.2" -> JCP;
            case "3.0", "3.1", "3.2" -> JAKARTA;
            default -> throw new PersistenceException("Unsupported persistence.xml version '" + declared + "' at " + rootUrl);
        };
        if (!expected.equals(namespace)) {
            throw new PersistenceException("persistence.xml version " + declared + " at " + rootUrl
                + " does not match the namespace " + namespace);
        }
        return declared;
    }

    private static PersistenceUnitDefinition readUnit(XMLStreamReader r, URL rootUrl, String version, ClassLoader classLoader)
            throws XMLStreamException {
        String name = r.getAttributeValue(null, "name");
        if (name == null || name.isBlank()) {
            throw new PersistenceException("A persistence unit of persistence.xml at " + rootUrl + " has no name");
        }
        PersistenceUnitTransactionType transactionType = enumValue(PersistenceUnitTransactionType.class,
            r.getAttributeValue(null, "transaction-type"), PersistenceUnitTransactionType.RESOURCE_LOCAL, "transaction-type");
        String description = null;
        String provider = null;
        String jta = null;
        String nonJta = null;
        String scope = null;
        List<String> qualifiers = new ArrayList<>();
        List<String> mappingFiles = new ArrayList<>();
        List<URL> jarFiles = new ArrayList<>();
        List<String> classes = new ArrayList<>();
        boolean excludeUnlisted = false;
        SharedCacheMode cacheMode = SharedCacheMode.UNSPECIFIED;
        ValidationMode validationMode = ValidationMode.AUTO;
        Map<String, Object> properties = new LinkedHashMap<>();

        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.END_ELEMENT && "persistence-unit".equals(r.getLocalName())) {
                break;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "description" -> description = text(r);
                case "provider" -> provider = text(r);
                case "qualifier" -> qualifiers.add(text(r));
                case "scope" -> scope = text(r);
                case "jta-data-source" -> jta = text(r);
                case "non-jta-data-source" -> nonJta = text(r);
                case "mapping-file" -> mappingFiles.add(text(r));
                case "jar-file" -> jarFiles.add(resolve(rootUrl, text(r)));
                case "class" -> classes.add(text(r));
                // §8.2.1.6.1: an empty element means true.
                case "exclude-unlisted-classes" -> {
                    String value = text(r);
                    excludeUnlisted = value.isEmpty() || Boolean.parseBoolean(value);
                }
                case "shared-cache-mode" -> cacheMode = enumValue(SharedCacheMode.class, text(r), cacheMode, "shared-cache-mode");
                case "validation-mode" -> validationMode = enumValue(ValidationMode.class, text(r), validationMode, "validation-mode");
                case "property" -> {
                    String key = r.getAttributeValue(null, "name");
                    if (key == null) {
                        throw new PersistenceException("A <property> of persistence unit " + name + " has no name");
                    }
                    String value = r.getAttributeValue(null, "value");
                    properties.put(key, value == null ? "" : value);
                }
                default -> {
                    // <properties> and unknown elements carry nothing by themselves.
                }
            }
        }
        return new PersistenceUnitDefinition(name, description, provider, transactionType, jta, nonJta, mappingFiles, jarFiles,
            classes, excludeUnlisted, cacheMode, validationMode, properties, rootUrl, version, qualifiers, scope, classLoader);
    }

    private static String text(XMLStreamReader r) throws XMLStreamException {
        return r.getElementText().strip();
    }

    private static URL resolve(URL rootUrl, String path) {
        try {
            return rootUrl == null ? new java.net.URI(path).toURL() : rootUrl.toURI().resolve(path).toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new PersistenceException("Invalid <jar-file> " + path + " in persistence.xml at " + rootUrl, e);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback, String element) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.strip());
        } catch (IllegalArgumentException e) {
            throw new PersistenceException("Invalid " + element + " '" + value + "' in persistence.xml", e);
        }
    }
}
