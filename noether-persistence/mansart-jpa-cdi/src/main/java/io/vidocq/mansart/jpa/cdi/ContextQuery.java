package io.vidocq.mansart.jpa.cdi;

import jakarta.persistence.*;
import java.util.*;
import java.util.function.*;

/** A container query resolves its transaction-scoped execution context when executed, not when created (§7.7.1). */
final class ContextQuery<X> implements TypedQuery<X> {
    private final ContainerEntityManager owner;
    private final Function<EntityManager, ? extends Query> creator;
    private final Query metadata;
    private final List<Consumer<Query>> configuration = new ArrayList<>();

    ContextQuery(ContainerEntityManager owner, Function<EntityManager, ? extends Query> creator, Query metadata) {
        this.owner = owner;
        this.creator = creator;
        this.metadata = metadata;
    }

    private Query query(EntityManager context) {
        Query query = creator.apply(context);
        configuration.forEach(setting -> setting.accept(query));
        return query;
    }

    private ContextQuery<X> configure(Consumer<Query> setting) {
        setting.accept(metadata);
        configuration.add(setting);
        return this;
    }

    Query metadata() { return metadata; }

    @Override @SuppressWarnings("unchecked") public List<X> getResultList() {
        return owner.readContext(em -> (List<X>) query(em).getResultList());
    }
    @Override @SuppressWarnings("unchecked") public X getSingleResult() {
        return owner.readContext(em -> (X) query(em).getSingleResult());
    }
    @Override @SuppressWarnings("unchecked") public X getSingleResultOrNull() {
        return owner.readContext(em -> (X) query(em).getSingleResultOrNull());
    }
    @Override public int executeUpdate() { return owner.writeContext(em -> query(em).executeUpdate()); }
    @Override public ContextQuery<X> setMaxResults(int value) { return configure(q -> q.setMaxResults(value)); }
    @Override public int getMaxResults() { return metadata.getMaxResults(); }
    @Override public ContextQuery<X> setFirstResult(int value) { return configure(q -> q.setFirstResult(value)); }
    @Override public int getFirstResult() { return metadata.getFirstResult(); }
    @Override public ContextQuery<X> setHint(String name, Object value) { return configure(q -> q.setHint(name, value)); }
    @Override public Map<String,Object> getHints() { return metadata.getHints(); }
    @Override public <T> ContextQuery<X> setParameter(Parameter<T> parameter, T value) {
        metadata.setParameter(parameter, value);
        configuration.add(q -> {
            if (parameter.getName() != null) q.setParameter(parameter.getName(), value);
            else q.setParameter(parameter.getPosition(), value);
        });
        return this;
    }
    @Override public ContextQuery<X> setParameter(Parameter<Calendar> parameter, Calendar value, TemporalType temporal) {
        metadata.setParameter(parameter, value, temporal);
        configuration.add(q -> {
            if (parameter.getName() != null) q.setParameter(parameter.getName(), value, temporal);
            else q.setParameter(parameter.getPosition(), value, temporal);
        });
        return this;
    }
    @Override public ContextQuery<X> setParameter(Parameter<Date> parameter, Date value, TemporalType temporal) {
        metadata.setParameter(parameter, value, temporal);
        configuration.add(q -> {
            if (parameter.getName() != null) q.setParameter(parameter.getName(), value, temporal);
            else q.setParameter(parameter.getPosition(), value, temporal);
        });
        return this;
    }
    @Override public ContextQuery<X> setParameter(String name, Object value) { return configure(q -> q.setParameter(name, value)); }
    @Override public ContextQuery<X> setParameter(String name, Calendar value, TemporalType temporal) { return configure(q -> q.setParameter(name, value, temporal)); }
    @Override public ContextQuery<X> setParameter(String name, Date value, TemporalType temporal) { return configure(q -> q.setParameter(name, value, temporal)); }
    @Override public ContextQuery<X> setParameter(int position, Object value) { return configure(q -> q.setParameter(position, value)); }
    @Override public ContextQuery<X> setParameter(int position, Calendar value, TemporalType temporal) { return configure(q -> q.setParameter(position, value, temporal)); }
    @Override public ContextQuery<X> setParameter(int position, Date value, TemporalType temporal) { return configure(q -> q.setParameter(position, value, temporal)); }
    @Override public Set<Parameter<?>> getParameters() { return metadata.getParameters(); }
    @Override public Parameter<?> getParameter(String name) { return metadata.getParameter(name); }
    @Override public <T> Parameter<T> getParameter(String name, Class<T> type) { return metadata.getParameter(name, type); }
    @Override public Parameter<?> getParameter(int position) { return metadata.getParameter(position); }
    @Override public <T> Parameter<T> getParameter(int position, Class<T> type) { return metadata.getParameter(position, type); }
    @Override public boolean isBound(Parameter<?> parameter) { return metadata.isBound(parameter); }
    @Override public <T> T getParameterValue(Parameter<T> parameter) { return metadata.getParameterValue(parameter); }
    @Override public Object getParameterValue(String name) { return metadata.getParameterValue(name); }
    @Override public Object getParameterValue(int position) { return metadata.getParameterValue(position); }
    @Override public ContextQuery<X> setFlushMode(FlushModeType mode) { return configure(q -> q.setFlushMode(mode)); }
    @Override public FlushModeType getFlushMode() { return metadata.getFlushMode(); }
    @Override public ContextQuery<X> setLockMode(LockModeType mode) { return configure(q -> q.setLockMode(mode)); }
    @Override public LockModeType getLockMode() { return metadata.getLockMode(); }
    @Override public ContextQuery<X> setCacheRetrieveMode(CacheRetrieveMode mode) { return configure(q -> q.setCacheRetrieveMode(mode)); }
    @Override public CacheRetrieveMode getCacheRetrieveMode() { return metadata.getCacheRetrieveMode(); }
    @Override public ContextQuery<X> setCacheStoreMode(CacheStoreMode mode) { return configure(q -> q.setCacheStoreMode(mode)); }
    @Override public CacheStoreMode getCacheStoreMode() { return metadata.getCacheStoreMode(); }
    @Override public ContextQuery<X> setTimeout(Integer timeout) { return configure(q -> q.setTimeout(timeout)); }
    @Override public Integer getTimeout() { return metadata.getTimeout(); }
    @Override public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) return type.cast(this);
        throw new PersistenceException("Unsupported contextual query unwrap: " + type.getName());
    }
}
