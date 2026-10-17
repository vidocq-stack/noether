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

import io.vidocq.noether.jpa.processorit.closed.Ledger;
import io.vidocq.noether.jpa.processorit.closed.Account;
import io.vidocq.noether.jpa.processorit.closed.PremiumAccount;
import io.vidocq.noether.jpa.processorit.closed.Portfolio;
import io.vidocq.noether.jpa.processorit.closed.Profile;
import io.vidocq.noether.jpa.processorit.closed.Balance;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import org.junit.jupiter.api.Test;

/** Compiled with noether-jpa-processor, an application needs no opens: the generated accesses are handed over. */
class GeneratedAccessModulePathTest {

    @Test
    void secondaryBusinessKeyUsesGeneratedPropertyAccessInAClosedModule() { // §11.1.26, §11.1.47
        var unit = new PersistenceConfiguration("processor-secondary-business")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(io.vidocq.noether.jpa.processorit.closed.SecondaryBusinessCustomer.class)
            .managedClass(io.vidocq.noether.jpa.processorit.closed.SecondaryBusinessOwner.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-secondary-business")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        try (var factory = unit.createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var customer = new io.vidocq.noether.jpa.processorit.closed.SecondaryBusinessCustomer(41, "CLOSED");
            factory.runInTransaction(writer -> writer.persist(
                new io.vidocq.noether.jpa.processorit.closed.SecondaryBusinessOwner(1, customer)));
            var owner = em.find(io.vidocq.noether.jpa.processorit.closed.SecondaryBusinessOwner.class, 1L);
            assertThat(owner.getCustomer()).isSameAs(em.find(customer.getClass(), 41L));
            assertThat(owner.getCustomer().getCode()).isEqualTo("CLOSED");
            assertThat(customer.getClass().getModule().isOpen(customer.getClass().getPackageName(),
                io.vidocq.noether.jpa.core.spi.ManagedAccess.class.getModule())).isFalse();
        }
    }

    @Test
    void lazyToOneUsesAClassFileGeneratedSubclassInAClosedApplicationModule() { // §2.10, §7.11
        var unit = new PersistenceConfiguration("processor-lazy-one")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(io.vidocq.noether.jpa.processorit.closed.LazyOwner.class)
            .managedClass(io.vidocq.noether.jpa.processorit.closed.LazyAccount.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-lazy-one;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        try (var emf = unit.createEntityManagerFactory(); var em = emf.createEntityManager()) {
            var account = new io.vidocq.noether.jpa.processorit.closed.LazyAccount(1, "closed");
            emf.runInTransaction(writer -> {
                writer.persist(account);
                writer.persist(new io.vidocq.noether.jpa.processorit.closed.LazyOwner(2, account));
            });
            var owner = em.find(io.vidocq.noether.jpa.processorit.closed.LazyOwner.class, 2L);
            var reference = owner.getAccount();
            assertThat(emf.getPersistenceUnitUtil().isLoaded(owner, "account")).isFalse();
            assertThat(reference.getClass().getName()).endsWith("$$NoetherLazy");
            assertThat(reference.getClass().getModule()).isSameAs(account.getClass().getModule());
            assertThat(account.getClass().getModule().isOpen(account.getClass().getPackageName(),
                io.vidocq.noether.jpa.core.spi.ManagedAccess.class.getModule())).isFalse();
            assertThat(reference.getId()).isEqualTo(1);
            assertThat(emf.getPersistenceUnitUtil().isLoaded(reference)).isFalse();
            assertThat(reference.getName()).isEqualTo("closed");
            assertThat(reference).isSameAs(em.find(io.vidocq.noether.jpa.processorit.closed.LazyAccount.class, 1L));
        }
    }

    @Test
    void xmlTablePerClassUsesClosedPackageGeneratedAccessAndMetamodel() { // §2.14.3, §12.2
        var unit = new PersistenceConfiguration("processor-tpc")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Account.class).managedClass(PremiumAccount.class)
            .mappingFile("orm/table-per-class.xml")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-tpc;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        try (var emf = unit.createEntityManagerFactory(); var em = emf.createEntityManager()) {
            assertThat(io.vidocq.noether.jpa.processorit.closed.Account_.id)
                .isSameAs(emf.getMetamodel().entity(Account.class).getId(long.class));
            em.getTransaction().begin(); em.persist(new PremiumAccount(1, "closed", 42)); em.getTransaction().commit(); em.clear();
            Account loaded = em.find(Account.class, 1L);
            assertThat(loaded).isInstanceOf(PremiumAccount.class).isSameAs(em.find(PremiumAccount.class, 1L));
            assertThat(em.createQuery("select a from Account a where a.name = 'closed'", Account.class).getSingleResult())
                .isSameAs(loaded);
            assertThat(em.createQuery("select TYPE(a) from Account a").getSingleResult()).isEqualTo(PremiumAccount.class);
            assertThat(em.createNativeQuery("select points from PremiumAccount where id = 1").getSingleResult()).isEqualTo(42);
            assertThat(em.createNativeQuery("select count(*) from INFORMATION_SCHEMA.TABLES where TABLE_NAME = 'ACCOUNT'")
                .getSingleResult()).isEqualTo(0L);
        }
    }

