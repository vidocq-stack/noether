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
package io.vidocq.noether.jpa.core.query.jpql;

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
        int i = skipWhitespace(query, 0);
        while (i < query.length()) {
            i = skipWhitespace(query, token(query, i, tokens));
        }
        tokens.add(new Token(Kind.END, "", null, i));
        return tokens;
    }

    private static int skipWhitespace(String query, int start) {
        int i = start;
        while (i < query.length() && Character.isWhitespace(query.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Adds the token that starts at {@code start} to {@code tokens}; gives the index that follows it. */
    private static int token(String query, int start, List<Token> tokens) {
        char c = query.charAt(start);
        if (Character.isJavaIdentifierStart(c)) {
            int end = identifierEnd(query, start);
            tokens.add(new Token(Kind.NAME, query.substring(start, end), null, start));
            return end;
        }
        if (c == '\'') {
            return string(query, start, tokens);
        }
        if (Character.isDigit(c) || c == '.' && start + 1 < query.length() && Character.isDigit(query.charAt(start + 1))) {
            return number(query, start, tokens);
        }
        if (c == ':' && start + 1 < query.length() && Character.isJavaIdentifierStart(query.charAt(start + 1))) {
            int end = identifierEnd(query, start + 1);
            tokens.add(new Token(Kind.NAMED_PARAMETER, query.substring(start, end), query.substring(start + 1, end), start));
            return end;
        }
        if (c == '?') {
            return positionalParameter(query, start, tokens);
        }
        String symbol = symbol(query, start);
        if (symbol == null) {
            throw new IllegalArgumentException("Unexpected character '" + c + "' at " + start + ": " + query);
        }
        tokens.add(new Token(Kind.SYMBOL, symbol, null, start));
        return start + symbol.length();
    }

    private static int identifierEnd(String query, int start) {
        int i = start;
        while (i < query.length() && Character.isJavaIdentifierPart(query.charAt(i))) {
            i++;
        }
        return i;
    }

    /** A string literal (§4.6.1), from its opening quote; gives the index after its closing quote. */
    private static int string(String query, int start, List<Token> tokens) {
        StringBuilder value = new StringBuilder();
        int i = start + 1;
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
        return i;
    }

    private static int positionalParameter(String query, int start, List<Token> tokens) {
        int end = start + 1;
        while (end < query.length() && Character.isDigit(query.charAt(end))) {
            end++;
        }
        if (end == start + 1) {
            throw new IllegalArgumentException("A positional parameter needs its position (?1) at " + start + ": " + query);
        }
        tokens.add(new Token(Kind.POSITIONAL_PARAMETER, query.substring(start, end), Integer.parseInt(query.substring(start + 1, end)),
            start));
        return end;
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
    private static int number(String query, int start, List<Token> tokens) {
        int i = start;
        boolean decimal = false;
        while (i < query.length() && (Character.isDigit(query.charAt(i)) || query.charAt(i) == '.')) {
            decimal |= query.charAt(i) == '.';
            i++;
        }
        int exponentEnd = exponentEnd(query, i);
        decimal |= exponentEnd > i;
        String digits = query.substring(start, exponentEnd);
        int end = exponentEnd;
        while (end < query.length() && Character.isLetter(query.charAt(end))) {
            end++;
        }
        String suffix = query.substring(exponentEnd, end).toUpperCase(java.util.Locale.ROOT);
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

    /** The end of the exponent ({@code e}, an optional sign, digits) that starts at {@code start}, if any. */
    private static int exponentEnd(String query, int start) {
        if (start >= query.length() || query.charAt(start) != 'e' && query.charAt(start) != 'E') {
            return start;
        }
        int end = start + 1;
        if (end < query.length() && (query.charAt(end) == '+' || query.charAt(end) == '-')) {
            end++;
        }
        while (end < query.length() && Character.isDigit(query.charAt(end))) {
            end++;
        }
        return end;
    }

    private static Object integer(String digits) {
        long value = Long.parseLong(digits);
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (Object) (int) value : value;
    }
}
