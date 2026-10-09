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

    private final Map<String, Object> hints = new LinkedHashMap<>();
    private FlushModeType flushMode;
    private int maxResults = Integer.MAX_VALUE;
    private int firstResult;
    private Integer timeout;
    private CacheRetrieveMode cacheRetrieveMode = CacheRetrieveMode.USE;
    private CacheStoreMode cacheStoreMode = CacheStoreMode.USE;
    private final BooleanSupplier open;

    /** @param open whether the entity manager that created the query is still open */
    AbstractQuery(FlushModeType flushMode, BooleanSupplier open) {
        this.flushMode = flushMode;
        this.open = open;
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
