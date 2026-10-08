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
package io.vidocq.mansart.jpa.dialect.h2;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.dialect.DialectFactory;
import io.vidocq.mansart.jpa.dialect.sql.Delete;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import java.util.List;
import org.junit.jupiter.api.Test;

class H2DialectFactoryTest {

    @Test
    void theModuleProvidesTheFactory() {
        assertThat(H2DialectFactory.class.getModule().getDescriptor().provides())
            .anySatisfy(p -> {
                assertThat(p.service()).isEqualTo(DialectFactory.class.getName());
                assertThat(p.providers()).containsExactly(H2DialectFactory.class.getName());
            });
    }

    @Test
    void itRecognisesH2FromItsJdbcMetadata() { // DatabaseMetaData.getDatabaseProductName()
        DialectFactory factory = new H2DialectFactory();
        assertThat(factory.supports("H2")).isTrue();
        assertThat(factory.supports("PostgreSQL")).isFalse();
        assertThat(factory.create(2, 3).name()).isEqualTo("h2");
    }

    @Test
    void itRendersTheStandardStatements() {
        assertThat(new H2DialectFactory().create(2, 3).render(new Delete(Table.of("BOOK"), List.of(Identifier.of("ID")))))
            .isEqualTo("DELETE FROM BOOK WHERE ID = ?");
    }

    @Test
    void generatedKeysAreAskedForAsTheDatabaseFoldsTheirNames() {
        var dialect = new H2DialectFactory().create(2, 3);
        assertThat(dialect.generatedKeyName(Identifier.of("Id"))).isEqualTo("ID");
        assertThat(dialect.generatedKeyName(Identifier.quoted("Id"))).isEqualTo("Id");
    }

    @Test
    void aUniqueViolationIsADuplicateKey() {
        var dialect = new H2DialectFactory().create(2, 3);
        assertThat(dialect.isDuplicateKey(new java.sql.SQLException("duplicate", "23505"))).isTrue();
        assertThat(dialect.isDuplicateKey(new java.sql.SQLException("not null", "23502"))).isFalse();
    }
}
