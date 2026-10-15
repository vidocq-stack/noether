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
package io.vidocq.noether.jpa.core.query;

import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.query.jpql.Ast;
import io.vidocq.noether.jpa.core.query.jpql.Parser;
import io.vidocq.noether.jpa.core.session.NotYet;
import io.vidocq.noether.jpa.dialect.Dialect;
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.Parameter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import jakarta.persistence.TypedQuery;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A query of the Jakarta Persistence query language (§3.11, §4): parsed and checked against the entity model when it
 * is created — a mistake is an {@link IllegalArgumentException} then — translated to SQL when it runs, with the values
 * bound to its parameters. Not thread-safe, as the entity manager that creates it (§7.2).
 *
 * @param <X> the type of its results
 */
public final class JpqlQuery<X> extends AbstractQuery implements TypedQuery<X> {

    /** A parameter of the query: {@code :name}, or {@code ?position}, and the Java type it is compared with if known. */
    private record QueryParameter<T>(String name, Integer position, Class<T> type) implements Parameter<T> {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public Integer getPosition() {
            return position;
        }

        @Override
        public Class<T> getParameterType() {
            return type;
        }
    }

    private final String jpql;
    private final Ast.Statement statement;
    private final Class<X> resultClass;
    private final QueryRuntime runtime;
    private final boolean collectionFetch;
    private Integer lockTimeout;
    private final Map<Object, QueryParameter<?>> parameters = new LinkedHashMap<>();
    private final Map<Object, Object> values = new HashMap<>();
    private LockModeType lockMode = LockModeType.NONE;
    private Map<Parameter<?>, Object> criteriaParameters = Map.of();
    private List<jakarta.persistence.criteria.Selection<?>> tupleElements = List.of();

    /**
     * @param resultClass the type the results must have, {@code null} for an untyped {@code Query}
     * @throws IllegalArgumentException if the query is not valid, or its results are not of {@code resultClass}
     */
    public JpqlQuery(String jpql, Class<X> resultClass, FlushModeType flushMode, QueryRuntime runtime) {
        this(jpql, Parser.parse(jpql), resultClass, flushMode, runtime);
    }

    JpqlQuery(Ast.Statement statement, Class<X> resultClass, FlushModeType flushMode, QueryRuntime runtime,
            Set<jakarta.persistence.criteria.ParameterExpression<?>> declared,
            List<jakarta.persistence.criteria.Selection<?>> tupleElements) {
        this("Criteria", statement, resultClass, flushMode, runtime);
        Map<Parameter<?>, Object> identities = new java.util.IdentityHashMap<>();
        for (var parameter : declared) {
            Ast.Parameter syntax = (Ast.Parameter) CriteriaExpressions.ast(parameter);
            identities.put(parameter, key(syntax));
            parameters.put(key(syntax), declared(syntax, parameter.getParameterType()));
        }
        criteriaParameters = java.util.Collections.unmodifiableMap(identities);
        this.tupleElements = List.copyOf(tupleElements);
    }

    private JpqlQuery(String jpql, Ast.Statement statement, Class<X> resultClass, FlushModeType flushMode, QueryRuntime runtime) {
        super(flushMode, runtime::isOpen);
        this.jpql = jpql;
        this.runtime = runtime;
        this.resultClass = resultClass;
        this.statement = statement;
        Compiled compiled = Translator.translate(statement, runtime.mapping(), null, null, null);
        collectionFetch = compiled.collectionFetch();
        if (!(statement instanceof Ast.Query) && resultClass != null && resultClass != Object.class) {
            throw new IllegalArgumentException("An UPDATE or a DELETE has no results of type " + resultClass.getName() + ": " + jpql);
        }
        for (Compiled.Slot slot : compiled.slots()) {
            if (slot.literal()) {
                continue;
            }
            Object key = key(slot.parameter());
            Class<?> type = slot.entity() != null ? slot.entity().model().javaType()
                : slot.parameterType() != null ? slot.parameterType() : slot.type();
            Class<?> parameterType = type == null ? Object.class : boxed(type);
            parameters.putIfAbsent(key, declared(slot.parameter(), parameterType));
        }
        if (resultClass != null && resultClass != Object.class && resultClass != jakarta.persistence.Tuple.class
                && compiled.resultType() != null
                && !resultClass.isAssignableFrom(compiled.resultType())
                && !(jpql.equals("Criteria") && Number.class.isAssignableFrom(boxed(resultClass))
                    && Number.class.isAssignableFrom(boxed(compiled.resultType())))) {
            throw new IllegalArgumentException("The query " + jpql + " gives results of type " + compiled.resultType().getName()
                + ", not " + resultClass.getName() + " (§3.11.1)");
        }
    }

    /** The query as the application wrote it. */
    public String queryString() {
        return jpql;
    }

    /** The type the results were asked to have, or {@code null} for an untyped query. */
    public Class<X> resultClass() {
        return resultClass;
    }

    record CriteriaDefinition(Ast.Statement statement, Set<jakarta.persistence.criteria.ParameterExpression<?>> parameters,
            List<jakarta.persistence.criteria.Selection<?>> selections) {}

