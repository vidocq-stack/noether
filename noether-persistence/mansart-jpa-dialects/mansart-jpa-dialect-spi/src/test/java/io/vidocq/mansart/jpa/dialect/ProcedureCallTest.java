/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.ProcedureCall;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 §2.15: routine identifiers are database object names, not SQL fragments. */
class ProcedureCallTest {
    private final Dialect ansi = new StandardDialect() {
        @Override public String name() { return "ansi"; }
    };

    @Test
    void routinePartsPreserveExplicitDelimitersAndUnitDefaults() {
        assertThat(Identifier.qualified("Catalog.\"Mixed.Schema\".\"Add\"\"One\"", false))
            .containsExactly(Identifier.of("Catalog"), Identifier.quoted("Mixed.Schema"), Identifier.quoted("Add\"One"));
        assertThat(Identifier.qualified("Catalog.MixedSchema.AddOne", true))
            .containsExactly(Identifier.quoted("Catalog"), Identifier.quoted("MixedSchema"), Identifier.quoted("AddOne"));
        assertThat(ansi.renderProcedureCall(new ProcedureCall(
            Identifier.qualified("Catalog.\"Mixed.Schema\".\"Add\"\"One\"", true), List.of())))
            .isEqualTo("CALL \"Catalog\".\"Mixed.Schema\".\"Add\"\"One\"()");
    }

    @Test
    void malformedDelimitersAndUnsafeUnquotedRoutinePartsAreRejected() {
        for (String text : List.of("\"unclosed", "\"a\"b\"", "\"a\"junk", "\"a\"\"", "\"\"")) {
            assertThatThrownBy(() -> Identifier.of(text)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
        for (String text : List.of("", "a.", ".a", "a..b", "\"a\".\"b", "a --", "a()", "a;DROP TABLE X")) {
            assertThatThrownBy(() -> Identifier.qualified(text, false)).as(text).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Identifier.qualified(text, true)).as(text).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ProcedureCall(List.of(Identifier.of("a;DROP TABLE X")), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProcedureCall.Parameter(ProcedureCall.Mode.IN, java.sql.Types.INTEGER,
            Identifier.of("a => 1);DROP TABLE X"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quotedSqlPunctuationIsRenderedAsOneIdentifierNotAnExecutableFragment() {
        var call = new ProcedureCall(List.of(Identifier.quoted("a\");DROP TABLE X;--")), List.of());
        assertThat(ansi.renderProcedureCall(call)).isEqualTo("CALL \"a\"\");DROP TABLE X;--\"()");
    }
}
