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
package io.vidocq.noether.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.model.build.fixtures.Crew;
import io.vidocq.noether.jpa.core.model.build.fixtures.Sailor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaQuery;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * BUG-20261113-04: a query takes the query and lock timeouts of its unit, the entity manager's override them, and
 * its own hints override both (§8.2.1.9).
 */
class QueryTimeoutsTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String QUERY_TIMEOUT = "jakarta.persistence.query.timeout";
    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    private EntityManagerFactory factory;

    @BeforeEach
    void open() {
        factory = new PersistenceConfiguration("query-timeouts")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Crew.class).managedClass(Sailor.class)
            .property(PersistenceConfiguration.JDBC_URL,
                "jdbc:h2:mem:query-timeouts-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(QUERY_TIMEOUT, 1500)
            .property(LOCK_TIMEOUT, "250")
            .createEntityManagerFactory();
    }

    @AfterEach
    void close() {
        factory.close();
    }

    @Test
    void aQueryTakesTheTimeoutsOfItsUnit() {
        EntityManager em = factory.createEntityManager();
        TypedQuery<Crew> jpql = em.createQuery("select c from Crew c", Crew.class);
        assertThat(jpql.getTimeout()).isEqualTo(1500);
        assertThat(jpql.unwrap(JpqlQuery.class).lockTimeout()).isEqualTo(250);
        CriteriaQuery<Crew> criteria = em.getCriteriaBuilder().createQuery(Crew.class);
        criteria.select(criteria.from(Crew.class));
        assertThat(em.createQuery(criteria).getTimeout()).isEqualTo(1500);
        assertThat(em.createNativeQuery("select 1").getTimeout()).isEqualTo(1500);
    }

    @Test
    void theEntityManagerTimeoutsOverrideTheUnitOnes() {
        EntityManager em = factory.createEntityManager(Map.of(QUERY_TIMEOUT, 700));
        em.setProperty(LOCK_TIMEOUT, 50);
        TypedQuery<Crew> jpql = em.createQuery("select c from Crew c", Crew.class);
        assertThat(jpql.getTimeout()).isEqualTo(700);
        assertThat(jpql.unwrap(JpqlQuery.class).lockTimeout()).isEqualTo(50);
        assertThat(em.createNativeQuery("select 1").getTimeout()).isEqualTo(700);
    }

    @Test
    void theHintsOfAQueryOverrideItsEntityManager() {
        EntityManager em = factory.createEntityManager();
        TypedQuery<Crew> jpql = em.createQuery("select c from Crew c", Crew.class)
            .setHint(QUERY_TIMEOUT, 300)
            .setHint(LOCK_TIMEOUT, 0);
        assertThat(jpql.getTimeout()).isEqualTo(300);
        assertThat(jpql.unwrap(JpqlQuery.class).lockTimeout()).isZero();
        assertThat(em.createNativeQuery("select 1").setHint(QUERY_TIMEOUT, "300").getTimeout()).isEqualTo(300);
    }

    @Test
    void aHintThatIsNoTimeoutOrCacheModeIsRefused() {
        EntityManager em = factory.createEntityManager();
        Query nativeQuery = em.createNativeQuery("select 1");
        assertThatThrownBy(() -> nativeQuery.setHint(QUERY_TIMEOUT, "soon")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nativeQuery.setHint("jakarta.persistence.cache.storeMode", "SOMETIMES"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(nativeQuery.setHint("jakarta.persistence.cache.retrieveMode", " BYPASS ").getCacheRetrieveMode())
            .isEqualTo(jakarta.persistence.CacheRetrieveMode.BYPASS);
    }

    @Test
    void aUnitTimeoutThatIsNoNumberIsIgnored() {
        EntityManagerFactory lenient = new PersistenceConfiguration("query-timeouts-lenient")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Crew.class).managedClass(Sailor.class)
            .property(PersistenceConfiguration.JDBC_URL,
                "jdbc:h2:mem:query-timeouts-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(QUERY_TIMEOUT, "soon")
            .createEntityManagerFactory();
        try {
            assertThat(lenient.createEntityManager().createQuery("select c from Crew c", Crew.class).getTimeout()).isNull();
        } finally {
            lenient.close();
        }
    }
}
