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
package io.vidocq.mansart.jpa.dialect.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A database object name as mapped, including routine names and arguments. A quoted name is delimited when rendered
 * and keeps its case (§2.15); an
 * unquoted one is left to the database's own folding.
 */
public record Identifier(String name, boolean quoted) {

    public Identifier {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("A SQL identifier cannot be blank");
        }
    }

    public static Identifier of(String name) {
        return of(name, false);
    }

    /** A mapped name (§2.15), with explicit double quotes or the unit's delimited-identifier default. */
    public static Identifier of(String name, boolean delimited) {
        Objects.requireNonNull(name, "name");
        if (name.startsWith("\"")) {
            StringBuilder text = new StringBuilder();
            int end = quotedPart(name, 0, text);
            if (end != name.length()) {
                throw new IllegalArgumentException("Invalid delimited SQL identifier: " + name);
            }
            return quoted(text.toString());
        }
        if (name.indexOf('"') >= 0) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + name);
        }
        return new Identifier(name, delimited);
    }

    /** Parses a qualified routine name without treating a dot inside delimiters as a separator. */
    public static List<Identifier> qualified(String name, boolean delimited) {
        Objects.requireNonNull(name, "name");
        List<Identifier> parts = new ArrayList<>();
        int start = 0;
        while (start < name.length()) {
            int end;
            if (name.charAt(start) == '"') {
                StringBuilder text = new StringBuilder();
                end = quotedPart(name, start, text);
                parts.add(quoted(text.toString()));
            } else {
                end = name.indexOf('.', start);
                if (end < 0) end = name.length();
                String part = name.substring(start, end);
                if (!part.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                    throw new IllegalArgumentException("Invalid routine identifier: " + name);
                }
                parts.add(of(part, delimited));
            }
            if (end == name.length()) return List.copyOf(parts);
            if (name.charAt(end) != '.' || end + 1 == name.length()) {
                throw new IllegalArgumentException("Invalid qualified routine name: " + name);
            }
            start = end + 1;
        }
        throw new IllegalArgumentException("A routine name cannot be empty");
    }

    private static int quotedPart(String name, int start, StringBuilder text) {
        for (int i = start + 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '"') {
                text.append(c);
            } else if (i + 1 < name.length() && name.charAt(i + 1) == '"') {
                text.append('"');
                i++;
            } else {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("Unclosed delimited SQL identifier: " + name);
    }

    public static Identifier quoted(String name) {
        return new Identifier(name, true);
    }
}
