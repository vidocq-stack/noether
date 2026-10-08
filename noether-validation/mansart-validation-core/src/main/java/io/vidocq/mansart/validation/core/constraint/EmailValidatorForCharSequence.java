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
package io.vidocq.mansart.validation.core.constraint;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;

import java.net.IDN;
import java.util.regex.Matcher;

/**
 * Checks that a {@code CharSequence} is a well-formed email address; {@code null} and the empty
 * string are valid.
 *
 * <p>Implements the {@link Email} constraint as defined by the Jakarta Validation 3.1
 * specification, chapter "Built-in Constraint definitions", which leaves the exact semantics to the
 * provider. The rule is deliberately simple and RFC 5322-inspired:
 * <ul>
 *   <li>the value is split at its last {@code '@'};</li>
 *   <li>the local part has at most 64 characters and is a dot-atom: one or more atoms made of ASCII
 *       letters, digits and {@code !#$%&'*+/=?^_`{|}~-}, separated by single dots (no leading,
 *       trailing or doubled dot);</li>
 *   <li>the domain is converted with {@link IDN#toASCII(String)} (so internationalized domain
 *       names are accepted), has at most 255 characters and is made of dot-separated labels of at
 *       most 63 letters, digits or hyphens, none starting or ending with a hyphen. A single label
 *       such as {@code localhost} is accepted;</li>
 *   <li>when the annotation sets a {@code regexp} other than {@code ".*"} or any {@code flags}, the
 *       whole value must additionally match that expression.</li>
 * </ul>
 *
 * <p>Deviations from Hibernate Validator: quoted local parts ({@code "john doe"@example.com}),
 * comments and IP-literal domains ({@code john@[127.0.0.1]}) are rejected, and non-ASCII characters
 * are only accepted in the domain.
 */
public final class EmailValidatorForCharSequence implements ConstraintValidator<Email, CharSequence> {

    private static final int MAX_LOCAL_LENGTH = 64;
    private static final int MAX_DOMAIN_LENGTH = 255;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final String ATOM_CHARS = "!#$%&'*+/=?^_`{|}~-";

    private java.util.regex.Pattern extra;

    @Override
    public void initialize(Email constraintAnnotation) {
        int flags = 0;
        for (Pattern.Flag flag : constraintAnnotation.flags()) {
            flags |= flag.getValue();
        }
        String regexp = constraintAnnotation.regexp();
        if (".*".equals(regexp) && flags == 0) {
            this.extra = null;
            return;
        }
        try {
            this.extra = java.util.regex.Pattern.compile(regexp, flags);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid regular expression: " + regexp, e);
        }
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null || value.length() == 0) {
            return true;
        }
        String address = value.toString();
        int at = address.lastIndexOf('@');
        if (at < 0 || !validLocalPart(address, at) || !validDomain(address.substring(at + 1))) {
            return false;
        }
        if (extra != null) {
            Matcher matcher = extra.matcher(address);
            return matcher.matches();
        }
        return true;
    }

    private static boolean validLocalPart(String address, int end) {
        if (end == 0 || end > MAX_LOCAL_LENGTH) {
            return false;
        }
        boolean previousDot = true; // forbids a leading dot
        for (int i = 0; i < end; i++) {
            char c = address.charAt(i);
            if (c == '.') {
                if (previousDot) {
                    return false;
                }
                previousDot = true;
            } else if (isAsciiAlphanumeric(c) || ATOM_CHARS.indexOf(c) >= 0) {
                previousDot = false;
            } else {
                return false;
            }
        }
        return !previousDot; // forbids a trailing dot
    }

    private static boolean validDomain(String domain) {
        if (domain.isEmpty()) {
            return false;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(domain);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (ascii.isEmpty() || ascii.length() > MAX_DOMAIN_LENGTH) {
            return false;
        }
        int start = 0;
        while (true) {
            int dot = ascii.indexOf('.', start);
            int end = dot < 0 ? ascii.length() : dot;
            if (!validLabel(ascii, start, end)) {
                return false;
            }
            if (dot < 0) {
                return true;
            }
            start = dot + 1;
        }
    }

    private static boolean validLabel(String s, int start, int end) {
        int length = end - start;
        if (length == 0 || length > MAX_LABEL_LENGTH || s.charAt(start) == '-' || s.charAt(end - 1) == '-') {
            return false;
        }
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (!isAsciiAlphanumeric(c) && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
