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
import io.vidocq.mansart.jpa.core.bootstrap.BeanValidation;
import io.vidocq.mansart.jpa.core.jdbc.ConnectionSource;
import io.vidocq.mansart.jpa.core.flush.Dialects;
import io.vidocq.mansart.jpa.core.flush.EntityLoader;
import io.vidocq.mansart.jpa.core.flush.FlushEngine;
import io.vidocq.mansart.jpa.core.flush.Locks;
import io.vidocq.mansart.jpa.core.generation.IdGenerators;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.query.NamedQueries;
import io.vidocq.mansart.jpa.dialect.Dialect;
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
import java.sql.Connection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The {@link EntityManagerFactory} of a resource-local persistence unit. Thread-safe and lock-free: its state is
 * immutable but for the open flag and the set of transactions still active, which are rolled back when it closes.
 */
public final class EntityManagerFactoryImpl implements EntityManagerFactory {

    private final UnitSettings settings;
    private final BeanValidation.Validator validator;
    private final ConnectionSource connections;
    private final MappedUnit mapping;
    private final AtomicReference<FlushEngine> flushEngine = new AtomicReference<>();
    private final AtomicReference<IdGenerators> idGenerators = new AtomicReference<>();
    private final int batchSize;
    private final SecondLevelCache cache;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Set<ResourceLocalTransaction> activeTransactions = ConcurrentHashMap.newKeySet();
    private final NamedQueries namedQueries;
    private final Metamodel metamodel;
    private final EntityGraphs entityGraphs;
    private final io.vidocq.mansart.jpa.core.bootstrap.SchemaGeneration schemaGeneration;

    public EntityManagerFactoryImpl(UnitSettings settings, ConnectionSource connections, MappedUnit mapping,
            BeanValidation.Validator validator) {
        this.settings = settings;
        this.validator = validator;
        this.connections = connections;
        this.mapping = mapping;
        this.cache = new SecondLevelCache(mapping, settings.sharedCacheMode());
        this.namedQueries = new NamedQueries(mapping);
        this.metamodel = new io.vidocq.mansart.jpa.core.model.build.RuntimeMetamodel(mapping.model(), mapping.source(),
            mapping.loader());
        this.entityGraphs = new EntityGraphs(metamodel, mapping.source(), mapping.loader());
        this.batchSize = batchSize(settings);
        schemaGeneration = new io.vidocq.mansart.jpa.core.bootstrap.SchemaGeneration(settings, connections, mapping, this::checkOpen);
    }

    /** The batch size the unit sets, checked when the factory is created rather than at the first flush. */
    private static int batchSize(UnitSettings settings) {
        String value = settings.string(BATCH_SIZE);
        if (value == null || value.isBlank()) {
            return DEFAULT_BATCH_SIZE;
        }
        try {
            int size = Integer.parseInt(value.strip());
            if (size < 1) {
                throw new NumberFormatException("not positive");
            }
            return size;
        } catch (NumberFormatException e) {
            throw new PersistenceException("The property " + BATCH_SIZE + " must be a positive integer, not '" + value + "'", e);
        }
    }

    /** The batch size of the flush, {@value #DEFAULT_BATCH_SIZE} unless the unit sets {@value #BATCH_SIZE}. */
    public static final String BATCH_SIZE = "io.vidocq.mansart.jpa.jdbc.batch-size";
    private static final int DEFAULT_BATCH_SIZE = 50;

    /**
     * The flush engine of the factory, created at first use with the dialect of the database behind
     * {@code connection} (or the one the unit names), then shared by every entity manager.
     */
    FlushEngine flushEngine(Connection connection) {
        FlushEngine engine = flushEngine.get();
        if (engine == null) {
            ClassLoader loader = settings.definition().classLoader() != null ? settings.definition().classLoader()
                : Thread.currentThread().getContextClassLoader();
            Dialect dialect = Dialects.resolve(connection, settings.string(Dialects.PROPERTY), loader);
            FlushEngine created = new FlushEngine(dialect, batchSize);
            engine = flushEngine.compareAndExchange(null, created);
            if (engine == null) {
                engine = created;
            }
        }
        return engine;
    }

    /** The identifier generators of the factory, created at first use with the dialect of the flush engine. */
    IdGenerators idGenerators(Connection connection) {
        IdGenerators generators = idGenerators.get();
        if (generators == null) {
            IdGenerators created = new IdGenerators(flushEngine(connection).dialect(), connections, mapping::identifier);
            generators = idGenerators.compareAndExchange(null, created);
            if (generators == null) {
                generators = created;
            }
        }
        return generators;
    }

    /** The locks of §3.5, rendered by the dialect of the flush engine. */
    Locks locks(Connection connection) {
        return new Locks(flushEngine(connection));
    }

    /** Builds managed instances from rows, with the dialect and SQL of the flush engine. */
    EntityLoader loader(Connection connection) {
        return new EntityLoader(flushEngine(connection), mapping());
    }

    /** The mapped persistence unit: entity model, generated access and binders. */
    public MappedUnit mapping() {
        return mapping;
    }

    UnitSettings settings() {
        return settings;
    }

    BeanValidation.Validator validator() {
        return validator;
    }

    SecondLevelCache cache() {
        return cache;
    }

    /** Where the connections of the unit come from: its pool, its data source, or the driver (decision D7). */
    public ConnectionSource connectionSource() {
        return connections;
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
        java.util.Objects.requireNonNull(synchronizationType, "synchronizationType");
        if (settings.transactionType() == PersistenceUnitTransactionType.JTA) {
            return new EntityManagerImpl(this, map, synchronizationType);
        }
        throw new IllegalStateException("The persistence unit " + settings.unitName()
            + " is resource-local: a synchronization type only applies to JTA entity managers");
    }

    @Override
    public CriteriaBuilder getCriteriaBuilder() {
        checkOpen();
        return new io.vidocq.mansart.jpa.core.query.CriteriaBuilderImpl(metamodel);
    }

    @Override
    public Metamodel getMetamodel() {
        checkOpen();
        return metamodel;
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
        // after the transactions: their connections are back in the pool (or closed) before it closes
        try {
            connections.close();
        } finally {
            validator.close();
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
        return new UnitUtil(mapping());
    }

    @Override
    public PersistenceUnitTransactionType getTransactionType() {
        return settings.transactionType();
    }

    @Override
    public SchemaManager getSchemaManager() {
        checkOpen();
        return schemaGeneration;
    }

    @Override
    public void addNamedQuery(String name, Query query) {
        checkOpen();
        namedQueries.add(name, query);
    }

    /** The named queries of the unit, declared and added. */
    NamedQueries namedQueries() {
        return namedQueries;
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
        entityGraphs.add(graphName, entityGraph);
    }

    @Override
    public <R> Map<String, TypedQueryReference<R>> getNamedQueries(Class<R> resultType) {
        checkOpen();
        return namedQueries.references(resultType);
    }

    @Override
    public <E> Map<String, EntityGraph<? extends E>> getNamedEntityGraphs(Class<E> entityType) {
        checkOpen();
        return entityGraphs.typed(entityType);
    }

    EntityGraphs entityGraphs() {
        return entityGraphs;
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
