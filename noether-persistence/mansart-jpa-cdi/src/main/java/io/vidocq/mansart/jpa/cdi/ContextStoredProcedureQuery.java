package io.vidocq.mansart.jpa.cdi;

import jakarta.persistence.*;
import java.util.*;
import java.util.function.*;

/**
 * A container stored-procedure query is created and configured without a transaction, then bound to the
 * current transaction-scoped context at execution (§3.11.12, §7.7.1). The underlying query is replayed
 * from the recorded configuration and its results, update counts and outputs are copied before that context ends.
 * Like the core query it is not thread-safe.
 */
final class ContextStoredProcedureQuery implements StoredProcedureQuery {
    private record Registered(Integer position, String name, ParameterMode mode) {}
    private record Result(List<Object> rows, int updateCount) {
        boolean isRows() { return rows != null; }
    }
    private record Execution(List<Result> results, Map<Registered, Object> outputs) {}

    private final ContainerEntityManager owner;
    private final Function<EntityManager, StoredProcedureQuery> creator;
    private final StoredProcedureQuery metadata;
    private final List<Consumer<StoredProcedureQuery>> configuration = new ArrayList<>();
    private final List<Registered> registered = new ArrayList<>();
    private Execution execution;
    private int resultIndex;

    ContextStoredProcedureQuery(ContainerEntityManager owner, Function<EntityManager, StoredProcedureQuery> creator,
            StoredProcedureQuery metadata) {
        this.owner = owner;
        this.creator = creator;
        this.metadata = metadata;
        for (Parameter<?> parameter : metadata.getParameters()) {
            // named queries arrive pre-registered; their modes are only known to the template query
            registered.add(new Registered(parameter.getPosition(), parameter.getName(), null));
        }
    }

    private Execution run(EntityManager context) {
        StoredProcedureQuery query = creator.apply(context);
        configuration.forEach(setting -> setting.accept(query));
        List<Result> results = new ArrayList<>();
        boolean rows = query.execute();
        int count = rows ? -1 : query.getUpdateCount();
        while (true) {
            if (rows) results.add(new Result(Collections.unmodifiableList(new ArrayList<>(query.getResultList())), -1));
            else if (count != -1) results.add(new Result(null, count));
            else break;
            rows = query.hasMoreResults();
            count = rows ? -1 : query.getUpdateCount();
        }
        Map<Registered, Object> outputs = new LinkedHashMap<>();
        for (Registered parameter : registered) {
            if (parameter.mode() == ParameterMode.IN) continue;
            try {
                outputs.put(parameter, parameter.name() != null ? query.getOutputParameterValue(parameter.name())
                    : query.getOutputParameterValue(parameter.position()));
            } catch (IllegalArgumentException input) {
                // a pre-registered named parameter whose mode is unknown here is an input one
            }
        }
        return new Execution(results, outputs);
    }

    private void store(Execution result) {
        execution = result;
        resultIndex = 0;
    }

    private Execution executed() {
        owner.ensureOpen();
        if (execution == null) throw new IllegalStateException("The stored procedure has not been executed");
        return execution;
    }

    private Result current() {
        Execution state = executed();
        return resultIndex < state.results().size() ? state.results().get(resultIndex) : null;
    }

    private StoredProcedureQuery configure(Consumer<StoredProcedureQuery> setting) {
        setting.accept(metadata);
        configuration.add(setting);
        return this;
    }

    private StoredProcedureQuery rebind(Consumer<StoredProcedureQuery> setting) {
        configure(setting);
        execution = null;
        return this;
    }

    private StoredProcedureQuery register(Registered parameter, Consumer<StoredProcedureQuery> setting) {
        rebind(setting);
        registered.add(parameter);
        return this;
    }

    @Override public StoredProcedureQuery registerStoredProcedureParameter(int position, Class<?> type, ParameterMode mode) {
        return register(new Registered(position, null, mode), q -> q.registerStoredProcedureParameter(position, type, mode));
    }
    @Override public StoredProcedureQuery registerStoredProcedureParameter(String name, Class<?> type, ParameterMode mode) {
        return register(new Registered(null, name, mode), q -> q.registerStoredProcedureParameter(name, type, mode));
    }

