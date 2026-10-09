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
package io.vidocq.mansart.jpa.core.model.xml;

import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Reads a mapping file (Jakarta Persistence 3.2, chapter 12) into {@link XmlElement}s, with StAX. The root must be an
 * {@code entity-mappings} of a namespace and version the specification defines (orm 1.0 to 3.2); the document is
 * validated, while it is read, against the orm 3.2 schema, which every earlier version is a subset of. DTDs and
 * external entities are refused, and no schema is ever fetched: the one bundled with Mansart is the only one used.
 * Every error names the mapping file and, when it has one, the line.
 */
final class OrmXmlReader {

    private static final String SUN = "http://java.sun.com/xml/ns/persistence/orm";
    private static final String JCP = "http://xmlns.jcp.org/xml/ns/persistence/orm";
    static final String JAKARTA = "https://jakarta.ee/xml/ns/persistence/orm";
    private static final String XSI = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    private OrmXmlReader() {
    }

    /** The {@code entity-mappings} element of {@code file}. */
    static XmlElement read(MappingFile file) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        try (InputStream in = open(file.url())) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                return read(reader, file);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            int line = e.getLocation() == null ? -1 : e.getLocation().getLineNumber();
            throw error(file, line, "not a well-formed XML document: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PersistenceException("Unable to read the mapping file " + file.name() + " at " + file.url(), e);
        }
    }

    private static InputStream open(URL url) throws IOException {
        URLConnection connection = url.openConnection();
        // a jar rewritten under the same name since it was cached (see ManagedClasses) must be read again
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    private static XmlElement read(XMLStreamReader r, MappingFile file) throws XMLStreamException {
        Validation validation = null;
        Deque<Builder> open = new ArrayDeque<>();
        XmlElement root = null;
        while (r.hasNext()) {
            int event = r.next();
            int line = r.getLocation().getLineNumber();
            switch (event) {
                case XMLStreamConstants.DTD -> throw error(file, line, "a DTD is not allowed in a mapping file", null);
                case XMLStreamConstants.ENTITY_REFERENCE -> throw error(file, line,
                    "the entity reference &" + r.getLocalName() + "; is not allowed in a mapping file", null);
                case XMLStreamConstants.START_ELEMENT -> {
                    if (validation == null) {
                        String version = checkRoot(r, file, line);
                        validation = new Validation(file, r);
                        validation.start(version);
                    }
                    Builder builder = new Builder(r.getLocalName(), line);
                    AttributesImpl attributes = new AttributesImpl();
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        String namespace = r.getAttributeNamespace(i) == null ? "" : r.getAttributeNamespace(i);
                        if (XSI.equals(namespace)) {
                            continue;
                        }
                        String local = r.getAttributeLocalName(i);
                        String value = r.getAttributeValue(i);
                        if (open.isEmpty() && namespace.isEmpty() && "version".equals(local)) {
                            value = "3.2"; // validated against the 3.2 schema, the version was checked above
                        } else if (namespace.isEmpty()) {
                            builder.attributes.put(local, r.getAttributeValue(i));
                        }
                        attributes.addAttribute(namespace, local, local, "CDATA", value);
                    }
                    validation.startElement(validationNamespace(r.getNamespaceURI(), validation.namespace), builder.name, attributes);
                    open.push(builder);
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!open.isEmpty()) {
                        open.peek().text.append(r.getText());
                        validation.characters(r.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    Builder closed = open.pop();
                    validation.endElement(validationNamespace(r.getNamespaceURI(), validation.namespace), closed.name);
                    XmlElement element = closed.build();
                    if (open.isEmpty()) {
                        root = element;
                    } else {
                        open.peek().children.add(element);
                    }
                }
                default -> {
                    // comments, processing instructions, the end of the document
                }
            }
        }
        if (root == null) {
            throw error(file, -1, "the mapping file has no root element", null);
        }
        validation.end();
        return root;
    }

    /** The namespace an element is validated in: the 3.2 one for the elements of the document's orm namespace. */
    private static String validationNamespace(String namespace, String documentNamespace) {
        return documentNamespace.equals(namespace) ? JAKARTA : namespace == null ? "" : namespace;
    }

    /** §12.2: the root is {@code entity-mappings}, in the namespace of its version; the version it declares. */
    private static String checkRoot(XMLStreamReader r, MappingFile file, int line) {
        if (!"entity-mappings".equals(r.getLocalName())) {
            throw error(file, line, "the root element is <" + r.getLocalName() + ">, not <entity-mappings>", null);
        }
        String version = r.getAttributeValue(null, "version");
        String declared = version == null ? "" : version.strip();
        String expected = switch (declared) {
            case "1.0", "2.0" -> SUN;
            case "2.1", "2.2" -> JCP;
            case "3.0", "3.1", "3.2" -> JAKARTA;
            default -> throw error(file, line, "unsupported mapping file version '" + declared + "' (1.0 to 3.2)", null);
        };
        if (!expected.equals(r.getNamespaceURI())) {
            throw error(file, line, "the version " + declared + " does not match the namespace " + r.getNamespaceURI()
                + " (expected " + expected + ")", null);
        }
        return declared;
    }

    static PersistenceException error(MappingFile file, int line, String message, Throwable cause) {
        String where = "Mapping file " + file.name() + (line > 0 ? ", line " + line : "");
        return new PersistenceException(where + ": " + message, cause);
    }

    private static final class Builder {
        private final String name;
        private final int line;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<XmlElement> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        Builder(String name, int line) {
            this.name = name;
            this.line = line;
        }

        XmlElement build() {
            return new XmlElement(name, attributes, children, text.toString(), line);
        }
    }

    /** The validation of the document against the bundled orm 3.2 schema, fed with the events StAX reads. */
    private static final class Validation implements Locator, ErrorHandler {
        private final MappingFile file;
        private final XMLStreamReader reader;
        private final ValidatorHandler handler;
        private String namespace;

        Validation(MappingFile file, XMLStreamReader reader) {
            this.file = file;
            this.reader = reader;
            this.handler = Schemas.ORM.newValidatorHandler();
            for (String property : List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
                try {
                    handler.setProperty(property, "");
                } catch (SAXException unsupported) {
                    // the bundled schema is already loaded; nothing is fetched while validating
                }
            }
            handler.setErrorHandler(this);
            handler.setDocumentLocator(this);
        }

        void start(String version) {
            this.namespace = reader.getNamespaceURI();
            run(() -> {
                handler.startDocument();
                handler.startPrefixMapping("", JAKARTA);
            });
        }

        void startElement(String uri, String local, AttributesImpl attributes) {
            run(() -> handler.startElement(uri, local, local, attributes));
        }

        void characters(String text) {
            run(() -> handler.characters(text.toCharArray(), 0, text.length()));
        }

        void endElement(String uri, String local) {
            run(() -> handler.endElement(uri, local, local));
        }

        void end() {
            run(() -> {
                handler.endPrefixMapping("");
                handler.endDocument();
            });
        }

        private void run(SaxAction action) {
            try {
                action.run();
            } catch (SAXParseException e) {
                throw OrmXmlReader.error(file, e.getLineNumber(), "invalid against the orm schema: " + e.getMessage(), e);
            } catch (SAXException e) {
                throw OrmXmlReader.error(file, getLineNumber(), "invalid against the orm schema: " + e.getMessage(), e);
            }
        }

        @Override
        public String getPublicId() {
            return null;
        }

        @Override
        public String getSystemId() {
            return file.name();
        }

        @Override
        public int getLineNumber() {
            return reader.getLocation().getLineNumber();
        }

        @Override
        public int getColumnNumber() {
            return reader.getLocation().getColumnNumber();
        }

        @Override
        public void warning(SAXParseException exception) {
            // warnings do not make a document invalid
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    }

    @FunctionalInterface
    private interface SaxAction {
        void run() throws SAXException;
    }

    /** The bundled orm 3.2 schema, loaded once: a {@link Schema} is immutable and thread-safe. */
    private static final class Schemas {
        static final Schema ORM = load();

        private static Schema load() {
            URL xsd = OrmXmlReader.class.getResource("orm_3_2.xsd");
            if (xsd == null) {
                throw new PersistenceException("The orm 3.2 schema is missing from mansart-jpa-core");
            }
            try (InputStream in = xsd.openStream()) {
                SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return factory.newSchema(new StreamSource(in, xsd.toExternalForm()));
            } catch (IOException | SAXException e) {
                throw new PersistenceException("Unable to load the orm 3.2 schema bundled with mansart-jpa-core", e);
            }
        }
    }
}
