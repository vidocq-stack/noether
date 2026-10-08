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
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.ConnectionConsumer;
import jakarta.persistence.ConnectionFunction;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FindOption;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.LockOption;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.RefreshOption;
import jakarta.persistence.StoredProcedureQuery;
import jakarta.persistence.TransactionRequiredException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.CriteriaSelect;
import jakarta.persistence.criteria.CriteriaUpdate;
import jakarta.persistence.metamodel.Metamodel;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An application-managed, resource-local {@link EntityManager} (§7.2.1). Not thread-safe, by specification.
 *
 * <p>Milestone P1 delivers its life cycle, its properties and its transaction; every operation on entities says
 * which later milestone of the roadmap delivers it, after the checks every operation shares (a closed entity
 * manager throws {@link IllegalStateException}, §7.7).
 */
final class EntityManagerImpl implements EntityManager {

    private static final String CACHE_RETRIEVE_MODE = "jakarta.persistence.cache.retrieveMode";
    private static final String CACHE_STORE_MODE = "jakarta.persistence.cache.storeMode";

    private final EntityManagerFactoryImpl factory;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final ResourceLocalTransaction transaction;
    private boolean closed;
    private FlushModeType flushMode = FlushModeType.AUTO;
    private CacheRetrieveMode cacheRetrieveMode = CacheRetrieveMode.USE;
    private CacheStoreMode cacheStoreMode = CacheStoreMode.USE;

    EntityManagerImpl(EntityManagerFactoryImpl factory, Map<?, ?> map) {
        this.factory = factory;
        this.transaction = new ResourceLocalTransaction(factory);
        if (map != null) {
            map.forEach((key, value) -> {
                if (key != null) {
                    setPropertyUnchecked(String.valueOf(key), value);
                }
            });
        }
    }

    // ---- life cycle --------------------------------------------------------------------------------------

    /** Open until closed, and only while its factory is open (§7.3). */
    @Override
    public boolean isOpen() {
        return !closed && factory.isOpen();
    }

    private void checkOpen() {
        if (!isOpen()) {
            throw failed(new IllegalStateException("The EntityManager is closed"));
        }
    }

    /**
     * §3.12: a runtime exception thrown by a method of the entity manager, other than a {@link LockTimeoutException},
     * marks the transaction it is joined to for rollback. Every exception this class throws goes through here.
     */
    private RuntimeException failed(RuntimeException failure) {
        if (!(failure instanceof LockTimeoutException) && transaction.isActive()) {
            transaction.setRollbackOnly();
        }
        return failure;
    }

    /**
     * Closes the entity manager. A transaction it started may still be committed or rolled back: the persistence
     * context stays until the transaction completes (§7.7).
     */
    @Override
    public void close() {
        checkOpen();
        closed = true;
    }

    /** Allowed once closed (§7.7). */
    @Override
    public EntityTransaction getTransaction() {
        return transaction;
    }

    @Override
    public EntityManagerFactory getEntityManagerFactory() {
        checkOpen();
        return factory;
    }

    @Override
    public void joinTransaction() {
        checkOpen();
        if (!transaction.isActive()) {
            throw failed(new TransactionRequiredException("No active transaction to join: this resource-local entity manager "
                + "takes part in the transaction of its own EntityTransaction"));
        }
    }

    @Override
    public boolean isJoinedToTransaction() {
        checkOpen();
        return transaction.isActive();
    }

    // ---- properties ---------------------------------------------------------------------------------------

    /** The factory's properties overridden by this entity manager's; allowed once closed (§7.7). */
    @Override
    public Map<String, Object> getProperties() {
        Map<String, Object> all = new LinkedHashMap<>(factory.settings().properties());
        all.putAll(properties);
        return Collections.unmodifiableMap(all);
    }

