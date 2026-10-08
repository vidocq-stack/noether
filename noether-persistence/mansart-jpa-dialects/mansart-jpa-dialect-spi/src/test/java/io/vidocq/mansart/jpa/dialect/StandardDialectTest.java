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
package io.vidocq.mansart.jpa.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Increment;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.NextValue;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import io.vidocq.mansart.jpa.dialect.sql.Update;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The ANSI rendering every dialect starts from; parameters always follow the order of the statement's lists. */
class StandardDialectTest {

    private final Dialect ansi = new StandardDialect() {
        @Override
        public String name() {
            return "ansi";
        }
    };

    private static List<Identifier> columns(String... names) {
        return List.of(names).stream().map(Identifier::of).toList();
    }

    @Test
    void insertListsItsColumnsAndOneParameterEach() {
        assertThat(ansi.render(new Insert(Table.of("BOOK"), columns("ID", "TITLE"), null)))
            .isEqualTo("INSERT INTO BOOK (ID, TITLE) VALUES (?, ?)");
    }

    @Test
    void anInsertWithoutColumnsUsesTheDefaultValues() { // an entity whose only column is a generated identifier
        assertThat(ansi.render(new Insert(Table.of("TICKET"), List.of(), Identifier.of("ID"))))
            .isEqualTo("INSERT INTO TICKET DEFAULT VALUES");
    }

    @Test
    void updateSetsItsAssignmentsThenMatchesItsConditions() {
        assertThat(ansi.render(new Update(Table.of("BOOK"), columns("TITLE", "VERSION"), columns("ID", "VERSION"))))
            .isEqualTo("UPDATE BOOK SET TITLE = ?, VERSION = ? WHERE ID = ? AND VERSION = ?");
    }

    @Test
    void deleteAndSelectMatchTheirConditions() {
        assertThat(ansi.render(new Delete(Table.of("BOOK"), columns("ID"))))
            .isEqualTo("DELETE FROM BOOK WHERE ID = ?");
        assertThat(ansi.render(new Select(Table.of("BOOK"), columns("TITLE", "PRICE"), columns("SHELF", "POSITION"))))
            .isEqualTo("SELECT TITLE, PRICE FROM BOOK WHERE SHELF = ? AND POSITION = ?");
    }

    @Test
    void quotedNamesAreDelimitedAndQualifiedTablesKeepTheirParts() { // §2.13
        Table table = new Table(Identifier.quoted("Order"), Identifier.of("SHOP"), Identifier.of("MAIN"));
        assertThat(ansi.render(new Delete(table, List.of(Identifier.quoted("key \"id\"")))))
            .isEqualTo("DELETE FROM MAIN.SHOP.\"Order\" WHERE \"key \"\"id\"\"\" = ?");
    }

    @Test
    void statementsThatWouldTouchEveryRowAreRefused() {
        assertThatThrownBy(() -> new Delete(Table.of("BOOK"), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Update(Table.of("BOOK"), columns("TITLE"), List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Update(Table.of("BOOK"), List.of(), columns("ID"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNextValueOfASequenceIsTheStandardOne() {
        assertThat(ansi.render(new NextValue(Identifier.of("SEQGENERATOR"), Identifier.of("SHOP"), null)))
            .isEqualTo("VALUES NEXT VALUE FOR SHOP.SEQGENERATOR");
    }

    @Test
    void aGeneratorRowIsIncrementedInPlace() { // table generators: no read-modify-write race
        assertThat(ansi.render(new Increment(Table.of("GENERATOR_TABLE"), Identifier.of("VAL_COL"), Identifier.of("PK_COL"))))
            .isEqualTo("UPDATE GENERATOR_TABLE SET VAL_COL = VAL_COL + ? WHERE PK_COL = ?");
    }
}
