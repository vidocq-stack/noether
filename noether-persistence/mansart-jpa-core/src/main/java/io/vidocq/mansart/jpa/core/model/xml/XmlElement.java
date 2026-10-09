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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An element of a mapping file, as read: its local name, its attributes (unqualified ones, by local name), its
 * children and its text, with the line it starts at for the error messages.
 */
record XmlElement(String name, Map<String, String> attributes, List<XmlElement> children, String text, int line) {

    XmlElement {
        attributes = Map.copyOf(attributes);
        children = List.copyOf(children);
    }

    String attribute(String attributeName) {
        return attributes.get(attributeName);
    }

    Optional<XmlElement> child(String childName) {
        return children.stream().filter(c -> c.name.equals(childName)).findFirst();
    }

    List<XmlElement> children(String childName) {
        List<XmlElement> found = new ArrayList<>();
        for (XmlElement child : children) {
            if (child.name.equals(childName)) {
                found.add(child);
            }
        }
        return found;
    }

    /** The trimmed text of the child named {@code childName}, or {@code null} if there is none. */
    String childText(String childName) {
        return child(childName).map(c -> c.text.strip()).orElse(null);
    }
}
