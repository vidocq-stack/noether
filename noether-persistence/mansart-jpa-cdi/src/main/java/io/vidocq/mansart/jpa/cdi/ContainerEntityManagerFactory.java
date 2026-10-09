package io.vidocq.mansart.jpa.cdi;

import jakarta.persistence.*;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Map;
import java.util.Objects;
import java.util.function.*;

/** The deployment, not application code, owns a factory injected by {@code @PersistenceUnit} (§7.8). */
public final class ContainerEntityManagerFactory implements EntityManagerFactory {
    private final EntityManagerFactory delegate;

    public ContainerEntityManagerFactory(EntityManagerFactory delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    public void destroy() { if (delegate.isOpen()) delegate.close(); }
    @Override public void close() { throw new IllegalStateException("An application must not close a container-managed entity manager factory"); }
    @Override public EntityManager createEntityManager() { return delegate.createEntityManager(); }
    @Override public EntityManager createEntityManager(Map<?,?> properties) { return delegate.createEntityManager(properties); }
    @Override public EntityManager createEntityManager(SynchronizationType synchronization) { return delegate.createEntityManager(synchronization); }
    @Override public EntityManager createEntityManager(SynchronizationType synchronization, Map<?,?> properties) { return delegate.createEntityManager(synchronization, properties); }
    @Override public CriteriaBuilder getCriteriaBuilder() { return delegate.getCriteriaBuilder(); }
    @Override public Metamodel getMetamodel() { return delegate.getMetamodel(); }
    @Override public boolean isOpen() { return delegate.isOpen(); }
    @Override public String getName() { return delegate.getName(); }
    @Override public Map<String,Object> getProperties() { return delegate.getProperties(); }
    @Override public Cache getCache() { return delegate.getCache(); }
    @Override public PersistenceUnitUtil getPersistenceUnitUtil() { return delegate.getPersistenceUnitUtil(); }
    @Override public PersistenceUnitTransactionType getTransactionType() { return delegate.getTransactionType(); }
    @Override public SchemaManager getSchemaManager() { return delegate.getSchemaManager(); }
    @Override public void addNamedQuery(String name, Query query) {
        delegate.addNamedQuery(name, query instanceof ContextQuery<?> contextual ? contextual.metadata() : query);
    }
    @Override public <T> T unwrap(Class<T> type) { return type.isInstance(this) ? type.cast(this) : delegate.unwrap(type); }
    @Override public <T> void addNamedEntityGraph(String name, EntityGraph<T> graph) { delegate.addNamedEntityGraph(name, graph); }
    @Override public <R> Map<String,TypedQueryReference<R>> getNamedQueries(Class<R> type) { return delegate.getNamedQueries(type); }
    @Override public <E> Map<String,EntityGraph<? extends E>> getNamedEntityGraphs(Class<E> type) { return delegate.getNamedEntityGraphs(type); }
    @Override public void runInTransaction(Consumer<EntityManager> work) { delegate.runInTransaction(work); }
    @Override public <R> R callInTransaction(Function<EntityManager,R> work) { return delegate.callInTransaction(work); }
}
