/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.query.jpql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.query.jpql.Lexer.Kind;
import io.vidocq.noether.jpa.core.query.jpql.Lexer.Token;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class LexerTest {

    private static Object number(String literal) {
        Token token = Lexer.tokens(literal).getFirst();
        assertThat(token.kind()).isEqualTo(Kind.NUMBER);
        return token.value();
    }

    @Test
    void exponentsMakeDoublesWithOrWithoutASign() { // §4.6.1: approximate numeric literals
        assertThat(number("1e3")).isEqualTo(1000.0);
        assertThat(number("2E+2")).isEqualTo(200.0);
        assertThat(number("15e-1")).isEqualTo(1.5);
        assertThat(number(".5")).isEqualTo(0.5);
    }

    @Test
    void suffixesChooseTheNumericType() { // §4.6.1
        assertThat(number("7")).isEqualTo(7);
        assertThat(number("3000000000")).isEqualTo(3_000_000_000L);
        assertThat(number("7L")).isEqualTo(7L);
        assertThat(number("1.5f")).isEqualTo(1.5f);
        assertThat(number("1e1D")).isEqualTo(10.0);
        assertThat(number("1.25BD")).isEqualTo(new BigDecimal("1.25"));
        assertThat(number("12BI")).isEqualTo(BigInteger.valueOf(12));
    }

    @Test
    void anUnknownSuffixIsRefused() {
        assertThatThrownBy(() -> Lexer.tokens("12X"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown numeric suffix 'X'");
    }

    @Test
    void tokensOfEveryKindEndWithTheEndToken() { // §4.4.1
        List<Token> tokens = Lexer.tokens("  e.name = :name AND e.id <> ?2 OR e.label = 'it''s'  ");
        assertThat(tokens).extracting(Token::kind).containsExactly(Kind.NAME, Kind.SYMBOL, Kind.NAME, Kind.SYMBOL,
            Kind.NAMED_PARAMETER, Kind.NAME, Kind.NAME, Kind.SYMBOL, Kind.NAME, Kind.SYMBOL, Kind.POSITIONAL_PARAMETER,
            Kind.NAME, Kind.NAME, Kind.SYMBOL, Kind.NAME, Kind.SYMBOL, Kind.STRING, Kind.END);
        assertThat(tokens.get(4).value()).isEqualTo("name");
        assertThat(tokens.get(10).value()).isEqualTo(2);
        assertThat(tokens.get(16).value()).isEqualTo("it's");
        assertThat(tokens.getLast().position()).isEqualTo(54);
    }

    @Test
    void anUnexpectedCharacterIsRefusedWhereItIs() {
        assertThatThrownBy(() -> Lexer.tokens("e.name # 1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unexpected character '#' at 7");
    }

    @Test
    void aPositionalParameterNeedsItsPosition() { // §4.6.4.1
        assertThatThrownBy(() -> Lexer.tokens("e.id = ?"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs its position (?1) at 7");
    }

    @Test
    void anUnclosedStringIsRefused() {
        assertThatThrownBy(() -> Lexer.tokens("e.name = 'open"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("is not closed");
    }
}
