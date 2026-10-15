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

import io.vidocq.noether.jpa.moduleit.closed.Secret;
import io.vidocq.noether.jpa.moduleit.opened.Book;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

/** The only requirement Noether puts on an application module: opening the packages of its managed classes. */
class OpensModulePathTest {

    private static PersistenceConfiguration unit(Class<?> entity) {
        return new PersistenceConfiguration("module-it").provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(entity).property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:module-it");
    }

    @Test
    void theseTestsRunInANamedModule() {
        assertThat(getClass().getModule().getName()).isEqualTo("io.vidocq.noether.jpa.moduleit");
        assertThat(Book.class.getModule().isOpen(Book.class.getPackageName(), ModuleLayer.boot()
            .findModule("io.vidocq.noether.jpa.core").orElseThrow())).isTrue();
    }

    @Test
    void anEntityOfAnOpenedPackageIsMapped() {
        try (EntityManagerFactory emf = unit(Book.class).createEntityManagerFactory()) {
            assertThat(emf.isOpen()).isTrue();
        }
    }

    @Test
    void aModuleWithoutAReadEdgeToTheGeneratedReferenceSpiKeepsEagerLoading() { // §2.10, §11.1.26
        var type = io.vidocq.noether.jpa.moduleit.opened.PropertyBook.class;
        assertThat(type.getModule().canRead(ModuleLayer.boot().findModule("io.vidocq.noether.jpa.core").orElseThrow())).isFalse();
        try (var emf = unit(type).property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            var parent = new io.vidocq.noether.jpa.moduleit.opened.PropertyBook(1, "parent", null);
            emf.runInTransaction(em -> {
                em.persist(parent);
                em.persist(new io.vidocq.noether.jpa.moduleit.opened.PropertyBook(2, "child", parent));
            });
            try (var em = emf.createEntityManager()) {
                var child = em.find(type, 2L);
                assertThat(emf.getPersistenceUnitUtil().isLoaded(child, "parent")).isTrue();
                assertThat(child.getParent().getTitle()).isEqualTo("parent");
            }
        }
    }

    @Test
    void anEntityOfAPackageThatIsNotOpenedIsRefusedWithTheClauseToAdd() {
        assertThatThrownBy(() -> unit(Secret.class).createEntityManagerFactory()).isInstanceOf(PersistenceException.class)
            .hasMessageContaining(Secret.class.getName())
            .hasMessageContaining("opens io.vidocq.noether.jpa.moduleit.closed to io.vidocq.noether.jpa.core;");
    }
}
