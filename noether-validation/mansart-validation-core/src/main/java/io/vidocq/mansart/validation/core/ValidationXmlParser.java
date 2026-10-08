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
package io.vidocq.mansart.validation.core;

import jakarta.validation.ValidationException;
import jakarta.validation.executable.ExecutableType;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * StAX parser of {@code META-INF/validation.xml} (Jakarta Validation 3.1, chapter 8). No JAXB, no
 * schema validation: the structure is checked while reading. DTDs and external entities are refused.
 */
final class ValidationXmlParser {

    private static final String ROOT = "validation-config";
    private static final String NS_1_0 = "http://jboss.org/xml/ns/javax/validation/configuration";
    private static final String NS_JCP = "http://xmlns.jcp.org/xml/ns/validation/configuration";
    private static final String NS_JAKARTA = "https://jakarta.ee/xml/ns/validation/configuration";

    private ValidationXmlParser() {
    }

    /** The configuration used when no {@code validation.xml} exists: every spec default. */
    static XmlBootstrapConfiguration absent() {
        return new XmlBootstrapConfiguration(null, null, null, null, null, null, Set.of(), Set.of(), true,
            XmlBootstrapConfiguration.DEFAULT_EXECUTABLE_TYPES, Map.of());
    }

    static XmlBootstrapConfiguration parse(InputStream in) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            try {
                return read(r);
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            throw new ValidationException("Unable to parse validation.xml: " + e.getMessage(), e);
        }
    }

    private static XmlBootstrapConfiguration read(XMLStreamReader r) throws XMLStreamException {
        while (r.hasNext() && r.getEventType() != XMLStreamConstants.START_ELEMENT) {
            if (r.next() == XMLStreamConstants.DTD) {
                throw new ValidationException("A DTD is not allowed in validation.xml");
            }
        }
        if (r.getEventType() != XMLStreamConstants.START_ELEMENT || !ROOT.equals(r.getLocalName())) {
            throw new ValidationException("validation.xml must have a <" + ROOT + "> root element");
        }
        checkVersion(r.getNamespaceURI(), r.getAttributeValue(null, "version"));

        String provider = null;
        String validatorFactory = null;
        String interpolator = null;
        String resolver = null;
        String parameterNames = null;
        String clock = null;
        Set<String> extractors = new HashSet<>();
        Set<String> mappings = new HashSet<>();
        Map<String, String> properties = new HashMap<>();
        boolean executableEnabled = true;
        Set<ExecutableType> executableTypes = XmlBootstrapConfiguration.DEFAULT_EXECUTABLE_TYPES;

        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.DTD) {
                throw new ValidationException("A DTD is not allowed in validation.xml");
            }
            if (event == XMLStreamConstants.END_ELEMENT && ROOT.equals(r.getLocalName())) {
                break;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "default-provider" -> provider = r.getElementText().strip();
                case "message-interpolator" -> interpolator = r.getElementText().strip();
                case "traversable-resolver" -> resolver = r.getElementText().strip();
                case "constraint-validator-factory" -> validatorFactory = r.getElementText().strip();
                case "parameter-name-provider" -> parameterNames = r.getElementText().strip();
                case "clock-provider" -> clock = r.getElementText().strip();
                case "value-extractor" -> extractors.add(r.getElementText().strip());
                case "constraint-mapping" -> mappings.add(r.getElementText().strip());
                case "property" -> {
                    String name = r.getAttributeValue(null, "name");
                    if (name == null) {
                        throw new ValidationException("A <property> of validation.xml has no name");
                    }
                    properties.put(name, r.getElementText().strip());
                }
                case "executable-validation" -> {
                    String enabled = r.getAttributeValue(null, "enabled");
                    executableEnabled = enabled == null || Boolean.parseBoolean(enabled.strip());
                }
                case "default-validated-executable-types" -> executableTypes = readExecutableTypes(r);
                default -> {
                    // <executable-type> is consumed by readExecutableTypes; unknown elements are ignored.
                }
            }
        }
        return new XmlBootstrapConfiguration(provider, validatorFactory, interpolator, resolver, parameterNames,
            clock, extractors, mappings, executableEnabled, executableTypes, properties);
    }

    /**
     * 1.0 has no {@code version} attribute; 1.0 and 1.1 share the JBoss namespace, 2.0 uses the JCP
     * one and 3.0 the Jakarta one. A version used with another version's namespace is rejected.
     */
    private static void checkVersion(String namespace, String version) {
        String declared = version == null ? "1.0" : version.strip();
        String expected = switch (declared) {
            case "1.0", "1.1" -> NS_1_0;
            case "2.0" -> NS_JCP;
            case "3.0" -> NS_JAKARTA;
            default -> throw new ValidationException("Unsupported validation.xml version: " + declared);
        };
        if (!expected.equals(namespace)) {
            throw new ValidationException("validation.xml version " + declared + " does not match the namespace " + namespace);
        }
    }

    /**
     * ALL wins over everything (NONE included); NONE alone gives no executable type; NONE next to
     * other types is dropped; an empty list is invalid.
     */
    private static Set<ExecutableType> readExecutableTypes(XMLStreamReader r) throws XMLStreamException {
        Set<ExecutableType> types = EnumSet.noneOf(ExecutableType.class);
        int count = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.END_ELEMENT && "default-validated-executable-types".equals(r.getLocalName())) {
                break;
            }
            if (event == XMLStreamConstants.START_ELEMENT && "executable-type".equals(r.getLocalName())) {
                String name = r.getElementText().strip();
                try {
                    types.add(ExecutableType.valueOf(name));
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("Unknown executable type in validation.xml: " + name, e);
                }
                count++;
            }
        }
        if (count == 0) {
            throw new ValidationException("<default-validated-executable-types> of validation.xml is empty");
        }
        if (types.contains(ExecutableType.ALL)) {
            return EnumSet.of(ExecutableType.CONSTRUCTORS, ExecutableType.GETTER_METHODS, ExecutableType.NON_GETTER_METHODS);
        }
        types.remove(ExecutableType.NONE);
        types.remove(ExecutableType.IMPLICIT);
        return types;
    }
}
