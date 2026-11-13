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

import io.vidocq.noether.jpa.core.bootstrap.UnitSettings;
import io.vidocq.noether.jpa.core.context.EntityKey;
import io.vidocq.noether.jpa.core.context.ManagedEntity;
import io.vidocq.noether.jpa.core.context.PersistenceContext;
import io.vidocq.noether.jpa.core.flush.Locks;
import io.vidocq.noether.jpa.core.mapping.CollectionMapping;
import io.vidocq.noether.jpa.core.mapping.LazyCollection;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.query.FetchPlan;
import io.vidocq.noether.jpa.core.query.JpqlQuery;
import io.vidocq.noether.jpa.core.query.NativeQuery;
import io.vidocq.noether.jpa.core.query.QueryRuntime;
import io.vidocq.noether.jpa.core.query.StoredProcedureQueryImpl;
import io.vidocq.noether.jpa.core.model.NamedStoredProcedureModel;
import io.vidocq.noether.jpa.dialect.Dialect;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final SessionTransaction transaction;
    private final PersistenceContext context = new PersistenceContext();
    private final EntityOperations operations;
    private boolean closed;
    private FlushModeType flushMode = FlushModeType.AUTO;
    private CacheRetrieveMode cacheRetrieveMode = CacheRetrieveMode.USE;
    private CacheStoreMode cacheStoreMode = CacheStoreMode.USE;
    private Connection executionConnection;
    /**
     * Whether the shared cache of the factory can hold an entity of the unit. Without one, nothing is kept about what
     * the transaction read and wrote: the three sets below are the empty set, never added to, and nothing is staged.
     */
    private final boolean cached;
    private final Set<EntityKey> transactionTouched;
    private final Set<EntityKey> transactionCacheCandidates;
    private final Set<EntityKey> bypassStore;
    private long transactionCacheGeneration = -1;
    private boolean cacheInvalidationPending;
    private SecondLevelCache.Publication cachePublication;

    EntityManagerImpl(EntityManagerFactoryImpl factory, Map<?, ?> map) {
        this(factory, map, jakarta.persistence.SynchronizationType.SYNCHRONIZED);
    }

    EntityManagerImpl(EntityManagerFactoryImpl factory, Map<?, ?> map, jakarta.persistence.SynchronizationType synchronization) {
        this.factory = factory;
        this.cached = factory.cache().holdsEntities();
        this.transactionTouched = cached ? new HashSet<>() : Collections.emptySet();
        this.transactionCacheCandidates = cached ? new HashSet<>() : Collections.emptySet();
        this.bypassStore = cached ? new HashSet<>() : Collections.emptySet();
        this.operations = new EntityOperations(factory, context, new EntityOperations.Connections() {
            @Override
            public <T> T on(Function<Connection, T> work) {
                return onConnection(work);
            }
        });
        context.lazyLoader(this::loadLazy);
        context.referenceLoader(this::loadReference);
        TransactionListener listener = transactionListener();
        if (factory.getTransactionType() == jakarta.persistence.PersistenceUnitTransactionType.JTA) {
            var integration = (io.vidocq.noether.jpa.core.spi.TransactionIntegration)
                factory.settings().property(io.vidocq.noether.jpa.core.spi.TransactionIntegration.PROPERTY);
            transaction = new ContainerTransaction(integration.open(synchronization, factory.connections()::acquire,
                completion(listener)));
            transaction.isActive();
        } else transaction = new ResourceLocalTransaction(factory, listener);
        if (map != null) {
            map.forEach((key, value) -> {
                if (key != null) {
                    setPropertyUnchecked(String.valueOf(key), value);
                }
            });
        }
    }

    /** What this entity manager does around the completion of its transaction: the flush, then the shared cache. */
    private TransactionListener transactionListener() {
        return new TransactionListener() {
            @Override
            public void beforeCommit(Connection connection) {
                withConnection(connection, current -> {
                    flush(current);
                    if (cached) {
                        cachePublication = factory.cache().stage(context, storeMode(), bypassStore, transactionTouched,
                            transactionCacheCandidates, transactionCacheGeneration);
                    }
                    return null;
                });
            }

            @Override
            public void afterRollback() {
                context.clear(); // §3.3.2: the instances become detached
                transactionTouched.clear();
                transactionCacheCandidates.clear();
                bypassStore.clear();
                transactionCacheGeneration = -1;
                cacheInvalidationPending = false;
                cachePublication = null;
            }

            @Override
            public void afterCommit() {
                if (cacheInvalidationPending) {
                    factory.cache().invalidateAll();
                } else {
                    factory.cache().publish(cachePublication);
                }
                transactionTouched.clear();
                transactionCacheCandidates.clear();
                bypassStore.clear();
                transactionCacheGeneration = -1;
                cacheInvalidationPending = false;
                cachePublication = null;
                context.releaseLocks();
                if (closed) {
                    context.disconnectAll(); // §7.7: the transaction of a closed entity manager is over
                }
            }
        };
    }

    /** The completion of a container transaction, as {@code listener} hears the one of a resource-local transaction. */
    private io.vidocq.noether.jpa.core.spi.TransactionIntegration.Completion completion(TransactionListener listener) {
        return new io.vidocq.noether.jpa.core.spi.TransactionIntegration.Completion() {
            @Override public void beforeCommit(Connection connection) { listener.beforeCommit(connection); }
            @Override public void afterCompletion(boolean committed) {
                if (committed) listener.afterCommit(); else listener.afterRollback();
                if (closed) context.clear();
            }
        };
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
        if (!(failure instanceof LockTimeoutException) && transaction.joined()) {
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
        if (!transaction.isActive()) {
            context.disconnectAll(); // §3.2.7: its instances are detached, their unloaded collections cannot load
        }
    }

    /**
     * Loads a LAZY collection of an instance of this persistence context on its first access (§2.10, §3.2.7), on the
     * connection of the active transaction, else on a connection of its own; {@link PersistenceException} once the
     * owner is detached or the entity manager closed.
     */
    private void loadReference(Object instance) {
        if (closed && !transaction.isActive() || !factory.isOpen()) {
            throw failed(new PersistenceException("The lazy to-one reference cannot be loaded: its entity manager is closed"));
        }
        ManagedEntity entry = context.entry(instance).orElseThrow(() -> failed(new PersistenceException(
            "The lazy to-one reference cannot be loaded: it is detached")));
        try {
            onConnection(connection -> {
                factory.loader(connection).initializeReference(entry, connection, context);
                return null;
            });
        } catch (PersistenceException failure) {
            throw failed(failure);
        }
    }

    private void loadLazy(LazyCollection lazy) {
        String what = lazy.owner().getClass().getSimpleName() + "." + lazy.attributeName();
        if (closed && !transaction.isActive() || !factory.isOpen()) {
            throw failed(new PersistenceException("The LAZY collection " + what
                + " cannot be loaded: its entity manager is closed"));
        }
        ManagedEntity entry = context.entry(lazy.owner()).orElseThrow(() -> failed(new PersistenceException(
            "The LAZY collection " + what + " cannot be loaded: its owner is detached")));
        try {
            onConnection(connection -> {
                factory.loader(connection).initialize(entry, lazy, connection, context);
                return null;
            });
        } catch (PersistenceException e) {
            throw failed(e);
        } catch (RuntimeException e) {
            throw failed(new PersistenceException("The LAZY collection " + what + " cannot be loaded: " + e.getMessage(), e));
        }
    }

    /**
     * Loads, before a flush, the LAZY collections that were replaced or whose owner is removed: their previous elements
     * are the rows to unlink, and loading them during the flush would change the persistence context it walks.
     */
    private void loadReplacedCollections() {
        List<LazyCollection> replaced = new ArrayList<>();
        for (ManagedEntity entry : context.entries()) {
            Object[] snapshot = entry.snapshot();
            if (snapshot == null) continue;
            Object[] state = null;
            for (Object slot : snapshot) {
                if (!LazyCollection.unloaded(slot)) continue;
                if (state == null) state = readState(entry);
                int attribute = ((LazyCollection) slot).attribute();
                // the rows of a removed owner are deleted by its key, but the foreign keys of its targets one by one
                if (entry.status() == ManagedEntity.Status.REMOVED ? entry.type().statements().collection(attribute)
                        .filter(CollectionMapping.ForeignKey.class::isInstance).isPresent() : state[attribute] != slot) {
                    replaced.add((LazyCollection) slot);
                }
            }
        }
        replaced.forEach(LazyCollection::initialize);
    }

    /** Allowed once closed (§7.7). */
    @Override
    public EntityTransaction getTransaction() {
        if (factory.getTransactionType() == jakarta.persistence.PersistenceUnitTransactionType.JTA) {
            throw new IllegalStateException("getTransaction() is not available for a JTA entity manager");
        }
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
        transaction.join();
    }

    @Override
    public boolean isJoinedToTransaction() {
        checkOpen();
        return transaction.isActive() && transaction.joined();
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
        Function<Connection, T> work = connection -> {
            try {
                return function.apply((C) connection);
            } catch (Exception e) {
                throw e instanceof RuntimeException runtime ? runtime : new PersistenceException(e.getMessage(), e);
            }
        };
        try {
            return onConnection(work);
        } catch (RuntimeException e) {
            throw failed(e);
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
            withTransactionConnection(connection -> {
                flush(connection);
                return null;
            });
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    /** An empty persistence context has nothing to write: no flush engine, hence no dialect, is needed for it. */
    private void flush(Connection connection) {
        if (context.size() == 0) {
            return;
        }
        loadReplacedCollections();
        List<Object> newInstances = checkBeforeFlush();
        operations.beforeFlush();
        factory.flushEngine(connection).flush(context, connection,
            entry -> {
                validateBusinessKey(entry);
                factory.validator().validate("pre-update", entry.instance());
            });
        if (newInstances != null) {
            for (Object instance : newInstances) {
                context.entry(instance).map(ManagedEntity::key).filter(java.util.Objects::nonNull)
                    .ifPresent(this::touch);
            }
        }
    }

    /**
     * Before a flush writes: a changed business key that is referenced is refused, and what the flush will write is
     * marked as touched by the transaction for the shared cache. Gives the instances the flush will insert, whose
     * identity may only be known after it: few per flush, none in most, hence a list once there is one, else
     * {@code null}; an instance has one entry, so it is in once.
     */
    private List<Object> checkBeforeFlush() {
        List<Object> newInstances = null;
        Object[] reusable = null; // the state is only compared here: one array serves every instance of its size
        for (ManagedEntity entry : context.entries()) {
            // one read of the instance serves the business-key validation and the dirty check
            boolean validates = validatesBusinessKey(entry);
            boolean dirty = false;
            if (validates || comparedForCache(entry)) {
                reusable = readState(entry, reusable);
                dirty = entry.type().state().dirty(entry.snapshot(), reusable);
            }
            if (validates && dirty) rejectBusinessKeyChange(entry, reusable);
            if (!entry.inserted()) newInstances = added(newInstances, entry.instance());
            if (written(entry, dirty)) touch(entry.key());
        }
        return newInstances;
    }

    /** {@code list} with {@code instance} added: a list is made for the first one. */
    private static List<Object> added(List<Object> list, Object instance) {
        List<Object> instances = list == null ? new ArrayList<>(4) : list;
        instances.add(instance);
        return instances;
    }

    /** Whether the flush writes the row of {@code entry}, whose identity is known: removed, new, or changed. */
    private static boolean written(ManagedEntity entry, boolean dirty) {
        return entry.key() != null && (entry.status() == ManagedEntity.Status.REMOVED || !entry.inserted()
            || entry.snapshot() != null && dirty);
    }

    /**
     * Whether only a comparison with its snapshot tells if the flush writes {@code entry}: it has an identity, a row
     * and a snapshot, and is not removed. A removed or a new instance is written whatever its state.
     */
    private static boolean comparedForCache(ManagedEntity entry) {
        return entry.key() != null && entry.status() != ManagedEntity.Status.REMOVED && entry.inserted()
            && entry.snapshot() != null;
    }

    private void validateBusinessKey(ManagedEntity entry) {
        if (validatesBusinessKey(entry)) rejectBusinessKeyChange(entry, readState(entry));
    }

    /** Whether {@code entry} has a row and a snapshot its referenced business keys are compared with. */
    private static boolean validatesBusinessKey(ManagedEntity entry) {
        return entry.inserted() && entry.snapshot() != null
            && !io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(entry.instance());
    }

    private void rejectBusinessKeyChange(ManagedEntity entry, Object[] state) {
        for (int i : entry.type().state().changes(entry.snapshot(), state)) {
            if (factory.mapping().businessKeyAttribute(entry.type(), i)) {
                throw new jakarta.persistence.PersistenceException("Referenced business-key attribute "
                    + entry.type().model().attributes().get(i).name() + " is immutable");
            }
        }
    }

    private void touch(EntityKey key) {
        if (!cached) return;
        captureCacheGeneration(factory.cache().generation());
        transactionTouched.add(key);
    }

    private void captureCacheGeneration(long observed) {
        if (transactionCacheGeneration < 0) {
            transactionCacheGeneration = observed;
        }
    }

    private static Object[] readState(ManagedEntity entry) {
        return readState(entry, null);
    }

    /**
     * The state of the instance of {@code entry}, read into {@code reusable} when it has the size of that state, else
     * into a new array. A read writes every attribute; the caller keeps nothing of the previous read.
     */
    private static Object[] readState(ManagedEntity entry, Object[] reusable) {
        int size = entry.type().model().attributes().size();
        Object[] state = reusable != null && reusable.length == size ? reusable : new Object[size];
        entry.type().access().read(entry.instance(), state);
        return state;
    }

    private SecondLevelCache.CacheStoreModeSetting storeMode() {
        return switch (cacheStoreMode) {
            case USE -> SecondLevelCache.CacheStoreModeSetting.USE;
            case REFRESH -> SecondLevelCache.CacheStoreModeSetting.REFRESH;
            case BYPASS -> SecondLevelCache.CacheStoreModeSetting.BYPASS;
        };
    }

    private CacheRetrieveMode retrieveMode(Map<String, Object> operationProperties) {
        Object value = operationProperties == null ? null : operationProperties.get(CACHE_RETRIEVE_MODE);
        return value == null ? cacheRetrieveMode : enumValue(CacheRetrieveMode.class, CACHE_RETRIEVE_MODE, value);
    }

    private CacheStoreMode storeMode(Map<String, Object> operationProperties) {
        Object value = operationProperties == null ? null : operationProperties.get(CACHE_STORE_MODE);
        return value == null ? cacheStoreMode : enumValue(CacheStoreMode.class, CACHE_STORE_MODE, value);
    }

    /** §3.2.7: detaches every managed instance; changes not flushed are lost. */
    @Override
    public void clear() {
        checkOpen();
        context.clear();
    }

    /** The standard lock timeout hint (§3.11.4), in milliseconds. */
    private static final String LOCK_TIMEOUT = "jakarta.persistence.lock.timeout";

    /**
     * The lock timeout of an operation: its hint, else the entity manager's property, else the unit's, else none (the
     * database's).
     */
    Integer lockTimeout(Map<String, Object> hints) {
        return hints != null && hints.containsKey(LOCK_TIMEOUT) ? milliseconds(hints.get(LOCK_TIMEOUT)) : timeout(LOCK_TIMEOUT);
    }

    /** A timeout property of this entity manager, else of its unit (§8.2.1.9), in milliseconds; {@code null} for none. */
    private Integer timeout(String name) {
        Object value = properties.containsKey(name) ? properties.get(name) : factory.settings().properties().get(name);
        return milliseconds(value);
    }

    private static Integer milliseconds(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            return null; // §3.11: a hint that cannot be used is ignored
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
        if (executionConnection != null) return work.apply(executionConnection);
        if (transaction.isActive()) {
            return withTransactionConnection(work);
        }
        try (Connection connection = factory.connections().acquire()) {
            return withConnection(connection, work);
        } catch (SQLException e) {
            throw new PersistenceException("Unable to obtain a connection: " + e.getMessage(), e);
        }
    }

    private <T> T withTransactionConnection(Function<Connection, T> work) {
        return transaction.onConnection(connection -> withConnection(connection, work));
    }

    /** Nested loaders and cascades reuse the connection of the operation that runs them, without a ThreadLocal. */
    private <T> T withConnection(Connection connection, Function<Connection, T> work) {
        Connection previous = executionConnection;
        executionConnection = connection;
        try { return work.apply(connection); }
        finally { executionConnection = previous; }
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
            return withTransactionConnection(connection -> {
                if (queryFlushMode != FlushModeType.COMMIT) {
                    flush(connection);
                }
                cacheInvalidationPending = true;
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
            FetchPlan plan = graphPlan(properties, entityClass);
            boolean graph = hasGraph(properties);
            boolean locking = locking(lockMode);
            if (locking) {
                requireTransaction("find with a lock mode");
            }
            MappedEntity type = type(entityClass);
            Object id = type.key(primaryKey);
            Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
            if (known.isPresent()) {
                return context.loading(() -> findManaged(known.get(), entityClass, type, id, plan, lockMode, properties));
            }
            CacheRetrieveMode retrieve = retrieveMode(properties);
            CacheStoreMode store = storeMode(properties);
            EntityKey lookupKey = new EntityKey(type.root(), id);
            if (!locking && !cacheInvalidationPending && !transactionTouched.contains(lookupKey)
                    && retrieve == CacheRetrieveMode.USE
                    && store != CacheStoreMode.REFRESH) {
                Optional<Object> restored = context.loading(() -> findCached(type, id, plan, graph));
                if (restored.isPresent()) {
                    return entityClass.cast(restored.get());
                }
            }
            Integer timeout = lockTimeout(properties);
            long cacheGeneration = factory.cache().generation();
            if (transaction.isActive() && store != CacheStoreMode.BYPASS) {
                captureCacheGeneration(cacheGeneration);
            }
            Object result = onConnection(connection -> context.loading(
                () -> findLoaded(connection, type, lookupKey, plan, lockMode, timeout, properties)));
            rememberFound(type, id, result, store, cacheGeneration);
            return entityClass.cast(result);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    private static final String FETCH_GRAPH = "jakarta.persistence.fetchgraph";
    private static final String LOAD_GRAPH = "jakarta.persistence.loadgraph";
    private static final List<String> GRAPH_HINTS = List.of(FETCH_GRAPH, LOAD_GRAPH);

    /** What the entity graph hints of a find fetch (§3.7.4), each validated against {@code entityClass}. */
    private FetchPlan graphPlan(Map<String, Object> properties, Class<?> entityClass) {
        FetchPlan plan = new FetchPlan();
        if (properties != null) {
            for (String name : GRAPH_HINTS) {
                if (properties.containsKey(name)) {
                    EntityGraphs.validateHint(properties.get(name), entityClass, factory.getMetamodel());
                    plan.merge(EntityGraphs.plan(properties.get(name)));
                }
            }
        }
        return plan;
    }

    private static boolean hasGraph(Map<String, Object> properties) {
        return properties != null && (properties.containsKey(FETCH_GRAPH) || properties.containsKey(LOAD_GRAPH));
    }

    private static boolean hasLoadGraph(Map<String, Object> properties) {
        return properties != null && properties.containsKey(LOAD_GRAPH);
    }

    private static boolean locking(LockModeType lockMode) {
        return lockMode != null && lockMode != LockModeType.NONE;
    }

    /** The find of an identity the context holds: its instance, completed, locked and fetched as the find asks. */
    private <T> T findManaged(ManagedEntity known, Class<T> entityClass, MappedEntity type, Object id, FetchPlan plan,
            LockModeType lockMode, Map<String, Object> properties) {
        // a removed instance or an incompatible concrete subtype is not found
        Object instance = known.instance();
        if (known.status() != ManagedEntity.Status.MANAGED || !entityClass.isInstance(instance)) {
            return null;
        }
        boolean locking = locking(lockMode);
        boolean graph = hasGraph(properties);
        boolean loadGraph = hasLoadGraph(properties);
        if (!locking && graph && io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(instance)) {
            onConnection(connection -> JpqlQuery.findGraph(factory.mapping(), type, id, plan, loadGraph,
                factory.flushEngine(connection).dialect(), connection, factory.loader(connection), context));
        }
        io.vidocq.noether.jpa.core.spi.LazyReference.initialize(instance);
        if (locking) {
            lock(instance, lockMode, properties);
        }
        if (!locking && graph) {
            completeGraph(instance, type, plan);
        } else fetch(instance, plan, null);
        return entityClass.cast(instance);
    }

    /** The instance the shared cache restores for a find, with what the find fetches; empty if it holds none. */
    private Optional<Object> findCached(MappedEntity type, Object id, FetchPlan plan, boolean graph) {
        Optional<Object> restored = factory.cache().restore(type, id, context);
        restored.ifPresent(instance -> {
            if (graph) completeGraph(instance, type, plan);
            else fetch(instance, plan, null);
        });
        return restored;
    }

    /** The instance a find reads from the database on {@code connection}, locked and fetched as the find asks. */
    private Object findLoaded(Connection connection, MappedEntity type, EntityKey lookupKey, FetchPlan plan,
            LockModeType lockMode, Integer timeout, Map<String, Object> properties) {
        boolean locking = locking(lockMode);
        Object id = lookupKey.id();
        var loader = factory.loader(connection);
        Object found = !locking && hasGraph(properties) && JpqlQuery.findGraph(factory.mapping(), type, id, plan,
            hasLoadGraph(properties), factory.flushEngine(connection).dialect(), connection, loader, context)
            ? context.find(lookupKey).map(ManagedEntity::instance).orElse(null)
            : loader.load(type, id, connection, context, lockMode, timeout);
        if (found != null && locking) {
            // the row is locked by the select already for a pessimistic mode: record the mode, check nothing
            ManagedEntity entry = context.entry(found).orElseThrow();
            factory.locks(connection).lock(type, entry, Locks.pessimistic(lockMode) ? LockModeType.NONE : lockMode, timeout,
                false, connection, context);
            context.lock(entry, lockMode);
        }
        fetch(found, plan, connection);
        return found;
    }

    /** What a find read from the database goes to the shared cache: with the transaction, or at once without one. */
    private void rememberFound(MappedEntity type, Object id, Object result, CacheStoreMode store, long cacheGeneration) {
        if (result == null || !cached) {
            return;
        }
        if (transaction.isActive()) {
            if (store == CacheStoreMode.BYPASS) {
                bypassStore.add(new EntityKey(type.root(), id));
            } else {
                transactionCacheCandidates.add(new EntityKey(type.root(), id));
            }
        } else if (store != CacheStoreMode.BYPASS) {
            factory.cache().store(result, cacheGeneration);
        }
    }

    /** 3.2: the options of a find; the cache modes and the timeout have no effect without a second-level cache (P11). */
    @Override
    public <T> T find(Class<T> entityClass, Object primaryKey, FindOption... options) {
        return find(entityClass, primaryKey, new HashMap<>(), options);
    }

    private <T> T find(Class<T> entityClass, Object primaryKey, Map<String, Object> properties, FindOption... options) {
        LockModeType lockMode = LockModeType.NONE;
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
        EntityGraphs.validateHint(entityGraph, null, factory.getMetamodel());
        if (!(entityGraph instanceof EntityGraphs.Root<T> graph)) {
            throw new IllegalArgumentException("Entity graph belongs to another provider");
        }
        // 3.2: the graph is applied as a fetch graph, its attribute nodes loaded with the instance
        Map<String, Object> properties = new HashMap<>();
        properties.put(FETCH_GRAPH, graph);
        return find(graph.type, primaryKey, properties, options);
    }

    /**
     * Initializes on the managed {@code instance} the LAZY collections {@code plan} names (§4.4.5.3 fetch joins, §3.7.4
     * entity graphs), and theirs on their targets: on {@code connection}, the one of the query or find that returned it,
     * else as on a first access. An instance this persistence context does not manage is left as it is.
     */
    private void fetch(Object instance, FetchPlan plan, Connection connection) {
        if (instance == null || plan.isEmpty()) return;
        Optional<MappedEntity> type = factory.mapping().entity(instance.getClass());
        if (type.isEmpty()) return;
        for (Map.Entry<String, FetchPlan> attribute : plan.attributes().entrySet()) {
            int index = UnitUtil.index(type.get(), attribute.getKey());
            if (index < 0) continue; // an attribute of another subtype of a graph
            Object value = type.get().access().get(instance, index);
            io.vidocq.noether.jpa.core.spi.LazyReference.initialize(value);
            if (value instanceof LazyCollection lazy && !lazy.initialized()) {
                Optional<ManagedEntity> entry = lazy.owner() == instance ? context.entry(instance) : Optional.empty();
                if (entry.isEmpty()) continue;
                if (connection == null) {
                    lazy.initialize();
                } else {
                    factory.loader(connection).initialize(entry.get(), lazy, connection, context);
                }
            }
            FetchPlan targets = attribute.getValue();
            if (targets.isEmpty() || LazyCollection.unloaded(value)) continue;
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> element : map.entrySet()) {
                    fetch(element.getKey(), targets, connection);
                    fetch(element.getValue(), targets, connection);
                }
            } else if (value instanceof java.util.Collection<?> elements) {
                for (Object element : elements) fetch(element, targets, connection);
            } else {
                fetch(value, targets, connection);
            }
        }
    }

    private void completeGraph(Object instance, MappedEntity type, FetchPlan plan) {
        if (!JpqlQuery.unloadedGraph(type, instance, plan, factory.mapping())) {
            context.loading(() -> { fetch(instance, plan, null); return null; });
            return;
        }
        onConnection(connection -> context.loading(() -> {
            JpqlQuery.completeGraph(factory.mapping(), type, instance, plan,
                factory.flushEngine(connection).dialect(), connection, factory.loader(connection), context);
            fetch(instance, plan, connection);
            return null;
        }));
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
            Integer timeout = lockTimeout(properties);
            withTransactionConnection(connection -> {
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
                    withTransactionConnection(connection -> {
                        factory.locks(connection).lock(entry.type(), entry, lockMode, lockTimeout(Map.of()), false, connection,
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
        try {
            return new JpqlQuery<>(qlString, null, flushMode, queries);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> TypedQuery<T> createQuery(String qlString, Class<T> resultClass) {
        checkOpen();
        try {
            return new JpqlQuery<>(qlString, resultClass, flushMode, queries);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    /** What the queries of this entity manager run with: its connection, its persistence context, its flush. */
    private final QueryRuntime queries = new QueryRuntime() {

        /** The cache modes of the query whose entities are being loaded; {@code null} outside one (BUG-20261113-05). */
        private CacheRetrieveMode queryRetrieveMode;
        private CacheStoreMode queryStoreMode;

        private CacheRetrieveMode loadRetrieveMode() {
            return queryRetrieveMode != null ? queryRetrieveMode : cacheRetrieveMode;
        }

        private CacheStoreMode loadStoreMode() {
            return queryStoreMode != null ? queryStoreMode : cacheStoreMode;
        }

        @Override
        public Defaults defaults() {
            return new Defaults(timeout(UnitSettings.QUERY_TIMEOUT), timeout(UnitSettings.LOCK_TIMEOUT),
                cacheRetrieveMode, cacheStoreMode);
        }

        @Override
        public <T> T read(FlushModeType queryFlushMode, CacheRetrieveMode retrieveMode, CacheStoreMode storeMode,
                Function<Connection, T> work) {
            CacheRetrieveMode outerRetrieveMode = queryRetrieveMode;
            CacheStoreMode outerStoreMode = queryStoreMode;
            queryRetrieveMode = retrieveMode;
            queryStoreMode = storeMode;
            try {
                return read(queryFlushMode, work);
            } finally {
                queryRetrieveMode = outerRetrieveMode;
                queryStoreMode = outerStoreMode;
            }
        }
        @Override
        public MappedUnit mapping() {
            return factory.mapping();
        }

        @Override
        public io.vidocq.noether.jpa.core.query.QueryPlans plans() {
            return factory.queryPlans();
        }

        @Override
        public void fetch(Object entity, FetchPlan plan, Connection connection) {
            EntityManagerImpl.this.fetch(entity, plan, connection);
        }

        @Override
        public FetchPlan plan(Object graph) {
            return EntityGraphs.plan(graph);
        }

        @Override
        public void validateGraph(Object graph, Class<?> root) {
            EntityGraphs.validateHint(graph, root, factory.getMetamodel());
        }

        @Override
        public <T> T read(FlushModeType queryFlushMode, Function<Connection, T> work) {
            checkOpen();
            boolean active = transaction.isActive();
            try {
                return onConnection(connection -> {
                    // §3.10.8: AUTO makes the changes of the transaction visible to the query; no flush outside one
                    if (queryFlushMode == FlushModeType.AUTO && active) {
                        flush(connection);
                    }
                    return context.loading(() -> work.apply(connection));
                });
            } catch (RuntimeException e) {
                throw failed(e);
            }
        }

        @Override
        public <T> T write(FlushModeType queryFlushMode, Function<Connection, T> work) {
            checkOpen();
            if (!transaction.isActive()) {
                throw failed(new TransactionRequiredException("executeUpdate() needs an active transaction (§3.11.6)"));
            }
            try {
                return withTransactionConnection(connection -> {
                    if (queryFlushMode != FlushModeType.COMMIT) {
                        flush(connection);
                    }
                    cacheInvalidationPending = true;
                    return work.apply(connection);
                });
            } catch (RuntimeException e) {
                throw failed(e);
            }
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public boolean inTransaction() {
            return transaction.isActive();
        }

        @Override
        public void locked(Object entity, LockModeType mode) {
            context.entry(entity).ifPresent(entry -> context.lock(entry, mode));
        }

        @Override
        public void lockResult(Object entity, LockModeType mode, Integer timeout, Connection connection) {
            context.entry(entity).ifPresent(entry ->
                factory.locks(connection).lock(entry.type(), entry, mode, timeout, true, connection, context));
        }

        @Override
        public Dialect dialect(Connection connection) {
            return factory.flushEngine(connection).dialect();
        }

        @Override
        public Object find(MappedEntity type, Object id, Connection connection) {
            Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
            if (known.isPresent()) {
                io.vidocq.noether.jpa.core.spi.LazyReference.initialize(known.get().instance());
                return known.get().instance();
            }
            if (!cacheInvalidationPending && loadRetrieveMode() == CacheRetrieveMode.USE
                    && loadStoreMode() != CacheStoreMode.REFRESH) {
                Optional<Object> cached = factory.cache().restore(type, id, context);
                if (cached.isPresent()) {
                    return cached.get();
                }
            }
            long cacheGeneration = factory.cache().generation();
            if (transaction.isActive() && loadStoreMode() != CacheStoreMode.BYPASS) {
                captureCacheGeneration(cacheGeneration);
            }
            return stored(type, id, factory.loader(connection).load(type, id, connection, context), cacheGeneration);
        }

        @Override
        public void hydrate(MappedEntity type, Object id, Object[] values, boolean[] nulls, Connection connection) {
            Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
            if (known.isEmpty() && !cacheInvalidationPending && loadRetrieveMode() == CacheRetrieveMode.USE
                    && loadStoreMode() != CacheStoreMode.REFRESH && factory.cache().restore(type, id, context).isPresent()) {
                return; // as find(): the shared cache's state, when it is the one find would give
            }
            long cacheGeneration = factory.cache().generation();
            if (known.isEmpty() && transaction.isActive() && loadStoreMode() != CacheStoreMode.BYPASS) {
                captureCacheGeneration(cacheGeneration);
            }
            Object loaded = factory.loader(connection).load(type, id, values, nulls, connection, context);
            if (known.isEmpty()) {
                stored(type, id, loaded, cacheGeneration);
            }
        }

        @Override
        public void hydrateCollection(List<io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedEntity> rows,
                MappedEntity owner, int attribute,
                List<io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedElement> elements, Connection connection) {
            hydrateCollections(rows, List.of(new io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedCollection(
                owner, attribute, elements)), connection);
        }

        @Override
        public int graphBatchBound(MappedEntity owner, Connection connection) {
            return factory.loader(connection).batchBound(owner);
        }

        @Override
        public void hydrateCollections(List<io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedEntity> rows,
                List<io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedCollection> collections, Connection connection) {
            if (!cached) { // nothing is restored from the shared cache, nothing is kept for it
                factory.loader(connection).loadCollections(rows, collections, connection, context);
                return;
            }
            Map<EntityKey, MappedEntity> fresh = new java.util.LinkedHashMap<>();
            for (var row : rows) {
                EntityKey key = row.key();
                if (context.find(key).isPresent()) continue;
                if (!cacheInvalidationPending && loadRetrieveMode() == CacheRetrieveMode.USE
                        && loadStoreMode() != CacheStoreMode.REFRESH
                        && factory.cache().restore(row.type(), row.id(), context).isPresent()) continue;
                fresh.putIfAbsent(key, row.type());
            }
            long generation = factory.cache().generation();
            if (!fresh.isEmpty() && transaction.isActive() && loadStoreMode() != CacheStoreMode.BYPASS) captureCacheGeneration(generation);
            factory.loader(connection).loadCollections(rows, collections, connection, context);
            for (var item : fresh.entrySet()) {
                stored(item.getKey(), context.find(item.getKey()).orElseThrow().instance(), generation);
            }
        }

        /** Records a newly loaded instance for the shared cache, as a find by identifier does (§3.10). */
        private Object stored(MappedEntity type, Object id, Object loaded, long cacheGeneration) {
            return loaded == null || !cached ? loaded : stored(new EntityKey(type.root(), id), loaded, cacheGeneration);
        }

        /**
         * {@link #stored(MappedEntity, Object, Object, long)} with the identity already at hand, the key of its root; the
         * cache reads the entity from the instance.
         */
        private Object stored(EntityKey key, Object loaded, long cacheGeneration) {
            if (loaded != null) {
                if (transaction.isActive()) {
                    if (loadStoreMode() == CacheStoreMode.BYPASS) {
                        bypassStore.add(key);
                    } else {
                        transactionCacheCandidates.add(key);
                    }
                } else if (loadStoreMode() != CacheStoreMode.BYPASS) {
                    factory.cache().store(loaded, cacheGeneration);
                }
            }
            return loaded;
        }
    };

    @Override
    public <T> TypedQuery<T> createQuery(TypedQueryReference<T> reference) {
        checkOpen();
        TypedQuery<T> query = createNamedQuery(reference.getName(), resultType(reference));
        if (reference.getHints() != null) {
            reference.getHints().forEach(query::setHint);
        }
        return query;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> resultType(TypedQueryReference<T> reference) {
        return (Class<T>) reference.getResultType();
    }

    @Override
    public <T> TypedQuery<T> createQuery(CriteriaQuery<T> criteriaQuery) {
        checkOpen();
        try {
            return io.vidocq.noether.jpa.core.query.CriteriaBuilderImpl.query(criteriaQuery, flushMode, queries);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> TypedQuery<T> createQuery(CriteriaSelect<T> selectQuery) {
        checkOpen();
        try {
            return io.vidocq.noether.jpa.core.query.CriteriaBuilderImpl.query(selectQuery, flushMode, queries);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Query createQuery(CriteriaUpdate<?> updateQuery) {
        checkOpen();
        try {
            return io.vidocq.noether.jpa.core.query.CriteriaBuilderImpl.query(updateQuery, flushMode, queries);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Query createQuery(CriteriaDelete<?> deleteQuery) {
        checkOpen();
        try {
            return io.vidocq.noether.jpa.core.query.CriteriaBuilderImpl.query(deleteQuery, flushMode, queries);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Query createNamedQuery(String name) {
        checkOpen();
        try {
            return factory.namedQueries().create(name, null, flushMode, queries, this::executeNativeUpdate).query();
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> TypedQuery<T> createNamedQuery(String name, Class<T> resultClass) {
        checkOpen();
        try {
            return factory.namedQueries().create(name, resultClass, flushMode, queries, this::executeNativeUpdate).jpql();
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Query createNativeQuery(String sqlString) {
        checkOpen();
        try {
            return new NativeQuery(sqlString, flushMode, this::executeNativeUpdate, queries, null, null, () -> !closed);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public <T> Query createNativeQuery(String sqlString, Class<T> resultClass) {
        checkOpen();
        try {
            return new NativeQuery(sqlString, flushMode, this::executeNativeUpdate, queries, resultClass, null, () -> !closed);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public Query createNativeQuery(String sqlString, String resultSetMapping) {
        checkOpen();
        try {
            return new NativeQuery(sqlString, flushMode, this::executeNativeUpdate, queries, null, resultSetMapping, () -> !closed);
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public StoredProcedureQuery createNamedStoredProcedureQuery(String name) {
        checkOpen();
        try {
            NamedStoredProcedureModel model = factory.mapping().model().namedStoredProcedure(name).orElseThrow(() ->
                new IllegalArgumentException("The persistence unit has no named stored procedure " + name));
            StoredProcedureQuery query = createStoredProcedureQuery(model.procedureName(),
                model.resultClasses().toArray(Class<?>[]::new));
            if (!model.resultSetMappings().isEmpty()) {
                query = new StoredProcedureQueryImpl(model.procedureName(), model.resultClasses(), model.resultSetMappings(),
                    flushMode, this::executeNativeUpdate, queries, () -> !closed);
            }
            for (NamedStoredProcedureModel.Parameter parameter : model.parameters()) {
                if (parameter.name() == null || parameter.name().isBlank()) {
                    query.registerStoredProcedureParameter(query.getParameters().size() + 1, parameter.type(), parameter.mode());
                } else {
                    query.registerStoredProcedureParameter(parameter.name(), parameter.type(), parameter.mode());
                }
            }
            model.hints().forEach(query::setHint);
            return query;
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String procedureName) {
        checkOpen();
        try {
            return createStoredProcedureQuery(procedureName, List.of(), List.of());
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String procedureName, Class<?>... resultClasses) {
        checkOpen();
        try {
            if (resultClasses == null || java.util.Arrays.stream(resultClasses).anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Stored-procedure result classes cannot contain null");
            }
            for (Class<?> resultClass : resultClasses) {
                if (factory.mapping().entity(resultClass).isEmpty() && !factory.mapping().model().entity(resultClass).isEmpty()) {
                    throw new IllegalArgumentException("Unsupported stored-procedure result class " + resultClass.getName());
                }
            }
            return createStoredProcedureQuery(procedureName, List.of(resultClasses), List.of());
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String procedureName, String... resultSetMappings) {
        checkOpen();
        try {
            if (resultSetMappings == null || java.util.Arrays.stream(resultSetMappings).anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Stored-procedure result mappings cannot contain null");
            }
            for (String mapping : resultSetMappings) {
                if (factory.mapping().model().sqlResultSetMapping(mapping).isEmpty()) {
                    throw new IllegalArgumentException("The persistence unit has no SQL result-set mapping " + mapping);
                }
            }
            return createStoredProcedureQuery(procedureName, List.of(), List.of(resultSetMappings));
        } catch (RuntimeException e) {
            throw failed(e);
        }
    }

    private StoredProcedureQuery createStoredProcedureQuery(String name, List<Class<?>> resultClasses, List<String> mappings) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A stored procedure needs a name");
        }
        return new StoredProcedureQueryImpl(name, resultClasses, mappings, flushMode, this::executeNativeUpdate, queries, () -> !closed);
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
        return factory.entityGraphs().create(rootType);
    }

    @Override
    public EntityGraph<?> createEntityGraph(String graphName) {
        checkOpen();
        return factory.entityGraphs().copy(graphName);
    }

    @Override
    public EntityGraph<?> getEntityGraph(String graphName) {
        checkOpen();
        return factory.entityGraphs().get(graphName);
    }

    @Override
    public <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> entityClass) {
        checkOpen();
        return factory.entityGraphs().applicable(entityClass);
    }
}
