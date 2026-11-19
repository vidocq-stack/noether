package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.QueryCopies;
import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * §7.7 container facade: transaction identity is shared per factory; an extended context belongs to its owner. The
 * transaction is the one of the container's {@link TransactionSynchronizationRegistry} (JTA 2.0 §3.4.4): its key
 * identifies the transaction-scoped contexts, an interposed synchronization releases them after completion.
 */
public final class ContainerEntityManager implements EntityManager {
    private static final Map<Object, Map<Key, EntityManager>> CONTEXTS = new ConcurrentHashMap<>();
    private record Key(EntityManagerFactory factory, SynchronizationType synchronization) {}
    private final EntityManagerFactory factory;
    private final TransactionSynchronizationRegistry registry;
    private final PersistenceContextType type;
    private final SynchronizationType synchronization;
    private final Map<String, Object> properties;
    private EntityManager extended;
    private volatile boolean destroyed;
    private EntityManager queryTemplate;
    private final java.util.concurrent.locks.ReentrantLock metadataLock = new java.util.concurrent.locks.ReentrantLock();
    private final Map<String,java.util.function.Consumer<EntityManager>> settingDefinitions = new LinkedHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<List<java.util.function.Consumer<EntityManager>>> settings =
        new java.util.concurrent.atomic.AtomicReference<>(List.of());

    public ContainerEntityManager(EntityManagerFactory factory, TransactionSynchronizationRegistry registry,
            PersistenceContextType type, SynchronizationType synchronization, Map<String, Object> properties) {
        this.factory = Objects.requireNonNull(factory);
        this.registry = Objects.requireNonNull(registry);
        this.type = Objects.requireNonNull(type);
        this.synchronization = Objects.requireNonNull(synchronization);
        this.properties = Map.copyOf(properties);
        // Only a resource-local unit is refused: the factory of another provider may state no transaction type, and the
        // unit the container hands it is JTA (BUG-20261119-91).
        if (factory.getTransactionType() == PersistenceUnitTransactionType.RESOURCE_LOCAL) {
            throw new PersistenceException("A container-managed persistence context requires a JTA persistence unit");
        }
        if (type == PersistenceContextType.EXTENDED) extended = factory.createEntityManager(synchronization, properties);
    }

    /** The key of the current transaction while it can still do work, otherwise {@code null}. */
    private Object currentTransaction() {
        int status = registry.getTransactionStatus();
        return status == Status.STATUS_ACTIVE || status == Status.STATUS_MARKED_ROLLBACK ? registry.getTransactionKey() : null;
    }

