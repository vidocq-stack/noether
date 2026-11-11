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
package io.vidocq.noether.jpa.core.generation;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.jdbc.ConnectionSource;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.noether.jpa.core.model.build.fixtures.Account;
import io.vidocq.noether.jpa.core.model.build.fixtures.Badge;
import io.vidocq.noether.jpa.core.model.build.fixtures.Stamp;
import io.vidocq.noether.jpa.core.model.build.fixtures.Token;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SequenceGenerator;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * BUG-20261018-04: a TABLE generator (§11.1.51) allocates its identifiers on a connection of its own, committed on its
 * own, while the persisting transaction keeps its connection. A unit with one needs two connections; a Noether pool
 * sized below that is refused when the factory is created, not at the first persist.
 */
class IdGeneratorsTest {

    private static final ConnectionSource UNSIZED = () -> {
        throw new AssertionError("the check acquires no connection");
    };

    private static List<EntityModel> entities(Class<?>... classes) {
        return EntityModelBuilder.build(Stream.of(classes).map(Class::getName).toList(), IdGeneratorsTest.class.getClassLoader())
            .entities();
    }

    private static ConnectionSource pool(int maxSize) {
        return new ConnectionSource() {
            @Override
            public Connection acquire() {
                throw new AssertionError("the check acquires no connection");
            }

            @Override
            public OptionalInt maxSize() {
                return OptionalInt.of(maxSize);
            }
        };
    }

    private static void check(ConnectionSource connections, Class<?>... classes) {
        IdGenerators.checkConnections("ids", entities(classes), connections);
    }

    @Test
    void aTableGeneratorRefusesAPoolOfOneConnection() {
        assertThatThrownBy(() -> check(pool(1), Badge.class, Account.class)).isInstanceOf(PersistenceException.class)
            .hasMessage("Persistence unit ids sets io.vidocq.noether.jpa.pool.max-size=1, but a TABLE identifier generator "
                + "needs at least 2 connections: one for the transaction, one for the allocation of identifiers, which it "
                + "commits on its own connection. TABLE generators of the unit: Badge (TABLE, generator badges). "
                + "Set io.vidocq.noether.jpa.pool.max-size to 2 or more.");
    }

    @Test
    void autoIsATableGeneratorUnlessTheIdentifierIsAUuidOrASequenceIsNamed() { // §11.1.20
        assertThatThrownBy(() -> check(pool(1), Stamp.class, UnnamedTable.class, AutoUuid.class, AutoSequence.class))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("Stamp (AUTO, default table generator)")
            .hasMessageContaining("UnnamedTable (TABLE, default table generator)")
            .hasMessageNotContaining("AutoUuid").hasMessageNotContaining("AutoSequence");
    }

    @Test
    void sequenceUuidAndIdentityGeneratorsAcceptAPoolOfOneConnection() {
        assertThatNoException().isThrownBy(() -> check(pool(1), Account.class, Token.class, AutoUuid.class, AutoSequence.class,
            Counted.class));
    }

    @Test
    void twoConnectionsAreEnoughForATableGenerator() {
        assertThatNoException().isThrownBy(() -> check(pool(2), Badge.class, Stamp.class));
    }

    @Test
    void aSourceOfUnknownSizeIsNotChecked() { // one connection per transaction, the pool default, a DataSource
        assertThatNoException().isThrownBy(() -> check(UNSIZED, Badge.class, Stamp.class));
    }

    /** BUG-20261111-04: a JTA unit with a table generator and nowhere to allocate is refused when the factory is created. */
    @Test
    void aJtaUnitWithATableGeneratorAndNoNonJtaDataSourceIsRefused() {
        assertThatThrownBy(() -> IdGenerators.checkAllocations("ids", entities(Badge.class, Stamp.class, Account.class),
                Optional.empty()))
            .isInstanceOf(PersistenceException.class)
            .hasMessage("Persistence unit ids is JTA and has TABLE identifier generators, but no non-JTA data source to "
                + "allocate through. A TABLE generator commits each allocation on a connection of its own, and the "
                + "container enlists every connection of the JTA data source in the transaction. TABLE generators of the "
                + "unit: Badge (TABLE, generator badges), Stamp (AUTO, default table generator). Declare a "
                + "<non-jta-data-source> for the unit (on Quarkus, a datasource with "
                + "quarkus.datasource.\"name\".jdbc.transactions=disabled), or use SEQUENCE or IDENTITY identifiers.");
    }

    @Test
    void aUnitWithASourceToAllocateThroughOrWithoutTableGeneratorIsAccepted() {
        assertThatNoException().isThrownBy(() -> IdGenerators.checkAllocations("ids", entities(Badge.class, Stamp.class),
            Optional.of(UNSIZED)));
        assertThatNoException().isThrownBy(() -> IdGenerators.checkAllocations("ids",
            entities(Account.class, Token.class, AutoUuid.class, AutoSequence.class, Counted.class), Optional.empty()));
    }

    /** {@code TABLE} without a named generator: the default generator table. */
    @Entity(name = "UnnamedTable")
    public static class UnnamedTable {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE)
        long id;
    }

    /** {@code AUTO} on a UUID identifier: a random UUID, no table. */
    @Entity(name = "AutoUuid")
    public static class AutoUuid {
        @Id
        @GeneratedValue
        UUID id;
    }

    /** {@code AUTO} naming a sequence generator: the sequence, no table. */
    @Entity(name = "AutoSequence")
    public static class AutoSequence {
        @Id
        @GeneratedValue(generator = "numbers")
        @SequenceGenerator(name = "numbers")
        long id;
    }

    /** {@code IDENTITY}: generated by the insert, no table. */
    @Entity(name = "Counted")
    public static class Counted {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        long id;
    }
}
