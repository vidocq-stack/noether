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
package io.vidocq.mansart.jpa.core.query.jpql;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a query into tokens (§4.4.1): names, string and numeric literals (§4.6.1), {@code :name} and {@code ?n}
 * parameters, operators and punctuation. Keywords are names the {@link Parser} recognises, whatever their case.
 */
final class Lexer {

    /** The kinds of tokens. */
    enum Kind {
        NAME, STRING, NUMBER, NAMED_PARAMETER, POSITIONAL_PARAMETER, SYMBOL, END
    }

    /** A token, its value (the number, the unquoted string, the parameter) and where it starts in the query. */
    record Token(Kind kind, String text, Object value, int position) {
        boolean is(String keywordOrSymbol) {
            return (kind == Kind.NAME || kind == Kind.SYMBOL) && text.equalsIgnoreCase(keywordOrSymbol);
        }
    }

    private Lexer() {
    }

    static List<Token> tokens(String query) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (true) {
            while (i < query.length() && Character.isWhitespace(query.charAt(i))) {
                i++;
            }
            if (i == query.length()) {
                tokens.add(new Token(Kind.END, "", null, i));
                return tokens;
            }
            char c = query.charAt(i);
            int start = i;
            if (Character.isJavaIdentifierStart(c)) {
                while (i < query.length() && Character.isJavaIdentifierPart(query.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.NAME, query.substring(start, i), null, start));
            } else if (c == '\'') {
                StringBuilder value = new StringBuilder();
                i++;
                while (true) {
                    if (i >= query.length()) {
                        throw new IllegalArgumentException("The string literal at " + start + " is not closed: " + query);
                    }
                    char s = query.charAt(i++);
                    if (s == '\'') {
                        if (i < query.length() && query.charAt(i) == '\'') {
                            value.append('\''); // §4.6.1: a quote within a string is doubled
                            i++;
                        } else {
                            break;
                        }
                    } else {
                        value.append(s);
                    }
                }
                tokens.add(new Token(Kind.STRING, query.substring(start, i), value.toString(), start));
            } else if (Character.isDigit(c) || c == '.' && i + 1 < query.length() && Character.isDigit(query.charAt(i + 1))) {
                i = number(query, i, tokens);
            } else if (c == ':' && i + 1 < query.length() && Character.isJavaIdentifierStart(query.charAt(i + 1))) {
                i++;
                while (i < query.length() && Character.isJavaIdentifierPart(query.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.NAMED_PARAMETER, query.substring(start, i), query.substring(start + 1, i), start));
            } else if (c == '?') {
                i++;
                while (i < query.length() && Character.isDigit(query.charAt(i))) {
                    i++;
                }
                if (i == start + 1) {
                    throw new IllegalArgumentException("A positional parameter needs its position (?1) at " + start + ": " + query);
                }
                tokens.add(new Token(Kind.POSITIONAL_PARAMETER, query.substring(start, i), Integer.parseInt(query.substring(start + 1, i)),
                    start));
            } else {
                String symbol = symbol(query, i);
                if (symbol == null) {
                    throw new IllegalArgumentException("Unexpected character '" + c + "' at " + start + ": " + query);
                }
                i += symbol.length();
                tokens.add(new Token(Kind.SYMBOL, symbol, null, start));
            }
        }
    }

    private static String symbol(String query, int i) {
        for (String symbol : List.of("<>", "<=", ">=", "||", "!=", "=", "<", ">", "+", "-", "*", "/", "(", ")", ",", ".", "{", "}")) {
            if (query.startsWith(symbol, i)) {
                return symbol;
            }
        }
        return null;
    }

    /**
     * A numeric literal (§4.6.1): an integer is an {@code Integer}, or a {@code Long} beyond its range or with the
     * suffix {@code L}; a decimal or an exponent is a {@code Double}, a {@code Float} with {@code F}, a
     * {@code BigDecimal} with {@code BD}; {@code BI} makes a {@code BigInteger}.
     */
    private static int number(String query, int i, List<Token> tokens) {
        int start = i;
        boolean decimal = false;
        while (i < query.length() && (Character.isDigit(query.charAt(i)) || query.charAt(i) == '.')) {
            decimal |= query.charAt(i) == '.';
            i++;
        }
        if (i < query.length() && (query.charAt(i) == 'e' || query.charAt(i) == 'E')) {
            decimal = true;
            i++;
            if (i < query.length() && (query.charAt(i) == '+' || query.charAt(i) == '-')) {
                i++;
            }
            while (i < query.length() && Character.isDigit(query.charAt(i))) {
                i++;
            }
        }
        String digits = query.substring(start, i);
        String suffix = "";
        int end = i;
        while (end < query.length() && Character.isLetter(query.charAt(end))) {
            end++;
        }
        suffix = query.substring(i, end).toUpperCase(java.util.Locale.ROOT);
        Object value = switch (suffix) {
            case "L" -> Long.parseLong(digits);
            case "F" -> Float.parseFloat(digits);
            case "D" -> Double.parseDouble(digits);
            case "BD" -> new java.math.BigDecimal(digits);
            case "BI" -> new java.math.BigInteger(digits);
            case "" -> decimal ? (Object) Double.parseDouble(digits) : integer(digits);
            default -> throw new IllegalArgumentException("Unknown numeric suffix '" + suffix + "' at " + start + ": " + query);
        };
        tokens.add(new Token(Kind.NUMBER, query.substring(start, end), value, start));
        return end;
    }

    private static Object integer(String digits) {
        long value = Long.parseLong(digits);
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value : value;
    }
}
