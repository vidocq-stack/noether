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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.query;

import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.query.jpql.Ast;
import io.vidocq.noether.jpa.core.query.jpql.Parser;
import io.vidocq.noether.jpa.dialect.Dialect;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Function;

/**
 * The query strings an entity manager factory has seen, parsed and translated: a string created again reuses its
 * parse tree, its translation and the SQL a dialect rendered for it, all immutable and independent of any session.
 *
 * <p>Only what is the same for every execution is kept. A translation is a function of the statement, the mapped
 * unit, the translation mode and the parameter values the translator asks for — it asks for the ones that shape the
 * SQL, such as a collection that {@code IN} expands. A translation that asked for none is reused; one that asked is
 * made again for each execution, as is a page, a lock or an entity graph, which are translated by their query.
 *
 * <p>Bounded: beyond {@link #LIMIT} distinct strings, a new one is parsed and translated for its query alone, so that
 * an application concatenating values into its queries cannot grow the factory without end.
 */
public final class QueryPlans {

    /** How many distinct query strings a factory keeps. */
    static final int LIMIT = 1024;

    private final ConcurrentHashMap<String, Parsed> parsed = new ConcurrentHashMap<>();

    /**
     * The parse of {@code jpql}, shared by every query created from that string.
     *
     * @throws IllegalArgumentException if the string is not a valid query; nothing is kept of it
     */
    Parsed parse(String jpql) {
        Parsed known = parsed.get(jpql);
        if (known != null) return known;
        Parsed fresh = new Parsed(Parser.parse(jpql));
        if (parsed.size() >= LIMIT) return fresh;
        Parsed raced = parsed.putIfAbsent(jpql, fresh);
        return raced != null ? raced : fresh;
    }

    /** A parsed query string and the translations of it that no parameter value shaped. */
    static final class Parsed {
        private final Ast.Statement statement;
        /** By translation mode: validation or execution, hydrating or not, graph rows or not. */
        private final AtomicReferenceArray<Plan> plans = new AtomicReferenceArray<>(8);

        private Parsed(Ast.Statement statement) {
            this.statement = statement;
        }

        Ast.Statement statement() {
            return statement;
        }

        /**
         * The translation of the statement without a page, as
         * {@link Translator#translate(Ast.Statement, MappedUnit, Function, Integer, Integer, boolean, boolean)} makes
         * it; the same {@link Plan} for every call in the same mode as long as the translator asks {@code bindings}
         * for no value.
         *
         * @param bindings the values bound to the parameters; {@code null} to validate, at {@code createQuery}
         */
        Plan plan(MappedUnit unit, Function<Ast.Parameter, Object> bindings, boolean hydrate, boolean graph) {
            int mode = (bindings == null ? 0 : 1) | (hydrate ? 2 : 0) | (graph ? 4 : 0);
            Plan known = plans.get(mode);
            if (known != null) return known;
            boolean[] asked = {false};
            Function<Ast.Parameter, Object> watched = bindings == null ? null : parameter -> {
                asked[0] = true;
                return bindings.apply(parameter);
            };
            Plan fresh = new Plan(Translator.translate(statement, unit, watched, null, null, hydrate, graph));
            if (asked[0]) return fresh; // shaped by the values of this execution
            return plans.compareAndSet(mode, null, fresh) ? fresh : plans.get(mode);
        }
    }

    /** A translation and, once a dialect rendered it, its SQL. */
    static final class Plan {
        private final Compiled compiled;
        private volatile Rendering rendering;

        private Plan(Compiled compiled) {
            this.compiled = compiled;
        }

        Compiled compiled() {
            return compiled;
        }

        /** {@link Dialect#renderQuery} of the translated statement, rendered once per dialect. */
        Dialect.Rendered rendered(Dialect dialect) {
            Rendering known = rendering;
            if (known == null || known.dialect() != dialect) {
                known = new Rendering(dialect, dialect.renderQuery(compiled.sql()));
                rendering = known;
            }
            return known.rendered();
        }
    }

    private record Rendering(Dialect dialect, Dialect.Rendered rendered) {
    }
}
