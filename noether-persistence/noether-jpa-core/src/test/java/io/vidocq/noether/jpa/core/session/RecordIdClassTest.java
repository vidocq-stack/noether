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
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.model.build.fixtures.key.Booth;
import io.vidocq.noether.jpa.core.model.build.fixtures.key.BoothKey;
import io.vidocq.noether.jpa.core.model.build.fixtures.key.Stall;
import io.vidocq.noether.jpa.core.model.build.fixtures.key.StallKey;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import org.junit.jupiter.api.Test;

/**
 * A record id class (§2.4.1) is built from the identifier attributes by component name: its components need not come
 * in the order the entity declares its identifier attributes.
 */
class RecordIdClassTest {

    @Test
    void theIdentifierOfAnEntityIsTheRecordOfItsOwnValues() {
        try (EntityManagerFactory factory = factory()) {
            Booth booth = new Booth("A", "7");
            factory.runInTransaction(em -> em.persist(booth));

            assertThat(factory.getPersistenceUnitUtil().getIdentifier(booth)).isEqualTo(new BoothKey("7", "A"));
        }
    }

    @Test
    void anEntityIsFoundByItsRecordKey() {
        try (EntityManagerFactory factory = factory()) {
            factory.runInTransaction(em -> em.persist(new Booth("B", "3")));

            factory.runInTransaction(em -> {
                Booth found = em.find(Booth.class, new BoothKey("3", "B"));
                assertThat(found).isNotNull();
                assertThat(em.find(Booth.class, new BoothKey("3", "B"))).isSameAs(found);
            });
        }
    }

    @Test
    void aRecordKeyWhoseComponentsHaveOtherTypesBuildsTheIdentifier() {
        try (EntityManagerFactory factory = factory()) {
            Stall stall = new Stall("C", 4);
            factory.runInTransaction(em -> em.persist(stall));

            assertThat(factory.getPersistenceUnitUtil().getIdentifier(stall)).isEqualTo(new StallKey(4, "C"));
            factory.runInTransaction(em -> assertThat(em.find(Stall.class, new StallKey(4, "C"))).isNotNull());
        }
    }

    private static EntityManagerFactory factory() {
        return new PersistenceConfiguration("record-id-class")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Booth.class)
            .managedClass(Stall.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:record-id-class;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
            .createEntityManagerFactory();
    }
}
