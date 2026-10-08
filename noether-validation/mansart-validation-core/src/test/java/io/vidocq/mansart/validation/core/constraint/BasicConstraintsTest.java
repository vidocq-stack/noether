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

import static io.vidocq.mansart.validation.core.constraint.TestSupport.annotation;
import static io.vidocq.mansart.validation.core.constraint.TestSupport.pattern;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.constraints.AssertFalse;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;

import org.junit.jupiter.api.Test;

/** Tests for the boolean, null, text and pattern constraints (Jakarta Validation 3.1, section 6.1). */
class BasicConstraintsTest {

    static class F {
        @AssertTrue Object assertTrue;
        @AssertFalse Object assertFalse;
        @NotNull Object notNull;
        @Null Object isNull;
        @NotBlank Object notBlank;
        @Pattern(regexp = "[a-z]+") Object lower;
        @Pattern(regexp = "[a-z]+", flags = Pattern.Flag.CASE_INSENSITIVE) Object anyCase;
        @Pattern(regexp = "[a-z") Object broken;
        @Email Object email;
        @Email(regexp = ".*@example\\.com") Object emailRegexp;
        @Email(regexp = "[A-Z].*", flags = Pattern.Flag.CASE_INSENSITIVE) Object emailFlags;
    }

    @Test
    void assertTrueAndFalse() {
        assertThat(pattern(new AssertTrueValidatorForBoolean(), annotation(F.class, "assertTrue", AssertTrue.class), true, false, null)).isEqualTo("TFT");
        assertThat(pattern(new AssertFalseValidatorForBoolean(), annotation(F.class, "assertFalse", AssertFalse.class), true, false, null)).isEqualTo("FTT");
    }

    @Test
    void notNullAndNull() {
        assertThat(pattern(new NotNullValidatorForObject(), annotation(F.class, "notNull", NotNull.class), "x", "", null)).isEqualTo("TTF");
        assertThat(pattern(new NullValidatorForObject(), annotation(F.class, "isNull", Null.class), "x", "", null)).isEqualTo("FFT");
    }

    @Test
    void notBlank() {
        var a = annotation(F.class, "notBlank", NotBlank.class);
        assertThat(pattern(new NotBlankValidatorForCharSequence(), a, "x", " x ", "", " ", "\t\n\r ", new StringBuilder("  "), new StringBuilder("a"), null)).isEqualTo("TTFFFFTF");
    }

    @Test
    void patternConstraint() {
        var a = annotation(F.class, "lower", Pattern.class);
        // the whole value must match, not a substring
        assertThat(pattern(new PatternValidatorForCharSequence(), a, "abc", "abc1", "ABC", "", new StringBuilder("abc"), null)).isEqualTo("TFFFTT");
        var ci = annotation(F.class, "anyCase", Pattern.class);
        assertThat(pattern(new PatternValidatorForCharSequence(), ci, "abc", "ABC", "AB1", null)).isEqualTo("TTFT");
        var broken = annotation(F.class, "broken", Pattern.class);
        assertThatThrownBy(() -> new PatternValidatorForCharSequence().initialize(broken)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailDefaultRule() {
        var a = annotation(F.class, "email", Email.class);
        var v = new EmailValidatorForCharSequence();
        assertThat(pattern(v, a,
                "john@example.com", "a.b+c@sub.example.org", "john@localhost", "x@y.z", "o'neil@example.com", new StringBuilder("a@b.co"), "", null))
                .isEqualTo("TTTTTTTT");
        assertThat(pattern(v, a,
                "john", "@example.com", "john@", "john@@example.com", "jo hn@example.com", "john@exa mple.com", ".john@example.com",
                "john.@example.com", "jo..hn@example.com", "john@-example.com", "john@example-.com", "john@example..com", "john@example.com."))
                .isEqualTo("FFFFFFFFFFFFF");
    }

    @Test
    void emailLengthLimits() {
        var a = annotation(F.class, "email", Email.class);
        var v = new EmailValidatorForCharSequence();
        assertThat(pattern(v, a, "a".repeat(64) + "@example.com", "a".repeat(65) + "@example.com",
                "a@" + "b".repeat(63) + ".com", "a@" + "b".repeat(64) + ".com")).isEqualTo("TFTF");
    }

    @Test
    void emailInternationalizedDomain() {
        var a = annotation(F.class, "email", Email.class);
        assertThat(pattern(new EmailValidatorForCharSequence(), a, "john@bücher.example", "john@例え.jp")).isEqualTo("TT");
    }

    @Test
    void emailHonoursRegexpAndFlags() {
        var v = new EmailValidatorForCharSequence();
        var a = annotation(F.class, "emailRegexp", Email.class);
        assertThat(pattern(v, a, "a@example.com", "a@other.com", "not-an-email@", null)).isEqualTo("TFFT");
        var f = annotation(F.class, "emailFlags", Email.class);
        assertThat(pattern(new EmailValidatorForCharSequence(), f, "john@example.com", "John@example.com", "1john@example.com")).isEqualTo("TTF");
    }
}
