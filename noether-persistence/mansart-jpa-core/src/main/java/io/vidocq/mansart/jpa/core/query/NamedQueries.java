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

import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.NamedQueryModel;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQueryReference;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The named queries of a persistence unit (§3.11, §10.4.1): those its managed classes declare, and those added with
 * {@code EntityManagerFactory.addNamedQuery}, which keep the settings their query had then. Shared by the entity
 * managers of a factory: a concurrent map, nothing locked.
 */
public final class NamedQueries {

    /** What a named query is created from. Settings left {@code null} keep the defaults of the entity manager. */
    record Definition(String name, String query, boolean nativeQuery, Class<?> resultClass, String resultSetMapping, LockModeType lockMode,
            Map<String, Object> hints, Integer maxResults, Integer firstResult, FlushModeType flushMode,
            JpqlQuery.CriteriaDefinition criteria) {
    }

    /** A named query as {@code getNamedQueries} hands it over (3.2). */
    private record Reference<R>(String name, Class<? extends R> resultType, Map<String, Object> hints) implements TypedQueryReference<R> {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public Class<? extends R> getResultType() {
            return resultType;
        }

        @Override
        public Map<String, Object> getHints() {
            return hints;
        }
    }

    private final MappedUnit unit;
    private final Map<String, Definition> byName = new ConcurrentHashMap<>();

    public NamedQueries(MappedUnit unit) {
        this.unit = unit;
        for (NamedQueryModel query : unit.model().namedQueries()) {
            byName.put(query.name(), new Definition(query.name(), query.query(), query.nativeQuery(), query.resultClass(),
                query.resultSetMapping(), LockModeType.valueOf(query.lockMode()), Map.copyOf(query.hints()), null, null, null, null));
        }
    }

    /** §7.? addNamedQuery: {@code query} as it is now; a query of the same name is replaced. */
    public void add(String name, Query query) {
        if (name == null) {
            throw new IllegalArgumentException("A named query needs a name");
        }
        Definition definition = switch (query) {
            case JpqlQuery<?> jpql -> new Definition(name, jpql.queryString(), false, jpql.resultClass(), null, jpql.getLockMode(),
                Map.copyOf(jpql.getHints()), jpql.getMaxResults(), jpql.getFirstResult(), jpql.getFlushMode(), jpql.criteriaDefinition());
            case NativeQuery sql -> new Definition(name, sql.sqlString(), true, null, null, LockModeType.NONE,
                Map.copyOf(sql.getHints()), sql.getMaxResults(), sql.getFirstResult(), sql.getFlushMode(), null);
            default -> throw new IllegalArgumentException("A query of " + query.getClass().getName() + " cannot be named");
        };
        byName.put(name, definition);
    }

    /** The query named {@code name}, typed {@code resultClass} if not {@code null}; an unknown name is an {@link IllegalArgumentException}. */
    public <T> JpqlQueryOrNative<T> create(String name, Class<T> resultClass, FlushModeType flushMode, QueryRuntime runtime,
            NativeQuery.Executor executor) {
        Definition definition = byName.get(name);
        if (definition == null) {
            throw new IllegalArgumentException("The persistence unit has no named query " + name + " (§3.11)");
        }
        if (definition.nativeQuery()) {
            if (resultClass != null && definition.resultClass() != null && !resultClass.isAssignableFrom(definition.resultClass())) {
                throw new IllegalArgumentException("The result type " + resultClass.getName() + " does not match named query " + name);
            }
            Class<?> type = resultClass != null ? resultClass : definition.resultClass();
            NativeQuery query = new NativeQuery(definition.query(), flushMode, executor, runtime, type, definition.resultSetMapping(),
                runtime::isOpen);
            definition.hints().forEach(query::setHint);
            settings(definition, query);
            return new JpqlQueryOrNative<>(null, query);
        }
        Class<T> type = resultClass;
        if (type == null && definition.criteria() != null) type = originalResultType(definition.resultClass());
        JpqlQuery<T> query = definition.criteria() == null ? new JpqlQuery<>(definition.query(), type, flushMode, runtime)
            : new JpqlQuery<>(definition.criteria().statement(), type, flushMode, runtime,
                definition.criteria().parameters(), definition.criteria().selections());
        definition.hints().forEach(query::setHint);
        if (definition.lockMode() != LockModeType.NONE) {
            query.setLockMode(definition.lockMode());
        }
        settings(definition, query);
        return new JpqlQueryOrNative<>(query, null);
    }

    @SuppressWarnings("unchecked") // An untyped named Criteria query retains the original query's reified result class.
    private static <T> Class<T> originalResultType(Class<?> type) {
        return (Class<T>) type;
    }

    private static void settings(Definition definition, Query query) {
        if (definition.maxResults() != null) {
            query.setMaxResults(definition.maxResults());
        }
        if (definition.firstResult() != null) {
            query.setFirstResult(definition.firstResult());
        }
        if (definition.flushMode() != null) {
            query.setFlushMode(definition.flushMode());
        }
    }

    /** A created named query: of the query language, or native. */
    public record JpqlQueryOrNative<T>(JpqlQuery<T> jpql, NativeQuery sql) {
        public Query query() {
            return jpql != null ? jpql : sql;
        }
    }

    /**
     * 3.2 getNamedQueries: the named queries whose results are of {@code resultType} — their {@code resultClass}, or
     * the type their select gives; those whose type cannot be told are listed for {@code Object} only.
     */
    @SuppressWarnings("unchecked")
    public <R> Map<String, TypedQueryReference<R>> references(Class<R> resultType) {
        Map<String, TypedQueryReference<R>> references = new LinkedHashMap<>();
        for (Definition definition : byName.values()) {
            Class<?> type = definition.resultClass() != null ? definition.resultClass() : definition.nativeQuery() ? null
                : JpqlQuery.resultType(definition.query(), unit);
            type = type == null ? Object.class : MethodType.methodType(type).wrap().returnType();
            if (resultType == Object.class || resultType.isAssignableFrom(type)) {
                references.put(definition.name(), new Reference<>(definition.name(), (Class<? extends R>) type, definition.hints()));
            }
        }
        return references;
    }
}