    @Override
    public void setProperty(String propertyName, Object value) {
        checkOpen();
        try {
            setPropertyUnchecked(propertyName, value);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    private void setPropertyUnchecked(String name, Object value) {
        switch (name) {
            case UnitSettings.LOCK_TIMEOUT, UnitSettings.QUERY_TIMEOUT -> requireInteger(name, value);
            case CACHE_RETRIEVE_MODE -> cacheRetrieveMode = enumValue(CacheRetrieveMode.class, name, value);
            case CACHE_STORE_MODE -> cacheStoreMode = enumValue(CacheStoreMode.class, name, value);
            default -> {
                // any other property is kept as it is
            }
        }
        properties.put(name, value);
    }

    private static void requireInteger(String name, Object value) {
        if (value instanceof Number) {
            return;
        }
        try {
            Integer.parseInt(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The value of " + name + " must be a number of milliseconds: " + value, e);
        }
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name, Object value) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        try {
            return Enum.valueOf(type, String.valueOf(value).strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for " + name + ": " + value, e);
        }
    }

    @Override
    public void setFlushMode(FlushModeType flushMode) {
        checkOpen();
        this.flushMode = flushMode;
    }

    @Override
    public FlushModeType getFlushMode() {
        checkOpen();
        return flushMode;
    }

    @Override
    public void setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode) {
        checkOpen();
        this.cacheRetrieveMode = cacheRetrieveMode;
    }

    @Override
    public void setCacheStoreMode(CacheStoreMode cacheStoreMode) {
        checkOpen();
        this.cacheStoreMode = cacheStoreMode;
    }

    @Override
    public CacheRetrieveMode getCacheRetrieveMode() {
        checkOpen();
        return cacheRetrieveMode;
    }

    @Override
    public CacheStoreMode getCacheStoreMode() {
        checkOpen();
        return cacheStoreMode;
    }

    // ---- provider access ----------------------------------------------------------------------------------

    /** Besides the entity manager itself, the JDBC {@link Connection} of the active transaction (as Hibernate offers). */
    @Override
    public <T> T unwrap(Class<T> cls) {
        checkOpen();
        if (cls == Connection.class) {
            if (!transaction.isActive()) {
                throw failed(new TransactionRequiredException("The JDBC connection is only reachable inside a transaction"));
            }
            return cls.cast(transaction.connection());
        }
        if (cls.isInstance(this)) {
            return cls.cast(this);
        }
        throw failed(new PersistenceException("Unsupported unwrap type " + cls.getName()));
    }

    @Override
    public Object getDelegate() {
        checkOpen();
        return this;
    }

    /** The connection of the active transaction, or a connection of its own, closed after the action. */
    @Override
    public <C> void runWithConnection(ConnectionConsumer<C> action) {
        callWithConnection(connection -> {
            @SuppressWarnings("unchecked")
            C typed = (C) connection;
            action.accept(typed);
            return null;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C, T> T callWithConnection(ConnectionFunction<C, T> function) {
        checkOpen();
        try {
            if (transaction.isActive()) {
                return function.apply((C) transaction.connection());
            }
            try (Connection connection = factory.connections().acquire()) {
                return function.apply((C) connection);
            }
        } catch (RuntimeException e) {
            throw failed(e);
        } catch (SQLException e) {
            throw failed(new PersistenceException("JDBC failure: " + e.getMessage(), e));
        } catch (Exception e) {
            throw failed(new PersistenceException(e.getMessage(), e));
        }
    }

    // ---- persistence context (P3) -------------------------------------------------------------------------

    /** Nothing to flush before the persistence context exists (P3), but a transaction is required (§3.3.4). */
    @Override
    public void flush() {
        checkOpen();
        if (!transaction.isActive()) {
            throw failed(new TransactionRequiredException("flush() needs an active transaction"));
        }
    }

    /** Nothing is managed before the persistence context exists (P3). */
    @Override
    public void clear() {
        checkOpen();
    }

    @Override
    public void detach(Object entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "detach"));
    }

    @Override
    public boolean contains(Object entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "contains"));
    }

    @Override
    public LockModeType getLockMode(Object entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "getLockMode"));
    }

    // ---- entity operations (P4) ---------------------------------------------------------------------------

    @Override
    public void persist(Object entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "persist"));
    }

    @Override
    public <T> T merge(T entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "merge"));
    }

    @Override
    public void remove(Object entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "remove"));
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "find"));
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, Map<String, Object> properties) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "find"));
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, LockModeType lockMode) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "find"));
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, LockModeType lockMode, Map<String, Object> properties) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "find"));
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, FindOption... options) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "find"));
    }

    @Override
    public <T> T find(EntityGraph<T> entityGraph, Object primaryKey, FindOption... options) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "find with an entity graph"));
    }

    @Override
    public <T> T getReference(Class<T> entityClass, Object primaryKey) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "getReference"));
    }

    @Override
    public <T> T getReference(T entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "getReference"));
    }

    @Override
    public void lock(Object entity, LockModeType lockMode) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "lock"));
    }

    @Override
    public void lock(Object entity, LockModeType lockMode, Map<String, Object> properties) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "lock"));
    }

    @Override
    public void lock(Object entity, LockModeType lockMode, LockOption... options) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "lock"));
    }

    @Override
    public void refresh(Object entity) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "refresh"));
    }

    @Override
    public void refresh(Object entity, Map<String, Object> properties) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "refresh"));
    }

    @Override
    public void refresh(Object entity, LockModeType lockMode) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "refresh"));
    }

    @Override
    public void refresh(Object entity, LockModeType lockMode, Map<String, Object> properties) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "refresh"));
    }

    @Override
    public void refresh(Object entity, RefreshOption... options) {
        checkOpen();
        throw failed(NotYet.milestone("P4", "refresh"));
    }

    // ---- queries (P7, P8) ---------------------------------------------------------------------------------

    @Override
    public Query createQuery(String qlString) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "Jakarta Persistence queries"));
    }

    @Override
    public <T> TypedQuery<T> createQuery(String qlString, Class<T> resultClass) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "Jakarta Persistence queries"));
    }

    @Override
    public <T> TypedQuery<T> createQuery(TypedQueryReference<T> reference) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "named queries"));
    }

    @Override
    public <T> TypedQuery<T> createQuery(CriteriaQuery<T> criteriaQuery) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "the Criteria API"));
    }

    @Override
    public <T> TypedQuery<T> createQuery(CriteriaSelect<T> selectQuery) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "the Criteria API"));
    }

    @Override
    public Query createQuery(CriteriaUpdate<?> updateQuery) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "the Criteria API"));
    }

    @Override
    public Query createQuery(CriteriaDelete<?> deleteQuery) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "the Criteria API"));
    }

    @Override
    public Query createNamedQuery(String name) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "named queries"));
    }

    @Override
    public <T> TypedQuery<T> createNamedQuery(String name, Class<T> resultClass) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "named queries"));
    }

    @Override
    public Query createNativeQuery(String sqlString) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "native queries"));
    }

    @Override
    public <T> Query createNativeQuery(String sqlString, Class<T> resultClass) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "native queries"));
    }

    @Override
    public Query createNativeQuery(String sqlString, String resultSetMapping) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "native queries"));
    }

    @Override
    public StoredProcedureQuery createNamedStoredProcedureQuery(String name) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "stored procedure queries"));
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String procedureName) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "stored procedure queries"));
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String procedureName, Class<?>... resultClasses) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "stored procedure queries"));
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String procedureName, String... resultSetMappings) {
        checkOpen();
        throw failed(NotYet.milestone("P7", "stored procedure queries"));
    }

    @Override
    public CriteriaBuilder getCriteriaBuilder() {
        checkOpen();
        try {
            return factory.getCriteriaBuilder();
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Metamodel getMetamodel() {
        checkOpen();
        try {
            return factory.getMetamodel();
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> EntityGraph<T> createEntityGraph(Class<T> rootType) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "entity graphs"));
    }

    @Override
    public EntityGraph<?> createEntityGraph(String graphName) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "entity graphs"));
    }

    @Override
    public EntityGraph<?> getEntityGraph(String graphName) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "entity graphs"));
    }

    @Override
    public <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> entityClass) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "entity graphs"));
    }
}
