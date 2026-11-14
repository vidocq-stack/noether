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
package io.vidocq.noether.jpa.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

/** §8.2.1.10: a schema or load script is split into statements at the semicolons outside literals and comments. */
class SchemaScriptSplitTest {

    @Test
    void semicolonsEndStatementsAndBlankStatementsAreDropped() {
        assertThat(SchemaGeneration.split(" CREATE TABLE a (id INT);;\n INSERT INTO a VALUES (1) ;  "))
            .containsExactly("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)");
    }

    @Test
    void quotedLiteralsAndIdentifiersKeepTheirSemicolonsCommentsAndDoubledQuotes() {
        assertThat(SchemaGeneration.split("INSERT INTO \"a;b\" VALUES ('it''s; -- not /* a */ comment', 'x\"y');"))
            .containsExactly("INSERT INTO \"a;b\" VALUES ('it''s; -- not /* a */ comment', 'x\"y')");
    }

    @Test
    void commentsAreReadAsOneSpace() {
        assertThat(SchemaGeneration.split("SELECT 1 -- one; two\nFROM a;SELECT/* x; y */2;SELECT 3 -- to the end"))
            .containsExactly("SELECT 1  FROM a", "SELECT 2", "SELECT 3");
    }

    @Test
    void aLineCommentEndsAtACarriageReturnToo() {
        assertThat(SchemaGeneration.split("SELECT 1 --; x\rFROM a")).containsExactly("SELECT 1  FROM a");
    }

    @Test
    void aBlockCommentNeedsItsOwnClosingMarker() {
        assertThat(SchemaGeneration.split("SELECT /*/ ; */ 1")).containsExactly("SELECT   1");
    }

    @Test
    void anUnterminatedLiteralIsRejected() {
        assertThatThrownBy(() -> SchemaGeneration.split("INSERT INTO a VALUES ('open;"))
            .isInstanceOf(PersistenceException.class).hasMessage("Unterminated SQL literal or block comment");
    }

    @Test
    void anUnterminatedBlockCommentIsRejected() {
        assertThatThrownBy(() -> SchemaGeneration.split("SELECT 1; /* open"))
            .isInstanceOf(PersistenceException.class).hasMessage("Unterminated SQL literal or block comment");
    }
}
