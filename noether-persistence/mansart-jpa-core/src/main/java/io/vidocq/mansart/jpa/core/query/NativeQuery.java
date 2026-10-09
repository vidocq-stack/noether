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
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.SqlResultSetMappingModel;
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A native query (§3.11): SQL passed to the database as written, its positional parameters ({@code ?1} or {@code ?})
 * bound in order. Result rows may be scalars, tuples, entities, or results declared by {@code @SqlResultSetMapping}.
 */
public class NativeQuery extends AbstractQuery implements Query {

    /** Runs a native update for the entity manager: transaction, flush, connection, exceptions (§3.11.6, §3.12). */
    public interface Executor {
        int executeUpdate(String sql, List<Object> parameters, FlushModeType flushMode);
    }

    /** A positional parameter of a native query. */
    private record Positional(int position) implements Parameter<Object> {
        @Override
        public String getName() {
            return null;
        }

        @Override
        public Integer getPosition() {
            return position;
        }

        @Override
        public Class<Object> getParameterType() {
            return Object.class;
        }
    }

    private final String sql;
    private final String sqlString;
    private final List<Integer> order = new ArrayList<>();
    private final Map<Integer, Object> values = new HashMap<>();
    private final Executor executor;
    private final QueryRuntime runtime;
    private final Class<?> resultClass;
    private final String resultSetMapping;

    public NativeQuery(String sqlString, FlushModeType flushMode, Executor executor, QueryRuntime runtime, Class<?> resultClass,
            String resultSetMapping, java.util.function.BooleanSupplier open) {
        super(flushMode, open);
        if (sqlString == null || sqlString.isBlank()) {
            throw new IllegalArgumentException("A native query needs SQL");
        }
        this.sqlString = sqlString;
        this.sql = translate(sqlString);
        this.executor = executor;
        this.runtime = runtime;
        this.resultClass = resultClass;
        this.resultSetMapping = resultSetMapping;
    }

