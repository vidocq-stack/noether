package io.vidocq.mansart.jpa.cdi;

import jakarta.persistence.*;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.transaction.Transaction;
import jakarta.transaction.Synchronization;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** §7.7 container facade: transaction identity is shared per factory; an extended context belongs to its owner. */
public final class ContainerEntityManager implements EntityManager {
    private static final Map<Transaction, Map<Key, EntityManager>> CONTEXTS = new ConcurrentHashMap<>();
    private record Key(EntityManagerFactory factory, SynchronizationType synchronization) {}
    private final EntityManagerFactory factory;
    private final JtaIntegration integration;
    private final PersistenceContextType type;
    private final SynchronizationType synchronization;
    private final Map<String, Object> properties;
    private EntityManager extended;
    private volatile boolean destroyed;
    private EntityManager queryTemplate;
    private final java.util.concurrent.locks.ReentrantLock metadataLock = new java.util.concurrent.locks.ReentrantLock();
    private final Map<String,java.util.function.Consumer<EntityManager>> settingDefinitions = new LinkedHashMap<>();
    private volatile List<java.util.function.Consumer<EntityManager>> settings = List.of();

    public ContainerEntityManager(EntityManagerFactory factory, JtaIntegration integration, PersistenceContextType type,
            SynchronizationType synchronization, Map<String, Object> properties) {
        this.factory = Objects.requireNonNull(factory);
        this.integration = Objects.requireNonNull(integration);
        this.type = Objects.requireNonNull(type);
        this.synchronization = Objects.requireNonNull(synchronization);
        this.properties = Map.copyOf(properties);
        if (factory.getTransactionType() != PersistenceUnitTransactionType.JTA) {
            throw new PersistenceException("A container-managed persistence context requires a JTA persistence unit");
        }
        if (type == PersistenceContextType.EXTENDED) extended = factory.createEntityManager(synchronization, properties);
    }

    private EntityManager delegate() {
        checkOpen();
        if (extended != null) return configured(extended);
        Transaction transaction = integration.current();
        if (transaction == null) throw new TransactionRequiredException("A transaction-scoped persistence context requires an active JTA transaction");
        Map<Key, EntityManager> contexts = CONTEXTS.computeIfAbsent(transaction, current -> {
            var created = new ConcurrentHashMap<Key, EntityManager>();
            try {
                current.registerSynchronization(new Synchronization() {
                    @Override public void beforeCompletion() {}
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
        settings.forEach(setting -> setting.accept(em));
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
            settings = List.copyOf(settingDefinitions.values());
            return null;
        });
        if (extended != null || integration.current() != null) setting.accept(delegate());
    }

    private <T> T read(Function<EntityManager, T> operation) {
        if (type == PersistenceContextType.TRANSACTION && integration.current() == null) {
            if (!isOpen()) throw new IllegalStateException("The container persistence context has been destroyed");
            try (var transientContext = factory.createEntityManager(synchronization, properties)) {
                T value = operation.apply(configured(transientContext));
                transientContext.clear();
                return value;
            }
        }
        return operation.apply(delegate());
    }

    <T> T readContext(Function<EntityManager, T> operation) { return read(operation); }
    <T> T writeContext(Function<EntityManager, T> operation) { return operation.apply(delegate()); }

    private <T> ContextQuery<T> query(Function<EntityManager, ? extends Query> creator) {
        return metadataWork(em -> new ContextQuery<>(this, creator, creator.apply(em)));
    }

    private ContextStoredProcedureQuery procedure(Function<EntityManager, StoredProcedureQuery> creator) {
        return metadataWork(em -> new ContextStoredProcedureQuery(this, creator, creator.apply(em)));
    }

    void ensureOpen() { checkOpen(); }

    boolean inTransaction() { return integration.current() != null; }

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
    @Override public void clear() { checkOpen(); if (extended != null || integration.current() != null) delegate().clear(); }
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
    @Override public <T> TypedQuery<T> createQuery(CriteriaQuery<T> query) { return query(em -> em.createQuery(query)); }
    @Override public <T> TypedQuery<T> createQuery(CriteriaSelect<T> query) { return query(em -> em.createQuery(query)); }
    @Override public Query createQuery(CriteriaUpdate<?> query) { return query(em -> em.createQuery(query)); }
    @Override public Query createQuery(CriteriaDelete<?> query) { return query(em -> em.createQuery(query)); }
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
    @Override public boolean isJoinedToTransaction() { checkOpen(); return integration.current() != null && delegate().isJoinedToTransaction(); }
    @Override public <T> T unwrap(Class<T> cls) { checkOpen(); if (cls.isInstance(this)) return cls.cast(this); return delegate().unwrap(cls); }
    @Override public Object getDelegate() { return delegate().getDelegate(); }
    @Override public CriteriaBuilder getCriteriaBuilder() { checkOpen(); return factory.getCriteriaBuilder(); }
    @Override public Metamodel getMetamodel() { checkOpen(); return factory.getMetamodel(); }
    @Override public <T> EntityGraph<T> createEntityGraph(Class<T> cls) { return read(em -> em.createEntityGraph(cls)); }
    @Override public EntityGraph<?> createEntityGraph(String name) { return read(em -> em.createEntityGraph(name)); }
    @Override public EntityGraph<?> getEntityGraph(String name) { return read(em -> em.getEntityGraph(name)); }
    @Override public <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> cls) { return read(em -> em.getEntityGraphs(cls)); }
    @Override public <C> void runWithConnection(ConnectionConsumer<C> action) { delegate().runWithConnection(action); }
    @Override public <C,T> T callWithConnection(ConnectionFunction<C,T> action) { return delegate().callWithConnection(action); }
}
