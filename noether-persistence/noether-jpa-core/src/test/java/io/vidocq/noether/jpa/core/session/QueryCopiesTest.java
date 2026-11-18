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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.model.build.fixtures.idx.Agent;
import io.vidocq.noether.jpa.core.model.build.fixtures.idx.Affair;
import io.vidocq.noether.jpa.core.model.build.fixtures.idx.Bureau;
import io.vidocq.noether.jpa.core.spi.QueryCopies;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.TypedQuery;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A query another entity manager of the unit created, created again as it was then: what a container facade runs in
 * the persistence context of the current transaction (BUG-20261118-84).
 */
class QueryCopiesTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private EntityManagerFactory emf;

    @BeforeEach
    void open() {
        emf = new PersistenceConfiguration("copies").provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Bureau.class).managedClass(Agent.class).managedClass(Affair.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:copies-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
            .createEntityManagerFactory();
        emf.runInTransaction(em -> {
            em.persist(new Bureau(1, "Sûreté"));
            em.persist(new Bureau(2, "Brigade"));
        });
    }

    @AfterEach
    void close() {
        emf.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCriteriaQueryIsCopiedAsItWasCreatedWithItsParameters() {
        try (var first = emf.createEntityManager(); var second = emf.createEntityManager()) {
            var builder = first.getCriteriaBuilder();
            var criteria = builder.createQuery(String.class);
            var root = criteria.from(Bureau.class);
            var id = builder.parameter(Long.class);
            criteria.select(root.get("name")).where(builder.equal(root.get("id"), id));
            var created = first.createQuery(criteria);
            criteria.where(builder.equal(root.get("id"), 2L));
            var copy = (TypedQuery<String>) second.unwrap(QueryCopies.class).copy(created);
            assertThat(copy.setParameter(id, 1L).getResultList()).containsExactly("Sûreté");
        }
    }

    @Test
    void aQueryStringIsCopied() {
        try (var first = emf.createEntityManager(); var second = emf.createEntityManager()) {
            var created = first.createQuery("SELECT b.name FROM Bureau b WHERE b.id = :id", String.class);
            assertThat(second.unwrap(QueryCopies.class).copy(created).setParameter("id", 2L).getSingleResult())
                .isEqualTo("Brigade");
        }
    }

    @Test
    void onlyAQueryStringOrACriteriaQueryOfNoetherIsCopied() {
        try (var em = emf.createEntityManager()) {
            var copies = em.unwrap(QueryCopies.class);
            var nativeQuery = em.createNativeQuery("SELECT 1");
            assertThatThrownBy(() -> copies.copy(nativeQuery)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a query string or criteria query");
        }
    }
}
