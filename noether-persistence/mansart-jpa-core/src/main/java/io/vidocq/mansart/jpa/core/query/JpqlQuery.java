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
    private final Ast.Statement statement;
    private final Class<X> resultClass;
    private final QueryRuntime runtime;
    private Integer lockTimeout;
    private final Map<Object, QueryParameter<?>> parameters = new LinkedHashMap<>();
    private final Map<Object, Object> values = new HashMap<>();
    private LockModeType lockMode = LockModeType.NONE;

    /**
     * @param resultClass the type the results must have, {@code null} for an untyped {@code Query}
     * @throws IllegalArgumentException if the query is not valid, or its results are not of {@code resultClass}
     */
    public JpqlQuery(String jpql, Class<X> resultClass, FlushModeType flushMode, QueryRuntime runtime) {
        super(flushMode, runtime::isOpen);
        this.jpql = jpql;
        this.runtime = runtime;
        this.resultClass = resultClass;
        this.statement = Parser.parse(jpql);
        Compiled compiled = Translator.translate(statement, runtime.mapping(), null, null, null);
        if (!(statement instanceof Ast.Select) && resultClass != null && resultClass != Object.class) {
            throw new IllegalArgumentException("An UPDATE or a DELETE has no results of type " + resultClass.getName() + ": " + jpql);
        }
        for (Compiled.Slot slot : compiled.slots()) {
            if (slot.literal()) {
                continue;
            }
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

    /** The query as the application wrote it. */
    public String queryString() {
        return jpql;
    }

    /** The type the results were asked to have, or {@code null} for an untyped query. */
    public Class<X> resultClass() {
        return resultClass;
    }

    /** The Java type of the results of {@code jpql}, or {@code null} if it cannot be told (it is not a valid select). */
    static Class<?> resultType(String jpql, io.vidocq.mansart.jpa.core.mapping.MappedUnit unit) {
        try {
            return Parser.parse(jpql) instanceof Ast.Select select ? Translator.translate(select, unit, null, null, null).resultType() : null;
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
        if (results.size() > 1) {
            throw new NonUniqueResultException("The query found several results: " + jpql);
        }
        return results.isEmpty() ? null : results.getFirst();
    }

    /** §4.10: runs a bulk update or delete in the transaction, and returns the rows it changed. */
    @Override
    public int executeUpdate() {
        checkOpen();
        if (statement instanceof Ast.Select) {
            throw new IllegalStateException("executeUpdate() runs an UPDATE or a DELETE, not a SELECT (§3.11): " + jpql);
        }
        checkBound();
        Compiled compiled = Translator.translate(statement, runtime.mapping(), p -> values.get(key(p)), null, null);
        return runtime.write(getFlushMode(), connection -> {
            Dialect dialect = runtime.dialect(connection);
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
        if (!(statement instanceof Ast.Select)) {
            throw new IllegalStateException("An UPDATE or a DELETE has no results: run it with executeUpdate() (§3.11): " + jpql);
        }
        checkBound();
        if (lockMode != LockModeType.NONE && !runtime.inTransaction()) {
            throw new jakarta.persistence.TransactionRequiredException("A query with the lock mode " + lockMode
                + " runs in a transaction (§3.11)");
        }
        if (maxResults == 0) {
            return new ArrayList<>();
        }
        Compiled compiled = Translator.translate(statement, runtime.mapping(), p -> values.get(key(p)),
            getFirstResult() > 0 ? getFirstResult() : null, maxResults < Integer.MAX_VALUE ? maxResults : null);
        return (List<X>) runtime.read(getFlushMode(), connection -> execute(compiled, connection));
    }

    private List<Object> execute(Compiled compiled, Connection connection) {
        Dialect dialect = runtime.dialect(connection);
        boolean pessimistic = io.vidocq.mansart.jpa.core.flush.Locks.pessimistic(lockMode);
        io.vidocq.mansart.jpa.dialect.sql.Statement sql = compiled.sql();
        if (pessimistic && sql instanceof io.vidocq.mansart.jpa.dialect.sql.Query query) { // §3.5.6: the rows it reads
            sql = query.locked(io.vidocq.mansart.jpa.core.flush.Locks.rowLock(lockMode), lockTimeout != null && lockTimeout == 0);
        }
        Dialect.Rendered rendered = dialect.renderQuery(sql);
        List<Object> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(rendered.sql())) {
            if (pessimistic) {
                io.vidocq.mansart.jpa.core.flush.Locks.timeout(dialect, lockTimeout, connection);
            }
            List<io.vidocq.mansart.jpa.dialect.sql.Expression.Parameter> parameters = rendered.parameters();
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
                }
            }
        } catch (SQLException e) {
            if (pessimistic) {
                throw io.vidocq.mansart.jpa.core.flush.Locks.failure(dialect, e, null);
            }
            throw new PersistenceException("The query failed: " + e.getMessage() + " — " + jpql, e);
        }
        // entities found, and results constructed, once the rows are read: the loader runs statements of its own
        List<Object> resultList = new ArrayList<>(rows.size());
        for (Object row : rows) {
            resultList.add(resolve(row, connection));
        }
        if (lockMode != LockModeType.NONE) {
            resultList.forEach(this::lockEntities);
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
        }
    }

    /** The identity of an entity read in a row, found once the rows are read. */
    private record EntityKey(MappedEntity type, Object id) {
    }

    /** A constructor result whose arguments are read, built once the entities among them are found. */
    private record Built(Class<?> type, Object[] arguments) {
    }

    private Object resolve(Object read, Connection connection) {
        return switch (read) {
            case EntityKey(MappedEntity type, Object id) -> runtime.find(type, id, connection);
            case Built built -> {
                Object[] arguments = new Object[built.arguments().length];
                for (int i = 0; i < arguments.length; i++) {
                    arguments[i] = resolve(built.arguments()[i], connection);
                }
                yield construct(built.type(), arguments);
            }
            case Object[] row -> {
                Object[] resolved = new Object[row.length];
                for (int i = 0; i < row.length; i++) {
                    resolved[i] = resolve(row[i], connection);
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
        };
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
        Class<?> type = parameter.type();
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
        set(param.getName() != null ? param.getName() : (Object) param.getPosition(), value);
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(Parameter<Calendar> param, Calendar value, TemporalType temporalType) {
        checkOpen();
        set(param.getName() != null ? param.getName() : (Object) param.getPosition(), temporal(value, temporalType));
        return this;
    }

    @Override
    public TypedQuery<X> setParameter(Parameter<Date> param, Date value, TemporalType temporalType) {
        checkOpen();
        set(param.getName() != null ? param.getName() : (Object) param.getPosition(), temporal(value, temporalType));
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
        return Collections.unmodifiableSet(new LinkedHashSet<>(parameters.values()));
    }

    @Override
    public Parameter<?> getParameter(String name) {
        checkOpen();
        return parameter(name);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(String name, Class<T> type) {
        checkOpen();
        QueryParameter<?> parameter = parameter(name);
        if (!type.isAssignableFrom(parameter.type())) {
            throw new IllegalArgumentException("The parameter :" + name + " is a " + parameter.type().getName());
        }
        return (Parameter<T>) parameter;
    }

    @Override
    public Parameter<?> getParameter(int position) {
        checkOpen();
        return parameter(position);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Parameter<T> getParameter(int position, Class<T> type) {
        checkOpen();
        QueryParameter<?> parameter = parameter(position);
        if (!type.isAssignableFrom(parameter.type())) {
            throw new IllegalArgumentException("The parameter ?" + position + " is a " + parameter.type().getName());
        }
        return (Parameter<T>) parameter;
    }

    @Override
    public boolean isBound(Parameter<?> param) {
        checkOpen();
        return param != null && values.containsKey(param.getName() != null ? param.getName() : param.getPosition());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getParameterValue(Parameter<T> param) {
        checkOpen();
        return (T) value(param.getName() != null ? param.getName() : (Object) param.getPosition());
    }

    @Override
    public Object getParameterValue(String name) {
        checkOpen();
        return value(name);
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
