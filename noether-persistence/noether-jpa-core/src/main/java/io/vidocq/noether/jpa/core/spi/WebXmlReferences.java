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
package io.vidocq.noether.jpa.core.spi;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.DescriptorReference;
import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.ReferenceKind;
import jakarta.persistence.PersistenceContextType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * StAX reader of the persistence references of {@code web.xml} (Jakarta EE 11 Platform §5.13.1.2, §5.14.1.2): the
 * {@code persistence-context-ref} and {@code persistence-unit-ref} children of its root, whatever the version of its
 * schema. Every other element is left to the servlet container. DTDs and external entities are refused.
 */
final class WebXmlReferences {

    /** Where a servlet container reads {@code web.xml}: the web module first, then a jar that carries its own. */
    private static final List<String> DESCRIPTORS = List.of("WEB-INF/web.xml", "META-INF/web.xml");
    private static final String CONTEXT = "persistence-context-ref";
    private static final String UNIT = "persistence-unit-ref";

    /** An element read whole: its text and its children, enough for the few elements a reference has. */
    private record Node(String name, String text, List<Node> children) {
        String child(String element) {
            return children.stream().filter(child -> child.name.equals(element)).map(Node::text).findFirst().orElse(null);
        }

        List<Node> all(String element) {
            return children.stream().filter(child -> child.name.equals(element)).toList();
        }
    }

    private WebXmlReferences() {
    }

    static List<DescriptorReference> of(ClassLoader loader) {
        for (String descriptor : DESCRIPTORS) {
            var url = loader.getResource(descriptor);
            if (url == null) {
                continue;
            }
            try (InputStream in = url.openStream()) {
                return parse(in, descriptor);
            } catch (IOException e) {
                throw new PersistenceException("Unable to read " + descriptor + " at " + url + ": " + e.getMessage(), e);
            }
        }
        return List.of();
    }

    static List<DescriptorReference> parse(InputStream in, String source) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext() && reader.next() != XMLStreamConstants.START_ELEMENT) {
                    refuseDtd(reader, source);
                }
                if (reader.getEventType() != XMLStreamConstants.START_ELEMENT) {
                    return List.of();
                }
                List<DescriptorReference> references = new ArrayList<>();
                for (Node child : read(reader, source).children()) {
                    if (CONTEXT.equals(child.name())) {
                        references.add(context(child, source));
                    } else if (UNIT.equals(child.name())) {
                        references.add(unit(child, source));
                    }
                }
                return List.copyOf(references);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new PersistenceException("Unable to parse " + source + ": " + e.getMessage(), e);
        }
    }

    private static void refuseDtd(XMLStreamReader reader, String source) {
        if (reader.getEventType() == XMLStreamConstants.DTD) {
            throw new PersistenceException("A DTD is not allowed in " + source);
        }
    }

    private static Node read(XMLStreamReader reader, String source) throws XMLStreamException {
        String name = reader.getLocalName();
        StringBuilder text = new StringBuilder();
        List<Node> children = new ArrayList<>();
        while (reader.hasNext()) {
            switch (reader.next()) {
                case XMLStreamConstants.START_ELEMENT -> children.add(read(reader, source));
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> text.append(reader.getText());
                case XMLStreamConstants.END_ELEMENT -> {
                    return new Node(name, text.toString().strip(), List.copyOf(children));
                }
                default -> refuseDtd(reader, source);
            }
        }
        throw new XMLStreamException("The element <" + name + "> is not closed");
    }

    private static DescriptorReference context(Node reference, String source) {
        String name = name(reference, CONTEXT + "-name", source);
        PersistenceContextType type = value(reference, "persistence-context-type", PersistenceContextType.class,
            PersistenceContextType.TRANSACTION, name, source);
        SynchronizationType synchronization = value(reference, "persistence-context-synchronization",
            SynchronizationType.class, SynchronizationType.SYNCHRONIZED, name, source);
        Map<String, String> properties = new TreeMap<>();
        for (Node property : reference.all("persistence-property")) {
            properties.put(text(property.child("name")), text(property.child("value")));
        }
        return new DescriptorReference(name, ReferenceKind.CONTEXT, unitName(reference), type, synchronization,
            properties, injectionTargets(reference), source);
    }

    private static DescriptorReference unit(Node reference, String source) {
        return new DescriptorReference(name(reference, UNIT + "-name", source), ReferenceKind.UNIT, unitName(reference),
            null, null, Map.of(), injectionTargets(reference), source);
    }

    private static String name(Node reference, String element, String source) {
        String name = text(reference.child(element));
        if (name.isEmpty()) {
            throw new PersistenceException("A <" + reference.name() + "> of " + source + " has no <" + element
                + ">: the name it is looked up by under java:comp/env (Jakarta EE 11 Platform §5.13.1.2)");
        }
        return name;
    }

    private static String unitName(Node reference) {
        return text(reference.child("persistence-unit-name"));
    }

    private static List<String> injectionTargets(Node reference) {
        return reference.all("injection-target").stream()
            .map(target -> text(target.child("injection-target-class")) + "#" + text(target.child("injection-target-name")))
            .toList();
    }

    /** The schema writes {@code Transaction}, {@code Unsynchronized}: an enumeration constant in another case. */
    private static <E extends Enum<E>> E value(Node reference, String element, Class<E> type, E fallback, String name,
            String source) {
        String written = text(reference.child(element));
        if (written.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, written.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new PersistenceException("The <" + element + "> of the persistence reference " + name + " of " + source
                + " is " + written + ", not one of " + List.of(type.getEnumConstants()), unknown);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
