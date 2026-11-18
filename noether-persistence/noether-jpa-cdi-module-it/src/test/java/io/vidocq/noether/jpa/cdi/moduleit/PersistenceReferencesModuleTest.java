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
package io.vidocq.noether.jpa.cdi.moduleit;

import io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer;
import io.vidocq.noether.jpa.cdi.NoetherPersistenceExtension;
import io.vidocq.noether.jpa.cdi.PersistenceReferences;
import io.vidocq.noether.jpa.cdi.PersistenceReferences.Reference;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceContextType;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.TransactionRequiredException;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Jakarta EE 11 Platform §5.13 and §5.14: a persistence context or unit declared on a class, with a name, is a
 * reference a component looks up under {@code java:comp/env}. The extension lists them, each with the bean a naming
 * service binds its name to.
 */
class PersistenceReferencesModuleTest {

    /** A persistence context declared on a class without the name the reference needs. */
    @Dependent
    @PersistenceContext(unitName = "container")
    public static class UnnamedReference {
    }

    /** An extended persistence context declared on a class, which only a stateful component may hold. */
    @Dependent
    @PersistenceContext(name = "persistence/extended", unitName = "container", type = PersistenceContextType.EXTENDED)
    public static class ExtendedReference {
    }

    /** A second declaration of a name of {@link ReferencingComponent}, for another bean. */
    @Dependent
    @PersistenceUnit(name = "persistence/container", unitName = "container")
    public static class ConflictingReference {
    }

    /** The same reference as {@link ReferencingComponent}'s, declared by another class. */
    @Dependent
    @PersistenceContext(name = "persistence/container", unitName = "container")
    public static class RepeatedReference {
    }

    private static VaubanContainer container(Class<?>... classes) {
        var builder = VaubanContainer.builder().addBeanClass(NoetherPersistenceExtension.class)
            .addBeanClass(MansartTransactionsProducer.class).addBeanClass(Services.class);
        for (Class<?> type : classes) builder.addBeanClass(type);
        return builder.build();
    }

    private static Reference reference(VaubanContainer container, String name) {
        return container.select(PersistenceReferences.class).references().stream()
            .filter(reference -> reference.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no reference " + name));
    }

    private static Object lookup(VaubanContainer container, Reference reference) {
        return container.getBeanManager().createInstance().select(reference.type(), reference.qualifier()).get();
    }

    @Test
    void theReferencesAClassDeclaresAreListedByName() {
        try (var container = container(ReferencingComponent.class)) {
            assertThat(container.select(PersistenceReferences.class).references())
                .extracting(Reference::name, Reference::type)
                .containsExactly(tuple("persistence/container", EntityManager.class),
                    tuple("persistence/container-factory", EntityManagerFactory.class),
                    tuple("persistence/container-unsynchronized", EntityManager.class));
        }
    }

    @Test
    void aContextReferenceIsOneTransactionScopedManagerForEveryLookup() throws Exception {
        try (var container = container(ReferencingComponent.class)) {
            var reference = reference(container, "persistence/container");
            var manager = (EntityManager) lookup(container, reference);

            assertThat(lookup(container, reference)).isSameAs(manager);
            assertThatThrownBy(() -> manager.persist(new Record(51))).isInstanceOf(TransactionRequiredException.class);
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            var record = new Record(51);
            manager.persist(record);
            assertThat(manager.find(Record.class, 51)).isSameAs(record);
            assertThat(manager.isJoinedToTransaction()).isTrue();
            tm.commit();
        }
    }

    @Test
    void aContextReferenceKeepsItsSynchronization() throws Exception {
        try (var container = container(ReferencingComponent.class)) {
            var manager = (EntityManager) lookup(container, reference(container, "persistence/container-unsynchronized"));
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            try {
                assertThat(manager.isJoinedToTransaction()).isFalse();
            } finally {
                tm.rollback();
            }
        }
    }

    @Test
    void aUnitReferenceIsTheFactoryOfItsUnit() {
        try (var container = container(ReferencingComponent.class, Repository.class)) {
            var factory = lookup(container, reference(container, "persistence/container-factory"));

            assertThat(factory).isSameAs(container.select(Repository.class).factory);
        }
    }

    @Test
    void twoClassesMayDeclareTheSameReference() {
        try (var container = container(ReferencingComponent.class, RepeatedReference.class)) {
            assertThat(container.select(PersistenceReferences.class).references())
                .extracting(Reference::name).containsOnlyOnce("persistence/container");
        }
    }

    @Test
    void aDeploymentWithoutReferencesHasNoListing() {
        // The extension runs on each part of a deployment, a module processed at build or classes at start: a part
        // without references adds no bean, so a deployment with one part that declares some holds one listing.
        try (var container = container(Repository.class)) {
            assertThat(container.getBeanManager().getBeans(PersistenceReferences.class)).isEmpty();
        }
    }

    @Test
    void aReferenceOnAClassWithoutANameFailsTheDeployment() {
        assertThatThrownBy(() -> container(UnnamedReference.class).close())
            .hasStackTraceContaining(UnnamedReference.class.getName())
            .hasStackTraceContaining("name");
    }

    @Test
    void anExtendedReferenceOnAClassFailsTheDeployment() {
        assertThatThrownBy(() -> container(ExtendedReference.class).close())
            .hasStackTraceContaining(ExtendedReference.class.getName())
            .hasStackTraceContaining("EXTENDED");
    }

    @Test
    void aNameDeclaredForTwoBeansFailsTheDeployment() {
        assertThatThrownBy(() -> container(ReferencingComponent.class, ConflictingReference.class).close())
            .hasStackTraceContaining("persistence/container")
            .hasStackTraceContaining(ConflictingReference.class.getName());
    }
}
