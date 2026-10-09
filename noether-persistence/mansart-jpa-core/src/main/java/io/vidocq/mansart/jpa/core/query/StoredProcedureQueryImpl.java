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

import io.vidocq.mansart.jpa.dialect.sql.ProcedureCall;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import jakarta.persistence.Parameter;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.StoredProcedureQuery;
import jakarta.persistence.TemporalType;
import jakarta.persistence.TransactionRequiredException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A JDBC stored-procedure call and the result sequence it returns (§3.11.12).
 *
 * <p>{@link #execute()} always calls the procedure with the current bindings and replaces the results and output
 * values of the previous execution; a failed call leaves the query unexecuted. Binding or registering a parameter
 * makes the query unexecuted again, so {@code getResultList}, {@code getSingleResult} and {@code executeUpdate},
 * which execute only an unexecuted query, never read results of stale inputs. Trailing unbound {@code IN} parameters
 * are left out of the call so that the procedure's default values apply.
 */
public final class StoredProcedureQueryImpl extends NativeQuery implements StoredProcedureQuery {

    private final String procedureName;
    private final List<Identifier> routineName;
    private final List<Class<?>> resultClasses;
    private final List<String> resultMappings;
    private final List<ProcedureParameter> parameters = new ArrayList<>();
    private final Map<Object, ProcedureParameter> byName = new LinkedHashMap<>();
    private final Map<ProcedureParameter, Binding> values = new LinkedHashMap<>();
    private final Map<ProcedureParameter, Object> outputs = new LinkedHashMap<>();
    private final List<ProcedureResult> results = new ArrayList<>();
    private boolean executed;
    private int resultIndex;
    private int updateCount = -1;
    private Boolean currentResult;
    private boolean namedParameters;

    private record ProcedureResult(boolean rows, List<?> values, int updateCount) {
        private ProcedureResult {
            values = Collections.unmodifiableList(new ArrayList<>(values)); // a scalar result may be null
        }
    }

    /** The bound value as given, and the temporal type it is sent as, if any. */
    private record Binding(Object value, TemporalType temporalType) {
        Object jdbcValue() {
            return temporalType == null ? value : temporal(value, temporalType);
        }
    }

    private record Execution(List<ProcedureResult> results, Map<ProcedureParameter, Object> outputs) {
    }

    public StoredProcedureQueryImpl(String procedureName, List<Class<?>> resultClasses, List<String> resultMappings,
            FlushModeType flushMode, Executor executor, QueryRuntime runtime, java.util.function.BooleanSupplier open) {
        super("select 1", flushMode, executor, runtime, null, null, open);
        this.procedureName = procedureName;
        this.routineName = runtime.mapping().routineName(procedureName);
        this.resultClasses = List.copyOf(resultClasses);
        this.resultMappings = List.copyOf(resultMappings);
        this.namedParameters = false;
    }

    private static final class ProcedureParameter implements Parameter<Object> {
        private final int position;
        private final String name;
        private final ProcedureCall.Parameter callParameter;
        private final Class<?> type;
        private final ParameterMode mode;

        private ProcedureParameter(int position, String name, ProcedureCall.Parameter callParameter, Class<?> type, ParameterMode mode) {
            this.position = position;
            this.name = name;
            this.callParameter = callParameter;
            this.type = type;
            this.mode = mode;
        }

        @Override public String getName() { return name; }
        @Override public Integer getPosition() { return name == null ? position : null; }
        @SuppressWarnings("unchecked")
        @Override public Class<Object> getParameterType() { return (Class<Object>) type; }
    }

    @Override
    public StoredProcedureQuery registerStoredProcedureParameter(int position, Class<?> type, ParameterMode mode) {
        checkOpen();
        if (position < 1 || namedParameters || type == null || mode == null) {
            throw new IllegalArgumentException("Invalid positional stored-procedure parameter");
        }
        register(position, null, type, mode);
        return this;
    }

    @Override
    public StoredProcedureQuery registerStoredProcedureParameter(String name, Class<?> type, ParameterMode mode) {
        checkOpen();
        if (name == null || name.isBlank() || (!parameters.isEmpty() && !namedParameters) || type == null || mode == null) {
            throw new IllegalArgumentException("Invalid named stored-procedure parameter");
        }
        register(parameters.size() + 1, name, type, mode);
        namedParameters = true;
        return this;
    }

    private void register(int position, String name, Class<?> type, ParameterMode mode) {
        Identifier targetName = runtime().mapping().identifier(name);
        ProcedureCall.Parameter callParameter = new ProcedureCall.Parameter(callMode(mode), jdbcType(type, mode), targetName);
        if (position != parameters.size() + 1) {
            throw new IllegalArgumentException("Stored-procedure parameters must be registered once in contiguous order");
        }
        if (mode == ParameterMode.IN && type == void.class || mode != ParameterMode.IN && type == void.class && mode != ParameterMode.REF_CURSOR) {
            throw new IllegalArgumentException("Invalid type for stored-procedure parameter " + position);
        }
        if (name != null && byName.containsKey(name) || name == null && byName.containsKey(position)) {
            throw new IllegalArgumentException("Stored-procedure parameter is already registered: " + (name == null ? position : name));
        }
        ProcedureParameter parameter = new ProcedureParameter(position, name, callParameter, type, mode);
        parameters.add(parameter);
        byName.put(name == null ? position : name, parameter);
        reset();
    }

    @Override
    public boolean execute() {
        checkOpen();
        int sent = parametersSent();
        reset();
        Execution execution = runtime().read(getFlushMode(), connection -> call(connection, sent));
        results.addAll(execution.results());
        outputs.putAll(execution.outputs());
        executed = true;
        resultIndex = 0;
        if (results.isEmpty()) {
            currentResult = false;
            return false;
        }
        currentResult = results.getFirst().rows();
        updateCount = results.getFirst().updateCount();
        return currentResult;
    }

    /** The number of leading parameters passed to the call: trailing unbound {@code IN} ones take their defaults. */
    private int parametersSent() {
        int sent = parameters.size();
        while (sent > 0 && parameters.get(sent - 1).mode == ParameterMode.IN && !values.containsKey(parameters.get(sent - 1))) {
            sent--;
        }
        for (ProcedureParameter parameter : parameters.subList(0, sent)) {
            if (parameter.mode != ParameterMode.OUT && parameter.mode != ParameterMode.REF_CURSOR && !values.containsKey(parameter)) {
                throw new IllegalStateException("The input parameter " + parameter.position
                    + " is not bound; only trailing IN parameters can be left to their database defaults");
            }
        }
        return sent;
    }

    private void reset() {
        executed = false;
        results.clear();
        outputs.clear();
        resultIndex = 0;
        updateCount = -1;
        currentResult = null;
    }

    private Execution call(Connection connection, int sent) {
        List<ProcedureParameter> called = parameters.subList(0, sent);
        List<ProcedureResult> read = new ArrayList<>();
        Map<ProcedureParameter, Object> out = new LinkedHashMap<>();
        var dialect = runtime().dialect(connection);
        String sql = dialect.renderProcedureCall(new ProcedureCall(routineName, called.stream()
            .map(parameter -> parameter.callParameter)
            .toList()));
        try (CallableStatement statement = connection.prepareCall(sql)) {
            for (ProcedureParameter parameter : called) {
                int position = parameter.position;
                if (parameter.mode != ParameterMode.IN) {
                    statement.registerOutParameter(position, jdbcType(parameter.type, parameter.mode));
                }
                if (parameter.mode == ParameterMode.IN || parameter.mode == ParameterMode.INOUT) {
                    bind(statement, position, values.get(parameter), jdbcType(parameter.type, parameter.mode));
                }
            }
            if (getTimeout() != null) {
                statement.setQueryTimeout(Math.max(1, (getTimeout() + 999) / 1000));
            }
            boolean hasResults = statement.execute();
            List<ProcedureParameter> cursors = called.stream()
                .filter(parameter -> parameter.mode == ParameterMode.REF_CURSOR).toList();
            int resultOrdinal = 0;
            if (!cursors.isEmpty()) {
                for (ProcedureParameter cursor : cursors) {
                    Object cursorName = statement.getObject(cursor.position);
                    out.put(cursor, cursorName);
                    if (cursorName instanceof ResultSet cursorResult) {
                        try (cursorResult) {
                            read.add(new ProcedureResult(true, readRows(cursorResult, resultOrdinal++), -1));
                        }
                    } else if (cursorName != null) {
                        try (Statement cursorStatement = connection.createStatement();
                                ResultSet cursorResult = cursorStatement.executeQuery(dialect.renderRefCursorFetch(cursorName.toString()))) {
                            read.add(new ProcedureResult(true, readRows(cursorResult, resultOrdinal++), -1));
                        }
                    }
                }
            } else {
                while (true) {
                    if (hasResults) {
                        try (ResultSet rows = statement.getResultSet()) {
                            read.add(new ProcedureResult(true, readRows(rows, resultOrdinal++), -1));
                        }
                    } else {
                        int count = statement.getUpdateCount();
                        if (count == -1) break;
                        read.add(new ProcedureResult(false, List.of(), count));
                    }
                    hasResults = statement.getMoreResults(CallableStatement.CLOSE_CURRENT_RESULT);
                }
            }
            for (ProcedureParameter parameter : called) {
                if (parameter.mode != ParameterMode.IN && !out.containsKey(parameter)) {
                    out.put(parameter, statement.getObject(parameter.position));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("The stored procedure " + procedureName + " failed: " + e.getMessage(), e);
        }
        return new Execution(read, out);
    }

    private List<Object> readRows(ResultSet rows, int ordinal) throws SQLException {
        Class<?> resultClass = resultClasses.isEmpty() ? null : resultClasses.get(Math.min(ordinal, resultClasses.size() - 1));
        String mapping = resultMappings.isEmpty() ? null : resultMappings.get(Math.min(ordinal, resultMappings.size() - 1));
        List<Object> data = new ArrayList<>();
        while (rows.next()) {
            data.add(mapResult(rows, resultClass, mapping));
        }
        return data;
    }

    @Override
    public Object getSingleResult() {
        List<?> rows = getResultList();
        if (rows == null) return null;
        if (rows.isEmpty()) throw new NoResultException("The stored procedure " + procedureName + " returned no result");
        if (rows.size() > 1) throw new NonUniqueResultException("The stored procedure " + procedureName + " returned several results");
        return rows.getFirst();
    }

    @Override
    public Object getSingleResultOrNull() {
        List<?> rows = getResultList();
        if (rows == null || rows.isEmpty()) return null;
        if (rows.size() > 1) throw new NonUniqueResultException("The stored procedure " + procedureName + " returned several results");
        return rows.getFirst();
    }

    private static ProcedureCall.Mode callMode(ParameterMode mode) {
        return switch (mode) {
            case IN -> ProcedureCall.Mode.IN;
            case OUT -> ProcedureCall.Mode.OUT;
            case INOUT -> ProcedureCall.Mode.INOUT;
            case REF_CURSOR -> ProcedureCall.Mode.REF_CURSOR;
        };
    }

    private static int jdbcType(Class<?> type, ParameterMode mode) {
        if (mode == ParameterMode.REF_CURSOR) return Types.REF_CURSOR;
        if (type == String.class || type == Character.class || type == char.class) return Types.VARCHAR;
        if (type == Integer.class || type == int.class) return Types.INTEGER;
        if (type == Long.class || type == long.class) return Types.BIGINT;
        if (type == Short.class || type == short.class) return Types.SMALLINT;
        if (type == Byte.class || type == byte.class) return Types.TINYINT;
        if (type == Boolean.class || type == boolean.class) return Types.BOOLEAN;
        if (type == Float.class || type == float.class) return Types.REAL;
        if (type == Double.class || type == double.class) return Types.DOUBLE;
        if (type == java.math.BigDecimal.class) return Types.DECIMAL;
        if (type == java.math.BigInteger.class) return Types.NUMERIC;
        if (type == java.sql.Date.class) return Types.DATE;
        if (type == java.sql.Time.class) return Types.TIME;
        if (type == java.sql.Timestamp.class || type == Date.class || type == Calendar.class) return Types.TIMESTAMP;
        if (type.isEnum()) return Types.VARCHAR;
        return Types.JAVA_OBJECT;
    }

    private static void bind(CallableStatement statement, int position, Binding binding, int jdbcType) throws SQLException {
        Object value = binding.jdbcValue();
        if (value == null) {
            statement.setNull(position, binding.temporalType() == null ? jdbcType : switch (binding.temporalType()) {
                case DATE -> Types.DATE;
                case TIME -> Types.TIME;
                case TIMESTAMP -> Types.TIMESTAMP;
            });
        } else if (value instanceof Enum<?> constant) {
            statement.setObject(position, constant.name());
        } else if (value instanceof Calendar calendar) {
            statement.setTimestamp(position, new java.sql.Timestamp(calendar.getTimeInMillis()));
        } else if (value.getClass() == Date.class) {
            statement.setTimestamp(position, new java.sql.Timestamp(((Date) value).getTime()));
        } else {
            statement.setObject(position, value);
        }
    }

    @Override
    public Object getOutputParameterValue(int position) {
        checkOpen();
        ProcedureParameter parameter = require(position);
        return output(parameter);
    }

    @Override
    public Object getOutputParameterValue(String name) {
        checkOpen();
        return output(require(name));
    }

    private Object output(ProcedureParameter parameter) {
        if (parameter.mode == ParameterMode.IN) {
            throw new IllegalArgumentException("The parameter is not an output parameter: " + parameter.position);
        }
        if (!executed) {
            throw new IllegalStateException("The stored procedure has not been executed");
        }
        return outputs.get(parameter);
    }

    @Override
    public boolean hasMoreResults() {
        checkOpen();
        requireExecuted();
        if (resultIndex + 1 >= results.size()) {
            currentResult = null;
            updateCount = -1;
            return false;
        }
        ProcedureResult next = results.get(++resultIndex);
        currentResult = next.rows();
        updateCount = next.updateCount();
        return next.rows();
    }

    @Override
    public int getUpdateCount() {
        checkOpen();
        requireExecuted();
        return updateCount;
    }

    @Override
    public int executeUpdate() {
        checkOpen();
        if (!runtime().inTransaction()) {
            throw new TransactionRequiredException("executeUpdate() needs an active transaction");
        }
        if (!executed) execute();
        if (Boolean.TRUE.equals(currentResult)) {
            throw new PersistenceException("The stored procedure returned a result set, not an update count");
        }
        return updateCount;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List getResultList() {
        checkOpen();
        if (!executed) execute();
        if (!Boolean.TRUE.equals(currentResult)) return null;
        List<?> rows = results.get(resultIndex).values();
        int first = Math.min(getFirstResult(), rows.size());
        int end = (int) Math.min(rows.size(), (long) first + getMaxResults());
        return rows.subList(first, end);
    }

    @Override
    public StoredProcedureQuery setHint(String name, Object value) { super.setHint(name, value); return this; }
    @Override
    public StoredProcedureQuery setFlushMode(jakarta.persistence.FlushModeType value) { super.setFlushMode(value); return this; }
    @Override
    public StoredProcedureQuery setCacheRetrieveMode(jakarta.persistence.CacheRetrieveMode value) {
        super.setCacheRetrieveMode(value); return this;
    }
    @Override
    public StoredProcedureQuery setCacheStoreMode(jakarta.persistence.CacheStoreMode value) { super.setCacheStoreMode(value); return this; }
    @Override
    public StoredProcedureQuery setTimeout(Integer value) { super.setTimeout(value); return this; }
    @Override
    public StoredProcedureQuery setMaxResults(int value) { super.setMaxResults(value); return this; }
    @Override
    public StoredProcedureQuery setFirstResult(int value) { super.setFirstResult(value); return this; }

    @Override public StoredProcedureQuery setParameter(int position, Object value) { bindParameter(require(position), value); return this; }
    @Override public StoredProcedureQuery setParameter(String name, Object value) { bindParameter(require(name), value); return this; }
    @Override public <T> StoredProcedureQuery setParameter(Parameter<T> parameter, T value) {
        bindParameter(require(parameter), value); return this;
    }
    @Override public StoredProcedureQuery setParameter(int position, Calendar value, TemporalType type) {
        bindTemporal(require(position), value, type); return this;
    }
    @Override public StoredProcedureQuery setParameter(int position, Date value, TemporalType type) {
        bindTemporal(require(position), value, type); return this;
    }
    @Override public StoredProcedureQuery setParameter(String name, Calendar value, TemporalType type) {
        bindTemporal(require(name), value, type); return this;
    }
    @Override public StoredProcedureQuery setParameter(String name, Date value, TemporalType type) {
        bindTemporal(require(name), value, type); return this;
    }
    @Override public StoredProcedureQuery setParameter(Parameter<Calendar> parameter, Calendar value, TemporalType type) {
        bindTemporal(require(parameter), value, type); return this;
    }
    @Override public StoredProcedureQuery setParameter(Parameter<Date> parameter, Date value, TemporalType type) {
        bindTemporal(require(parameter), value, type); return this;
    }

    private void bindTemporal(ProcedureParameter parameter, Object value, TemporalType temporalType) {
        if (parameter.mode == ParameterMode.OUT || parameter.mode == ParameterMode.REF_CURSOR) {
            throw new IllegalArgumentException("The parameter cannot be bound: " + parameter.position);
        }
        if (temporalType == null) {
            throw new IllegalArgumentException("A temporal type is required for parameter " + parameter.position);
        }
        Class<?> type = box(parameter.type);
        if (value != null && type != Date.class && type != Calendar.class && !type.isInstance(value)
                && !type.isInstance(temporal(value, temporalType))) {
            throw new IllegalArgumentException("Expected " + parameter.type.getName() + " for parameter " + parameter.position);
        }
        values.put(parameter, new Binding(value, temporalType));
        reset();
    }

    private void bindParameter(ProcedureParameter parameter, Object value) {
        if (parameter.mode == ParameterMode.OUT || parameter.mode == ParameterMode.REF_CURSOR) {
            throw new IllegalArgumentException("The parameter cannot be bound: " + parameter.position);
        }
        if (value != null && !box(parameter.type).isInstance(value)) {
            throw new IllegalArgumentException("Expected " + parameter.type.getName() + " for parameter " + parameter.position);
        }
        values.put(parameter, new Binding(value, null));
        reset();
    }

    @Override
    public Set<Parameter<?>> getParameters() {
        checkOpen();
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(parameters));
    }
    @Override public Parameter<?> getParameter(int position) { checkOpen(); return require(position); }
    @Override public <T> Parameter<T> getParameter(int position, Class<T> type) {
        checkParameterType(require(position), type); return cast(require(position));
    }
    @Override public Parameter<?> getParameter(String name) { checkOpen(); return require(name); }
    @Override public <T> Parameter<T> getParameter(String name, Class<T> type) {
        checkParameterType(require(name), type); return cast(require(name));
    }
    @SuppressWarnings("unchecked") private static <T> Parameter<T> cast(ProcedureParameter p) { return (Parameter<T>) p; }
    private static void checkParameterType(ProcedureParameter p, Class<?> type) {
        if (type == null || !box(p.type).equals(box(type))) throw new IllegalArgumentException("Wrong stored-procedure parameter type");
    }
    @Override public boolean isBound(Parameter<?> parameter) { checkOpen(); return values.containsKey(require(parameter)); }
    @Override public <T> T getParameterValue(Parameter<T> parameter) {
        checkOpen();
        ProcedureParameter registered = require(parameter);
        if (registered.mode == ParameterMode.OUT || registered.mode == ParameterMode.REF_CURSOR) {
            @SuppressWarnings("unchecked") T value = (T) output(registered);
            return value;
        }
        @SuppressWarnings("unchecked") T value = (T) inputValue(registered);
        return value;
    }
    @Override public Object getParameterValue(int position) {
        checkOpen();
        ProcedureParameter parameter = require(position);
        if (parameter.mode == ParameterMode.OUT || parameter.mode == ParameterMode.REF_CURSOR) return output(parameter);
        return inputValue(parameter);
    }
    @Override public Object getParameterValue(String name) {
        checkOpen();
        ProcedureParameter parameter = require(name);
        if (parameter.mode == ParameterMode.OUT || parameter.mode == ParameterMode.REF_CURSOR) return output(parameter);
        return inputValue(parameter);
    }
    private Object inputValue(ProcedureParameter parameter) {
        if (!values.containsKey(parameter)) throw new IllegalStateException("The parameter is not bound: " + parameter.position);
        return values.get(parameter).value();
    }
    private ProcedureParameter require(int position) {
        checkOpen();
        ProcedureParameter p = byName.get(position);
        if (p == null) throw new IllegalArgumentException("No stored-procedure parameter at position " + position);
        return p;
    }
    private ProcedureParameter require(String name) {
        checkOpen();
        ProcedureParameter p = byName.get(name);
        if (p == null) throw new IllegalArgumentException("No stored-procedure parameter named " + name);
        return p;
    }
    private ProcedureParameter require(Parameter<?> p) {
        checkOpen();
        if (p == null) throw new IllegalArgumentException("A parameter is required");
        ProcedureParameter registered = p.getName() == null && p.getPosition() != null ? byName.get(p.getPosition()) : byName.get(p.getName());
        if (registered == null || registered != p) {
            throw new IllegalArgumentException("The parameter does not belong to this stored-procedure query");
        }
        return registered;
    }
    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return type;
    }
    private void requireExecuted() {
        if (!executed) throw new IllegalStateException("The stored procedure has not been executed");
    }
}