    @Test
    void embeddableMapKeysUseGeneratedAccessWithoutOpeningThePackage() { // §2.7, §11.1.8
        var unit = new PersistenceConfiguration("processor-map-keys")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider").managedClass(Ledger.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-map-keys;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        try (var emf = unit.createEntityManagerFactory()) {
            var key = new Balance(500, "EUR");
            Ledger ledger = new Ledger(1);
            ledger.balances().put(key, "deposit");
            emf.runInTransaction(em -> em.persist(ledger));
            emf.runInTransaction(em -> {
                assertThat(em.find(Ledger.class, 1L).balances()).containsEntry(key, "deposit");
                assertThat(em.createQuery("select key(b) from Ledger l join l.balances b", Balance.class).getResultList())
                    .containsExactly(key);
                em.remove(em.find(Ledger.class, 1L));
            });
        }
    }

    @Test
    void embeddedAssociationOverridesComposeGeneratedAccessesWithoutOpeningThePackage() {
        var unit = new PersistenceConfiguration("processor-embedded")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Portfolio.class).managedClass(Account.class).managedClass(PremiumAccount.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-embedded;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        try (var emf = unit.createEntityManagerFactory()) {
            assertThat(io.vidocq.noether.jpa.processorit.closed.Profile_.owner)
                .isSameAs(emf.getMetamodel().embeddable(Profile.class).getAttribute("owner"));
            emf.runInTransaction(em -> em.persist(new Portfolio(10, new Profile(7, new PremiumAccount(11, "nested", 42)))));
            emf.runInTransaction(em -> {
                Portfolio found = em.find(Portfolio.class, 10L);
                assertThat(found.profile().revision()).isEqualTo(7);
                assertThat(found.profile().owner()).isInstanceOf(PremiumAccount.class);
                assertThat(found.profile().owner().name()).isEqualTo("nested");
                assertThat(em.createQuery("select p.profile.owner from Portfolio p", Account.class).getSingleResult())
                    .isSameAs(found.profile().owner());
                assertThat(em.createNativeQuery("select PortfolioOwner from Portfolio where id = 10").getSingleResult())
                    .isEqualTo(11L);
                em.remove(found);
            });
            emf.runInTransaction(em -> {
                assertThat(em.find(Portfolio.class, 10L)).isNull();
                assertThat(em.find(Account.class, 11L)).isNull();
            });
        }
    }

    @Test
    void inheritanceUsesGeneratedAccessWithoutOpeningEntityPackages() throws Exception { // §2.14.1, §3.3
        String url = "jdbc:h2:mem:processor-inheritance;DB_CLOSE_DELAY=-1";
        try (var connection = java.sql.DriverManager.getConnection(url, "sa", "");
                var ddl = connection.createStatement()) {
            ddl.execute("create table Account (id bigint primary key, name varchar(50), version int, "
                + "DTYPE varchar(31), points int)");
        }
        var unit = new PersistenceConfiguration("processor-inheritance")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Account.class).managedClass(PremiumAccount.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa");
        try (var emf = unit.createEntityManagerFactory(); var em = emf.createEntityManager()) {
            assertThat(io.vidocq.noether.jpa.processorit.closed.Account_.id)
                .isSameAs(emf.getMetamodel().entity(Account.class).getId(long.class));
            assertThat(io.vidocq.noether.jpa.processorit.closed.PremiumAccount_.points)
                .isSameAs(emf.getMetamodel().entity(PremiumAccount.class).getAttribute("points"));
            em.getTransaction().begin();
            em.persist(new PremiumAccount(1, "owner", 100));
            em.getTransaction().commit();
            em.clear();
            Account loaded = em.find(Account.class, 1L);
            assertThat(loaded).isInstanceOf(PremiumAccount.class).isSameAs(em.find(PremiumAccount.class, 1L));
            assertThat(loaded.name()).isEqualTo("owner");
            assertThat(((PremiumAccount) loaded).points()).isEqualTo(100);
            assertThat(em.createQuery("select TYPE(a) from Account a").getResultList()).containsExactly(PremiumAccount.class);
        }
    }

    @Test
    void theEntityPackageIsNotOpenedToNoether() {
        Module noether = ModuleLayer.boot().findModule("io.vidocq.noether.jpa.core").orElseThrow();
        assertThat(getClass().getModule().getName()).isEqualTo("io.vidocq.noether.jpa.processorit");
        assertThat(Ledger.class.getModule().isOpen(Ledger.class.getPackageName(), noether)).isFalse();
        assertThat(Ledger.class.getModule().isExported(Ledger.class.getPackageName(), noether)).isFalse();
    }

    @Test
    void theEntityIsMappedThroughItsGeneratedAccess() { // without it, Noether would ask for an opens clause
        PersistenceConfiguration unit = new PersistenceConfiguration("processor-it")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider").managedClass(Ledger.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:processor-it");
        try (EntityManagerFactory emf = unit.createEntityManagerFactory()) {
            assertThat(emf.isOpen()).isTrue();
            assertThat(io.vidocq.noether.jpa.processorit.closed.Ledger_.class_).isSameAs(emf.getMetamodel().entity(Ledger.class));
        }
    }
}
