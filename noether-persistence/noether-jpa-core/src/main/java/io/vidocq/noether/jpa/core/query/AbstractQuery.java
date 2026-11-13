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

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * What every query of an entity manager keeps (§3.11): paging, hints, flush mode, cache modes and timeout. The public
 * setters stay in each query, so that a {@code TypedQuery} returns itself typed; they validate here. Not thread-safe,
 * as the entity manager that creates it (§7.2).
 */
abstract class AbstractQuery {

    /** The standard query timeout hint, in milliseconds. */
    static final String QUERY_TIMEOUT = "jakarta.persistence.query.timeout";
    /** The standard cache retrieve mode hint. */
    static final String CACHE_RETRIEVE_MODE = "jakarta.persistence.cache.retrieveMode";
    /** The standard cache store mode hint. */
    static final String CACHE_STORE_MODE = "jakarta.persistence.cache.storeMode";

    private final Map<String, Object> hints = new LinkedHashMap<>();
    private FlushModeType flushMode;
    private int maxResults = Integer.MAX_VALUE;
    private int firstResult;
    private Integer timeout;
    private CacheRetrieveMode cacheRetrieveMode;
    private CacheStoreMode cacheStoreMode;
    private final BooleanSupplier open;

    /**
     * @param open whether the entity manager that created the query is still open
     * @param defaults the timeout and the cache modes the query takes from its entity manager and its unit
     */
    AbstractQuery(FlushModeType flushMode, BooleanSupplier open, QueryRuntime.Defaults defaults) {
        this.flushMode = flushMode;
        this.open = open;
        this.timeout = defaults.queryTimeout();
        this.cacheRetrieveMode = defaults.retrieveMode();
        this.cacheStoreMode = defaults.storeMode();
    }

    /**
     * Applies the standard hints every query reads: the query timeout and the cache modes. Returns whether
     * {@code hintName} is one of them.
     */
    boolean standardHint(String hintName, Object value) {
        switch (hintName) {
            case QUERY_TIMEOUT -> timeout(milliseconds(hintName, value));
            case CACHE_RETRIEVE_MODE -> cacheRetrieveMode(mode(CacheRetrieveMode.class, hintName, value));
            case CACHE_STORE_MODE -> cacheStoreMode(mode(CacheStoreMode.class, hintName, value));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** A timeout hint, in milliseconds: a number, or its text. */
    static Integer milliseconds(String hint, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The hint " + hint + " is a number of milliseconds, not " + value, e);
        }
    }

    /** A cache mode hint: the constant, or its name. */
    private static <E extends Enum<E>> E mode(Class<E> type, String hint, Object value) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        try {
            return Enum.valueOf(type, String.valueOf(value).strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The hint " + hint + " is a " + type.getSimpleName() + ", not " + value, e);
        }
    }

    /** §3.11 (PERSISTENCE:SPEC:608): every method of a query of a closed entity manager is an IllegalStateException. */
    void checkOpen() {
        if (!open.getAsBoolean()) {
            throw new IllegalStateException("The EntityManager that created the query is closed");
        }
    }

    void maxResults(int maxResult) {
        if (maxResult < 0) {
            throw new IllegalArgumentException("The maximum number of results cannot be negative");
        }
        this.maxResults = maxResult;
    }

    public int getMaxResults() {
        checkOpen();
        return maxResults;
    }

    void firstResult(int startPosition) {
        if (startPosition < 0) {
            throw new IllegalArgumentException("The first result cannot be negative");
        }
        this.firstResult = startPosition;
    }

    public int getFirstResult() {
        checkOpen();
        return firstResult;
    }

    void hint(String hintName, Object value) {
        hints.put(hintName, value);
    }

    public Map<String, Object> getHints() {
        checkOpen();
        return Collections.unmodifiableMap(hints);
    }

    void flushMode(FlushModeType flushMode) {
        this.flushMode = flushMode;
    }

    public FlushModeType getFlushMode() {
        checkOpen();
        return flushMode;
    }

    void cacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode) {
        this.cacheRetrieveMode = cacheRetrieveMode;
    }

    void cacheStoreMode(CacheStoreMode cacheStoreMode) {
        this.cacheStoreMode = cacheStoreMode;
    }

    public CacheRetrieveMode getCacheRetrieveMode() {
        checkOpen();
        return cacheRetrieveMode;
    }

    public CacheStoreMode getCacheStoreMode() {
        checkOpen();
        return cacheStoreMode;
    }

    void timeout(Integer timeout) {
        this.timeout = timeout;
    }

    public Integer getTimeout() {
        checkOpen();
        return timeout;
    }

    public <T> T unwrap(Class<T> cls) {
        checkOpen();
        if (cls.isInstance(this)) {
            return cls.cast(this);
        }
        throw new PersistenceException("Unsupported unwrap type " + cls.getName());
    }

    /** A legacy temporal bound as the {@code java.sql} type its {@link jakarta.persistence.TemporalType} names. */
    static Object temporal(Object value, jakarta.persistence.TemporalType type) {
        if (value == null) {
            return null;
        }
        long millis = value instanceof Calendar calendar ? calendar.getTimeInMillis() : ((Date) value).getTime();
        return switch (type) {
            case DATE -> new java.sql.Date(millis);
            case TIME -> new java.sql.Time(millis);
            case TIMESTAMP -> new java.sql.Timestamp(millis);
        };
    }
}
