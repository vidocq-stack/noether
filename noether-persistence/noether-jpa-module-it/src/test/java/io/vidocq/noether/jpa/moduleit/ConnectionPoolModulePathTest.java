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
package io.vidocq.noether.jpa.moduleit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.moduleit.opened.Book;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * Decision D7: {@code noether-jpa-pool-mansart} on the module path is enough to pool the connections of a
 * {@code jakarta.persistence.jdbc.*} unit. The application module does not require it: service binding resolves it,
 * because {@code io.vidocq.noether.jpa.core} uses the {@code ConnectionPoolFactory} service that it provides.
 */
class ConnectionPoolModulePathTest {

    private static final String POOL_MODULE = "io.vidocq.noether.jpa.pool.mansart";

    private static PersistenceConfiguration unit(String database) {
        return new PersistenceConfiguration("module-it-pool").provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Book.class).property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + database);
    }

    private static Object session(EntityManagerFactory emf) {
        return emf.callInTransaction(em -> em.createNativeQuery("select session_id()").getSingleResult());
    }

    @Test
    void thePoolModuleIsResolvedThroughServiceBindingAlone() {
        assertThat(ModuleLayer.boot().findModule(POOL_MODULE)).isPresent();
        assertThat(getClass().getModule().getDescriptor().requires())
            .noneMatch(requires -> requires.name().equals(POOL_MODULE));
    }

    @Test
    void theTransactionsOfAUnitShareThePooledConnections() {
        try (EntityManagerFactory emf = unit("module-it-pooled;DB_CLOSE_DELAY=-1").createEntityManagerFactory()) {
            assertThat(session(emf)).isEqualTo(session(emf));
        }
    }

    @Test
    void poolingTurnedOffGivesEachTransactionItsOwnConnection() {
        try (EntityManagerFactory emf = unit("module-it-unpooled;DB_CLOSE_DELAY=-1")
                .property("io.vidocq.noether.jpa.pool", "false").createEntityManagerFactory()) {
            assertThat(session(emf)).isNotEqualTo(session(emf));
        }
    }

    @Test
    void anInvalidPoolSettingFailsTheCreationOfTheFactory() {
        PersistenceConfiguration unit = unit("module-it-invalid;DB_CLOSE_DELAY=-1").property("io.vidocq.noether.jpa.pool.max-size", "0");
        assertThatThrownBy(unit::createEntityManagerFactory).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("io.vidocq.noether.jpa.pool.max-size");
    }

    @Test
    void closingTheFactoryClosesItsPool() throws SQLException {
        // without DB_CLOSE_DELAY, H2 drops an in-memory database when its last connection closes
        String database = "module-it-closing";
        try (EntityManagerFactory emf = unit(database).createEntityManagerFactory()) {
            emf.runInTransaction(em -> em.createNativeQuery("create table kept (id int)").executeUpdate());
            assertThat(tableExists(database)).isTrue(); // the idle pooled connection keeps the database
        }
        assertThat(tableExists(database)).isFalse();
    }

    private static boolean tableExists(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + database);
             ResultSet tables = connection.getMetaData().getTables(null, null, "KEPT", null)) {
            return tables.next();
        }
    }
}
