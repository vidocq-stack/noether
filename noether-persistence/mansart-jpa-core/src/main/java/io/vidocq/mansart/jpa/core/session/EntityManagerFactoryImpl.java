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

import io.vidocq.mansart.jpa.core.bootstrap.UnitSettings;
import io.vidocq.mansart.jpa.core.jdbc.ConnectionSource;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Query;
import jakarta.persistence.SchemaManager;
import jakarta.persistence.SynchronizationType;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The {@link EntityManagerFactory} of a resource-local persistence unit. Thread-safe and lock-free: its state is
 * immutable but for the open flag and the set of transactions still active, which are rolled back when it closes.
 */
public final class EntityManagerFactoryImpl implements EntityManagerFactory {

    private final UnitSettings settings;
    private final ConnectionSource connections;
    private final Cache cache = new NoSecondLevelCache();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Set<ResourceLocalTransaction> activeTransactions = ConcurrentHashMap.newKeySet();

    public EntityManagerFactoryImpl(UnitSettings settings, ConnectionSource connections) {
        this.settings = settings;
        this.connections = connections;
    }

    UnitSettings settings() {
        return settings;
    }

    ConnectionSource connections() {
        return connections;
    }

    void register(ResourceLocalTransaction transaction) {
        activeTransactions.add(transaction);
    }

    void unregister(ResourceLocalTransaction transaction) {
        activeTransactions.remove(transaction);
    }

    private void checkOpen() {
        if (!open.get()) {
            throw new IllegalStateException("The EntityManagerFactory of persistence unit " + settings.unitName() + " is closed");
        }
    }

    @Override
    public EntityManager createEntityManager() {
        return createEntityManager(Map.of());
    }

    @Override
    public EntityManager createEntityManager(Map<?, ?> map) {
        checkOpen();
        return new EntityManagerImpl(this, map);
    }

    @Override
    public EntityManager createEntityManager(SynchronizationType synchronizationType) {
        return createEntityManager(synchronizationType, Map.of());
    }

    /** §7.3: a synchronization type only makes sense for JTA entity managers. */
    @Override
    public EntityManager createEntityManager(SynchronizationType synchronizationType, Map<?, ?> map) {
        checkOpen();
        throw new IllegalStateException("The persistence unit " + settings.unitName()
            + " is resource-local: a synchronization type only applies to JTA entity managers");
    }

    @Override
    public CriteriaBuilder getCriteriaBuilder() {
        checkOpen();
        throw NotYet.milestone("P8", "the Criteria API");
    }

    @Override
    public Metamodel getMetamodel() {
        checkOpen();
        throw NotYet.milestone("P8", "the metamodel API");
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    /** Closing rolls back the transactions left active and makes every entity manager of the factory closed. */
    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            throw new IllegalStateException("The EntityManagerFactory of persistence unit " + settings.unitName() + " is already closed");
        }
        for (ResourceLocalTransaction transaction : activeTransactions) {
            try {
                transaction.abandon();
            } catch (RuntimeException e) {
                // one failed release must not keep the others from being rolled back
                System.getLogger(EntityManagerFactoryImpl.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to release a transaction of persistence unit " + settings.unitName(), e);
            }
        }
    }

    @Override
    public String getName() {
        return settings.unitName();
    }

    @Override
    public Map<String, Object> getProperties() {
        checkOpen();
        return settings.properties();
    }

    @Override
    public Cache getCache() {
        checkOpen();
        return cache;
    }

    @Override
    public PersistenceUnitUtil getPersistenceUnitUtil() {
        checkOpen();
        throw NotYet.milestone("P5", "PersistenceUnitUtil");
    }

    @Override
    public PersistenceUnitTransactionType getTransactionType() {
        return settings.transactionType();
    }

    @Override
    public SchemaManager getSchemaManager() {
        checkOpen();
        throw NotYet.milestone("P9", "the SchemaManager");
    }

    @Override
    public void addNamedQuery(String name, Query query) {
        checkOpen();
        throw NotYet.milestone("P7", "named queries");
    }

    @Override
    public <T> T unwrap(Class<T> cls) {
        checkOpen();
        if (cls.isInstance(this)) {
            return cls.cast(this);
        }
        throw new PersistenceException("Unsupported unwrap type " + cls.getName());
    }

    @Override
    public <T> void addNamedEntityGraph(String graphName, EntityGraph<T> entityGraph) {
        checkOpen();
        throw NotYet.milestone("P8", "entity graphs");
    }

    @Override
    public <R> Map<String, TypedQueryReference<R>> getNamedQueries(Class<R> resultType) {
        checkOpen();
        throw NotYet.milestone("P7", "named queries");
    }

    @Override
    public <E> Map<String, EntityGraph<? extends E>> getNamedEntityGraphs(Class<E> entityType) {
        checkOpen();
        throw NotYet.milestone("P8", "entity graphs");
    }

    @Override
    public void runInTransaction(Consumer<EntityManager> work) {
        callInTransaction(em -> {
            work.accept(em);
            return null;
        });
    }

    /** A new entity manager, in a new transaction committed on success and rolled back on any failure, then closed. */
    @Override
    public <R> R callInTransaction(Function<EntityManager, R> work) {
        checkOpen();
        try (EntityManager em = createEntityManager()) {
            EntityTransaction transaction = em.getTransaction();
            transaction.begin();
            R result;
            try {
                result = work.apply(em);
            } catch (RuntimeException | Error failure) {
                if (transaction.isActive()) {
                    try {
                        transaction.rollback();
                    } catch (RuntimeException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                }
                throw failure;
            }
            if (transaction.isActive()) {
                transaction.commit();
            } else if (!isOpen()) {
                // the factory was closed meanwhile and rolled the work back: that is not a success
                throw new IllegalStateException("The EntityManagerFactory was closed during the transaction, which was rolled back");
            }
            return result;
        }
    }
}
