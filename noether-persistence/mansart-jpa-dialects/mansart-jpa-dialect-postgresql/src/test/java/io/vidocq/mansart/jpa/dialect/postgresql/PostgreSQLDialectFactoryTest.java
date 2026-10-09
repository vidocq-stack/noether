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
package io.vidocq.mansart.jpa.dialect.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.dialect.DialectFactory;
import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.ProcedureCall;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostgreSQLDialectFactoryTest {

    @Test
    void procedureArgumentTargetsAreDelimitedWithoutChangingJdbcOrdinalBindings() {
        var call = new ProcedureCall("\"Mixed.Schema\".\"Add\"\"One\"", List.of(
            new ProcedureCall.Parameter(ProcedureCall.Mode.INOUT, java.sql.Types.INTEGER, Identifier.of("\"Output.Value\"")),
            new ProcedureCall.Parameter(ProcedureCall.Mode.IN, java.sql.Types.INTEGER, Identifier.of("\"Input\"\"Value\""))));
        assertThat(new PostgreSQLDialectFactory().create(17, 0).renderProcedureCall(call))
            .isEqualTo("CALL \"Mixed.Schema\".\"Add\"\"One\"(\"Output.Value\" => ?, \"Input\"\"Value\" => ?)");
    }

    @Test
    void theModuleProvidesTheFactory() {
        assertThat(PostgreSQLDialectFactory.class.getModule().getDescriptor().provides())
            .anySatisfy(p -> {
                assertThat(p.service()).isEqualTo(DialectFactory.class.getName());
                assertThat(p.providers()).containsExactly(PostgreSQLDialectFactory.class.getName());
            });
    }

    @Test
    void locateFromAPositionIsWrittenWithPositionAndItsParametersRepeated() { // §4.6.17.2
        var search = new io.vidocq.mansart.jpa.dialect.sql.Expression.Parameter("s");
        var start = new io.vidocq.mansart.jpa.dialect.sql.Expression.Parameter("start");
        var query = io.vidocq.mansart.jpa.dialect.sql.Query.from(new io.vidocq.mansart.jpa.dialect.sql.Query.From(Table.of("EMP"), "e",
            List.of())).select(new io.vidocq.mansart.jpa.dialect.sql.Expression.Function("LOCATE", List.of(search,
                new io.vidocq.mansart.jpa.dialect.sql.Expression.Column("e", Identifier.of("NAME")), start))).build();
        var rendered = new PostgreSQLDialectFactory().create(17, 0).renderQuery(query);
        assertThat(rendered.sql()).isEqualTo("SELECT CASE WHEN POSITION(? IN SUBSTRING(e.NAME FROM ?)) = 0 THEN 0 "
            + "ELSE POSITION(? IN SUBSTRING(e.NAME FROM ?)) + ? - 1 END FROM EMP e");
        assertThat(rendered.parameters()).containsExactly(search, start, search, start, start);
    }

    @Test
    void itRecognisesPostgreSqlFromItsJdbcMetadata() { // DatabaseMetaData.getDatabaseProductName()
        DialectFactory factory = new PostgreSQLDialectFactory();
        assertThat(factory.supports("PostgreSQL")).isTrue();
        assertThat(factory.supports("H2")).isFalse();
        assertThat(factory.create(17, 0).name()).isEqualTo("postgresql");
    }

    @Test
    void itRendersTheStandardStatements() {
        assertThat(new PostgreSQLDialectFactory().create(17, 0).render(new Delete(Table.of("BOOK"), List.of(Identifier.of("ID")))))
            .isEqualTo("DELETE FROM BOOK WHERE ID = ?");
    }

    @Test
    void generatedKeysAreAskedForAsTheDatabaseFoldsTheirNames() {
        var dialect = new PostgreSQLDialectFactory().create(17, 0);
        assertThat(dialect.generatedKeyName(Identifier.of("Id"))).isEqualTo("id");
        assertThat(dialect.generatedKeyName(Identifier.quoted("Id"))).isEqualTo("Id");
    }

    @Test
    void aUniqueViolationIsADuplicateKey() {
        var dialect = new PostgreSQLDialectFactory().create(17, 0);
        assertThat(dialect.isDuplicateKey(new java.sql.SQLException("duplicate", "23505"))).isTrue();
        assertThat(dialect.isDuplicateKey(new java.sql.SQLException("not null", "23502"))).isFalse();
    }

    @Test
    void theNextValueOfASequenceIsNextval() {
        assertThat(new PostgreSQLDialectFactory().create(17, 0).render(new io.vidocq.mansart.jpa.dialect.sql.NextValue(
            Identifier.of("SEQGENERATOR"), null, null))).isEqualTo("SELECT nextval('SEQGENERATOR')");
        assertThat(new PostgreSQLDialectFactory().create(17, 0).render(new io.vidocq.mansart.jpa.dialect.sql.NextValue(
            Identifier.quoted("Seq"), Identifier.of("shop"), null))).isEqualTo("SELECT nextval('shop.\"Seq\"')");
    }

    @Test
    void theLockTimeoutIsScopedToTheTransaction() {
        var dialect = new PostgreSQLDialectFactory().create(17, 0);
        assertThat(dialect.lockTimeout(100)).isEqualTo("SET LOCAL lock_timeout = '100ms'");
        assertThat(dialect.lockFailure(new java.sql.SQLException("no lock", "55P03")))
            .isEqualTo(io.vidocq.mansart.jpa.dialect.Dialect.LockFailure.TIMEOUT);
        assertThat(dialect.lockFailure(new java.sql.SQLException("deadlock", "40P01")))
            .isEqualTo(io.vidocq.mansart.jpa.dialect.Dialect.LockFailure.PESSIMISTIC);
    }
}
