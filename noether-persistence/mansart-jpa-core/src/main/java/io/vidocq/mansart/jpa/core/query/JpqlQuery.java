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
package io.vidocq.mansart.jpa.core.query;

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.query.jpql.Ast;
import io.vidocq.mansart.jpa.core.query.jpql.Parser;
import io.vidocq.mansart.jpa.core.session.NotYet;
import io.vidocq.mansart.jpa.dialect.Dialect;
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
    private final Ast.Select select;
    private final QueryRuntime runtime;
    private final Map<Object, QueryParameter<?>> parameters = new LinkedHashMap<>();
    private final Map<Object, Object> values = new HashMap<>();
    private LockModeType lockMode = LockModeType.NONE;

    /**
     * @param resultClass the type the results must have, {@code null} for an untyped {@code Query}
     * @throws IllegalArgumentException if the query is not valid, or its results are not of {@code resultClass}
     */
    public JpqlQuery(String jpql, Class<X> resultClass, FlushModeType flushMode, QueryRuntime runtime) {
        super(flushMode);
        this.jpql = jpql;
        this.runtime = runtime;
        if (!(Parser.parse(jpql) instanceof Ast.Select statement)) {
            throw NotYet.milestone("P7", "bulk updates and deletes");
        }
        this.select = statement;
        Compiled compiled = Translator.translate(select, runtime.mapping(), null, null, null);
        for (Compiled.Slot slot : compiled.slots()) {
            Object key = key(slot.parameter());
            Class<?> type = slot.entity() != null ? slot.entity().model().javaType() : slot.type();
            Class<?> parameterType = type == null ? Object.class : boxed(type);
            parameters.putIfAbsent(key, declared(slot.parameter(), parameterType));
        }
        if (resultClass != null && resultClass != Object.class && compiled.resultType() != null
                && !resultClass.isAssignableFrom(compiled.resultType())) {
            throw new IllegalArgumentException("The query " + jpql + " gives results of type " + compiled.resultType().getName()
                + ", not " + resultClass.getName() + " (§3.11.1)");
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
        return run(getMaxResults());
    }

    @Override
    public Stream<X> getResultStream() {
        return getResultList().stream();
    }

    @Override
    public X getSingleResult() {
        List<X> results = run(Math.min(getMaxResults(), 2));
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
        List<X> results = run(Math.min(getMaxResults(), 2));
        if (results.size() > 1) {
            throw new NonUniqueResultException("The query found several results: " + jpql);
        }
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public int executeUpdate() {
        throw new IllegalStateException("executeUpdate() runs an UPDATE or a DELETE, not a SELECT (§3.11): " + jpql);
    }

    /** Runs the query for at most {@code maxResults} results. */
    @SuppressWarnings("unchecked")
    private List<X> run(int maxResults) {
        for (Map.Entry<Object, QueryParameter<?>> parameter : parameters.entrySet()) {
            if (!values.containsKey(parameter.getKey())) {
                throw new IllegalStateException("The parameter " + describe(parameter.getValue()) + " of the query is not bound: " + jpql);
            }
        }
        if (lockMode != LockModeType.NONE) {
            throw NotYet.milestone("P7", "lock modes on queries");
        }
        if (maxResults == 0) {
            return new ArrayList<>();
        }
        Compiled compiled = Translator.translate(select, runtime.mapping(), p -> values.get(key(p)),
            getFirstResult() > 0 ? getFirstResult() : null, maxResults < Integer.MAX_VALUE ? maxResults : null);
        return (List<X>) runtime.read(getFlushMode(), connection -> execute(compiled, connection));
    }

    private List<Object> execute(Compiled compiled, Connection connection) {
        Dialect dialect = runtime.dialect(connection);
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(dialect.render(compiled.sql()))) {
            List<Compiled.Slot> slots = compiled.slots();
            for (int i = 0; i < slots.size(); i++) {
                bind(dialect, statement, i + 1, slots.get(i));
            }
            if (getTimeout() != null) {
                statement.setQueryTimeout(Math.max(1, (getTimeout() + 999) / 1000));
            }
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(row(compiled, results));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("The query failed: " + e.getMessage() + " — " + jpql, e);
        }
        // entities resolved once the rows are read: the loader runs statements of its own
        List<Object> resultList = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (row[i] instanceof EntityKey(MappedEntity type, Object id)) {
                    row[i] = runtime.find(type, id, connection);
                }
            }
            resultList.add(row.length == 1 ? row[0] : row);
        }
        return resultList;
    }

    /** The identity of an entity read in a row, found once the rows are read. */
    private record EntityKey(MappedEntity type, Object id) {
    }

    private static Object[] row(Compiled compiled, ResultSet results) throws SQLException {
        List<Compiled.Item> items = compiled.items();
        Object[] row = new Object[items.size()];
        int column = 1;
        for (int i = 0; i < row.length; i++) {
            switch (items.get(i)) {
                case Compiled.EntityItem entity -> {
                    Object[] key = new Object[entity.width()];
                    boolean empty = true;
                    for (int k = 0; k < key.length; k++) {
                        key[k] = entity.keyBinders().get(k).read(results, column++);
                        empty &= results.wasNull();
                    }
                    row[i] = empty ? null : new EntityKey(entity.type(), entity.type().statements().key(key));
                }
                case Compiled.ValueItem value -> {
                    Object read = value.binder() != null ? value.binder().read(results, column) : results.getObject(column);
                    row[i] = results.wasNull() ? null : value.binder() != null ? read : convert(read, value.type());
                    column++;
                }
            }
        }
        return row;
    }

    /** A value the driver read, as the type the specification gives it (§4.8.5). */
    private static Object convert(Object value, Class<?> type) {
        if (type == null || value == null || type.isInstance(value) || !(value instanceof Number number)) {
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
        Object value = values.get(key(slot.parameter()));
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
        Class<?> type = parameter.type();
        if (value != null && type != Object.class && !(value instanceof Collection<?>) && !type.isInstance(value)
                && !(value instanceof Number && Number.class.isAssignableFrom(type))) {
            throw new IllegalArgumentException("The parameter " + describe(parameter) + " is a " + type.getName() + ", not a "
                + value.getClass().getName() + " (§3.11.4)");
        }
        values.put(key, value);
    }

    @Override
    public TypedQuery<X> setParameter(String name, Object value) {
        set(name, value);
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(int position, Object value) {
        set(position, value);
        return this;
    }

    @Override
    public <T> TypedQuery<X> setParameter(Parameter<T> param, T value) {
        if (param == null) {
            throw new IllegalArgumentException("The parameter cannot be null");
        }
        set(param.getName() != null ? param.getName() : (Object) param.getPosition(), value);
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
        return setParameter(param, value);
    }

    @Override
    public TypedQuery<X> setParameter(Parameter<Date> param, Date value, TemporalType temporalType) {
        return setParameter(param, value);
    }

    @Override
    public TypedQuery<X> setParameter(String name, Calendar value, TemporalType temporalType) {
        return setParameter(name, value);
    }

    @Override
    public TypedQuery<X> setParameter(String name, Date value, TemporalType temporalType) {
        return setParameter(name, value);
    }

    @Override
    public TypedQuery<X> setParameter(int position, Calendar value, TemporalType temporalType) {
        return setParameter(position, value);
    }

    @Override
    public TypedQuery<X> setParameter(int position, Date value, TemporalType temporalType) {
        return setParameter(position, value);
    }

    @Override
    public Set<Parameter<?>> getParameters() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(parameters.values()));
    }

    @Override
    public Parameter<?> getParameter(String name) {
        return parameter(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(String name, Class<T> type) {
        QueryParameter<?> parameter = parameter(name);
        if (!type.isAssignableFrom(parameter.type())) {
            throw new IllegalArgumentException("The parameter :" + name + " is a " + parameter.type().getName());
        }
        return (Parameter<T>) parameter;
    }

    @Override
    public Parameter<?> getParameter(int position) {
        return parameter(position);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(int position, Class<T> type) {
        QueryParameter<?> parameter = parameter(position);
        if (!type.isAssignableFrom(parameter.type())) {
            throw new IllegalArgumentException("The parameter ?" + position + " is a " + parameter.type().getName());
        }
        return (Parameter<T>) parameter;
    }

    @Override
    public boolean isBound(Parameter<?> param) {
        return param != null && values.containsKey(param.getName() != null ? param.getName() : param.getPosition());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getParameterValue(Parameter<T> param) {
        return (T) value(param.getName() != null ? param.getName() : (Object) param.getPosition());
    }

    @Override
    public Object getParameterValue(String name) {
        return value(name);
    }

    @Override
    public Object getParameterValue(int position) {
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
        maxResults(maxResult);
        return this;
    }

    @Override
    public TypedQuery<X> setFirstResult(int startPosition) {
        firstResult(startPosition);
        return this;
    }

    @Override
    public TypedQuery<X> setHint(String hintName, Object value) {
        hint(hintName, value);
        return this;
    }

    @Override
    public TypedQuery<X> setFlushMode(FlushModeType flushMode) {
        flushMode(flushMode);
        return this;
    }

    @Override
    public TypedQuery<X> setLockMode(LockModeType lockMode) {
        this.lockMode = lockMode;
        return this;
    }

    @Override
    public LockModeType getLockMode() {
        return lockMode;
    }

    @Override
    public TypedQuery<X> setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode) {
        cacheRetrieveMode(cacheRetrieveMode);
        return this;
    }

    @Override
    public TypedQuery<X> setCacheStoreMode(CacheStoreMode cacheStoreMode) {
        cacheStoreMode(cacheStoreMode);
        return this;
    }

    @Override
    public TypedQuery<X> setTimeout(Integer timeout) {
        timeout(timeout);
        return this;
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }
}
