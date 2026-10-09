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

import io.vidocq.mansart.jpa.core.session.NotYet;
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Parameter;
import jakarta.persistence.Query;
import jakarta.persistence.TemporalType;
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
 * bound in order. Its updates are executed now; reading results and result-set mappings come with P7.
 */
public final class NativeQuery extends AbstractQuery implements Query {

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

    public NativeQuery(String sqlString, FlushModeType flushMode, Executor executor, java.util.function.BooleanSupplier open) {
        super(flushMode, open);
        if (sqlString == null || sqlString.isBlank()) {
            throw new IllegalArgumentException("A native query needs SQL");
        }
        this.sqlString = sqlString;
        this.sql = translate(sqlString);
        this.executor = executor;
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
        throw NotYet.milestone("P7", "the results of native queries");
    }

    @Override
    public Object getSingleResult() {
        checkOpen();
        throw NotYet.milestone("P7", "the results of native queries");
    }

    @Override
    public Object getSingleResultOrNull() {
        checkOpen();
        throw NotYet.milestone("P7", "the results of native queries");
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
