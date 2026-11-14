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
package io.vidocq.noether.jpa.processorit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.processorit.closed.Account;
import io.vidocq.noether.jpa.processorit.closed.Badge;
import io.vidocq.noether.jpa.processorit.closed.Note;
import io.vidocq.noether.jpa.processorit.closed.Tier;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Compiled with noether-jpa-processor, the code a unit runs of a package that is neither exported nor opened, beyond
 * its entities: a converter, an enum mapped by its {@code @EnumeratedValue} field.
 */
class ClosedPackageCodeModulePathTest {

    @BeforeAll
    static void thePackageIsClosedToNoether() {
        Module application = Account.class.getModule();
        assertThat(application.isOpen(Account.class.getPackageName(), ManagedAccess.class.getModule())).isFalse();
        assertThat(application.isExported(Account.class.getPackageName())).isFalse();
    }

    @Test
    void aConverterOfTheClosedPackageConvertsTheAttribute() { // §3.9
        try (var factory = factory("processor-converter", Note.class)) {
            factory.runInTransaction(em -> em.persist(new Note(1, "quiet")));
            factory.runInTransaction(em -> {
                assertThat(em.createNativeQuery("select text from Note where id = 1").getSingleResult()).isEqualTo("QUIET");
                assertThat(em.find(Note.class, 1L).text()).isEqualTo("quiet");
            });
        }
    }

    @Test
    void anEnumOfTheClosedPackageIsStoredAsItsEnumeratedValue() { // §11.1.18
        try (var factory = factory("processor-enumerated-value", Badge.class)) {
            factory.runInTransaction(em -> em.persist(new Badge(1, Tier.GOLD)));
            factory.runInTransaction(em -> {
                assertThat(((Number) em.createNativeQuery("select tier from Badge where id = 1").getSingleResult()).intValue())
                    .isEqualTo(3);
                assertThat(em.find(Badge.class, 1L).tier()).isEqualTo(Tier.GOLD);
            });
        }
    }

    private static EntityManagerFactory factory(String name, Class<?>... classes) {
        var unit = new PersistenceConfiguration(name)
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        for (Class<?> managed : classes) {
            unit.managedClass(managed);
        }
        return unit.createEntityManagerFactory();
    }
}
