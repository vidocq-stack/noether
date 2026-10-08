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
import io.vidocq.mansart.jpa.core.context.EntityKey;
import io.vidocq.mansart.jpa.core.context.ManagedEntity;
import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.flush.Locks;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.query.NativeQuery;
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.ConnectionConsumer;
import jakarta.persistence.ConnectionFunction;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
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
import jakarta.persistence.Timeout;
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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
    private final PersistenceContext context = new PersistenceContext();
    private final EntityOperations operations;
    private boolean closed;
    private FlushModeType flushMode = FlushModeType.AUTO;
    private CacheRetrieveMode cacheRetrieveMode = CacheRetrieveMode.USE;
    private CacheStoreMode cacheStoreMode = CacheStoreMode.USE;

    EntityManagerImpl(EntityManagerFactoryImpl factory, Map<?, ?> map) {
        this.factory = factory;
        this.operations = new EntityOperations(factory, context, new EntityOperations.Connections() {
            @Override
            public <T> T on(Function<Connection, T> work) {
                return onConnection(work);
            }
        });
        this.transaction = new ResourceLocalTransaction(factory, new TransactionListener() {
            @Override
            public void beforeCommit(Connection connection) {
                flush(connection);
            }

            @Override
            public void afterRollback() {
                context.clear(); // §3.3.2: the instances become detached
            }

            @Override
            public void afterCommit() {
                context.releaseLocks();
            }
        });
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

    /** §3.2.4: writes the changes of the persistence context in the transaction, which a flush requires (§3.3.4). */
    @Override
    public void flush() {
        checkOpen();
        if (!transaction.isActive()) {
            throw failed(new TransactionRequiredException("flush() needs an active transaction"));
        }
        try {
            transaction.onConnection(connection -> {
                flush(connection);
                return null;
            });
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    /** An empty persistence context has nothing to write: no flush engine, hence no dialect, is needed for it. */
    private void flush(Connection connection) {
        if (context.size() > 0) {
            operations.beforeFlush();
            factory.flushEngine(connection).flush(context, connection);
        }
    }

    /** §3.2.7: detaches every managed instance; changes not flushed are lost. */
    @Override
    public void clear() {
        checkOpen();
        context.clear();
    }

    /** The standard lock timeout hint (§3.11.4), in milliseconds. */
    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    /** The lock timeout of an operation: its hint, else the entity manager's property, else none (the database's). */
    private Integer lockTimeout(Map<String, Object> hints, Integer fallback) {
        Object value = hints != null && hints.containsKey(LOCK_TIMEOUT) ? hints.get(LOCK_TIMEOUT) : properties.get(LOCK_TIMEOUT);
        if (value == null) {
            return fallback;
        }
        try {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            return fallback; // §3.11: a hint that cannot be used is ignored
        }
    }

    private void requireTransaction(String operation) {
        if (!transaction.isActive()) {
            throw new TransactionRequiredException(operation + " needs an active transaction");
        }
    }

    /** The entry of a managed instance; {@link IllegalArgumentException} for anything else. */
    private ManagedEntity managedEntry(Object entity) {
        operations.type(entity);
        return context.entry(entity).filter(e -> e.status() == ManagedEntity.Status.MANAGED)
            .orElseThrow(() -> new IllegalArgumentException("The instance is not managed by this entity manager"));
    }

    /** The mapped entity {@code entityClass}; {@link IllegalArgumentException} if it is not an entity of the unit. */
    private MappedEntity type(Class<?> entityClass) {
        if (entityClass == null) {
            throw new IllegalArgumentException("The entity class cannot be null");
        }
        return factory.mapping().entity(entityClass).orElseThrow(() -> new IllegalArgumentException(entityClass.getName()
            + " is not an entity of persistence unit " + factory.getName()));
    }

    /**
     * Runs {@code work} on the connection of the active transaction, or, outside a transaction (an extended
     * persistence context reads without one, §7.7.1), on a connection of its own, released at the end.
     */
    private <T> T onConnection(Function<Connection, T> work) {
        if (transaction.isActive()) {
            return transaction.onConnection(work);
        }
        try (Connection connection = factory.connections().acquire()) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new PersistenceException("Unable to obtain a connection: " + e.getMessage(), e);
        }
    }

    /**
     * §3.11.6: a native update runs in the transaction, after a flush unless the query's flush mode is COMMIT; a
     * failure marks the transaction for rollback (§3.12).
     */
    private int executeNativeUpdate(String sql, List<Object> parameters, FlushModeType queryFlushMode) {
        checkOpen();
        if (!transaction.isActive()) {
            throw failed(new TransactionRequiredException("executeUpdate() needs an active transaction"));
        }
        try {
            return transaction.onConnection(connection -> {
                if (queryFlushMode != FlushModeType.COMMIT) {
                    flush(connection);
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (int i = 0; i < parameters.size(); i++) {
                        Object value = parameters.get(i);
                        if (value == null) {
                            statement.setNull(i + 1, Types.NULL);
                        } else {
                            statement.setObject(i + 1, value instanceof Enum<?> constant ? constant.name() : value);
                        }
                    }
                    return statement.executeUpdate();
                } catch (SQLException e) {
                    throw new PersistenceException("The native update failed: " + e.getMessage(), e);
                }
            });
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    /** The persistence context of this entity manager (the entity operations of P4 drive it). */
    PersistenceContext context() {
        return context;
    }

    @Override
    public void detach(Object entity) {
        checkOpen();
        try {
            operations.detach(entity);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public boolean contains(Object entity) {
        checkOpen();
        try {
            type(entity.getClass());
            return context.contains(entity);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public LockModeType getLockMode(Object entity) {
        checkOpen();
        try {
            requireTransaction("getLockMode");
            return managedEntry(entity).lockMode();
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    // ---- entity operations (P4) ---------------------------------------------------------------------------

    @Override
    public void persist(Object entity) {
        checkOpen();
        try {
            operations.persist(entity);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> T merge(T entity) {
        checkOpen();
        try {
            return operations.merge(entity);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public void remove(Object entity) {
        checkOpen();
        try {
            operations.remove(entity);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey) {
        return find(entityClass, primaryKey, LockModeType.NONE);
    }

    /** The properties are hints (§3.11): the lock timeout is the one that matters without a second-level cache. */
    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, Map<String, Object> properties) {
        return find(entityClass, primaryKey, LockModeType.NONE, properties);
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, LockModeType lockMode) {
        return find(entityClass, primaryKey, lockMode, Map.of());
    }

    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, LockModeType lockMode, Map<String, Object> properties) {
        checkOpen();
        try {
            boolean locking = lockMode != null && lockMode != LockModeType.NONE;
            if (locking) {
                requireTransaction("find with a lock mode");
            }
            MappedEntity type = type(entityClass);
            Object id = type.key(primaryKey);
            Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
            if (known.isPresent()) {
                // a removed instance is no longer found; another class of the hierarchy is not this one (P6)
                Object instance = known.get().instance();
                if (known.get().status() != ManagedEntity.Status.MANAGED || !entityClass.isInstance(instance)) {
                    return null;
                }
                if (locking) {
                    lock(instance, lockMode, properties);
                }
                return entityClass.cast(instance);
            }
            Integer timeout = lockTimeout(properties, null);
            return entityClass.cast(onConnection(connection -> {
                Object loaded = factory.loader(connection).load(type, id, connection, context, lockMode, timeout);
                if (loaded != null && locking) {
                    // the row is locked by the select already for a pessimistic mode: record the mode, check nothing
                    ManagedEntity entry = context.entry(loaded).orElseThrow();
                    factory.locks(connection).lock(type, entry, Locks.pessimistic(lockMode) ? LockModeType.NONE : lockMode, timeout,
                        false, connection, context);
                    context.lock(entry, lockMode);
                }
                return loaded;
            }));
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    /** 3.2: the options of a find; the cache modes and the timeout have no effect without a second-level cache (P11). */
    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, FindOption... options) {
        LockModeType lockMode = LockModeType.NONE;
        Map<String, Object> properties = new HashMap<>();
        for (FindOption option : options) {
            if (option instanceof LockModeType mode) {
                lockMode = mode;
            } else if (option instanceof Timeout timeout) {
                properties.put(LOCK_TIMEOUT, timeout.milliseconds());
            }
        }
        return find(entityClass, primaryKey, lockMode, properties);
    }

    @Override
    public <T> T find(EntityGraph<T> entityGraph, Object primaryKey, FindOption... options) {
        checkOpen();
        throw failed(NotYet.milestone("P8", "find with an entity graph"));
    }

    @Override
    public <T> T getReference(Class<T> entityClass, Object primaryKey) {
        // without proxies, a reference is the instance itself: §3.2.8 allows the EntityNotFoundException now
        T instance = find(entityClass, primaryKey);
        if (instance == null) {
            throw failed(new EntityNotFoundException("No " + entityClass.getName() + " has the identifier " + primaryKey));
        }
        return instance;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getReference(T entity) {
        checkOpen();
        MappedEntity type;
        Object id;
        try {
            type = type(entity.getClass());
            id = type.id(entity);
        } catch (RuntimeException e) {
            throw failed(e);
        }
        if (id == null) {
            throw failed(new IllegalArgumentException("The instance of " + type.model().entityName() + " has no identifier"));
        }
        Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
        if (known.isPresent()) {
            return (T) known.get().instance();
        }
        Object loaded = onConnection(connection -> factory.loader(connection).load(type, id, connection, context));
        if (loaded == null) {
            throw failed(new EntityNotFoundException("No " + type.model().entityName() + " has the identifier " + id));
        }
        return (T) loaded;
    }

    @Override
    public void lock(Object entity, LockModeType lockMode) {
        lock(entity, lockMode, Map.of());
    }

    @Override
    public void lock(Object entity, LockModeType lockMode, Map<String, Object> properties) {
        checkOpen();
        try {
            requireTransaction("lock");
            ManagedEntity entry = managedEntry(entity);
            Integer timeout = lockTimeout(properties, null);
            transaction.onConnection(connection -> {
                factory.locks(connection).lock(entry.type(), entry, lockMode, timeout, true, connection, context);
                return null;
            });
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public void lock(Object entity, LockModeType lockMode, LockOption... options) {
        Map<String, Object> properties = new HashMap<>();
        for (LockOption option : options) {
            if (option instanceof Timeout timeout) {
                properties.put(LOCK_TIMEOUT, timeout.milliseconds());
            }
        }
        lock(entity, lockMode, properties);
    }

    @Override
    public void refresh(Object entity) {
        refresh(entity, LockModeType.NONE);
    }

    @Override
    public void refresh(Object entity, Map<String, Object> properties) {
        refresh(entity, LockModeType.NONE);
    }

    @Override
    public void refresh(Object entity, LockModeType lockMode) {
        checkOpen();
        try {
            boolean locking = lockMode != null && lockMode != LockModeType.NONE;
            if (locking) {
                requireTransaction("refresh with a lock mode");
                ManagedEntity entry = managedEntry(entity);
                if (Locks.pessimistic(lockMode)) {
                    // lock the row first: the refresh then reads what no one can change any more
                    transaction.onConnection(connection -> {
                        factory.locks(connection).lock(entry.type(), entry, lockMode, lockTimeout(Map.of(), null), false, connection,
                            context);
                        return null;
                    });
                }
            }
            operations.refresh(entity);
            if (locking && !Locks.pessimistic(lockMode)) {
                lock(entity, lockMode);
            }
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public void refresh(Object entity, LockModeType lockMode, Map<String, Object> properties) {
        refresh(entity, lockMode);
    }

    @Override
    public void refresh(Object entity, RefreshOption... options) {
        LockModeType lockMode = LockModeType.NONE;
        for (RefreshOption option : options) {
            if (option instanceof LockModeType mode) {
                lockMode = mode;
            }
        }
        refresh(entity, lockMode);
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
        try {
            return new NativeQuery(sqlString, flushMode, this::executeNativeUpdate);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> Query createNativeQuery(String sqlString, Class<T> resultClass) {
        checkOpen();
        try {
            return new NativeQuery(sqlString, flushMode, this::executeNativeUpdate);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Query createNativeQuery(String sqlString, String resultSetMapping) {
        checkOpen();
        try {
            return new NativeQuery(sqlString, flushMode, this::executeNativeUpdate);
        } catch (RuntimeException e) {
            throw failed(e);
        }
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