    CriteriaDefinition criteriaDefinition() {
        if (!jpql.equals("Criteria")) return null;
        Set<jakarta.persistence.criteria.ParameterExpression<?>> declared = new LinkedHashSet<>();
        for (Parameter<?> parameter : criteriaParameters.keySet())
            declared.add((jakarta.persistence.criteria.ParameterExpression<?>) parameter);
        return new CriteriaDefinition(statement, Set.copyOf(declared), tupleElements);
    }

    /** The Java type of the results of {@code jpql}, or {@code null} if it cannot be told (it is not a valid select). */
    static Class<?> resultType(String jpql, io.vidocq.noether.jpa.core.mapping.MappedUnit unit) {
        try {
            return Parser.parse(jpql) instanceof Ast.Query query ? Translator.translate(query, unit, null, null, null).resultType() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> QueryParameter<T> declared(Ast.Parameter parameter, Class<T> type) {
        return new QueryParameter<>(parameter.name(), parameter.name() == null ? parameter.position() : null, type);
    }

    private static Object key(Ast.Parameter parameter) {
        return parameter.name() != null ? parameter.name() : (Object) parameter.position();
    }

    // ---- results (§3.11) ----------------------------------------------------------------------------------

    @Override
    public List<X> getResultList() {
        checkOpen();
        return run(getMaxResults());
    }

    @Override
    public Stream<X> getResultStream() {
        checkOpen();
        return getResultList().stream();
    }

    @Override
    public X getSingleResult() {
        checkOpen();
        List<X> results = run(Math.min(getMaxResults(), 2));
        if (collectionFetch()) results = uniqueReferences(results);
        if (results.isEmpty()) {
            throw new NoResultException("The query found no result: " + jpql);
        }
        if (results.size() > 1) {
            throw new NonUniqueResultException("The query found several results: " + jpql);
        }
        return results.getFirst();
    }

    @Override
    public X getSingleResultOrNull() {
        checkOpen();
        List<X> results = run(Math.min(getMaxResults(), 2));
        if (collectionFetch()) results = uniqueReferences(results);
        if (results.size() > 1) {
            throw new NonUniqueResultException("The query found several results: " + jpql);
        }
        return results.isEmpty() ? null : results.getFirst();
    }

    /** §4.10: runs a bulk update or delete in the transaction, and returns the rows it changed. */
    @Override
    public int executeUpdate() {
        checkOpen();
        if (statement instanceof Ast.Query) {
            throw new IllegalStateException("executeUpdate() runs an UPDATE or a DELETE, not a SELECT (§3.11): " + jpql);
        }
        checkBound();
        Compiled compiled = Translator.translate(statement, runtime.mapping(), p -> values.get(key(p)), null, null);
        return runtime.write(getFlushMode(), connection -> {
            Dialect dialect = runtime.dialect(connection);
            if (!compiled.mutations().isEmpty()) {
                return executeMutations(compiled, connection, dialect);
            }
            Dialect.Rendered rendered = dialect.renderQuery(compiled.sql());
            try (PreparedStatement update = connection.prepareStatement(rendered.sql())) {
                for (int i = 0; i < rendered.parameters().size(); i++) {
                    bind(dialect, update, i + 1, (Compiled.Slot) rendered.parameters().get(i).slot());
                }
                if (getTimeout() != null) {
                    update.setQueryTimeout(Math.max(1, (getTimeout() + 999) / 1000));
                }
                return update.executeUpdate();
            } catch (SQLException e) {
                throw new PersistenceException("The bulk statement failed: " + e.getMessage() + " — " + jpql, e);
            }
        });
    }

    private int executeMutations(Compiled compiled, Connection connection, Dialect dialect) {
        List<Object> captured = execute(compiled, connection);
        for (Compiled.Mutation mutation : compiled.mutations()) {
            try (PreparedStatement statement = connection.prepareStatement(dialect.render(mutation.sql()))) {
                if (getTimeout() != null) {
                    statement.setQueryTimeout(Math.max(1, (getTimeout() + 999) / 1000));
                }
                for (Object item : captured) {
                    Object[] row = compiled.items().size() == 1 ? new Object[] {item} : (Object[]) item;
                    if (mutation.required().stream().anyMatch(i -> row[i] == null)) continue;
                    for (int i = 0; i < mutation.values().size(); i++) {
                        mutation.binders().get(i).bind(dialect, statement, i + 1, row[mutation.values().get(i)]);
                    }
                    statement.executeUpdate();
                }
            } catch (SQLException e) {
                throw new PersistenceException("The joined bulk statement failed: " + e.getMessage() + " — " + jpql, e);
            }
        }
        return captured.size();
    }

    /**
     * What each select item initializes on the instances it returns: the fetch joins from its identification variable
     * (§4.4.5.3, a Criteria fetch included), chained through the variables of fetch joins, and the attributes of a fetch
     * or load graph hint (§3.7.4) for an entity of the {@code FROM} clause; {@code null} for nothing.
     */
    private FetchPlan[] fetchPlans() {
        if (!(statement instanceof Ast.Select select)) {
            return new FetchPlan[0];
        }
        Map<String, FetchPlan> variables = new HashMap<>();
        for (Ast.Range range : select.from()) {
            if (range.entity() == null) continue;
            variables.put(key(range.variable()), new FetchPlan());
            for (Ast.Join join : range.joins()) {
                List<String> segments = join.path().segments();
                FetchPlan plan = join.fetch() && segments.size() > 1 ? variables.get(key(segments.getFirst())) : null;
                if (plan == null) continue; // not a fetch join, or from a variable that is not fetched
                for (String attribute : segments.subList(1, segments.size())) {
                    plan = plan.attribute(attribute);
                }
                if (join.variable() != null) variables.put(key(join.variable()), plan);
            }
        }
        Object graph = getHints().containsKey(FETCH_GRAPH) ? getHints().get(FETCH_GRAPH) : getHints().get(LOAD_GRAPH);
        FetchPlan graphPlan = graph == null ? null : runtime.plan(graph);
        FetchPlan[] plans = new FetchPlan[select.items().size()];
        for (int i = 0; i < plans.length; i++) {
            if (!(select.items().get(i).expression() instanceof Ast.Path path) || path.segments().size() != 1
                    || path.treatEntity() != null) continue;
            String variable = key(path.segments().getFirst());
            boolean root = select.from().stream().anyMatch(r -> r.entity() != null && key(r.variable()).equals(variable));
            FetchPlan plan = root ? variables.get(variable) : null;
            if (plan == null) continue;
            if (graphPlan != null) {
                plan = merged(plan, graphPlan);
            }
            plans[i] = plan.isEmpty() ? null : plan;
        }
        return plans;
    }

    private static final String FETCH_GRAPH = "jakarta.persistence.fetchgraph";
    private static final String LOAD_GRAPH = "jakarta.persistence.loadgraph";

    private static String key(String variable) {
        return variable.toLowerCase(java.util.Locale.ROOT);
    }

    private static FetchPlan merged(FetchPlan first, FetchPlan second) {
        FetchPlan plan = new FetchPlan();
        plan.merge(first);
        plan.merge(second);
        return plan;
    }

    /** Initializes on a result what its select items fetch; a row of several items, item by item. */
    private void fetch(Object resolved, FetchPlan[] plans, Connection connection) {
        if (plans.length == 1) {
            if (plans[0] != null && resolved != null) runtime.fetch(resolved, plans[0], connection);
        } else if (resolved instanceof Object[] array && array.length == plans.length) {
            for (int i = 0; i < array.length; i++) {
                if (plans[i] != null && array[i] != null) runtime.fetch(array[i], plans[i], connection);
            }
        }
    }

    private void checkBound() {
        for (Map.Entry<Object, QueryParameter<?>> parameter : parameters.entrySet()) {
            if (!values.containsKey(parameter.getKey())) {
                throw new IllegalStateException("The parameter " + describe(parameter.getValue()) + " of the query is not bound: " + jpql);
            }
        }
    }

    /** Runs the query for at most {@code maxResults} results. */
    @SuppressWarnings("unchecked")
    private List<X> run(int maxResults) {
        if (!(statement instanceof Ast.Query)) {
            throw new IllegalStateException("An UPDATE or a DELETE has no results: run it with executeUpdate() (§3.11): " + jpql);
        }
        checkBound();
        boolean collectionFetch = collectionFetch();
        boolean paginateCollection = collectionFetch && (getFirstResult() > 0 || getMaxResults() < Integer.MAX_VALUE);
        if (paginateCollection && lockMode != LockModeType.NONE) throw Translator.collectionPagination();
        if (lockMode != LockModeType.NONE && !runtime.inTransaction()) {
            throw new jakarta.persistence.TransactionRequiredException("A query with the lock mode " + lockMode
                + " runs in a transaction (§3.11)");
        }
        if (maxResults == 0) {
            return new ArrayList<>();
        }
        Compiled compiled = paginateCollection
            ? Translator.paginateCollection(statement, runtime.mapping(), p -> values.get(key(p)),
                getFirstResult() > 0 ? getFirstResult() : null, getMaxResults() < Integer.MAX_VALUE ? getMaxResults() : null)
            : Translator.translate(statement, runtime.mapping(), p -> values.get(key(p)),
            getFirstResult() > 0 ? getFirstResult() : null, !collectionFetch && maxResults < Integer.MAX_VALUE ? maxResults : null,
            lockMode == LockModeType.NONE); // a locking query reads its entities as before: its rows are the locked ones
        return (List<X>) runtime.read(getFlushMode(), connection -> execute(compiled, connection));
    }

    /** A collection fetch anywhere in the query must never receive a SQL row limit. */
    private boolean collectionFetch() {
        return collectionFetch;
    }

    private static <T> List<T> uniqueReferences(List<T> rows) {
        Set<T> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<T> unique = new ArrayList<>();
        for (T row : rows) if (seen.add(row)) unique.add(row);
        return unique;
    }

    private List<Object> execute(Compiled compiled, Connection connection) {
        Dialect dialect = runtime.dialect(connection);
        boolean pessimistic = io.vidocq.noether.jpa.core.flush.Locks.pessimistic(lockMode);
        io.vidocq.noether.jpa.dialect.sql.Statement sql = compiled.sql();
        boolean followOn = pessimistic && sql instanceof io.vidocq.noether.jpa.dialect.sql.Query query
            && query.from().stream().anyMatch(f -> f.table().query() != null || f.joins().stream().anyMatch(j -> j.table().query() != null));
        if (followOn && compiled.items().stream().noneMatch(JpqlQuery::entityItem)) {
            throw new PersistenceException("A pessimistic TABLE_PER_CLASS query must select an entity for follow-on row locking");
        }
        if (pessimistic && !followOn && sql instanceof io.vidocq.noether.jpa.dialect.sql.Query query) { // §3.5.6: the rows it reads
            sql = query.locked(io.vidocq.noether.jpa.core.flush.Locks.rowLock(lockMode), lockTimeout != null && lockTimeout == 0);
        }
        Dialect.Rendered rendered = dialect.renderQuery(sql);
        List<Object> rows = new ArrayList<>();
        List<Fetched[]> fetchedRows = new ArrayList<>();
        List<io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedElement> collectionRows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(rendered.sql())) {
            if (pessimistic) {
                io.vidocq.noether.jpa.core.flush.Locks.timeout(dialect, lockTimeout, connection);
            }
            List<io.vidocq.noether.jpa.dialect.sql.Expression.Parameter> parameters = rendered.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                bind(dialect, statement, i + 1, (Compiled.Slot) parameters.get(i).slot());
            }
            if (getTimeout() != null) {
                statement.setQueryTimeout(Math.max(1, (getTimeout() + 999) / 1000));
            }
            try (ResultSet results = statement.executeQuery()) {
                List<Compiled.Item> items = compiled.items();
                while (results.next()) {
                    int[] column = {1};
                    Object[] row = new Object[items.size()];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = read(items.get(i), results, column);
                    }
                    rows.add(row.length == 1 ? row[0] : row);
                    if (!compiled.hydrations().isEmpty()) {
                        fetchedRows.add(fetched(compiled.hydrations(), results, column));
                    }
                    if (compiled.collection() != null) {
                        var collection = compiled.collection();
                        Object[] payload = new Object[collection.readers().size()];
                        boolean[] nulls = new boolean[payload.length];
                        for (int c = 0; c < payload.length; c++) {
                            payload[c] = collection.readers().get(c).read(results, column[0]++);
                            nulls[c] = results.wasNull();
                            if (nulls[c]) payload[c] = null;
                        }
                        Object element;
                        boolean present;
                        int indexFrom;
                        if (collection.elements() != null) {
                            int width = collection.elements().columns().size();
                            present = !nulls[0];
                            element = present ? collection.elements().element(java.util.Arrays.copyOfRange(payload, 1, width + 1),
                                java.util.Arrays.copyOfRange(nulls, 1, width + 1)) : null;
                            indexFrom = width + 1;
                        } else {
                            Fetched target = fetchedRows.getLast()[1];
                            present = target != null;
                            element = present ? target.id() : null;
                            indexFrom = 0;
                        }
                        collectionRows.add(new io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedElement(
                            ((EntityKey) row[0]).id(), element,
                            java.util.Arrays.copyOfRange(payload, indexFrom, payload.length), present));
                    }
                }
            }
        } catch (SQLException e) {
            if (pessimistic) {
                throw io.vidocq.noether.jpa.core.flush.Locks.failure(dialect, e, null);
            }
            throw new PersistenceException("The query failed: " + e.getMessage() + " — " + jpql, e);
        }
        // entities found, and results constructed, once the rows are read: the loader runs statements of its own
        List<Object> resultList = new ArrayList<>(rows.size());
        FetchPlan[] plans = fetchPlans();
        if (compiled.collection() != null) {
            List<io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedEntity> entities = new ArrayList<>();
            for (Fetched[] row : fetchedRows) {
                for (Fetched fetched : row) {
                    if (fetched != null) entities.add(new io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedEntity(
                        fetched.type(), fetched.id(), fetched.values(), fetched.nulls()));
                }
            }
            runtime.hydrateCollection(entities, compiled.collection().owner(), compiled.collection().attribute(), collectionRows, connection);
        }
        for (int r = 0; r < rows.size(); r++) {
            if (compiled.collection() == null && !fetchedRows.isEmpty()) { // the fetched entities first
                for (Fetched fetched : fetchedRows.get(r)) {
                    if (fetched != null) runtime.hydrate(fetched.type(), fetched.id(), fetched.values(), fetched.nulls(), connection);
                }
            }
            Object row = rows.get(r);
            Object resolved = resolve(row, connection, followOn);
            fetch(resolved, plans, connection);
            if (!tupleElements.isEmpty()) {
                if (resolved instanceof Object[] array && array.length == tupleElements.size()) {
                    for (int i = 0; i < array.length; i++) array[i] = convert(array[i], boxed(tupleElements.get(i).getJavaType()));
                } else if (tupleElements.size() == 1) {
                    resolved = convert(resolved, boxed(tupleElements.getFirst().getJavaType()));
                }
            }
            resultList.add(resultClass == jakarta.persistence.Tuple.class
                ? new CriteriaTuple(resolved instanceof Object[] array ? array : new Object[]{resolved}, tupleElements) : resolved);
        }
        if (lockMode != LockModeType.NONE) {
            resultList.forEach(this::lockEntities);
        }
        if (compiled.collection() != null && statement instanceof Ast.Select select && select.distinct()) {
            return uniqueReferences(resultList);
        }
        return resultList;
    }

    /** §3.5: the entities of a result hold the lock mode of the query. */
    private void lockEntities(Object result) {
        if (result instanceof Object[] row) {
            for (Object value : row) {
                lockEntities(value);
            }
        } else if (result != null && runtime.mapping().entity(result.getClass()).isPresent()) {
            runtime.locked(result, lockMode);
        } else if (result instanceof Map.Entry<?, ?> entry) {
            lockEntities(entry.getKey());
            lockEntities(entry.getValue());
        }
    }

    /** The columns of an entity a fetch join read with its owner, indexed like its statement columns. */
    private record Fetched(MappedEntity type, Object id, Object[] values, boolean[] nulls) {
    }

    /** Reads, after the select items, the columns of each fetched entity; {@code null} for one the row does not hold. */
    private static Fetched[] fetched(List<Compiled.Hydration> hydrations, ResultSet results, int[] column) throws SQLException {
        Fetched[] fetched = new Fetched[hydrations.size()];
        for (int h = 0; h < fetched.length; h++) {
            Compiled.Hydration hydration = hydrations.get(h);
            List<io.vidocq.noether.jpa.core.mapping.EntityStatements.Column> columns = hydration.type().statements().columns();
            Object[] values = new Object[columns.size()];
            boolean[] nulls = new boolean[values.length];
            java.util.Arrays.fill(nulls, true);
            for (int c : hydration.columns()) {
                values[c] = columns.get(c).binder().read(results, column[0]++);
                nulls[c] = results.wasNull();
                if (nulls[c] && columns.get(c).foreignKey() != null) {
                    values[c] = null;
                }
            }
            Object[] key = new Object[hydration.key().length];
            boolean empty = true;
            for (int k = 0; k < key.length; k++) {
                key[k] = values[hydration.key()[k]];
                empty &= nulls[hydration.key()[k]];
            }
            fetched[h] = empty ? null : new Fetched(hydration.type(), hydration.type().statements().key(key), values, nulls);
        }
        return fetched;
    }

    /** The identity of an entity read in a row, found once the rows are read. */
    private record EntityKey(MappedEntity type, Object id) {
    }

    /** A constructor result whose arguments are read, built once the entities among them are found. */
    private record Built(Class<?> type, Object[] arguments) {
    }

    /** An {@code ENTRY()} result whose entity keys or values are resolved through the persistence context. */
    private record EntryBuilt(Object key, Object value) {
    }

    private record EmbeddedBuilt(Compiled.EmbeddedItem item, Object[] components) {
    }

    private static boolean entityItem(Compiled.Item item) {
        return switch (item) {
            case Compiled.EntityItem _ -> true;
            case Compiled.ConstructorItem constructor -> constructor.arguments().stream().anyMatch(JpqlQuery::entityItem);
            case Compiled.MapEntryItem entry -> entityItem(entry.key()) || entityItem(entry.value());
            default -> false;
        };
    }

    private Object resolve(Object read, Connection connection, boolean followOn) {
        return switch (read) {
            case EntityKey(MappedEntity type, Object id) -> {
                Object entity = runtime.find(type, id, connection);
                if (followOn && entity != null) runtime.lockResult(entity, lockMode, lockTimeout, connection);
                yield entity;
            }
            case Built built -> {
                Object[] arguments = new Object[built.arguments().length];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = resolve(built.arguments()[i], connection, followOn);
                }
                yield construct(built.type(), arguments);
            }
            case EntryBuilt entry -> new java.util.AbstractMap.SimpleImmutableEntry<>(resolve(entry.key(), connection, followOn),
                resolve(entry.value(), connection, followOn));
            case EmbeddedBuilt embedded -> embedded(embedded, connection);
            case Object[] row -> {
                Object[] resolved = new Object[row.length];
                for (int i = 0; i < row.length; i++) {
                    resolved[i] = resolve(row[i], connection, followOn);
                }
                yield resolved;
            }
            case null, default -> read;
        };
    }

    private static Object read(Compiled.Item item, ResultSet results, int[] column) throws SQLException {
        return switch (item) {
            case Compiled.EntityItem entity -> {
                Object[] key = new Object[entity.width()];
                boolean empty = true;
                for (int k = 0; k < key.length; k++) {
                    key[k] = entity.keyBinders().get(k).read(results, column[0]++);
                    empty &= results.wasNull();
                }
                yield empty ? null : new EntityKey(entity.type(), entity.type().statements().key(key));
            }
            case Compiled.ValueItem value -> {
                Object read = value.binder() != null ? value.binder().read(results, column[0]) : results.getObject(column[0]);
                column[0]++;
                yield results.wasNull() ? null : value.binder() != null ? read : convert(read, value.type());
            }
            case Compiled.ConstructorItem constructor -> {
                Object[] arguments = new Object[constructor.arguments().size()];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = read(constructor.arguments().get(i), results, column);
                }
                yield new Built(constructor.type(), arguments);
            }
            case Compiled.MapEntryItem entry ->
                new EntryBuilt(read(entry.key(), results, column), read(entry.value(), results, column));
            case Compiled.EmbeddedItem embedded -> {
                Object[] components = new Object[embedded.parts().size()];
                for (int i = 0; i < components.length; i++) {
                    Compiled.EmbeddedPart part = embedded.parts().get(i);
                    if (part instanceof Compiled.ScalarEmbedded scalar) {
                        Object value = scalar.binder() != null ? scalar.binder().read(results, column[0]) : results.getObject(column[0]);
                        column[0]++;
                        components[i] = results.wasNull() ? null : scalar.binder() != null ? value : convert(value, scalar.type());
                    } else {
                        components[i] = read(((Compiled.NestedEmbedded) part).item(), results, column);
                    }
                }
                yield new EmbeddedBuilt(embedded, components);
            }
        };
    }

    private Object embedded(EmbeddedBuilt built, Connection connection) {
        Object[] components = new Object[built.components().length];
        boolean empty = true;
        for (int i = 0; i < components.length; i++) {
            components[i] = resolve(built.components()[i], connection, false);
            empty &= components[i] == null;
        }
        if (empty) {
            return null;
        }
        if (built.item().model().isRecord()) {
            return built.item().access().construct(components);
        }
        Object value = built.item().access().instantiate();
        built.item().access().write(value, components);
        return value;
    }

    /**
     * {@code NEW type(arguments)} (§4.8.2): a public constructor of the application's class whose parameters take the
     * arguments. The class is not an entity — no access is generated for it — so the constructor is called reflectively:
     * on the module path, its package must be exported (or opened) to this module, as for any provider.
     */
    private Object construct(Class<?> type, Object[] arguments) {
        for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length != arguments.length) {
                continue;
            }
            Object[] converted = new Object[arguments.length];
            boolean fits = true;
            for (int i = 0; i < arguments.length && fits; i++) {
                Class<?> parameter = boxed(parameters[i]);
                converted[i] = convert(arguments[i], parameter);
                fits = converted[i] == null ? !parameters[i].isPrimitive() : parameter.isInstance(converted[i]);
            }
            if (fits) {
                try {
                    return constructor.newInstance(converted);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw new PersistenceException("The constructor of " + type.getName() + " failed: " + e.getCause(), e.getCause());
                } catch (ReflectiveOperationException e) {
                    throw new PersistenceException("The constructor of " + type.getName() + " cannot be called: " + e.getMessage(), e);
                }
            }
        }
        throw new PersistenceException("No public constructor of " + type.getName() + " takes the arguments of the query: " + jpql);
    }

    /** A date or time as the {@code java.sql} or {@code java.time} type expected, or {@code null} if neither is one. */
    private static Object temporal(Object value, Class<?> type) {
        if (value instanceof Calendar calendar) {
            return type == Calendar.class ? value : temporal(new java.sql.Timestamp(calendar.getTimeInMillis()), type);
        }
        if (value.getClass() == Date.class) { // a java.util.Date: its time, as a timestamp
            return type == Date.class ? value : temporal(new java.sql.Timestamp(((Date) value).getTime()), type);
        }
        return switch (value) {
            case java.sql.Date date when type == java.time.LocalDate.class -> date.toLocalDate();
            case java.sql.Time time when type == java.time.LocalTime.class -> time.toLocalTime();
            case java.sql.Timestamp timestamp when type == java.time.LocalDateTime.class -> timestamp.toLocalDateTime();
            case java.sql.Timestamp timestamp when type == java.time.LocalDate.class -> timestamp.toLocalDateTime().toLocalDate();
            case java.sql.Timestamp timestamp when type == java.time.LocalTime.class -> timestamp.toLocalDateTime().toLocalTime();
            case java.sql.Timestamp timestamp when type == java.sql.Date.class -> new java.sql.Date(timestamp.getTime());
            case java.sql.Timestamp timestamp when type == java.sql.Time.class -> new java.sql.Time(timestamp.getTime());
            case java.sql.Date date when type == java.sql.Timestamp.class -> new java.sql.Timestamp(date.getTime());
            case java.sql.Date date when type == java.time.LocalDateTime.class -> date.toLocalDate().atStartOfDay();
            case Date date when type == Date.class -> date;
            case Date date when type == Calendar.class -> calendar(date);
            case java.time.LocalDate date when type == java.sql.Date.class -> java.sql.Date.valueOf(date);
            case java.time.LocalTime time when type == java.sql.Time.class -> java.sql.Time.valueOf(time);
            case java.time.LocalDateTime dateTime when type == java.sql.Timestamp.class -> java.sql.Timestamp.valueOf(dateTime);
            case java.time.OffsetDateTime dateTime when type == java.sql.Timestamp.class -> java.sql.Timestamp.from(dateTime.toInstant());
            default -> null;
        };
    }

    /** A value the driver read, as the type the specification gives it (§4.8.5). */
    private static Object convert(Object value, Class<?> type) {
        if (type == null || value == null || type.isInstance(value)) {
            return value;
        }
        Object temporal = temporal(value, type);
        if (temporal != null) {
            return temporal;
        }
        if (!(value instanceof Number number)) {
            return value;
        }
        if (type == Long.class) {
            return number.longValue();
        }
        if (type == Integer.class) {
            return number.intValue();
        }
        if (type == Double.class) {
            return number.doubleValue();
        }
        if (type == Float.class) {
            return number.floatValue();
        }
        if (type == Short.class) {
            return number.shortValue();
        }
        if (type == BigDecimal.class) {
            return new BigDecimal(number.toString());
        }
        if (type == BigInteger.class) {
            return new BigDecimal(number.toString()).toBigInteger();
        }
        return value;
    }

    private void bind(Dialect dialect, PreparedStatement statement, int index, Compiled.Slot slot) throws SQLException {
        Object value = slot.literal() ? slot.constant() : values.get(key(slot.parameter()));
        if (slot.element() >= 0) {
            value = new ArrayList<>((Collection<?>) value).get(slot.element());
        }
        if (slot.entity() != null) {
            Object id = value == null ? null : slot.entity().id(value);
            value = id == null ? null : slot.entity().statements().keyValues(id)[slot.part()];
        } else if (slot.type() != null) {
            value = convert(value, boxed(slot.type()));
        }
        if (slot.binder() != null) {
            slot.binder().bind(dialect, statement, index, value);
        } else if (value == null) {
            statement.setNull(index, Types.NULL);
        } else {
            statement.setObject(index, value instanceof Enum<?> constant ? constant.name() : value);
        }
    }

    // ---- parameters (§3.11.4, §4.6.4) ---------------------------------------------------------------------

    private QueryParameter<?> parameter(Object key) {
        QueryParameter<?> parameter = parameters.get(key);
        if (parameter == null) {
            throw new IllegalArgumentException("The query has no parameter " + (key instanceof String ? ":" : "?") + key + ": " + jpql);
        }
        return parameter;
    }

    private static String describe(QueryParameter<?> parameter) {
        return parameter.name() != null ? ":" + parameter.name() : "?" + parameter.position();
    }

    /** Binds {@code value}, which must be of the type of the parameter, or a collection of it for {@code IN}. */
    private void set(Object key, Object value) {
        QueryParameter<?> parameter = parameter(key);
        Class<?> type = boxed(parameter.type());
        if (value != null && type != Object.class && !(value instanceof Collection<?>) && !type.isInstance(value)
                && !(value instanceof Number && Number.class.isAssignableFrom(type)) && !(temporal(value) && temporal(type))) {
            throw new IllegalArgumentException("The parameter " + describe(parameter) + " is a " + type.getName() + ", not a "
                + value.getClass().getName() + " (§3.11.4)");
        }
        values.put(key, value);
    }

    @Override
    public TypedQuery<X> setParameter(String name, Object value) {
        checkOpen();
        set(name, value);
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(int position, Object value) {
        checkOpen();
        set(position, value);
        return this;
    }

    @Override
    public <T> TypedQuery<X> setParameter(Parameter<T> param, T value) {
        checkOpen();
        if (param == null) {
            throw new IllegalArgumentException("The parameter cannot be null");
        }
        set(parameterKey(param), value);
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
        checkOpen();
        set(parameterKey(param), temporal(value, temporalType));
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(Parameter<Date> param, Date value, TemporalType temporalType) {
        checkOpen();
        set(parameterKey(param), temporal(value, temporalType));
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(String name, Calendar value, TemporalType temporalType) {
        checkOpen();
        set(name, temporal(value, temporalType));
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(String name, Date value, TemporalType temporalType) {
        checkOpen();
        set(name, temporal(value, temporalType));
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(int position, Calendar value, TemporalType temporalType) {
        checkOpen();
        set(position, temporal(value, temporalType));
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(int position, Date value, TemporalType temporalType) {
        checkOpen();
        set(position, temporal(value, temporalType));
        return this;
    }

    /** Whether a value or a type is a date or a time: legacy ({@code java.util}, {@code java.sql}) or {@code java.time}. */
    private static boolean temporal(Object valueOrType) {
        Class<?> type = valueOrType instanceof Class<?> c ? c : valueOrType.getClass();
        return Date.class.isAssignableFrom(type) || Calendar.class.isAssignableFrom(type)
            || java.time.temporal.Temporal.class.isAssignableFrom(type) && type.getPackageName().equals("java.time");
    }

    private static Calendar calendar(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        return calendar;
    }

    @Override
    public Set<Parameter<?>> getParameters() {
        checkOpen();
        if (!criteriaParameters.isEmpty()) return new LinkedHashSet<>(criteriaParameters.keySet());
        return Collections.unmodifiableSet(new LinkedHashSet<>(parameters.values()));
    }

    @Override
    public Parameter<?> getParameter(String name) {
        checkOpen();
        QueryParameter<?> declared = parameter(name);
        return criteriaParameters.keySet().stream().filter(p -> name.equals(p.getName())).findFirst().orElse(declared);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(String name, Class<T> type) {
        checkOpen();
        QueryParameter<?> parameter = parameter(name);
        if (!boxed(type).isAssignableFrom(boxed(parameter.type()))) {
            throw new IllegalArgumentException("The parameter :" + name + " is a " + parameter.type().getName());
        }
        return (Parameter<T>) getParameter(name);
    }

    @Override
    public Parameter<?> getParameter(int position) {
        checkOpen();
        if (!criteriaParameters.isEmpty()) throw new IllegalArgumentException("Criteria parameters have no positional index");
        return parameter(position);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(int position, Class<T> type) {
        checkOpen();
        if (!criteriaParameters.isEmpty()) throw new IllegalArgumentException("Criteria parameters have no positional index");
        QueryParameter<?> parameter = parameter(position);
        if (!boxed(type).isAssignableFrom(boxed(parameter.type()))) {
            throw new IllegalArgumentException("The parameter ?" + position + " is a " + parameter.type().getName());
        }
        return (Parameter<T>) parameter;
    }

    @Override
    public boolean isBound(Parameter<?> param) {
        checkOpen();
        return param != null && values.containsKey(parameterKey(param));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getParameterValue(Parameter<T> param) {
        checkOpen();
        return (T) value(parameterKey(param));
    }

    @Override
    public Object getParameterValue(String name) {
        checkOpen();
        return value(name);
    }

    private Object parameterKey(Parameter<?> parameter) {
        if (!criteriaParameters.isEmpty()) {
            Object identity = criteriaParameters.get(parameter);
            if (identity == null) throw new IllegalArgumentException("Parameter does not belong to this criteria query");
            return identity;
        }
        return parameter.getName() != null ? parameter.getName() : parameter.getPosition();
    }

    @Override
    public Object getParameterValue(int position) {
        checkOpen();
        return value(position);
    }

    private Object value(Object key) {
        QueryParameter<?> parameter = parameter(key);
        if (!values.containsKey(key)) {
            throw new IllegalStateException("The parameter " + describe(parameter) + " is not bound");
        }
        return values.get(key);
    }

    // ---- settings -----------------------------------------------------------------------------------------

    @Override
    public TypedQuery<X> setMaxResults(int maxResult) {
        checkOpen();
        maxResults(maxResult);
        return this;
    }

    @Override
    public TypedQuery<X> setFirstResult(int startPosition) {
        checkOpen();
        firstResult(startPosition);
        return this;
    }

    /** The standard hints (§3.11.? 3.2: §3.12): query and lock timeouts, in milliseconds; others are kept as given. */
    @Override
    public TypedQuery<X> setHint(String hintName, Object value) {
        checkOpen();
        switch (hintName) {
            case "jakarta.persistence.query.timeout" -> timeout(milliseconds(hintName, value));
            case "jakarta.persistence.lock.timeout" -> lockTimeout = milliseconds(hintName, value);
            case "jakarta.persistence.fetchgraph", "jakarta.persistence.loadgraph" ->
                runtime.validateGraph(value, resultClass != null && runtime.mapping().entity(resultClass).isPresent() ? resultClass : null);
            default -> {
            }
        }
        hint(hintName, value);
        return this;
    }

    private static Integer milliseconds(String hint, Object value) {
        try {
            return value == null ? null : value instanceof Number number ? number.intValue() : Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The hint " + hint + " is a number of milliseconds, not " + value, e);
        }
    }

    @Override
    public TypedQuery<X> setFlushMode(FlushModeType flushMode) {
        checkOpen();
        flushMode(flushMode);
        return this;
    }

    /** §3.11: for a select only — an UPDATE or a DELETE takes no lock mode (IllegalStateException). */
    @Override
    public TypedQuery<X> setLockMode(LockModeType lockMode) {
        checkOpen();
        if (!(statement instanceof Ast.Select)) {
            throw new IllegalStateException("A lock mode is for a SELECT, not an UPDATE or a DELETE: " + jpql);
        }
        this.lockMode = lockMode == null ? LockModeType.NONE : lockMode;
        return this;
    }

    @Override
    public LockModeType getLockMode() {
        checkOpen();
        if (!(statement instanceof Ast.Select)) {
            throw new IllegalStateException("A lock mode is for a SELECT, not an UPDATE or a DELETE: " + jpql);
        }
        return lockMode;
    }

    @Override
    public TypedQuery<X> setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode) {
        checkOpen();
        cacheRetrieveMode(cacheRetrieveMode);
        return this;
    }

    @Override
    public TypedQuery<X> setCacheStoreMode(CacheStoreMode cacheStoreMode) {
        checkOpen();
        cacheStoreMode(cacheStoreMode);
        return this;
    }

    @Override
    public TypedQuery<X> setTimeout(Integer timeout) {
        checkOpen();
        timeout(timeout);
        return this;
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }
}
