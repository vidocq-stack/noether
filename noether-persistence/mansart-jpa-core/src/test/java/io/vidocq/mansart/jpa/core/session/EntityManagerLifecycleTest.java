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
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TransactionRequiredException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, §3.2 and §7.3–7.5: the life cycle of an application-managed entity manager. */
class EntityManagerLifecycleTest {

    private EntityManagerFactory emf;

    @BeforeEach
    void open() {
        emf = Persistence.createEntityManagerFactory("h2");
    }

    @AfterEach
    void close() {
        if (emf.isOpen()) {
            emf.close();
        }
    }

    @Test
    void anEntityManagerIsOpenAndKnowsItsFactory() {
        EntityManager em = emf.createEntityManager();
        assertThat(em.isOpen()).isTrue();
        assertThat(em.getEntityManagerFactory()).isSameAs(emf);
        assertThat(em.getFlushMode()).isEqualTo(FlushModeType.AUTO); // §3.11.2 default
        em.close();
    }

    @Test
    void itsPropertiesAreTheFactoryOnesPlusItsOwn() { // §7.3
        EntityManager em = emf.createEntityManager(Map.of("from.em", "em", "overridden", "em"));
        assertThat(em.getProperties()).containsEntry("from.xml", "xml").containsEntry("from.em", "em").containsEntry("overridden", "em");
        em.setProperty("later", "set");
        assertThat(em.getProperties()).containsEntry("later", "set");
        assertThatThrownBy(() -> em.getProperties().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        em.close();
    }

    @Test
    void anInvalidTimeoutHintIsRefused() { // EntityManager.setProperty: recognized name, invalid value
        EntityManager em = emf.createEntityManager();
        assertThatThrownBy(() -> em.setProperty("jakarta.persistence.lock.timeout", "soon")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> em.setProperty("jakarta.persistence.query.timeout", "never")).isInstanceOf(IllegalArgumentException.class);
        em.setProperty("jakarta.persistence.lock.timeout", 1000);
        em.setProperty("jakarta.persistence.query.timeout", "2000");
        em.close();
    }

    @Test
    void aClosedEntityManagerRefusesEverythingButIsOpenGetPropertiesAndGetTransaction() { // §7.7, EntityManager.close
        EntityManager em = emf.createEntityManager();
        em.close();
        assertThat(em.isOpen()).isFalse();
        assertThat(em.getProperties()).isNotNull();
        assertThat(em.getTransaction()).isNotNull();
        assertThatThrownBy(em::close).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::getEntityManagerFactory).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::clear).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::flush).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::getFlushMode).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em.setFlushMode(FlushModeType.COMMIT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em.setProperty("a", "b")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em.persist(new Object())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em.find(Object.class, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em.createQuery("select x from X x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> em.unwrap(EntityManager.class)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::getDelegate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::joinTransaction).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::isJoinedToTransaction).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::getMetamodel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(em::getCriteriaBuilder).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closingTheFactoryClosesItsEntityManagers() { // §7.3: "considered to be in the closed state"
        EntityManager em = emf.createEntityManager();
        emf.close();
        assertThat(em.isOpen()).isFalse();
        assertThatThrownBy(em::clear).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theTransactionIsAlwaysTheSameObject() { // EntityManager.getTransaction: may be used serially
        EntityManager em = emf.createEntityManager();
        assertThat(em.getTransaction()).isSameAs(em.getTransaction());
        em.close();
    }

    @Test
    void aResourceLocalFactoryHasNoSynchronizationType() { // EntityManagerFactory.createEntityManager(SynchronizationType)
        assertThatThrownBy(() -> emf.createEntityManager(SynchronizationType.SYNCHRONIZED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> emf.createEntityManager(SynchronizationType.UNSYNCHRONIZED, Map.of()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void isJoinedToTransactionFollowsTheResourceLocalTransaction() {
        EntityManager em = emf.createEntityManager();
        assertThat(em.isJoinedToTransaction()).isFalse();
        em.getTransaction().begin();
        assertThat(em.isJoinedToTransaction()).isTrue();
        em.joinTransaction(); // already part of it: nothing to do
        em.getTransaction().rollback();
        assertThat(em.isJoinedToTransaction()).isFalse();
        assertThatThrownBy(em::joinTransaction).isInstanceOf(TransactionRequiredException.class);
        em.close();
    }

    @Test
    void unwrapAndGetDelegateReturnTheEntityManager() {
        EntityManager em = emf.createEntityManager();
        assertThat(em.unwrap(EntityManager.class)).isSameAs(em);
        assertThat(em.getDelegate()).isSameAs(em);
        assertThatThrownBy(() -> em.unwrap(String.class)).isInstanceOf(PersistenceException.class);
        em.close();
    }

    @Test
    void flushModeCanBeChanged() {
        EntityManager em = emf.createEntityManager();
        em.setFlushMode(FlushModeType.COMMIT);
        assertThat(em.getFlushMode()).isEqualTo(FlushModeType.COMMIT);
        em.close();
    }
}