    /** JDBC SQL: {@code ?n} becomes {@code ?}, outside string literals and quoted names; the positions are kept. */
    private String translate(String sqlString) {
        StringBuilder jdbc = new StringBuilder(sqlString.length());
        int plain = 0;
        char quote = 0;
        for (int i = 0; i < sqlString.length(); i++) {
            char c = sqlString.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
                jdbc.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                jdbc.append(c);
            } else if (c == '?') {
                int end = i + 1;
                while (end < sqlString.length() && Character.isDigit(sqlString.charAt(end))) {
                    end++;
                }
                order.add(end > i + 1 ? Integer.parseInt(sqlString.substring(i + 1, end)) : ++plain);
                jdbc.append('?');
                i = end - 1;
            } else {
                jdbc.append(c);
            }
        }
        return jdbc.toString();
    }

    /** The SQL as the application wrote it. */
    public String sqlString() {
        return sqlString;
    }

    QueryRuntime runtime() {
        return runtime;
    }

    Object mapResult(ResultSet row, Class<?> type, String mapping) throws SQLException {
        return readResult(row, type, mapping);
    }

    @Override
    public int executeUpdate() {
        checkOpen();
        List<Object> parameters = new ArrayList<>(order.size());
        for (int position : order) {
            if (!values.containsKey(position)) {
                throw new IllegalStateException("The parameter ?" + position + " of the native query is not bound");
            }
            parameters.add(values.get(position));
        }
        return executor.executeUpdate(sql, parameters, getFlushMode());
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List getResultList() {
        checkOpen();
        List<Object> parameters = parameters();
        return runtime.read(getFlushMode(), connection -> read(connection, parameters));
    }

    @Override
    public Object getSingleResult() {
        checkOpen();
        List<?> results = limited(2);
        if (results.isEmpty()) {
            throw new NoResultException("The native query found no result: " + sqlString);
        }
        if (results.size() > 1) {
            throw new NonUniqueResultException("The native query found several results: " + sqlString);
        }
        return results.getFirst();
    }

    @Override
    public Object getSingleResultOrNull() {
        checkOpen();
        List<?> results = limited(2);
        if (results.size() > 1) {
            throw new NonUniqueResultException("The native query found several results: " + sqlString);
        }
        return results.isEmpty() ? null : results.getFirst();
    }

    private List<?> limited(int limit) {
        setMaxResults(Math.min(getMaxResults(), limit));
        return getResultList();
    }

    private List<Object> parameters() {
        List<Object> parameters = new ArrayList<>(order.size());
        for (int position : order) {
            if (!values.containsKey(position)) {
                throw new IllegalStateException("The parameter ?" + position + " of the native query is not bound");
            }
            parameters.add(values.get(position));
        }
        return parameters;
    }

    private List<Object> read(Connection connection, List<Object> parameters) {
        List<Object> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                Object value = parameters.get(i);
                if (value == null) {
                    statement.setNull(i + 1, Types.NULL);
                } else {
                    statement.setObject(i + 1, value instanceof Enum<?> constant ? constant.name() : value);
                }
            }
            if (getTimeout() != null) {
                statement.setQueryTimeout(Math.max(1, (getTimeout() + 999) / 1000));
            }
            try (ResultSet results = statement.executeQuery()) {
                ResultSetMetaData metadata = results.getMetaData();
                int columns = metadata.getColumnCount();
                int first = getFirstResult();
                int max = getMaxResults();
                int skipped = 0;
                while (results.next() && rows.size() < max) {
                    if (skipped++ < first) {
                        continue;
                    }
                    rows.add(readResult(results, resultClass, resultSetMapping));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("The native query failed: " + e.getMessage() + " — " + sqlString, e);
        }
        return rows;
    }

    /** Maps one result row for native queries and stored procedures (§3.11.11–12). */
    Object readResult(ResultSet row, Class<?> resultClass, String resultSetMapping) throws SQLException {
        ResultSetMetaData metadata = row.getMetaData();
        int columns = metadata.getColumnCount();
        if (resultSetMapping != null) {
            SqlResultSetMappingModel mapping = runtime.mapping().model().sqlResultSetMapping(resultSetMapping).orElseThrow(() ->
                new PersistenceException("The persistence unit has no SQL result-set mapping " + resultSetMapping));
            return mappedResult(row, metadata, mapping);
        }
        if (resultClass != null && runtime.mapping().entity(resultClass).isPresent()) {
            return entityResult(row, metadata, resultClass, List.of());
        }
        if (columns == 1) {
            return convert(row.getObject(1), resultClass);
        }
        Object[] tuple = new Object[columns];
        for (int i = 0; i < columns; i++) {
            tuple[i] = row.getObject(i + 1);
        }
        return tuple;
    }

    private Object mappedResult(ResultSet row, ResultSetMetaData metadata, SqlResultSetMappingModel mapping) throws SQLException {
        if (mapping.results().isEmpty()) {
            throw new PersistenceException("The SQL result-set mapping " + mapping.name() + " has no result items");
        }
        Object[] values = new Object[mapping.results().size()];
        for (int i = 0; i < values.length; i++) {
            SqlResultSetMappingModel.Result result = mapping.results().get(i);
            values[i] = switch (result) {
                case SqlResultSetMappingModel.EntityResult entity ->
                    entityResult(row, metadata, entity.entityClass(), entity.fields());
                case SqlResultSetMappingModel.ConstructorResult constructor -> constructorResult(row, metadata, constructor);
                case SqlResultSetMappingModel.ColumnResult column ->
                    convert(row.getObject(columnIndex(metadata, column.column())), column.type());
            };
        }
        return values.length == 1 ? values[0] : values;
    }

    private Object constructorResult(ResultSet row, ResultSetMetaData metadata,
            SqlResultSetMappingModel.ConstructorResult result) throws SQLException {
        Object[] arguments = new Object[result.columns().size()];
        Class<?>[] types = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            SqlResultSetMappingModel.ColumnResult column = result.columns().get(i);
            Object value = row.getObject(columnIndex(metadata, column.column()));
            Class<?> type = column.type() == null ? value == null ? Object.class : value.getClass() : column.type();
            arguments[i] = convert(value, type);
            types[i] = primitiveBox(type);
        }
        try {
            for (var constructor : result.targetClass().getConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != types.length) {
                    continue;
                }
                boolean matches = true;
                for (int i = 0; i < parameters.length; i++) {
                    if (!primitiveBox(parameters[i]).isAssignableFrom(types[i])) {
                        matches = false;
                        break;
                    }
                    arguments[i] = convert(arguments[i], parameters[i]);
                }
                if (matches) {
                    return constructor.newInstance(arguments);
                }
            }
            throw new PersistenceException("The SQL result-set mapping " + result.targetClass().getName()
                + " has no public constructor matching its column results");
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new PersistenceException("The SQL result-set constructor for " + result.targetClass().getName() + " failed", e);
        }
    }

    private static Class<?> primitiveBox(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static int columnIndex(ResultSetMetaData metadata, String label) throws SQLException {
        int column = findColumn(metadata, label);
        if (column < 0) {
            throw new PersistenceException("The native result has no column " + label);
        }
        return column;
    }

    private Object entityResult(ResultSet row, ResultSetMetaData metadata, Class<?> type,
            List<SqlResultSetMappingModel.FieldResult> fields) throws SQLException {
        MappedEntity entity = runtime.mapping().entity(type).orElseThrow();
        List<io.vidocq.mansart.jpa.core.mapping.EntityStatements.Column> keyColumns = entity.statements().keyColumns();
        Object[] key = new Object[keyColumns.size()];
        for (int k = 0; k < keyColumns.size(); k++) {
            String columnName = keyColumns.get(k).name().name();
            if (!fields.isEmpty()) {
                String keyColumnName = columnName;
                String attribute = entity.model().attributes().stream()
                    .filter(a -> a instanceof io.vidocq.mansart.jpa.core.model.BasicAttribute basic
                        && basic.column().name().equalsIgnoreCase(keyColumnName))
                    .map(io.vidocq.mansart.jpa.core.model.AttributeModel::name).findFirst().orElse(null);
                if (attribute != null) {
                    columnName = fields.stream().filter(field -> field.name().equals(attribute))
                        .map(SqlResultSetMappingModel.FieldResult::column).findFirst().orElse(columnName);
                }
            }
            int column = findColumn(metadata, columnName);
            if (column < 0) {
                throw new PersistenceException("The native result for " + type.getName() + " has no identifier column " + columnName);
            }
            key[k] = keyColumns.get(k).binder().read(row, column);
        }
        return runtime.find(entity, entity.statements().key(key), row.getStatement().getConnection());
    }

    private static int findColumn(ResultSetMetaData metadata, String name) throws SQLException {
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            if (name.equalsIgnoreCase(metadata.getColumnLabel(column)) || name.equalsIgnoreCase(metadata.getColumnName(column))) {
                return column;
            }
        }
        return -1;
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == null || type == null || type == Object.class || type.isInstance(value)) {
            return value;
        }
        if (value instanceof Number number) {
            if (type == String.class) {
                return number.toString();
            }
            if (type == Integer.class || type == int.class) {
                return number.intValue();
            }
            if (type == Long.class || type == long.class) {
                return number.longValue();
            }
            if (type == Short.class || type == short.class) {
                return number.shortValue();
            }
            if (type == Byte.class || type == byte.class) {
                return number.byteValue();
            }
            if (type == Float.class || type == float.class) {
                return number.floatValue();
            }
            if (type == Double.class || type == double.class) {
                return number.doubleValue();
            }
            if (type == BigDecimal.class) {
                return new BigDecimal(number.toString());
            }
            if (type == BigInteger.class) {
                return new BigDecimal(number.toString()).toBigInteger();
            }
        }
        if (type == String.class) {
            return value.toString();
        }
        throw new PersistenceException("The native result " + value.getClass().getName() + " cannot be converted to " + type.getName());
    }

    // ---- parameters: positional only; named parameters are not defined for native queries (§3.11.17) ------------

    @Override
    public Query setParameter(int position, Object value) {
        checkOpen();
        if (!order.contains(position)) {
            throw new IllegalArgumentException("The native query has no parameter ?" + position);
        }
        values.put(position, value);
        return this;
    }

    @Override
    public <T> Query setParameter(Parameter<T> param, T value) {
        checkOpen();
        if (param == null || param.getPosition() == null) {
            throw new IllegalArgumentException("A native query has positional parameters only");
        }
        return setParameter(param.getPosition(), value);
    }

    @Override
    public Query setParameter(String name, Object value) {
        checkOpen();
        throw new IllegalArgumentException("A native query has positional parameters only, not :" + name);
    }

    @Override
    public Query setParameter(Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
        checkOpen();
        return setParameter(param.getPosition(), temporal(value, temporalType));
    }

    @Override
    public Query setParameter(Parameter<Date> param, Date value, TemporalType temporalType) {
        checkOpen();
        return setParameter(param.getPosition(), temporal(value, temporalType));
    }

    @Override
    public Query setParameter(String name, Calendar value, TemporalType temporalType) {
        checkOpen();
        return setParameter(name, (Object) value);
    }

    @Override
    public Query setParameter(String name, Date value, TemporalType temporalType) {
        checkOpen();
        return setParameter(name, (Object) value);
    }

    @Override
    public Query setParameter(int position, Calendar value, TemporalType temporalType) {
        checkOpen();
        return setParameter(position, temporal(value, temporalType));
    }

    @Override
    public Query setParameter(int position, Date value, TemporalType temporalType) {
        checkOpen();
        return setParameter(position, temporal(value, temporalType));
    }

    @Override
    public Set<Parameter<?>> getParameters() {
        checkOpen();
        Set<Parameter<?>> parameters = new LinkedHashSet<>();
        new LinkedHashSet<>(order).forEach(position -> parameters.add(new Positional(position)));
        return Collections.unmodifiableSet(parameters);
    }

    @Override
    public Parameter<?> getParameter(String name) {
        checkOpen();
        throw new IllegalArgumentException("A native query has positional parameters only, not :" + name);
    }

    @Override
    public <T> Parameter<T> getParameter(String name, Class<T> type) {
        checkOpen();
        throw new IllegalArgumentException("A native query has positional parameters only, not :" + name);
    }

    @Override
    public Parameter<?> getParameter(int position) {
        checkOpen();
        if (!order.contains(position)) {
            throw new IllegalArgumentException("The native query has no parameter ?" + position);
        }
        return new Positional(position);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(int position, Class<T> type) {
        checkOpen();
        return (Parameter<T>) getParameter(position);
    }

    @Override
    public boolean isBound(Parameter<?> param) {
        checkOpen();
        return param != null && param.getPosition() != null && values.containsKey(param.getPosition());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getParameterValue(Parameter<T> param) {
        checkOpen();
        return (T) getParameterValue(param.getPosition());
    }

    @Override
    public Object getParameterValue(String name) {
        checkOpen();
        throw new IllegalArgumentException("A native query has positional parameters only, not :" + name);
    }

    @Override
    public Object getParameterValue(int position) {
        checkOpen();
        if (!values.containsKey(position)) {
            throw new IllegalStateException("The parameter ?" + position + " is not bound");
        }
        return values.get(position);
    }

    // ---- settings -----------------------------------------------------------------------------------------

    @Override
    public Query setMaxResults(int maxResult) {
        checkOpen();
        maxResults(maxResult);
        return this;
    }

    @Override
    public Query setFirstResult(int startPosition) {
        checkOpen();
        firstResult(startPosition);
        return this;
    }

    @Override
    public Query setHint(String hintName, Object value) {
        checkOpen();
        hint(hintName, value);
        return this;
    }

    @Override
    public Query setFlushMode(FlushModeType flushMode) {
        checkOpen();
        flushMode(flushMode);
        return this;
    }

    /** §3.11.9: lock modes are for JPQL and criteria queries, not native ones. */
    @Override
    public Query setLockMode(LockModeType lockMode) {
        checkOpen();
        throw new IllegalStateException("A native query has no lock mode");
    }

    @Override
    public LockModeType getLockMode() {
        checkOpen();
        throw new IllegalStateException("A native query has no lock mode");
    }

    @Override
    public Query setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode) {
        checkOpen();
        cacheRetrieveMode(cacheRetrieveMode);
        return this;
    }

    @Override
    public Query setCacheStoreMode(CacheStoreMode cacheStoreMode) {
        checkOpen();
        cacheStoreMode(cacheStoreMode);
        return this;
    }

    @Override
    public Query setTimeout(Integer timeout) {
        checkOpen();
        timeout(timeout);
        return this;
    }
}