    private Registered find(Predicate<Registered> match, Object key) {
        for (Registered parameter : registered) {
            if (match.test(parameter)) {
                if (parameter.mode() == ParameterMode.IN) throw new IllegalArgumentException("The parameter is not an output parameter: " + key);
                return parameter;
            }
        }
        throw new IllegalArgumentException("The stored procedure has no parameter " + key);
    }

    private Object output(Predicate<Registered> match, Object key) {
        Registered parameter = find(match, key);
        Execution state = executed();
        if (!state.outputs().containsKey(parameter)) throw new IllegalArgumentException("The parameter is not an output parameter: " + key);
        return state.outputs().get(parameter);
    }

    @Override public Object getOutputParameterValue(int position) { return output(p -> Objects.equals(p.position(), position), position); }
    @Override public Object getOutputParameterValue(String name) { return output(p -> name.equals(p.name()), name); }

    @Override public boolean execute() {
        owner.ensureOpen();
        execution = null;
        store(owner.readContext(this::run));
        Result first = current();
        return first != null && first.isRows();
    }

    @Override public boolean hasMoreResults() {
        executed();
        if (resultIndex + 1 >= execution.results().size()) {
            resultIndex = execution.results().size();
            return false;
        }
        return execution.results().get(++resultIndex).isRows();
    }

    @Override public int getUpdateCount() {
        Result result = current();
        return result == null ? -1 : result.updateCount();
    }

    @Override public int executeUpdate() {
        owner.ensureOpen();
        if (!owner.inTransaction()) throw new TransactionRequiredException("executeUpdate() needs an active transaction");
        if (execution == null) store(owner.writeContext(this::run));
        Result result = current();
        if (result != null && result.isRows()) {
            throw new PersistenceException("The stored procedure returned a result set, not an update count");
        }
        return result == null ? -1 : result.updateCount();
    }

    @Override @SuppressWarnings("rawtypes") public List getResultList() {
        owner.ensureOpen();
        if (execution == null) execute();
        Result result = current();
        if (result == null || !result.isRows()) return null;
        List<Object> rows = result.rows();
        int first = Math.min(metadata.getFirstResult(), rows.size());
        int end = (int) Math.min(rows.size(), (long) first + metadata.getMaxResults());
        return rows.subList(first, end);
    }