    private EntityManager delegate() {
        checkOpen();
        if (extended != null) return configured(extended);
        Object transaction = currentTransaction();
        if (transaction == null) throw new TransactionRequiredException("A transaction-scoped persistence context requires an active JTA transaction");
        Map<Key, EntityManager> contexts = CONTEXTS.computeIfAbsent(transaction, current -> {
            var created = new ConcurrentHashMap<Key, EntityManager>();
            try {
                registry.registerInterposedSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() { /* the contexts flush through their own synchronization */ }
                    @Override public void afterCompletion(int status) {
                        CONTEXTS.remove(current, created);
                        created.values().forEach(em -> {
                            try {
                                if (em.isOpen()) { em.clear(); em.close(); }
                            } catch (RuntimeException failure) {
                                System.getLogger(ContainerEntityManager.class.getName()).log(System.Logger.Level.WARNING,
                                    "Unable to release a completed transaction-scoped persistence context", failure);
                            }
                        });
                    }
                });
            } catch (Exception failure) { throw new PersistenceException("Unable to bind the transaction-scoped context", failure); }
            return created;
        });
        return configured(contexts.computeIfAbsent(new Key(factory, synchronization), _ -> factory.createEntityManager(synchronization, properties)));
    }

    private EntityManager configured(EntityManager em) {
        settings.get().forEach(setting -> setting.accept(em));
        return em;
    }

    private void checkOpen() {
        if (!isOpen()) throw new IllegalStateException("The container persistence context has been destroyed");
    }

    private EntityManager metadata() {
        checkOpen();
        if (queryTemplate == null) queryTemplate = factory.createEntityManager(SynchronizationType.UNSYNCHRONIZED, properties);
        return configured(queryTemplate);
    }

    private <T> T metadataWork(Function<EntityManager,T> work) {
        metadataLock.lock();
        try { return work.apply(metadata()); }
        finally { metadataLock.unlock(); }
    }

    private void setting(String key, java.util.function.Consumer<EntityManager> setting) {
        metadataWork(em -> {
            setting.accept(em);
            settingDefinitions.remove(key);
            settingDefinitions.put(key, setting);
            settings.set(List.copyOf(settingDefinitions.values()));
            return null;
        });
        if (extended != null || currentTransaction() != null) setting.accept(delegate());
    }

    private <T> T read(Function<EntityManager, T> operation) {
        if (type == PersistenceContextType.TRANSACTION && currentTransaction() == null) {
            if (!isOpen()) throw new IllegalStateException("The container persistence context has been destroyed");
            try (var transientContext = factory.createEntityManager(synchronization, properties)) {
                T value = operation.apply(configured(transientContext));
                transientContext.clear();
                return value;
            }
        }
        return operation.apply(delegate());
    }

    /**
     * The provider's entity manager of this context, as {@code unwrap} and {@code getDelegate} reach it: outside a
     * transaction, the one the container keeps for work outside transactions, since only persist, merge, remove,
     * refresh, flush and lock need a transaction (§7.6.2, BUG-20261119-92).
     */
    private <T> T provider(Function<EntityManager, T> access) {
        if (type == PersistenceContextType.TRANSACTION && currentTransaction() == null) return metadataWork(access);
        return access.apply(delegate());
    }

    <T> T readContext(Function<EntityManager, T> operation) { return marking(() -> read(operation)); }
    <T> T writeContext(Function<EntityManager, T> operation) { return marking(() -> operation.apply(delegate())); }

    private <T> ContextQuery<T> query(Function<EntityManager, ? extends Query> creator) {
        return marking(() -> metadataWork(em -> new ContextQuery<>(this, creator, creator.apply(em))));
    }

    /**
     * A criteria query, made once as its criteria object is now, then copied into the context that runs it: a later
     * change of that object does not reach the query (BUG-20261118-84).
     */
    private <T> ContextQuery<T> copied(Function<EntityManager, ? extends Query> creator) {
        return marking(() -> metadataWork(em -> {
            Query created = creator.apply(em);
            return new ContextQuery<>(this, context -> context.unwrap(QueryCopies.class).copy(created), created);
        }));
    }

    private ContextStoredProcedureQuery procedure(Function<EntityManager, StoredProcedureQuery> creator) {
        return marking(() -> metadataWork(em -> new ContextStoredProcedureQuery(this, creator, creator.apply(em))));
    }

    /**
     * Runs a method of this facade or of one of its queries, and marks the transaction the persistence context joins
     * for rollback when it throws (§3.1.1, §3.11.1). A query is created and configured on a context no transaction
     * joins, and runs on another one: the provider cannot mark the transaction of the caller's context, so the facade
     * does.
     */
    <T> T marking(Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException failure) {
            if (!(failure instanceof LockTimeoutException || failure instanceof QueryTimeoutException
                    || failure instanceof NoResultException || failure instanceof NonUniqueResultException)
                    && joinedContext()) {
                registry.setRollbackOnly();
            }
            throw failure;
        }
    }

    /** Whether the persistence context of this facade is joined to the current transaction (§7.6.1). */
    private boolean joinedContext() {
        if (!isOpen() || currentTransaction() == null) return false;
        return synchronization == SynchronizationType.SYNCHRONIZED || delegate().isJoinedToTransaction();
    }

    void ensureOpen() { checkOpen(); }

    boolean inTransaction() { return currentTransaction() != null; }

    /** Called only by the CDI owner/disposer, never by application code. */
    public void destroy() {
        EntityManager template;
        metadataLock.lock();
        try {
            if (destroyed) return;
            destroyed = true;
            template = queryTemplate;
            queryTemplate = null;
        } finally { metadataLock.unlock(); }
        if (extended != null) extended.close();
        if (template != null && template.isOpen()) template.close();
    }

    @Override public void close() { throw new IllegalStateException("An application must not close a container-managed entity manager (§7.7)"); }
    @Override public boolean isOpen() { return !destroyed && factory.isOpen(); }
    @Override public EntityTransaction getTransaction() { throw new IllegalStateException("A container-managed entity manager has no resource-local transaction"); }
    @Override public EntityManagerFactory getEntityManagerFactory() { return factory; }
    @Override public void persist(Object entity) { delegate().persist(entity); }
    @Override public <T> T merge(T entity) { return delegate().merge(entity); }
    @Override public void remove(Object entity) { delegate().remove(entity); }
    @Override public <T> T find(Class<T> cls, Object key) { return read(em -> em.find(cls, key)); }
    @Override public <T> T find(Class<T> cls, Object key, Map<String,Object> hints) { return read(em -> em.find(cls, key, hints)); }
    @Override public <T> T find(Class<T> cls, Object key, LockModeType lock) { return read(em -> em.find(cls, key, lock)); }
    @Override public <T> T find(Class<T> cls, Object key, LockModeType lock, Map<String,Object> hints) { return read(em -> em.find(cls, key, lock, hints)); }
    @Override public <T> T find(Class<T> cls, Object key, FindOption... options) { return read(em -> em.find(cls, key, options)); }
    @Override public <T> T find(EntityGraph<T> graph, Object key, FindOption... options) { return read(em -> em.find(graph, key, options)); }
    @Override public <T> T getReference(Class<T> cls, Object key) { return read(em -> em.getReference(cls, key)); }
    @Override public <T> T getReference(T entity) { return read(em -> em.getReference(entity)); }
    @Override public void flush() { delegate().flush(); }
    @Override public void setFlushMode(FlushModeType mode) { setting("flush-mode", em -> em.setFlushMode(mode)); }
    @Override public FlushModeType getFlushMode() { return metadataWork(EntityManager::getFlushMode); }
    @Override public void lock(Object entity, LockModeType lock) { delegate().lock(entity, lock); }
    @Override public void lock(Object entity, LockModeType lock, Map<String,Object> hints) { delegate().lock(entity, lock, hints); }
    @Override public void lock(Object entity, LockModeType lock, LockOption... options) { delegate().lock(entity, lock, options); }
    @Override public void refresh(Object entity) { delegate().refresh(entity); }
    @Override public void refresh(Object entity, Map<String,Object> hints) { delegate().refresh(entity, hints); }
    @Override public void refresh(Object entity, LockModeType lock) { delegate().refresh(entity, lock); }
    @Override public void refresh(Object entity, LockModeType lock, Map<String,Object> hints) { delegate().refresh(entity, lock, hints); }
    @Override public void refresh(Object entity, RefreshOption... options) { delegate().refresh(entity, options); }
    @Override public void clear() { checkOpen(); if (extended != null || currentTransaction() != null) delegate().clear(); }
    @Override public void detach(Object entity) { read(em -> { em.detach(entity); return null; }); }
    @Override public boolean contains(Object entity) { return read(em -> em.contains(entity)); }
    @Override public LockModeType getLockMode(Object entity) { return delegate().getLockMode(entity); }
    @Override public void setCacheRetrieveMode(CacheRetrieveMode mode) { setting("retrieve-mode", em -> em.setCacheRetrieveMode(mode)); }
    @Override public void setCacheStoreMode(CacheStoreMode mode) { setting("store-mode", em -> em.setCacheStoreMode(mode)); }
    @Override public CacheRetrieveMode getCacheRetrieveMode() { return metadataWork(EntityManager::getCacheRetrieveMode); }
    @Override public CacheStoreMode getCacheStoreMode() { return metadataWork(EntityManager::getCacheStoreMode); }
    @Override public void setProperty(String name, Object value) { setting("property:" + name, em -> em.setProperty(name, value)); }
    @Override public Map<String,Object> getProperties() { return metadataWork(EntityManager::getProperties); }
    @Override public Query createQuery(String query) { return query(em -> em.createQuery(query)); }
    @Override public <T> TypedQuery<T> createQuery(CriteriaQuery<T> query) { return copied(em -> em.createQuery(query)); }
    @Override public <T> TypedQuery<T> createQuery(CriteriaSelect<T> query) { return copied(em -> em.createQuery(query)); }
    @Override public Query createQuery(CriteriaUpdate<?> query) { return copied(em -> em.createQuery(query)); }
    @Override public Query createQuery(CriteriaDelete<?> query) { return copied(em -> em.createQuery(query)); }
    @Override public <T> TypedQuery<T> createQuery(String query, Class<T> cls) { return query(em -> em.createQuery(query, cls)); }
    @Override public Query createNamedQuery(String name) { return query(em -> em.createNamedQuery(name)); }
    @Override public <T> TypedQuery<T> createNamedQuery(String name, Class<T> cls) { return query(em -> em.createNamedQuery(name, cls)); }
    @Override public <T> TypedQuery<T> createQuery(TypedQueryReference<T> reference) { return query(em -> em.createQuery(reference)); }
    @Override public Query createNativeQuery(String query) { return query(em -> em.createNativeQuery(query)); }
    @Override public <T> Query createNativeQuery(String query, Class<T> cls) { return query(em -> em.createNativeQuery(query, cls)); }
    @Override public Query createNativeQuery(String query, String mapping) { return query(em -> em.createNativeQuery(query, mapping)); }
    @Override public StoredProcedureQuery createNamedStoredProcedureQuery(String name) { return procedure(em -> em.createNamedStoredProcedureQuery(name)); }
    @Override public StoredProcedureQuery createStoredProcedureQuery(String name) { return procedure(em -> em.createStoredProcedureQuery(name)); }
    @Override public StoredProcedureQuery createStoredProcedureQuery(String name, Class<?>... classes) { return procedure(em -> em.createStoredProcedureQuery(name, classes)); }
    @Override public StoredProcedureQuery createStoredProcedureQuery(String name, String... mappings) { return procedure(em -> em.createStoredProcedureQuery(name, mappings)); }
    @Override public void joinTransaction() { delegate().joinTransaction(); }
    @Override public boolean isJoinedToTransaction() { checkOpen(); return currentTransaction() != null && delegate().isJoinedToTransaction(); }
    @Override public <T> T unwrap(Class<T> cls) { checkOpen(); return cls.isInstance(this) ? cls.cast(this) : provider(em -> em.unwrap(cls)); }
    @Override public Object getDelegate() { return provider(EntityManager::getDelegate); }
    @Override public CriteriaBuilder getCriteriaBuilder() { checkOpen(); return factory.getCriteriaBuilder(); }
    @Override public Metamodel getMetamodel() { checkOpen(); return factory.getMetamodel(); }
    @Override public <T> EntityGraph<T> createEntityGraph(Class<T> cls) { return read(em -> em.createEntityGraph(cls)); }
    @Override public EntityGraph<?> createEntityGraph(String name) { return read(em -> em.createEntityGraph(name)); }
    @Override public EntityGraph<?> getEntityGraph(String name) { return read(em -> em.getEntityGraph(name)); }
    @Override public <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> cls) { return read(em -> em.getEntityGraphs(cls)); }
    @Override public <C> void runWithConnection(ConnectionConsumer<C> action) { delegate().runWithConnection(action); }
    @Override public <C,T> T callWithConnection(ConnectionFunction<C,T> action) { return delegate().callWithConnection(action); }
}