    @Override public Object getSingleResult() {
        List<?> rows = getResultList();
        if (rows == null) return null;
        if (rows.isEmpty()) throw new NoResultException("The stored procedure returned no result");
        if (rows.size() > 1) throw new NonUniqueResultException("The stored procedure returned several results");
        return rows.getFirst();
    }
    @Override public Object getSingleResultOrNull() {
        List<?> rows = getResultList();
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() > 1) throw new NonUniqueResultException("The stored procedure returned several results");
        return rows.getFirst();
    }

    @Override public StoredProcedureQuery setMaxResults(int value) { return configureLimit(q -> q.setMaxResults(value)); }
    @Override public int getMaxResults() { return metadata.getMaxResults(); }
    @Override public StoredProcedureQuery setFirstResult(int value) { return configureLimit(q -> q.setFirstResult(value)); }
    @Override public int getFirstResult() { return metadata.getFirstResult(); }

    // Paging is applied to the copied results, so it is applied to the metadata only and never replayed.
    private StoredProcedureQuery configureLimit(Consumer<StoredProcedureQuery> setting) {
        setting.accept(metadata);
        return this;
    }

    @Override public StoredProcedureQuery setHint(String name, Object value) { return configure(q -> q.setHint(name, value)); }
    @Override public Map<String,Object> getHints() { return metadata.getHints(); }
    @Override public StoredProcedureQuery setFlushMode(FlushModeType mode) { return configure(q -> q.setFlushMode(mode)); }
    @Override public FlushModeType getFlushMode() { return metadata.getFlushMode(); }
    @Override public StoredProcedureQuery setLockMode(LockModeType mode) { return configure(q -> q.setLockMode(mode)); }
    @Override public LockModeType getLockMode() { return metadata.getLockMode(); }
    @Override public StoredProcedureQuery setCacheRetrieveMode(CacheRetrieveMode mode) { return configure(q -> q.setCacheRetrieveMode(mode)); }
    @Override public CacheRetrieveMode getCacheRetrieveMode() { return metadata.getCacheRetrieveMode(); }
    @Override public StoredProcedureQuery setCacheStoreMode(CacheStoreMode mode) { return configure(q -> q.setCacheStoreMode(mode)); }
    @Override public CacheStoreMode getCacheStoreMode() { return metadata.getCacheStoreMode(); }
    @Override public StoredProcedureQuery setTimeout(Integer timeout) { return configure(q -> q.setTimeout(timeout)); }
    @Override public Integer getTimeout() { return metadata.getTimeout(); }

    @Override public StoredProcedureQuery setParameter(int position, Object value) { return rebind(q -> q.setParameter(position, value)); }
    @Override public StoredProcedureQuery setParameter(String name, Object value) { return rebind(q -> q.setParameter(name, value)); }
    @Override public <T> StoredProcedureQuery setParameter(Parameter<T> parameter, T value) {
        metadata.setParameter(parameter, value);
        configuration.add(q -> {
            if (parameter.getName() != null) q.setParameter(parameter.getName(), value);
            else q.setParameter(parameter.getPosition(), value);
        });
        execution = null;
        return this;
    }
    @Override public StoredProcedureQuery setParameter(int position, Calendar value, TemporalType temporal) { return rebind(q -> q.setParameter(position, value, temporal)); }
    @Override public StoredProcedureQuery setParameter(int position, Date value, TemporalType temporal) { return rebind(q -> q.setParameter(position, value, temporal)); }
    @Override public StoredProcedureQuery setParameter(String name, Calendar value, TemporalType temporal) { return rebind(q -> q.setParameter(name, value, temporal)); }
    @Override public StoredProcedureQuery setParameter(String name, Date value, TemporalType temporal) { return rebind(q -> q.setParameter(name, value, temporal)); }
    @Override public StoredProcedureQuery setParameter(Parameter<Calendar> parameter, Calendar value, TemporalType temporal) {
        metadata.setParameter(parameter, value, temporal);
        configuration.add(q -> {
            if (parameter.getName() != null) q.setParameter(parameter.getName(), value, temporal);
            else q.setParameter(parameter.getPosition(), value, temporal);
        });
        execution = null;
        return this;
    }
    @Override public StoredProcedureQuery setParameter(Parameter<Date> parameter, Date value, TemporalType temporal) {
        metadata.setParameter(parameter, value, temporal);
        configuration.add(q -> {
            if (parameter.getName() != null) q.setParameter(parameter.getName(), value, temporal);
            else q.setParameter(parameter.getPosition(), value, temporal);
        });
        execution = null;
        return this;
    }

    @Override public Set<Parameter<?>> getParameters() { return metadata.getParameters(); }
    @Override public Parameter<?> getParameter(String name) { return metadata.getParameter(name); }
    @Override public <T> Parameter<T> getParameter(String name, Class<T> type) { return metadata.getParameter(name, type); }
    @Override public Parameter<?> getParameter(int position) { return metadata.getParameter(position); }
    @Override public <T> Parameter<T> getParameter(int position, Class<T> type) { return metadata.getParameter(position, type); }
    @Override public boolean isBound(Parameter<?> parameter) { return metadata.isBound(parameter); }
    @Override public <T> T getParameterValue(Parameter<T> parameter) { return metadata.getParameterValue(parameter); }
    @Override public Object getParameterValue(String name) { return metadata.getParameterValue(name); }
    @Override public Object getParameterValue(int position) { return metadata.getParameterValue(position); }
    @Override public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) return type.cast(this);
        throw new PersistenceException("Unsupported contextual stored-procedure query unwrap: " + type.getName());
    }
}
