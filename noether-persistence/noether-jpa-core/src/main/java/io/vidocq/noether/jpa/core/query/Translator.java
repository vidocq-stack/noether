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

import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.mapping.CollectionMapping;
import io.vidocq.noether.jpa.core.mapping.ElementCollectionMapping;
import io.vidocq.noether.jpa.core.mapping.EntityStatements;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.mapping.IndexMapping;
import io.vidocq.noether.jpa.core.model.CollectionIndex;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.BasicAttribute;
import io.vidocq.noether.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.noether.jpa.core.model.EmbeddedAttribute;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.model.EmbeddableModel;
import io.vidocq.noether.jpa.core.query.jpql.Ast;
import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.core.session.NotYet;
import io.vidocq.noether.jpa.dialect.sql.Expression;
import io.vidocq.noether.jpa.dialect.sql.Expression.Binary;
import io.vidocq.noether.jpa.dialect.sql.Expression.Operator;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import io.vidocq.noether.jpa.dialect.sql.DeleteQuery;
import io.vidocq.noether.jpa.dialect.sql.Query;
import io.vidocq.noether.jpa.dialect.sql.SelectStatement;
import io.vidocq.noether.jpa.dialect.sql.SetQuery;
import io.vidocq.noether.jpa.dialect.sql.UpdateQuery;
import io.vidocq.noether.jpa.dialect.sql.Table;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Translates a select query (§4) to the SQL AST against the entity model: identification variables become table aliases,
 * paths columns, single-valued path navigation inner joins (§4.4.4), declared joins joins (§4.4.5), and entities their
 * key columns — compared key part by key part, read back as managed instances. A subquery is translated by a translator
 * of its own that sees the variables of the enclosing one (§4.5.10). One translator per translation: it holds the aliases
 * and joins it made.
 */
final class Translator {
    private boolean inJoinCondition;

    /**
     * An identification variable: the alias of its table, the FROM item it joins to, the translator that declared it;
     * the entity it ranges over, or the element collection whose elements it ranges over (§4.4.5).
     */
    private record Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element,
            Expression position, IndexMapping index, String indexAlias) {
        Variable(MappedEntity type, String alias, int root, Translator owner) {
            this(type, alias, root, owner, null, null, null, alias);
        }

        Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element) {
            this(type, alias, root, owner, element, null, element == null ? null : element.index(), alias);
        }

        Variable(MappedEntity type, String alias, int root, Translator owner, ElementCollectionMapping element,
                Expression position) {
            this(type, alias, root, owner, element, position, null, alias);
        }
    }

    /** What an expression denotes. */
    private sealed interface Value permits Scalar, Entity, Constant, Embedded, MapEntry {
    }

    /** A SQL value; {@code binder} binds and reads it when it is an attribute, {@code type} its Java type if known. */
    private record Scalar(Expression sql, ValueBinder binder, Class<?> type) implements Value {
    }

    /** An entity of {@code type}: the SQL of its key columns, in the order of its key, and their binders. */
    private record Entity(MappedEntity type, List<Expression> key, List<ValueBinder> binders) implements Value {
    }

    /** A literal the SQL does not write — an enum constant, a date (§4.6.1) — bound like what it meets. */
    private record Constant(Object value) implements Value {
    }

    /** The basic columns of one embeddable value and their paths through its generated access. */
    private record Embedded(EmbeddableModel model, ManagedAccess access, List<Scalar> fields, List<int[]> paths) implements Value {
    }

    private record MapEntry(Value key, Value value) implements Value {
    }

    private final MappedUnit unit;
    private final Function<Ast.Parameter, Object> bindings;
    private final Translator parent;
    private final int[] aliases;
    private final Map<String, Variable> variables = new HashMap<>();
    private final Map<String, Value> resultVariables = new HashMap<>();
    private final Map<String, Variable> navigations = new HashMap<>();
    private final List<Table> roots = new ArrayList<>();
    private final List<String> rootAliases = new ArrayList<>();
    private final List<List<Query.Join>> joins = new ArrayList<>();
    private final List<Expression> correlations = new ArrayList<>();
    private final Map<String, String> tableAliases = new HashMap<>();
    private final Map<Ast.Join, Variable> declaredJoins = new java.util.IdentityHashMap<>();
    private final List<Compiled.Hydration> hydrations = new ArrayList<>();
    private Compiled.CollectionHydration collectionHydration;
    private final java.util.Set<String> collectionAliases = new java.util.HashSet<>();
    private boolean collectionFetch;
    private boolean hydrating;
    /** Whether the statement is graph-synthesized: only then may root/element to-ones accompany a collection. */
    private boolean graphRows;
    private boolean selecting;

    /** @param bindings the values bound to the parameters, to expand those holding collections; {@code null} to validate */
    private Translator(MappedUnit unit, Function<Ast.Parameter, Object> bindings, Translator parent) {
        this.unit = unit;
        this.bindings = bindings;
        this.parent = parent;
        this.aliases = parent == null ? new int[1] : parent.aliases;
    }

    /**
     * Translates {@code select}. Without {@code bindings} (at {@code createQuery}) a collection-valued parameter counts
     * one element: the translation checks the query; with them, it is the one executed.
     */
    static Compiled translate(Ast.Statement statement, MappedUnit unit, Function<Ast.Parameter, Object> bindings, Integer offset,
            Integer limit) {
        return translate(statement, unit, bindings, offset, limit, false);
    }

    /**
     * Lowers query graph to-ones to the existing fetch-join pipeline. No semantic query clause is changed:
     * graph joins are LEFT joins, and shapes not admitting D11 hydration keep their ordinary follow-up fetch plan.
     * FETCH only requests explicit nodes here (the loader may still overfetch eager state); LOAD additionally
     * requests mapped eager to-ones. One root collection may instead reuse D12's complete row collector, with the
     * to-one subgraphs of its entity elements joined from the collection alias (D17), alongside root to-one
     * branches (D18). Nested collection subgraphs, multiple collections and unsupported types remain loader work.
     */
    static Ast.Statement graphSelect(Ast.Statement statement, MappedUnit unit, FetchPlan graph, boolean load) {
        if (!(statement instanceof Ast.Select select) || select.from().size() != 1
                || select.items().size() != 1 || !select.groupBy().isEmpty() || select.having() != null
                || !(select.items().getFirst().expression() instanceof Ast.Path path)
                || path.treatEntity() != null || path.segments().size() != 1) return statement;
        Ast.Range range = select.from().getFirst();
        if (range.entity() == null || !range.variable().equalsIgnoreCase(path.segments().getFirst())
                || range.joins().stream().anyMatch(j -> j.on() != null || j.path().treatEntity() != null)) return statement;
        Translator planner = new Translator(unit, null, null);
        MappedEntity root = planner.entityNamed(range.entity());
        if (!planner.hydratable(root)) return statement;
        String collection = planner.graphCollection(root, graph);
        if (collection != null && range.joins().isEmpty()) {
            String alias = "_noether_graph_collection";
            while (statement.toString().toLowerCase(Locale.ROOT).contains(alias)) alias += "_";
            Ast.Select execution = new Ast.Select(select.distinct(), select.items(),
                List.of(new Ast.Range(range.entity(), range.collection(), range.variable(),
                    List.of(new Ast.Join(Ast.Join.Kind.LEFT, true,
                        new Ast.Path(List.of(range.variable(), collection)), alias, null)))),
                select.where(), select.groupBy(), select.having(), select.orderBy());
            FetchPlan elements = graph.attributes().get(collection);
            List<Ast.Join> joins = new ArrayList<>(execution.from().getFirst().joins());
            String occupied = statement.toString().toLowerCase(Locale.ROOT);
            int[] next = new int[1];
            if (root.model().attribute(collection).orElse(null) instanceof AssociationAttribute association
                    && (!elements.isEmpty() || load)) {
                java.util.Set<Class<?>> eagerPath = new java.util.HashSet<>(List.of(root.model().javaType()));
                planner.graphJoins(unit.entity(association.targetEntity()).orElseThrow(), alias, elements, load, eagerPath,
                    joins, occupied, next);
            }
            planner.graphJoins(root, range.variable(), graph, load, new java.util.HashSet<>(), joins, occupied, next);
            Ast.Select subgraphs = new Ast.Select(select.distinct(), select.items(),
                List.of(new Ast.Range(range.entity(), range.collection(), range.variable(), joins)),
                select.where(), select.groupBy(), select.having(), select.orderBy());
            if (translate(subgraphs, unit, null, null, null, true, true).collection() != null) return subgraphs;
            // Never add a multiplying join unless the existing collector can assemble this exact shape.
            return translate(execution, unit, null, null, null, true, true).collection() != null ? execution : statement;
        }
        if (select.distinct()) return statement;
        List<Ast.Join> joins = new ArrayList<>(range.joins());
        planner.graphJoins(root, range.variable(), graph, load, new java.util.HashSet<>(), joins,
            statement.toString().toLowerCase(Locale.ROOT), new int[1]);
        if (joins.equals(range.joins())) return statement;
        return new Ast.Select(select.distinct(), select.items(),
            List.of(new Ast.Range(range.entity(), range.collection(), range.variable(), joins)),
            select.where(), select.groupBy(), select.having(), select.orderBy());
    }

    private String graphCollection(MappedEntity root, FetchPlan graph) {
        String collection = null;
        for (var node : graph.attributes().entrySet()) {
            var attribute = root.model().attribute(node.getKey()).orElse(null);
            if (attribute instanceof AssociationAttribute association && association.singleValued()) {
                if (!toOneSubgraph(unit.entity(association.targetEntity()).orElse(null), node.getValue())) return null;
                continue;
            }
            if (attribute instanceof ElementCollectionAttribute
                    || attribute instanceof AssociationAttribute association && !association.singleValued()) {
                if (collection != null) return null;
                collection = node.getKey();
                // D17: an entity collection's subgraph may only request to-one chains of hydratable entities.
                if (!node.getValue().isEmpty() && (!(attribute instanceof AssociationAttribute association)
                        || !toOneSubgraph(unit.entity(association.targetEntity()).orElse(null), node.getValue()))) return null;
            } else if (!node.getValue().isEmpty()) return null;
        }
        return collection;
    }

    /**
     * D19/D20: independent D16-D18 collectors in at most two collection stages. Templates have no user
     * predicates: execution constrains roots to the SQL-selected page, then groups distinct nested owners.
     */
    static List<List<Compiled>> splitGraph(Ast.Statement statement, MappedUnit unit, FetchPlan graph) {
        if (!(statement instanceof Ast.Select select) || select.from().size() != 1 || select.items().size() != 1
                || !select.from().getFirst().joins().isEmpty() || !select.groupBy().isEmpty() || select.having() != null
                || !(select.items().getFirst().expression() instanceof Ast.Path path) || path.treatEntity() != null
                || path.segments().size() != 1 || select.from().getFirst().entity() == null
                || !path.segments().getFirst().equalsIgnoreCase(select.from().getFirst().variable())) return List.of();
        Translator planner = new Translator(unit, null, null);
        MappedEntity root = planner.entityNamed(select.from().getFirst().entity());
        if (!planner.hydratable(root) || root.statements().keyColumns().size() > 512) return List.of();
        for (var node : graph.attributes().entrySet()) {
            if (root.model().attribute(node.getKey()).orElse(null) instanceof AssociationAttribute association
                    && association.singleValued()
                    && !planner.toOneSubgraph(unit.entity(association.targetEntity()).orElse(null), node.getValue())) {
                return planner.rootToOneCollections(root, graph);
            }
        }
        Map<String, FetchPlan> collections = new java.util.LinkedHashMap<>();
        FetchPlan branches = new FetchPlan();
        for (var node : graph.attributes().entrySet()) {
            var attribute = root.model().attribute(node.getKey()).orElse(null);
            if (attribute instanceof ElementCollectionAttribute
                    || attribute instanceof AssociationAttribute a && !a.singleValued()) {
                collections.put(node.getKey(), node.getValue());
            } else branches.attribute(node.getKey()).merge(node.getValue());
        }
        Map<MappedEntity, FetchPlan> nested = new java.util.LinkedHashMap<>();
        Map<String, FetchPlan> pruned = new java.util.LinkedHashMap<>();
        for (var collection : collections.entrySet()) {
            FetchPlan elements = new FetchPlan();
            var attribute = root.model().attribute(collection.getKey()).orElseThrow();
            if (attribute instanceof AssociationAttribute association) {
                if (!planner.nestedCollections(unit.entity(association.targetEntity()).orElse(null),
                        collection.getValue(), elements, nested, new java.util.HashSet<>())) return List.of();
            } else elements.merge(collection.getValue());
            pruned.put(collection.getKey(), elements);
        }
        if (collections.size() < 2 && nested.isEmpty()) return List.of();
        collections = pruned;
        // FETCH may overfetch mapped eager collections; never resolve those per owner during staged hydration.
        for (var attribute : root.model().attributes()) {
            if (attribute instanceof ElementCollectionAttribute e && e.fetch() == jakarta.persistence.FetchType.EAGER
                    || attribute instanceof AssociationAttribute a && !a.singleValued()
                        && a.fetch() == jakarta.persistence.FetchType.EAGER) {
                collections.putIfAbsent(attribute.name(), new FetchPlan());
            }
        }
        List<Compiled> result = planner.collectionCollectors(root, collections, branches, nested);
        if (result.isEmpty()) return List.of();
        List<Compiled> second = new ArrayList<>();
        for (var owner : nested.entrySet()) {
            Map<String, FetchPlan> leaves = new java.util.LinkedHashMap<>();
            FetchPlan toOnes = new FetchPlan();
            for (var node : owner.getValue().attributes().entrySet()) {
                var attribute = owner.getKey().model().attribute(node.getKey()).orElse(null);
                if (attribute instanceof ElementCollectionAttribute
                        || attribute instanceof AssociationAttribute a && !a.singleValued()) leaves.put(node.getKey(), node.getValue());
                else toOnes.attribute(node.getKey()).merge(node.getValue());
            }
            List<Compiled> compiled = planner.collectionCollectors(owner.getKey(), leaves, toOnes, Map.of());
            if (compiled.isEmpty()) return List.of();
            second.addAll(compiled);
        }
        return second.isEmpty() ? List.of(result) : List.of(result, List.copyOf(second));
    }

    /**
     * D21: seed finite root to-one paths, then reuse the collection collectors at each of the two
     * collection depths. A collection requested at both depths is read once, after all its owners are known.
     */
    private List<List<Compiled>> rootToOneCollections(MappedEntity root, FetchPlan graph) {
        List<Map<MappedEntity, FetchPlan>> owners = List.of(new java.util.LinkedHashMap<>(), new java.util.LinkedHashMap<>());
        FetchPlan seed = new FetchPlan();
        if (!stagedCollections(root, graph, seed, owners, 0, new java.util.HashSet<>())) return List.of();
        if (owners.stream().allMatch(Map::isEmpty)) return List.of();
        for (var later : owners.get(1).entrySet()) {
            FetchPlan earlier = owners.get(0).get(later.getKey());
            if (earlier == null) continue;
            // Move compatible requests together; no shared owner/collection is read once per root branch.
            later.getValue().merge(earlier);
            owners.get(0).remove(later.getKey());
        }
        Map<MappedEntity, FetchPlan> scheduled = new java.util.LinkedHashMap<>();
        owners.forEach(stage -> stage.forEach((type, plan) -> scheduled.computeIfAbsent(type, _ -> new FetchPlan()).merge(plan)));
        Compiled initial = translate(graphSelect(graphRoot(root), unit, seed, true), unit, null, null, null, true, true);
        if (initial.hydrations().isEmpty()) return List.of();
        List<List<Compiled>> stages = new ArrayList<>();
        stages.add(List.of(initial));
        for (var stage : owners) {
            List<Compiled> collectors = new ArrayList<>();
            for (var owner : stage.entrySet()) {
                Map<String, FetchPlan> collections = new java.util.LinkedHashMap<>();
                FetchPlan branches = new FetchPlan();
                for (var node : owner.getValue().attributes().entrySet()) {
                    var attribute = owner.getKey().model().attribute(node.getKey()).orElse(null);
                    if (attribute instanceof ElementCollectionAttribute
                            || attribute instanceof AssociationAttribute a && !a.singleValued()) {
                        collections.put(node.getKey(), node.getValue());
                    } else branches.attribute(node.getKey()).merge(node.getValue());
                }
                List<Compiled> compiled = collectionCollectors(owner.getKey(), collections, branches, scheduled);
                if (compiled.isEmpty()) return List.of();
                collectors.addAll(compiled);
            }
            if (!collectors.isEmpty()) stages.add(List.copyOf(collectors));
        }
        return List.copyOf(stages);
    }

    /** Separates collection edges from their finite LEFT to-one paths without changing the caller's graph. */
    private boolean stagedCollections(MappedEntity source, FetchPlan requested, FetchPlan pruned,
            List<Map<MappedEntity, FetchPlan>> owners, int depth, java.util.Set<Class<?>> eagerPath) {
        if (!hydratable(source) || source.statements().keyColumns().size() > 512) return false;
        if (!requested.isEmpty() && eagerPath.contains(source.model().javaType())) return false;
        FetchPlan expanded = new FetchPlan();
        expanded.merge(requested);
        boolean expand = eagerPath.add(source.model().javaType());
        if (expand) {
            for (var attribute : source.model().attributes()) {
                if (attribute instanceof ElementCollectionAttribute e && e.fetch() == jakarta.persistence.FetchType.EAGER
                        || attribute instanceof AssociationAttribute a && a.fetch() == jakarta.persistence.FetchType.EAGER) {
                    expanded.attribute(attribute.name());
                }
            }
        }
        FetchPlan collections = new FetchPlan();
        for (var node : expanded.attributes().entrySet()) {
            var attribute = source.model().attribute(node.getKey()).orElse(null);
            if (attribute instanceof ElementCollectionAttribute
                    || attribute instanceof AssociationAttribute a && !a.singleValued()) {
                if (depth == owners.size()) return false;
                FetchPlan elements = collections.attribute(node.getKey());
                if (attribute instanceof AssociationAttribute a) {
                    if (!stagedCollections(unit.entity(a.targetEntity()).orElse(null), node.getValue(), elements,
                            owners, depth + 1, eagerPath)) return false;
                } else if (!node.getValue().isEmpty()) return false;
            } else if (attribute instanceof AssociationAttribute a) {
                if (!stagedCollections(unit.entity(a.targetEntity()).orElse(null), node.getValue(),
                        pruned.attribute(node.getKey()), owners, depth, eagerPath)) return false;
            } else {
                if (attribute == null || !node.getValue().isEmpty()) return false;
                pruned.attribute(node.getKey());
            }
        }
        if (!collections.isEmpty()) {
            FetchPlan merged = owners.get(depth).computeIfAbsent(source, _ -> new FetchPlan());
            merged.merge(collections);
            merged.merge(pruned);
        }
        if (expand) eagerPath.remove(source.model().javaType());
        return true;
    }

    /** Cuts the second collection edge out of the first collector while retaining its joined to-one owners. */
    private boolean nestedCollections(MappedEntity source, FetchPlan requested, FetchPlan pruned,
            Map<MappedEntity, FetchPlan> nested, java.util.Set<Class<?>> eagerPath) {
        if (!hydratable(source) || source.statements().keyColumns().size() > 512) return false;
        FetchPlan expanded = new FetchPlan();
        expanded.merge(requested);
        boolean expand = eagerPath.add(source.model().javaType());
        if (expand) {
            for (var attribute : source.model().attributes()) {
                if (attribute instanceof ElementCollectionAttribute e && e.fetch() == jakarta.persistence.FetchType.EAGER
                        || attribute instanceof AssociationAttribute a && a.fetch() == jakarta.persistence.FetchType.EAGER) {
                    expanded.attribute(attribute.name());
                }
            }
        }
        FetchPlan leaves = new FetchPlan();
        for (var node : expanded.attributes().entrySet()) {
            var attribute = source.model().attribute(node.getKey()).orElse(null);
            if (attribute instanceof ElementCollectionAttribute
                    || attribute instanceof AssociationAttribute a && !a.singleValued()) {
                if (attribute instanceof AssociationAttribute a) {
                    if (!toOneSubgraph(unit.entity(a.targetEntity()).orElse(null), node.getValue())) return false;
                } else if (!node.getValue().isEmpty()) return false;
                leaves.attribute(node.getKey()).merge(node.getValue());
            } else if (attribute instanceof AssociationAttribute a) {
                if (!nestedCollections(unit.entity(a.targetEntity()).orElse(null), node.getValue(),
                        pruned.attribute(node.getKey()), nested, eagerPath)) return false;
            } else {
                if (attribute == null || !node.getValue().isEmpty()) return false;
                pruned.attribute(node.getKey());
            }
        }
        if (!leaves.isEmpty()) {
            FetchPlan merged = nested.computeIfAbsent(source, _ -> new FetchPlan());
            merged.merge(leaves);
            merged.merge(pruned);
        }
        if (expand) eagerPath.remove(source.model().javaType());
        return true;
    }

    private List<Compiled> collectionCollectors(MappedEntity root, Map<String, FetchPlan> collections,
            FetchPlan branches, Map<MappedEntity, FetchPlan> nested) {
        Ast.Select owners = graphRoot(root);
        List<Compiled> result = new ArrayList<>();
        for (var collection : collections.entrySet()) {
            FetchPlan branch = new FetchPlan();
            branch.merge(branches);
            branch.attribute(collection.getKey()).merge(collection.getValue());
            if (!collection.getKey().equals(graphCollection(root, branch))) return List.of();
            // Permitted FETCH overfetch keeps mapped eager to-ones in the collectors, not per-owner reads.
            Ast.Statement execution = graphSelect(owners, unit, branch, true);
            Compiled compiled = translate(execution, unit, null, null, null, true, true);
            if (compiled.collection() == null) return List.of();
            for (Compiled.Hydration hydration : compiled.hydrations()) {
                for (var attribute : hydration.type().model().attributes()) {
                    if (attribute instanceof AssociationAttribute a && a.fetch() == jakarta.persistence.FetchType.EAGER) {
                        if (a.singleValued() && !hydratable(unit.entity(a.targetEntity()).orElse(null))) return List.of();
                        if (!a.singleValued() && hydration.type() != root
                                && !nested.getOrDefault(hydration.type(), new FetchPlan()).attributes().containsKey(a.name())) return List.of();
                    } else if (attribute instanceof ElementCollectionAttribute e
                            && e.fetch() == jakarta.persistence.FetchType.EAGER && hydration.type() != root
                            && !nested.getOrDefault(hydration.type(), new FetchPlan()).attributes().containsKey(e.name())) return List.of();
                }
            }
            result.add(compiled);
        }
        return List.copyOf(result);
    }

    static Ast.Select graphRoot(MappedEntity type) {
        return new Ast.Select(false, List.of(new Ast.Item(new Ast.Path(List.of("this")), null)),
            List.of(new Ast.Range(type.model().entityName(), null, "this", List.of())), null, List.of(), null, List.of());
    }

    /** Restricts an existing collector with D13's bounded complete owner-key tuples. */
    static Compiled graphOwners(Compiled compiled, List<Object> ids) {
        Query query = (Query) compiled.sql();
        Compiled.EntityItem root = (Compiled.EntityItem) compiled.items().getFirst();
        MappedEntity type = root.type();
        Expression predicate = null;
        if (root.keyBinders().size() == 1) {
            List<Expression> keys = ids.stream().map(id -> (Expression) new Expression.Parameter(
                Compiled.Slot.constant(type.statements().keyValues(id)[0], root.keyBinders().getFirst(), null))).toList();
            predicate = new Expression.In(query.select().getFirst(), keys, false);
        } else {
            for (Object id : ids) {
                Object[] values = type.statements().keyValues(id);
                Expression tuple = null;
                for (int i = 0; i < values.length; i++) {
                    Expression equal = new Binary(query.select().get(i), Operator.EQ,
                        new Expression.Parameter(Compiled.Slot.constant(values[i], root.keyBinders().get(i), null)));
                    tuple = tuple == null ? equal : new Binary(tuple, Operator.AND, equal);
                }
                predicate = predicate == null ? tuple : new Binary(predicate, Operator.OR, tuple);
            }
        }
        Expression where = query.where() == null ? predicate : new Binary(query.where(), Operator.AND, predicate);
        return new Compiled(new Query(false, query.select(), query.from(), where, query.groupBy(),
            query.having(), query.orderBy(), null, null), compiled.items(), compiled.resultType(), compiled.slots(),
            compiled.mutations(), compiled.hydrations(), compiled.collection(), compiled.collectionFetch());
    }

    /** Whether every node is a basic/embedded leaf or a to-one of a hydratable entity with such a subgraph. */
    private boolean toOneSubgraph(MappedEntity source, FetchPlan plan) {
        if (!hydratable(source)) return false;
        for (var node : plan.attributes().entrySet()) {
            var attribute = source.model().attribute(node.getKey()).orElse(null);
            if (attribute instanceof AssociationAttribute association && association.singleValued()) {
                if (!toOneSubgraph(unit.entity(association.targetEntity()).orElse(null), node.getValue())) return false;
            } else if (attribute == null || attribute instanceof ElementCollectionAttribute
                    || attribute instanceof AssociationAttribute || !node.getValue().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reuses graph selection/hydration for a primary-key lookup, not a query execution. The key expressions and
     * binders are the translated root's own (including composite keys); existing discriminator restrictions remain.
     * No hydration means ordinary find must handle this shape.
     */
    static Compiled graphFind(MappedUnit unit, MappedEntity type, Object id, FetchPlan graph, boolean load) {
        Ast.Select select = graphRoot(type);
        Ast.Statement execution = graphSelect(select, unit, graph, load);
        if (execution == select) return null;
        Compiled compiled = translate(execution, unit, null, null, null, true, true);
        if (compiled.hydrations().isEmpty() || !(compiled.sql() instanceof Query query)) return null;
        Compiled.EntityItem root = (Compiled.EntityItem) compiled.items().getFirst();
        Object[] keys = type.statements().keyValues(id);
        Expression where = query.where();
        for (int i = 0; i < keys.length; i++) {
            Expression equal = new Binary(query.select().get(i), Operator.EQ,
                new Expression.Parameter(Compiled.Slot.constant(keys[i], root.keyBinders().get(i), null)));
            where = where == null ? equal : new Binary(where, Operator.AND, equal);
        }
        return new Compiled(new Query(false, query.select(), query.from(), where, query.groupBy(),
            query.having(), query.orderBy(), null, null), compiled.items(), compiled.resultType(),
            compiled.slots(), compiled.mutations(), compiled.hydrations(), compiled.collection(), compiled.collectionFetch());
    }

    private void graphJoins(MappedEntity source, String variable, FetchPlan requested, boolean load,
            java.util.Set<Class<?>> eagerPath, List<Ast.Join> joins, String occupied, int[] next) {
        FetchPlan plan = new FetchPlan();
        plan.merge(requested);
        boolean expand = load && eagerPath.add(source.model().javaType());
        if (expand) {
            for (var attribute : source.model().attributes()) {
                if (attribute instanceof AssociationAttribute association && association.singleValued()
                        && association.fetch() == jakarta.persistence.FetchType.EAGER) plan.attribute(attribute.name());
            }
        }
        for (var node : plan.attributes().entrySet()) {
            if (!(source.model().attribute(node.getKey()).orElse(null) instanceof AssociationAttribute association)
                    || !association.singleValued()) continue;
            MappedEntity target = unit.entity(association.targetEntity()).orElse(null);
            if (!hydratable(target)) continue;
            Ast.Path path = new Ast.Path(List.of(variable, node.getKey()));
            int existing = -1;
            for (int i = 0; i < joins.size(); i++) {
                Ast.Join join = joins.get(i);
                if (join.fetch() && join.on() == null && join.path().treatEntity() == null
                        && join.path().segments().size() == 2
                        && join.path().segments().getFirst().equalsIgnoreCase(variable)
                        && join.path().segments().get(1).equals(node.getKey())) {
                    existing = i;
                    break;
                }
            }
            String alias = existing < 0 ? null : joins.get(existing).variable();
            if (alias == null) {
                do {
                    alias = "_noether_graph_" + next[0]++;
                } while (occupied.contains(alias));
                if (existing < 0) joins.add(new Ast.Join(Ast.Join.Kind.LEFT, true, path, alias, null));
                else {
                    Ast.Join join = joins.get(existing);
                    joins.set(existing, new Ast.Join(join.kind(), true, join.path(), alias, null));
                }
            }
            // An explicit finite subgraph may repeat a type; only automatic eager expansion is cycle guarded.
            graphJoins(target, alias, node.getValue(), load, eagerPath, joins, occupied, next);
        }
        if (expand) eagerPath.remove(source.model().javaType());
    }

    /**
     * Pages distinct owner identities in SQL, then lets the ordinary fetch plan load complete collections.
     * Only scalar root ordering is admitted: an element's ordering could give several distinct rows per owner.
     */
    static Compiled paginateCollection(Ast.Statement statement, MappedUnit unit, Function<Ast.Parameter, Object> bindings,
            Integer offset, Integer limit) {
        if (!(statement instanceof Ast.Select select) || select.from().size() != 1 || select.items().size() != 1
                || !select.groupBy().isEmpty() || select.having() != null
                || select.from().getFirst().entity() == null
                || !(select.items().getFirst().expression() instanceof Ast.Path root)
                || root.treatEntity() != null || root.segments().size() != 1
                || !root.segments().getFirst().equalsIgnoreCase(select.from().getFirst().variable())) {
            throw collectionPagination();
        }
        EntityModel owner = unit.model().entity(select.from().getFirst().entity()).orElseThrow();
        for (Ast.OrderItem order : select.orderBy()) {
            if (!(order.expression() instanceof Ast.Path path) || path.treatEntity() != null || path.segments().size() != 2
                    || !path.segments().getFirst().equalsIgnoreCase(root.segments().getFirst())
                    || !(owner.attribute(path.segments().get(1)).orElse(null) instanceof BasicAttribute)) {
                throw collectionPagination();
            }
        }
        Ast.Select owners = new Ast.Select(true, select.items(), select.from(), select.where(), List.of(), null, select.orderBy());
        Compiled compiled = translate(owners, unit, bindings, offset, limit, false);
        Query query = (Query) compiled.sql();
        List<Expression> columns = new ArrayList<>(query.select());
        for (Query.Order order : query.orderBy()) {
            if (!columns.contains(order.expression())) columns.add(order.expression());
        }
        Query sql = new Query(true, columns, query.from(), query.where(), List.of(), null, query.orderBy(), offset, limit);
        return new Compiled(sql, compiled.items(), compiled.resultType(), slots(sql.parameters()));
    }

    static jakarta.persistence.PersistenceException collectionPagination() {
        return new jakarta.persistence.PersistenceException("Pagination with a collection fetch requires one root entity,"
            + " no grouping or locking, and scalar root ordering; this shape cannot page complete owners in SQL (§3.11.1)");
    }

    /**
     * Like {@link #translate(Ast.Statement, MappedUnit, Function, Integer, Integer)}; with {@code hydrate}, a select
     * also reads the columns of the entities its eligible to-one fetch joins return with their owners
     * ({@link Compiled#hydrations()}).
     */
    static Compiled translate(Ast.Statement statement, MappedUnit unit, Function<Ast.Parameter, Object> bindings, Integer offset,
            Integer limit, boolean hydrate) {
        return translate(statement, unit, bindings, offset, limit, hydrate, false);
    }

    /**
     * Like {@link #translate(Ast.Statement, MappedUnit, Function, Integer, Integer, boolean)}; {@code graph} marks a
     * statement {@link #graphSelect} synthesized, whose collection elements may also hydrate their to-one joins.
     */
    static Compiled translate(Ast.Statement statement, MappedUnit unit, Function<Ast.Parameter, Object> bindings, Integer offset,
            Integer limit, boolean hydrate, boolean graph) {
        Translator translator = new Translator(unit, bindings, null);
        translator.hydrating = hydrate;
        translator.graphRows = graph;
        String bulkEntity = statement instanceof Ast.Update update ? update.entity()
            : statement instanceof Ast.Delete delete ? delete.entity() : null;
        if (bulkEntity != null && (unit.inheritance(translator.entityNamed(bulkEntity).model().javaType()).joined()
                || unit.inheritance(translator.entityNamed(bulkEntity).model().javaType()).tablePerClass()
                || translator.entityNamed(bulkEntity).statements().tables().size() > 1
                || translator.entityNamed(bulkEntity).statements().collections().stream()
                    .anyMatch(m -> m instanceof CollectionMapping.ForeignKey || m.association().singleValued()))) {
            return translator.joinedBulk(statement);
        }
        return switch (statement) {
            case Ast.Query query -> {
                List<Compiled.Item> items = new ArrayList<>();
                SelectStatement sql = translator.selectStatement(query, offset, limit, items);
                Class<?> resultType = items.size() > 1 ? Object[].class : type(items.getFirst());
                yield new Compiled(sql, List.copyOf(items), resultType == null ? null : boxed(resultType), slots(sql.parameters()),
                    List.of(), List.copyOf(translator.hydrations), translator.collectionHydration, translator.collectionFetch);
            }
            case Ast.Update update -> {
                UpdateQuery sql = translator.update(update);
                yield new Compiled(sql, List.of(), null, slots(sql.parameters()));
            }
            case Ast.Delete delete -> {
                DeleteQuery sql = translator.delete(delete);
                yield new Compiled(sql, List.of(), null, slots(sql.parameters()));
            }
        };
    }

    private SelectStatement selectStatement(Ast.Query query, Integer offset, Integer limit, List<Compiled.Item> items) {
        return switch (query) {
            case Ast.Select select -> query(select, offset, limit, items);
            case Ast.SetQuery setQuery -> setQuery(setQuery, offset, limit, items);
        };
    }

    private SetQuery setQuery(Ast.SetQuery setQuery, Integer offset, Integer limit, List<Compiled.Item> items) {
        List<Query> operands = new ArrayList<>();
        List<Compiled.Item> firstItems = null;
        for (Ast.Select operand : setQuery.operands()) {
            List<Compiled.Item> operandItems = new ArrayList<>();
            Translator translator = new Translator(unit, bindings, this);
            Query sql = translator.query(operand, null, null, operandItems);
            collectionFetch |= translator.collectionFetch;
            if (firstItems == null) {
                firstItems = operandItems;
                items.addAll(operandItems);
            } else {
                List<Compiled.Item> expectedItems = firstItems;
                if (operandItems.size() != expectedItems.size()
                        || java.util.stream.IntStream.range(0, expectedItems.size())
                            .anyMatch(i -> operandItems.get(i).width() != expectedItems.get(i).width())) {
                    throw new IllegalArgumentException("Every query in a set operation must select the same number of values");
                }
            }
            operands.add(sql);
        }
        Ast.Select first = setQuery.operands().getFirst();
        List<SetQuery.Order> orderBy = new ArrayList<>();
        for (Ast.OrderItem order : setQuery.orderBy()) {
            int position = orderPosition(first, order.expression());
            orderBy.add(new SetQuery.Order(position, order.descending(), order.nullsFirst()));
        }
        List<SetQuery.Operation> operations = setQuery.operations().stream()
            .map(operation -> new SetQuery.Operation(SetQuery.Operator.valueOf(operation.operator().name()), operation.all()))
            .toList();
        return new SetQuery(operands, operations, orderBy, offset, limit);
    }

    private static int orderPosition(Ast.Select select, Ast.Expr expression) {
        for (int i = 0; i < select.items().size(); i++) {
            Ast.Item item = select.items().get(i);
            if (item.expression().equals(expression)
                    || expression instanceof Ast.Path path && path.segments().size() == 1
                        && item.alias() != null && item.alias().equalsIgnoreCase(path.segments().getFirst())) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("The ORDER BY expression of a set operation must be selected");
    }

    private static List<Compiled.Slot> slots(List<Expression.Parameter> parameters) {
        return parameters.stream().map(p -> (Compiled.Slot) p.slot()).toList();
    }

    // ---- bulk statements (§4.10) --------------------------------------------------------------------------

    /** Captures qualifying identities and assignment values before changing any table (§4.10). */
    private Compiled joinedBulk(Ast.Statement statement) {
        Ast.Update update = statement instanceof Ast.Update u ? u : null;
        Ast.Delete delete = statement instanceof Ast.Delete d ? d : null;
        MappedEntity type = entityNamed(update != null ? update.entity() : delete.entity());
        Variable variable = root(table(type), type, null);
        define(update != null ? update.variable() : delete.variable(), variable);
        List<Expression> projected = new ArrayList<>(key(variable.alias(), type));
        List<Compiled.Item> items = new ArrayList<>();
        List<ValueBinder> readers = new ArrayList<>(type.statements().keyColumns().stream()
            .map(EntityStatements.Column::binder).toList());
        readers.forEach(b -> items.add(new Compiled.ValueItem(b, null)));
        Map<Integer, List<Integer>> assigned = new java.util.LinkedHashMap<>();
        Map<Integer, List<Identifier>> setColumns = new java.util.LinkedHashMap<>();
        Map<CollectionMapping.JoinTable, List<Integer>> tableReferences = new java.util.LinkedHashMap<>();
        if (update != null) {
            for (Ast.Assignment assignment : update.assignments()) {
                List<String> segments = assignment.path().segments();
                List<String> path = lookup(segments.getFirst()) == variable ? segments.subList(1, segments.size()) : segments;
                int index = attribute(variable, path.getFirst());
                AttributeModel attribute = type.model().attributes().get(index);
                List<EntityStatements.Column> columns;
                List<Expression> values;
                if (attribute instanceof AssociationAttribute association && association.singleValued()) {
                    var mapping = type.statements().collection(index).orElse(null);
                    if (mapping instanceof CollectionMapping.JoinTable table) {
                        Entity like = new Entity(entity(association.targetEntity()),
                            names(variable.alias(), table.targetColumns()), table.targetBinders());
                        values = assignment.value() instanceof Ast.Literal(Object v) && v == null
                            ? table.targetColumns().stream().<Expression>map(_ -> new Expression.Literal(null)).toList()
                            : sql(assignment.value() instanceof Ast.Parameter ? boundValue(assignment.value(), like) : value(assignment.value()));
                        List<Integer> positions = new ArrayList<>();
                        for (int k = 0; k < values.size(); k++) {
                            positions.add(projected.size());
                            projected.add(values.get(k));
                            readers.add(table.targetBinders().get(k));
                            items.add(new Compiled.ValueItem(table.targetBinders().get(k), null));
                        }
                        tableReferences.put(table, positions);
                        continue;
                    }
                    var reference = type.statements().reference(index).orElseThrow();
                    columns = Arrays.stream(reference.columns()).mapToObj(c -> type.statements().columns().get(c)).toList();
                    Entity like = new Entity(entity(association.targetEntity()), columns(variable, reference.columns()),
                        binders(type.statements(), reference.columns()));
                    values = assignment.value() instanceof Ast.Literal(Object v) && v == null
                        ? columns.stream().<Expression>map(_ -> new Expression.Literal(null)).toList()
                        : sql(assignment.value() instanceof Ast.Parameter ? boundValue(assignment.value(), like) : value(assignment.value()));
                } else {
                    Scalar lhs = scalar(path.size() > 1 ? embeddedPath(variable, index, path.subList(1, path.size()))
                        : attribute instanceof BasicAttribute basic ? basicValue(variable, index, basic) : null);
                    Expression.Column name = (Expression.Column) lhs.sql();
                    columns = List.of(type.statements().columns().stream().filter(c -> c.attribute() == index
                        && unit.queryColumn(type, c).equals(name.name()) && tableAlias(variable, c.table()).equals(name.alias())).findFirst().orElseThrow());
                    values = List.of(bound(assignment.value(), lhs).sql());
                }
                for (int c = 0; c < columns.size(); c++) {
                    EntityStatements.Column column = columns.get(c);
                    assigned.computeIfAbsent(column.table(), _ -> new ArrayList<>()).add(projected.size());
                    setColumns.computeIfAbsent(column.table(), _ -> new ArrayList<>()).add(column.name());
                    projected.add(values.get(c));
                    readers.add(column.binder());
                    items.add(new Compiled.ValueItem(column.binder(), null));
                }
            }
        }
        Ast.Expr predicate = update != null ? update.where() : delete.where();
        Expression where = restricted(predicate == null ? null : condition(predicate));
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        Query selection = new Query(false, projected, from, where, List.of(), null, List.of(), null, null);
        List<Integer> keyIndexes = java.util.stream.IntStream.range(0, type.statements().keyColumns().size()).boxed().toList();
        List<Compiled.Mutation> mutations = new ArrayList<>();
        if (update != null) {
            for (var entry : tableReferences.entrySet()) {
                var table = entry.getKey();
                mutations.add(new Compiled.Mutation(table.deleteOwner(), keyIndexes, table.ownerBinders()));
                List<Integer> values = new ArrayList<>(keyIndexes);
                values.addAll(entry.getValue());
                List<ValueBinder> binders = new ArrayList<>(table.ownerBinders());
                binders.addAll(table.targetBinders());
                mutations.add(new Compiled.Mutation(table.insert(), List.copyOf(values), List.copyOf(binders), entry.getValue()));
            }
            for (int t : assigned.keySet()) {
                List<Integer> values = new ArrayList<>(assigned.get(t));
                values.addAll(keyIndexes);
                var inheritance = unit.inheritance(type.model().javaType());
                var targets = inheritance.tablePerClass() ? inheritance.hierarchy().stream()
                    .filter(e -> !inheritance.abstractEntity(e) && type.model().javaType().isAssignableFrom(e.javaType()))
                    .map(e -> entity(e.javaType())).toList() : List.of(type);
                var updated = new java.util.HashSet<Table>();
                for (var target : targets) {
                    var sourceTable = type.statements().tables().get(t).select().table();
                    var table = t == 0 ? target.statements().tables().getFirst().select()
                        : target.statements().tables().stream().map(EntityStatements.TableStatements::select)
                            .filter(s -> s.table().equals(sourceTable)).findFirst().orElseThrow();
                    if (!updated.add(table.table())) continue;
                    mutations.add(new Compiled.Mutation(new io.vidocq.noether.jpa.dialect.sql.Update(table.table(),
                        setColumns.get(t), table.conditions()), List.copyOf(values), values.stream().map(readers::get).toList()));
                }
            }
        } else {
            var inheritance = unit.inheritance(type.model().javaType());
            var collections = inheritance.tablePerClass() ? inheritance.hierarchy().stream()
                .filter(e -> !inheritance.abstractEntity(e) && type.model().javaType().isAssignableFrom(e.javaType()))
                .flatMap(e -> entity(e.javaType()).statements().collections().stream()).distinct().toList()
                : type.statements().collections();
            for (CollectionMapping mapping : collections) {
                if (mapping instanceof CollectionMapping.JoinTable table && table.association().singleValued()) {
                    mutations.add(new Compiled.Mutation(table.deleteOwner(), keyIndexes, table.ownerBinders()));
                } else if (mapping instanceof CollectionMapping.ForeignKey foreign) {
                    List<Integer> values = new ArrayList<>();
                    for (var binder : foreign.ownerBinders()) {
                        values.add(projected.size());
                        projected.add(new Expression.Literal(null));
                        readers.add(binder);
                        items.add(new Compiled.ValueItem(binder, null));
                    }
                    values.addAll(keyIndexes);
                    List<ValueBinder> binders = new ArrayList<>(foreign.ownerBinders());
                    binders.addAll(foreign.ownerBinders());
                    for (Table table : unit.collectionTables(entity(foreign.association().targetEntity()), foreign.table())) {
                        mutations.add(new Compiled.Mutation(new io.vidocq.noether.jpa.dialect.sql.Update(table,
                            foreign.ownerColumns(), foreign.ownerColumns()), List.copyOf(values), List.copyOf(binders)));
                    }
                }
            }
            if (inheritance.tablePerClass()) {
                Map<Table, Compiled.Mutation> cleanup = new java.util.LinkedHashMap<>();
                for (var candidate : inheritance.hierarchy()) {
                    if (inheritance.abstractEntity(candidate) || !type.model().javaType().isAssignableFrom(candidate.javaType())) continue;
                    var statements = entity(candidate.javaType()).statements();
                    for (var collection : statements.collections()) {
                        if (collection instanceof CollectionMapping.JoinTable table) {
                            cleanup.putIfAbsent(table.table(), new Compiled.Mutation(table.deleteOwner(), keyIndexes, table.ownerBinders()));
                        }
                    }
                    for (var collection : statements.elementCollections()) {
                        cleanup.putIfAbsent(collection.table(), new Compiled.Mutation(collection.deleteOwner(),
                            keyIndexes, collection.ownerBinders()));
                    }
                }
                cleanup.forEach((table, mutation) -> {
                    if (mutations.stream().noneMatch(m -> m.sql().table().equals(table))) mutations.add(mutation);
                });
            }
            Map<Table, List<Identifier>> tables = new java.util.LinkedHashMap<>();
            unit.inheritance(type.model().javaType()).hierarchy().stream()
                .filter(e -> type.model().javaType().isAssignableFrom(e.javaType()))
                .filter(e -> !unit.inheritance(e.javaType()).tablePerClass() || !unit.inheritance(e.javaType()).abstractEntity(e))
                .sorted(java.util.Comparator.comparingInt(e -> unit.inheritance(e.javaType()).chain().size()))
                .forEach(e -> entity(e.javaType()).statements().tables().forEach(t ->
                    tables.putIfAbsent(t.select().table(), t.select().conditions())));
            List<Table> order = new ArrayList<>(tables.keySet());
            java.util.Collections.reverse(order);
            for (Table table : order) {
                mutations.add(new Compiled.Mutation(new io.vidocq.noether.jpa.dialect.sql.Delete(table, tables.get(table)),
                    keyIndexes, keyIndexes.stream().map(readers::get).toList()));
            }
        }
        // Delete cleanup may append NULL values projected for target-table updates.
        selection = new Query(false, projected, from, where, List.of(), null, List.of(), null, null);
        return new Compiled(selection, List.copyOf(items), Object[].class, slots(selection.parameters()), List.copyOf(mutations));
    }

    private UpdateQuery update(Ast.Update update) {
        Variable variable = bulkVariable(update.entity(), update.variable());
        List<UpdateQuery.Assignment> assignments = new ArrayList<>();
        for (Ast.Assignment assignment : update.assignments()) {
            List<String> segments = assignment.path().segments();
            List<String> attribute = lookup(segments.getFirst()) == variable ? segments.subList(1, segments.size()) : segments;
            if (attribute.isEmpty()) {
                throw new IllegalArgumentException("SET assigns an attribute, not the variable " + segments.getFirst() + " (§4.10)");
            }
            int index = attribute(variable, attribute.getFirst());
            AttributeModel model = variable.type().model().attributes().get(index);
            if (unit.businessKeyAttribute(variable.type(), index)) {
                throw new IllegalArgumentException("Referenced business-key attributes are immutable; bulk SET is not supported");
            }
            if (model instanceof AssociationAttribute association && association.singleValued()) {
                var reference = variable.type().statements().reference(index).orElseThrow(() -> new IllegalArgumentException(
                    "SET assigns " + association.name() + ", which this side of the relationship does not own (§4.10)"));
                EntityStatements statements = variable.type().statements();
                if (reference.businessKey()) {
                    List<Expression> values = businessAssignment(statements, reference, assignment.value());
                    for (int k = 0; k < reference.columns().length; k++) {
                        assignments.add(new UpdateQuery.Assignment(statements.columns().get(reference.columns()[k]).name(), values.get(k)));
                    }
                    continue;
                }
                Entity like = new Entity(entity(association.targetEntity()), columns(variable.alias(), statements,
                    reference.columns()), binders(statements, reference.columns()));
                List<Expression> values = assignment.value() instanceof Ast.Literal(Object v) && v == null ? null
                    : sql(assignment.value() instanceof Ast.Parameter ? boundValue(assignment.value(), like) : value(assignment.value()));
                for (int k = 0; k < reference.columns().length; k++) {
                    assignments.add(new UpdateQuery.Assignment(statements.columns().get(reference.columns()[k]).name(),
                        values == null ? new Expression.Literal(null) : values.get(k)));
                }
                continue;
            }
            Scalar target = scalar(attribute.size() > 1 ? embeddedPath(variable, index, attribute.subList(1, attribute.size()))
                : model instanceof BasicAttribute basic ? basicValue(variable, index, basic) : null);
            Identifier column = ((Expression.Column) target.sql()).name();
            assignments.add(new UpdateQuery.Assignment(column, bound(assignment.value(), target).sql()));
        }
        Expression where = restricted(update.where() == null ? null : condition(update.where()));
        requireSingleTable();
        return new UpdateQuery(roots.getFirst(), variable.alias(), assignments, where);
    }

    private List<Expression> businessAssignment(EntityStatements statements, EntityStatements.Reference reference, Ast.Expr value) {
        if (!(value instanceof Ast.Parameter) && !(value instanceof Ast.Literal)) {
            throw new IllegalArgumentException("Business-key association SET accepts only an entity parameter/literal or NULL");
        }
        if (value instanceof Ast.Literal literal && literal.value() == null) {
            return reference.referencedColumns().stream().<Expression>map(_ -> new Expression.Literal(null)).toList();
        }
        MappedEntity target = entity(reference.target());
        String alias = alias();
        Entity identity = entityValue(alias, target);
        List<Expression> id = sql(value instanceof Ast.Parameter || value instanceof Ast.Literal
            ? boundValue(value, identity) : value(value));
        Expression where = equal(key(alias, target), id);
        return reference.referencedColumns().stream().<Expression>map(column -> new Expression.Subquery(
            new Query(false, List.of(new Expression.Column(alias, column)),
                List.of(new Query.From(table(target), alias, List.of())), where, List.of(), null, List.of(), null, null))).toList();
    }

    private DeleteQuery delete(Ast.Delete delete) {
        Variable variable = bulkVariable(delete.entity(), delete.variable());
        Expression where = restricted(delete.where() == null ? null : condition(delete.where()));
        requireSingleTable();
        return new DeleteQuery(roots.getFirst(), variable.alias(), where);
    }

    private Variable bulkVariable(String entity, String name) {
        MappedEntity type = entityNamed(entity);
        if (type.statements().tables().size() > 1) {
            throw NotYet.milestone("P7", "bulk statements on an entity with secondary tables");
        }
        Variable variable = root(table(type), type, null);
        define(name, variable);
        return variable;
    }

    private List<Expression> referencedKey(String alias, EntityStatements owner, EntityStatements.Reference reference) {
        if (!reference.businessKey()) return key(alias, entity(reference.target()));
        return owner.referencedColumns(reference).stream().<Expression>map(c -> new Expression.Column(alias, c.name())).toList();
    }

    /** A bulk statement works on one table: a path that would need a join is not translated (§4.10). */
    private void requireSingleTable() {
        if (!joins.getFirst().isEmpty()) {
            throw NotYet.milestone("P7", "bulk statements whose paths navigate relationships");
        }
    }

    /** {@code segments} after the name of the implicit identification variable. */
    private static List<String> implicit(List<String> segments) {
        List<String> path = new ArrayList<>();
        path.add(Ast.IMPLICIT_VARIABLE);
        path.addAll(segments);
        return path;
    }

    private static Class<?> type(Compiled.Item item) {
        return switch (item) {
            case Compiled.EntityItem entity -> entity.type().model().javaType();
            case Compiled.ValueItem value -> value.type();
            case Compiled.ConstructorItem constructor -> constructor.type();
            case Compiled.MapEntryItem _ -> java.util.Map.Entry.class;
            case Compiled.EmbeddedItem embedded -> embedded.model().javaType();
        };
    }

    // ---- statements ---------------------------------------------------------------------------------------

    /** The SQL of {@code select}; {@code items} receives how each select item is read. */
    private Query query(Ast.Select select, Integer offset, Integer limit, List<Compiled.Item> items) {
        for (Ast.Range range : select.from()) {
            declare(range);
        }
        List<Expression> sqlItems = new ArrayList<>();
        for (Ast.Item item : select.items()) {
            selecting = true; // §4.4.4: a selected single-valued path is navigated, with inner join semantics
            Value value = item.expression() instanceof Ast.Constructor constructor ? null : value(item.expression());
            if (value == null) {
                items.add(constructor((Ast.Constructor) item.expression(), sqlItems));
            } else {
                items.add(selectItem(value, sqlItems));
            }
            selecting = false;
            if (item.alias() != null && value != null) {
                resultVariables.put(item.alias().toLowerCase(Locale.ROOT), value);
            }
        }
        Expression where = select.where() == null ? null : condition(select.where());
        for (Expression correlation : correlations) {
            where = where == null ? correlation : new Binary(correlation, Operator.AND, where);
        }
        List<Expression> groupBy = new ArrayList<>();
        for (Ast.Expr key : select.groupBy()) {
            groupBy.addAll(sql(value(key)));
        }
        Expression having = select.having() == null ? null : condition(select.having());
        List<Query.Order> orderBy = new ArrayList<>();
        for (Ast.OrderItem order : select.orderBy()) {
            for (Expression key : sql(value(order.expression()))) {
                orderBy.add(new Query.Order(key, order.descending(), order.nullsFirst()));
            }
        }
        if (hydrating && parent == null && groupBy.isEmpty() && having == null) {
            if (!collectionHydration(select, sqlItems) && !select.distinct()) hydrations(select, sqlItems);
        }
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        return new Query(select.distinct() && collectionHydration == null, sqlItems, from, where, groupBy, having, orderBy, offset, limit);
    }

    /**
     * A single unrestricted collection fetch of the selected concrete root; other shapes retain secondary loading. A
     * graph-synthesized statement may follow it with LEFT to-one fetch joins from the elements (D17) or root (D18).
     */
    private boolean collectionHydration(Ast.Select select, List<Expression> sqlItems) {
        if (select.from().size() != 1 || select.items().size() != 1) return false;
        Ast.Range range = select.from().getFirst();
        if (range.entity() == null || range.joins().isEmpty() || range.joins().size() > 1 && !graphRows
                || !(select.items().getFirst().expression() instanceof Ast.Path selected)
                || selected.treatEntity() != null || selected.segments().size() != 1
                || !selected.segments().getFirst().equalsIgnoreCase(range.variable())) return false;
        Ast.Join join = range.joins().getFirst();
        List<String> path = join.path().segments();
        if (!join.fetch() || join.on() != null || join.path().treatEntity() != null
                || path.size() != 2 || !path.getFirst().equalsIgnoreCase(range.variable())) return false;
        if (collectionRestriction(select.where(), join.variable())
                || select.orderBy().stream().anyMatch(o -> collectionRestriction(o.expression(), join.variable()))) return false;
        Variable owner = variables.get(range.variable().toLowerCase(Locale.ROOT));
        Variable target = declaredJoins.get(join);
        if (owner == null || target == null || !hydratable(owner.type())) return false;
        int attribute = attribute(owner.type(), path.get(1));
        AttributeModel model = owner.type().model().attributes().get(attribute);
        if (!(model instanceof ElementCollectionAttribute)
                && !(model instanceof AssociationAttribute association && !association.singleValued())) return false;
        if (target.type() != null && !hydratable(target.type())) return false;
        IndexMapping index = target.index();
        // Entity-valued keys need their own hydration graph; embeddable keys keep the established loader for now.
        if (index != null && (index.keyEntity() != null || index.index() instanceof CollectionIndex.ByEmbedded)) return false;
        List<Variable> toOnes = new ArrayList<>();
        java.util.Set<String> chained = new java.util.HashSet<>();
        chained.add(range.variable().toLowerCase(Locale.ROOT));
        if (join.variable() != null) chained.add(join.variable().toLowerCase(Locale.ROOT));
        for (Ast.Join next : range.joins().subList(1, range.joins().size())) {
            List<String> segments = next.path().segments();
            if (!next.fetch() || next.kind() != Ast.Join.Kind.LEFT || next.on() != null
                    || next.path().treatEntity() != null || next.variable() == null || segments.size() != 2
                    || !chained.contains(segments.getFirst().toLowerCase(Locale.ROOT))) return false;
            Variable source = variables.get(segments.getFirst().toLowerCase(Locale.ROOT));
            Variable fetched = declaredJoins.get(next);
            if (source == null || source.type() == null || fetched == null || fetched.element() != null
                    || !(source.type().model().attribute(segments.get(1)).orElse(null) instanceof AssociationAttribute to)
                    || !to.singleValued() || !hydratable(fetched.type())
                    || collectionRestriction(select.where(), next.variable())
                    || select.orderBy().stream().anyMatch(o -> collectionRestriction(o.expression(), next.variable()))) return false;
            chained.add(next.variable().toLowerCase(Locale.ROOT));
            toOnes.add(fetched);
        }
        addHydration(owner, sqlItems);
        if (target.type() != null) addHydration(target, sqlItems);
        for (Variable fetched : toOnes) addHydration(fetched, sqlItems);
        List<ValueBinder> readers = new ArrayList<>();
        if (target.element() != null) {
            // The owner FK distinguishes an absent LEFT row from a present row with a NULL value.
            sqlItems.add(new Expression.Column(target.alias(), target.element().ownerColumns().getFirst()));
            readers.add(target.element().ownerBinders().getFirst());
            for (EntityStatements.Column column : target.element().columns()) {
                sqlItems.add(new Expression.Column(target.alias(), column.name()));
                readers.add(column.binder());
            }
        }
        if (index != null) {
            for (int i = 0; i < index.columns().size(); i++) {
                sqlItems.add(new Expression.Column(target.indexAlias(), index.columns().get(i)));
                readers.add(index.binders().get(i));
            }
        }
        collectionHydration = new Compiled.CollectionHydration(owner.type(), attribute, target.type(), target.element(),
            index, List.copyOf(readers));
        return true;
    }

    /** Conservatively retain complete secondary loading when predicates could restrict the fetched rows. */
    private boolean collectionRestriction(Ast.Expr expression, String alias) {
        return switch (expression) {
            case null -> false;
            case Ast.Path path -> path.treatEntity() != null
                || alias != null && path.segments().getFirst().equalsIgnoreCase(alias);
            case Ast.Literal _, Ast.Parameter _ -> false;
            case Ast.Binary b -> collectionRestriction(b.left(), alias) || collectionRestriction(b.right(), alias);
            case Ast.Not n -> collectionRestriction(n.operand(), alias);
            case Ast.Negate n -> collectionRestriction(n.operand(), alias);
            case Ast.IsNull n -> collectionRestriction(n.operand(), alias);
            case Ast.Between b -> collectionRestriction(b.operand(), alias) || collectionRestriction(b.low(), alias)
                || collectionRestriction(b.high(), alias);
            case Ast.Like l -> collectionRestriction(l.operand(), alias) || collectionRestriction(l.pattern(), alias)
                || collectionRestriction(l.escape(), alias);
            case Ast.In i -> collectionRestriction(i.operand(), alias)
                || i.values().stream().anyMatch(e -> collectionRestriction(e, alias));
            case Ast.Function f -> f.arguments().stream().anyMatch(e -> collectionRestriction(e, alias));
            case Ast.Cast c -> collectionRestriction(c.expression(), alias);
            default -> true;
        };
    }

    private void addHydration(Variable variable, List<Expression> sqlItems) {
        EntityStatements statements = variable.type().statements();
        List<Integer> read = new ArrayList<>();
        for (int t = 0; t < statements.tables().size(); t++) {
            for (int c : statements.tables().get(t).selected()) {
                EntityStatements.Column column = statements.columns().get(c);
                sqlItems.add(new Expression.Column(tableAlias(variable, t), unit.queryColumn(variable.type(), column)));
                read.add(c);
            }
        }
        int[] key = statements.tables().getFirst().selectParameters().stream().mapToInt(EntityStatements.Parameter::column).toArray();
        hydrations.add(new Compiled.Hydration(variable.type(), read.stream().mapToInt(Integer::intValue).toArray(), key));
    }
    /**
     * §4.4.5.3: the entities the to-one fetch joins of a selected root return with it, read from the same row. Eligible:
     * a fetch join {@code var.attribute} of a single-valued relationship, from the selected root variable or from the
     * variable of an eligible fetch join of it; the root is read too when one of its fetch joins is. An entity is read
     * only when its rows hold one concrete type: no entity subclass, no joined or table-per-class inheritance. Anything
     * else keeps being found by identifier after the rows are read.
     */
    private void hydrations(Ast.Select select, List<Expression> sqlItems) {
        java.util.Set<String> selected = new java.util.HashSet<>();
        for (Ast.Item item : select.items()) {
            if (item.expression() instanceof Ast.Path path && path.segments().size() == 1 && path.treatEntity() == null) {
                selected.add(path.segments().getFirst().toLowerCase(Locale.ROOT));
            }
        }
        List<Variable> ordered = new ArrayList<>(); // what fetches before what it fetches
        for (Ast.Range range : select.from()) {
            String rootName = range.variable() == null ? null : range.variable().toLowerCase(Locale.ROOT);
            if (range.entity() == null || !selected.contains(rootName)) continue;
            java.util.Set<String> fetched = new java.util.HashSet<>(List.of(rootName));
            List<Variable> targets = new ArrayList<>();
            for (Ast.Join join : range.joins()) {
                List<String> segments = join.path().segments();
                if (!join.fetch() || segments.size() != 2 || join.path().treatEntity() != null
                        || !fetched.contains(segments.getFirst().toLowerCase(Locale.ROOT))) continue;
                Variable source = variables.get(segments.getFirst().toLowerCase(Locale.ROOT));
                Variable target = declaredJoins.get(join);
                if (source == null || source.type() == null || target == null || target.owner() != this || target.element() != null
                        || !(source.type().model().attribute(segments.get(1)).orElse(null) instanceof AssociationAttribute association)
                        || !association.singleValued()) continue;
                if (join.variable() != null) fetched.add(join.variable().toLowerCase(Locale.ROOT));
                if (hydratable(target.type())) targets.add(target);
            }
            if (targets.isEmpty()) continue;
            Variable root = variables.get(rootName);
            if (hydratable(root.type())) ordered.add(root);
            ordered.addAll(targets);
        }
        for (int i = ordered.size() - 1; i >= 0; i--) {
            addHydration(ordered.get(i), sqlItems);
        }
    }

    /** Whether the rows of {@code type} are of that one concrete entity, read from its own tables. */
    private boolean hydratable(MappedEntity type) {
        if (type == null || !type.statements().supported()) return false;
        if (type.statements().references().stream().anyMatch(EntityStatements.Reference::businessKey)) return false;
        var inheritance = unit.inheritance(type.model().javaType());
        return !inheritance.joined() && !inheritance.tablePerClass() && !inheritance.abstractEntity(type.model())
            && inheritance.hierarchy().stream().noneMatch(e -> e.javaType() != type.model().javaType() && type.model().javaType().isAssignableFrom(e.javaType()));
    }

    private Compiled.Item selectItem(Value value, List<Expression> sqlItems) {
        return switch (value) {
            case Entity entity -> {
                sqlItems.addAll(entity.key());
                yield new Compiled.EntityItem(entity.type(), entity.binders());
            }
            case Scalar scalar -> {
                sqlItems.add(scalar.sql());
                yield new Compiled.ValueItem(scalar.binder(), scalar.type());
            }
            case Embedded embedded -> {
                sqlItems.addAll(embedded.fields().stream().map(Scalar::sql).toList());
                yield embeddedItem(embedded);
            }
            case MapEntry entry -> new Compiled.MapEntryItem(selectItem(entry.key(), sqlItems), selectItem(entry.value(), sqlItems));
            case Constant constant -> {
                sqlItems.add(bound(constant, null).sql());
                yield new Compiled.ValueItem(null, constant.value() == null ? null : constant.value().getClass());
            }
        };
    }

    /** {@code NEW type(arguments)} (§4.8.2): the class is the application's, loaded by the class loader of the unit. */
    private Compiled.Item constructor(Ast.Constructor constructor, List<Expression> sqlItems) {
        Class<?> type;
        try {
            type = Class.forName(constructor.className(), false, unit.loader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("The constructor expression names " + constructor.className()
                + ", which cannot be loaded (§4.8.2)", e);
        }
        List<Compiled.Item> arguments = new ArrayList<>();
        for (Ast.Expr argument : constructor.arguments()) {
            arguments.add(selectItem(value(argument), sqlItems));
        }
        return new Compiled.ConstructorItem(type, List.copyOf(arguments));
    }

    /** The SQL of a subquery (§4.5.10): translated with the variables of this query in scope. */
    private Translator child() {
        return new Translator(unit, bindings, this);
    }

    private SelectStatement subquery(Ast.Query query, List<Compiled.Item> items) {
        return child().selectStatement(query, null, null, items);
    }

    /** The SQL expressions of a value: itself, or the key columns of an entity. */
    private List<Expression> sql(Value value) {
        return switch (value) {
            case Scalar scalar -> List.of(scalar.sql());
            case Entity entity -> entity.key();
            case Embedded embedded -> embedded.fields().stream().map(Scalar::sql).toList();
            case MapEntry _ -> throw new IllegalArgumentException("ENTRY() is a select item (§4.6.17.2.3)");
            case Constant constant -> List.of(bound(constant, null).sql());
        };
    }

    // ---- FROM (§4.4) --------------------------------------------------------------------------------------

    private void declare(Ast.Range range) {
        Variable variable;
        if (range.entity() != null) {
            MappedEntity type = entityNamed(range.entity());
            variable = root(table(type), type, null);
        } else if (isOuter(range.collection().segments().getFirst())) {
            // a subquery ranging over a collection of an enclosing variable: its rows, correlated (§4.5.10)
            variable = rows(range.collection());
        } else { // IN(path) var: the members of a collection, an inner join (§4.4.6)
            variable = join(range.collection(), Ast.Join.Kind.INNER);
        }
        define(range.variable(), variable);
        for (Ast.Join join : range.joins()) {
            boolean entityJoin = join.path().segments().size() == 1;
            Variable joined;
            if (entityJoin) {
                MappedEntity target = entityNamed(join.path().segments().getFirst());
                String alias = alias();
                joined = new Variable(target, alias, variable.root(), this);
                Expression restriction = inheritanceRestriction(joined);
                joins.get(variable.root()).add(new Query.Join(
                    join.kind() == Ast.Join.Kind.LEFT ? Query.Join.Kind.LEFT : Query.Join.Kind.INNER,
                    table(target), alias, restriction == null ? new Expression.Literal(true) : restriction));
            } else joined = join(join.path(), join.kind());
            declaredJoins.put(join, joined);
            if (join.fetch() && (joined.element() != null || collectionAliases.contains(joined.alias()))) collectionFetch = true;
            if (join.variable() != null) {
                define(join.variable(), joined);
            }
            if (join.on() != null) {
                List<Query.Join> list = joins.get(joined.root());
                int index = 0;
                while (!list.get(index).alias().equals(joined.alias())) index++;
                Query.Join declared = list.get(index);
                Expression condition;
                inJoinCondition = true;
                try { condition = condition(join.on()); }
                finally { inJoinCondition = false; }
                list.set(index, new Query.Join(declared.kind(), declared.table(), declared.alias(),
                    new Binary(declared.on(), Operator.AND, condition)));
            }
        }
    }

    /** A new FROM item of this query. */
    private Variable root(Table table, MappedEntity type, ElementCollectionMapping element) {
        int root = roots.size();
        String alias = alias();
        roots.add(table);
        rootAliases.add(alias);
        joins.add(new ArrayList<>());
        Variable variable = new Variable(type, alias, root, this, element);
        if (type != null) {
            Expression restriction = inheritanceRestriction(variable);
            if (restriction != null) {
                correlations.add(restriction);
            }
            var inheritance = unit.inheritance(type.model().javaType());
            if (inheritance.joined()) {
                for (int t = 1; t < inheritance.chain().size(); t++) {
                    tableAlias(variable, t);
                }
            }
        }
        return variable;
    }

    private Expression restricted(Expression where) {
        for (Expression restriction : correlations) {
            where = where == null ? restriction : new Binary(where, Operator.AND, restriction);
        }
        return where;
    }

    private Expression inheritanceRestriction(Variable variable) {
        var inheritance = unit.inheritance(variable.type().model().javaType());
        if (variable.type().model().javaType() == inheritance.root().javaType()) {
            return null;
        }
        if (inheritance.typeColumn() == null) {
            if (!inheritance.joined()) {
                return null;
            }
            var table = variable.type().statements().tables().get(inheritance.chain().size() - 1).select();
            String alias = alias();
            Query query = new Query(false, List.of(new Expression.Literal(1)),
                List.of(new Query.From(table.table(), alias, List.of())),
                equal(names(alias, table.conditions()), key(variable.alias(), variable.type())),
                List.of(), null, List.of(), null, null);
            return new Expression.Exists(query, false);
        }
        List<Expression> values = inheritance.hierarchy().stream()
            .filter(e -> variable.type().model().javaType().isAssignableFrom(e.javaType()))
            .filter(e -> !inheritance.abstractEntity(e))
            .<Expression>map(e -> new Expression.Literal(inheritance.discriminatorValue(e))).toList();
        return new Expression.In(new Expression.Column(variable.alias(), unit.identifier(inheritance.typeColumn())), values, false);
    }

    private String tableAlias(Variable variable, int table) {
        if (variable.owner() != this) {
            return variable.owner().tableAlias(variable, table);
        }
        if (table == 0 || unit.inheritance(variable.type().model().javaType()).tablePerClass()) {
            return variable.alias();
        }
        String key = variable.alias() + "#" + table;
        String known = tableAliases.get(key);
        if (known != null) {
            return known;
        }
        String alias = alias();
        var statements = variable.type().statements().tables().get(table);
        var inheritance = unit.inheritance(variable.type().model().javaType());
        Query.Join.Kind kind = inheritance.joined() && table < inheritance.chain().size()
            ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT;
        joins.get(variable.root()).add(new Query.Join(kind, statements.select().table(), alias,
            equal(names(alias, statements.select().conditions()), key(variable.alias(), variable.type()))));
        tableAliases.put(key, alias);
        return alias;
    }

    private boolean isOuter(String name) {
        return !variables.containsKey(name.toLowerCase(Locale.ROOT)) && parent != null && parent.lookup(name) != null;
    }

    private void define(String name, Variable variable) {
        if (variables.putIfAbsent(name.toLowerCase(Locale.ROOT), variable) != null) {
            throw new IllegalArgumentException("The identification variable " + name + " is declared twice (§4.4.2)");
        }
    }

    private MappedEntity entityNamed(String name) {
        EntityModel model = unit.model().entity(name).orElseThrow(() -> new IllegalArgumentException(
            "The query names " + name + ", which is not an entity of the persistence unit (§4.3)"));
        return unit.entity(model.javaType()).orElseThrow();
    }

    /** A declared join to the end of {@code path}: an entity of a relationship, or the elements of an element collection. */
    private Variable join(Ast.Path path, Ast.Join.Kind kind) {
        List<String> segments = path.segments();
        Variable from;
        int start=1;
        if (path.treatEntity() != null) {
            int at=path.treatAt();
            if (at<1 || at>segments.size()) throw new IllegalArgumentException("Invalid treated join (§4.4.5)");
            if (at==segments.size() && segments.size()>=2) {
                return treatedJoin(segments, at, path.treatEntity(),
                    kind == Ast.Join.Kind.LEFT ? Query.Join.Kind.LEFT : Query.Join.Kind.INNER);
            }
            if (at==1) {
                Variable original=variable(segments.getFirst());
                MappedEntity target=treatedType(original.type().model().javaType(),path.treatEntity());
                from=new Variable(target,original.alias(),original.root(),original.owner());
                Expression restriction=inheritanceRestriction(from);
                if (restriction!=null) correlations.add(restriction);
            } else from=treatedJoin(segments.subList(0,at),at,path.treatEntity(),Query.Join.Kind.INNER);
            start=at;
        } else {
            from=variable(segments.getFirst());
        }
        if (segments.size() < 2) {
            throw new IllegalArgumentException("A join names a relationship, not " + String.join(".", segments));
        }
        AttributePath resolved = relationshipPath(from, segments.subList(start, segments.size()));
        from = resolved.owner();
        int attribute = resolved.attribute();
        Query.Join.Kind sqlKind = kind == Ast.Join.Kind.LEFT ? Query.Join.Kind.LEFT : Query.Join.Kind.INNER;
        AttributeModel model = from.type().model().attributes().get(attribute);
        return switch (model) {
            case AssociationAttribute association when association.singleValued() -> relationshipJoin(from, attribute, association, sqlKind);
            case AssociationAttribute association -> collectionJoin(from, attribute, association, sqlKind);
            case ElementCollectionAttribute _ -> elementJoin(from, attribute, sqlKind);
            default -> throw new IllegalArgumentException("A join names a relationship or an element collection, not the attribute "
                + segments.getLast() + " of " + from.type().model().entityName() + " (§4.4.5)");
        };
    }

    private record AttributePath(Variable owner, int attribute) {
    }

    private AttributePath relationshipPath(Variable owner, List<String> segments) {
        int start = 0;
        while (start < segments.size()) {
            int attribute = attribute(owner, segments.get(start));
            int end = start + 1;
            if (owner.type().model().attributes().get(attribute) instanceof EmbeddedAttribute) {
                boolean found = false;
                for (end = start + 2; end <= segments.size(); end++) {
                    String dotted = String.join(".", segments.subList(start, end));
                    if (owner.type().model().attribute(dotted).isPresent()) {
                        attribute = attribute(owner, dotted);
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IllegalArgumentException("The path does not name an executable embedded relationship: " + segments);
            }
            if (end == segments.size()) return new AttributePath(owner, attribute);
            owner = navigate(owner, attribute, Query.Join.Kind.INNER);
            start = end;
        }
        throw new IllegalArgumentException("An empty relationship path");
    }

    private Variable treatedJoin(List<String> segments, int treatAt, String typeName, Query.Join.Kind kind) {
        Variable from = variable(segments.getFirst());
        for (int s = 1; s < treatAt - 1; s++) {
            from = navigate(from, attribute(from, segments.get(s)), Query.Join.Kind.INNER);
        }
        int attribute = attribute(from, segments.get(treatAt - 1));
        if (!(from.type().model().attributes().get(attribute) instanceof AssociationAttribute association)) {
            throw new IllegalArgumentException("TREAT in a join must name an entity relationship (§4.4.5)");
        }
        MappedEntity target = treatedType(association.targetEntity(), typeName);
        return association.singleValued()
            ? relationshipJoin(from, attribute, association, kind, target)
            : collectionJoin(from, attribute, association, kind, target);
    }

    /** The variable a single-valued path navigates to: one join per path (§4.4.4), shared by its uses. */
    private Variable navigate(Variable from, int attribute, Query.Join.Kind kind) {
        if (from.owner() != this) {
            return from.owner().navigate(from, attribute, kind);
        }
        AttributeModel model = from.type().model().attributes().get(attribute);
        if (!(model instanceof AssociationAttribute association) || !association.singleValued()) {
            throw new IllegalArgumentException("The path goes through " + model.name() + " of " + from.type().model().entityName()
                + ", which is not a single-valued relationship (§4.4.4)");
        }
        String key = from.alias() + "." + attribute + "." + kind;
        Variable known = navigations.get(key);
        if (known == null) {
            known = relationshipJoin(from, attribute, association, kind);
            navigations.put(key, known);
        }
        return known;
    }

    private Variable relationshipJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind) {
        return relationshipJoin(from, attribute, association, kind, entity(association.targetEntity()));
    }

    private Variable relationshipJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind,
            MappedEntity target) {
        String alias = alias();
        Expression on;
        var owned = from.type().statements().reference(attribute);
        if (owned.isPresent()) { // from.fk = target.key
            on = equal(columns(from, owned.get().columns()), referencedKey(alias, from.type().statements(), owned.get()));
        } else if (!association.owning()) { // the owner's foreign key holds this key: target.fk = from.key
            var inverse = target.statements().reference(attribute(target, association.mappedBy()));
            if (inverse.isEmpty()) return collectionJoin(from, attribute, association, kind, target);
            var reference = inverse.get();
            on = foreignKeyPredicate(alias, target, reference.columns(), referencedKey(from.alias(), target.statements(), reference));
        } else {
            return collectionJoin(from, attribute, association, kind, target);
        }
        Variable variable = new Variable(target, alias, from.root(), this);
        Expression restriction = inheritanceRestriction(variable);
        joins.get(from.root()).add(new Query.Join(kind, table(target), alias,
            restriction == null ? on : new Binary(on, Operator.AND, restriction)));
        return variable;
    }

    private Variable collectionJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind) {
        return collectionJoin(from, attribute, association, kind, entity(association.targetEntity()));
    }

    private Variable collectionJoin(Variable from, int attribute, AssociationAttribute association, Query.Join.Kind kind,
            MappedEntity target) {
        String alias = alias();
        List<Query.Join> list = joins.get(from.root());
        String positions = alias; // the table that holds the position of a member of an ordered list
        switch (collection(from, attribute, association)) {
            case Link.ByForeignKey link -> list.add(new Query.Join(kind, table(target), alias,
                foreignKeyPredicate(alias, target, link.columns(), key(from.alias(), from.type()))));
            case Link.ByColumns link -> list.add(new Query.Join(kind, table(target), alias,
                collectionKeyPredicate(alias, target, link, key(from.alias(), from.type()))));
            case Link.ByTable link -> {
                String row = alias();
                positions = row;
                list.add(new Query.Join(kind, link.table(), row, equal(names(row, link.ownerColumns()), key(from.alias(), from.type()))));
                list.add(new Query.Join(kind, table(target), alias, equal(key(alias, target), names(row, link.memberColumns()))));
            }
        }
        io.vidocq.noether.jpa.core.mapping.IndexMapping index = switch (from.type().statements().collection(attribute).orElse(null)) {
            case null -> null;
            case CollectionMapping.JoinTable table -> table.index();
            case CollectionMapping.MappedBy inverse -> inverse.index();
            case CollectionMapping.ForeignKey foreign -> foreign.index();
            case CollectionMapping.Unsupported _ -> null;
        };
        Expression position = index != null && index.positional() ? new Expression.Column(positions, index.columns().getFirst()) : null;
        Variable variable = new Variable(target, alias, from.root(), this, null, position, index, positions);
        if (!association.singleValued()) collectionAliases.add(alias);
        Expression restriction = inheritanceRestriction(variable);
        if (restriction != null) {
            Query.Join last = list.getLast();
            list.set(list.size() - 1, new Query.Join(last.kind(), last.table(), last.alias(),
                new Binary(last.on(), Operator.AND, restriction)));
        }
        return variable;
    }

    /** {@code JOIN e.aliases a}: the rows of the collection table, the variable an element (§4.4.5). */
    private Variable elementJoin(Variable from, int attribute, Query.Join.Kind kind) {
        ElementCollectionMapping mapping = elementCollection(from, attribute);
        String alias = alias();
        joins.get(from.root()).add(new Query.Join(kind, mapping.table(), alias,
            equal(names(alias, mapping.ownerColumns()), key(from.alias(), from.type()))));
        return new Variable(null, alias, from.root(), this, mapping, null, mapping.index(), alias);
    }

    private Expression foreignKeyPredicate(String targetAlias, MappedEntity target, int[] indexes, List<Expression> ownerKey) {
        EntityStatements statements = target.statements();
        if (unit.inheritance(target.model().javaType()).tablePerClass()) {
            return equal(Arrays.stream(indexes).<Expression>mapToObj(i -> new Expression.Column(targetAlias,
                unit.queryColumn(target, statements.columns().get(i)))).toList(), ownerKey);
        }
        int tableIndex = statements.columns().get(indexes[0]).table();
        if (tableIndex == 0) {
            return equal(columns(targetAlias, statements, indexes), ownerKey);
        }
        var table = statements.tables().get(tableIndex).select();
        String alias = alias();
        Expression where = new Binary(equal(names(alias, table.conditions()), key(targetAlias, target)), Operator.AND,
            equal(columns(alias, statements, indexes), ownerKey));
        return new Expression.Exists(new Query(false, List.of(new Expression.Literal(1)),
            List.of(new Query.From(table.table(), alias, List.of())), where, List.of(), null, List.of(), null, null), false);
    }

    private ElementCollectionMapping elementCollection(Variable from, int attribute) {
        return from.type().statements().elementCollection(attribute).orElseThrow(() -> NotYet.milestone("P5",
            "the element collection " + from.type().model().attributes().get(attribute).name() + " of " + from.type().model().entityName()));
    }

    private Expression collectionKeyPredicate(String targetAlias, MappedEntity target, Link.ByColumns link,
            List<Expression> ownerKey) {
        if (unit.inheritance(target.model().javaType()).tablePerClass()
                && !unit.collectionTables(target, link.table()).equals(List.of(link.table()))) {
            return equal(names(targetAlias, link.columns()), ownerKey);
        }
        if (link.table().equals(table(target))) return equal(names(targetAlias, link.columns()), ownerKey);
        String row = alias();
        Expression where = new Binary(equal(names(row, link.keys()), key(targetAlias, target)), Operator.AND,
            equal(names(row, link.columns()), ownerKey));
        return new Expression.Exists(new Query(false, List.of(new Expression.Literal(1)),
            List.of(new Query.From(link.table(), row, List.of())), where, List.of(), null, List.of(), null, null), false);
    }

    /** How the members of a collection-valued relationship are reached from their owner. */
    private sealed interface Link {
        /** The members' foreign key {@code columns} (in the target's columns) hold the owner's key. */
        record ByForeignKey(int[] columns) implements Link {
        }

        record ByColumns(Table table, List<Identifier> columns, List<Identifier> keys) implements Link {
        }

        /** A table of rows, owner key then member key. */
        record ByTable(Table table, List<Identifier> ownerColumns, List<Identifier> memberColumns) implements Link {
        }
    }

    private Link collection(Variable from, int attribute, AssociationAttribute association) {
        MappedEntity target = entity(association.targetEntity());
        if (!association.owning()) {
            int mappedBy = attribute(target, association.mappedBy());
            var reference = target.statements().reference(mappedBy);
            var owner = target.statements().collection(mappedBy);
            if (reference.isPresent()) return new Link.ByForeignKey(reference.get().columns());
            if (owner.isPresent() && owner.get() instanceof CollectionMapping.JoinTable table) {
                return new Link.ByTable(table.table(), table.targetColumns(), table.ownerColumns());
            }
            throw NotYet.milestone("P7", "a collection whose owning side is mapped by a shape not mapped yet");
        }
        return switch (from.type().statements().collection(attribute).orElse(null)) {
            case CollectionMapping.JoinTable table -> new Link.ByTable(table.table(), table.ownerColumns(), table.targetColumns());
            case CollectionMapping.ForeignKey foreign -> new Link.ByColumns(foreign.table(), foreign.ownerColumns(), foreign.targetColumns());
            case CollectionMapping.MappedBy _ -> {
                throw NotYet.milestone("P7", "a collection whose owning side is mapped by a shape not mapped yet");
            }
            case CollectionMapping.Unsupported unsupported -> throw NotYet.milestone("P5", unsupported.feature());
            case null -> throw NotYet.milestone("P5", "a relationship without a relational mapping");
        };
    }

    /**
     * The members of the collection at {@code path} as a FROM item of this (sub)query, correlated with the owner the
     * enclosing query holds: the variable of a member, the correlation added to this query's conditions.
     */
    private Variable rows(Ast.Path path) {
        List<String> segments = path.segments();
        AttributePath resolved = relationshipPath(variable(segments.getFirst()), segments.subList(1, segments.size()));
        Variable owner = resolved.owner();
        int attribute = resolved.attribute();
        AttributeModel model = owner.type().model().attributes().get(attribute);
        List<Expression> ownerKey = key(owner.alias(), owner.type());
        return switch (model) {
            case AssociationAttribute association when association.singleValued() -> {
                MappedEntity target = entity(association.targetEntity());
                Variable member = root(table(target), target, null);
                var reference = owner.type().statements().reference(attribute);
                if (reference.isPresent()) {
                    correlations.add(equal(columns(owner, reference.get().columns()), referencedKey(member.alias(), owner.type().statements(), reference.get())));
                } else if (!association.owning()) {
                    var inverse = target.statements().reference(attribute(target, association.mappedBy()));
                    if (inverse.isEmpty()) {
                        var link = (Link.ByTable) collection(owner, attribute, association);
                        Variable row = root(link.table(), null, null);
                        correlations.add(equal(names(row.alias(), link.ownerColumns()), ownerKey));
                        correlations.add(equal(names(row.alias(), link.memberColumns()), key(member.alias(), target)));
                    } else {
                        correlations.add(foreignKeyPredicate(member.alias(), target, inverse.get().columns(),
                            referencedKey(owner.alias(), target.statements(), inverse.get())));
                    }
                } else {
                    var link = (Link.ByTable) collection(owner, attribute, association);
                    Variable row = root(link.table(), null, null);
                    correlations.add(equal(names(row.alias(), link.ownerColumns()), ownerKey));
                    correlations.add(equal(names(row.alias(), link.memberColumns()), key(member.alias(), target)));
                }
                yield member;
            }
            case AssociationAttribute association when !association.singleValued() -> {
                MappedEntity target = entity(association.targetEntity());
                yield switch (collection(owner, attribute, association)) {
                    case Link.ByForeignKey link -> {
                        Variable member = root(table(target), target, null);
                        correlations.add(foreignKeyPredicate(member.alias(), target, link.columns(), ownerKey));
                        yield member;
                    }
                    case Link.ByColumns link -> {
                        Variable member = root(table(target), target, null);
                        correlations.add(collectionKeyPredicate(member.alias(), target, link, ownerKey));
                        yield member;
                    }
                    case Link.ByTable link -> {
                        Variable row = root(link.table(), null, null);
                        correlations.add(equal(names(row.alias(), link.ownerColumns()), ownerKey));
                        String alias = alias();
                        joins.get(row.root()).add(new Query.Join(Query.Join.Kind.INNER, table(target), alias,
                            equal(key(alias, target), names(row.alias(), link.memberColumns()))));
                        yield new Variable(target, alias, row.root(), this);
                    }
                };
            }
            case ElementCollectionAttribute _ -> {
                ElementCollectionMapping mapping = elementCollection(owner, attribute);
                Variable member = root(mapping.table(), null, mapping);
                correlations.add(equal(names(member.alias(), mapping.ownerColumns()), ownerKey));
                yield member;
            }
            default -> throw new IllegalArgumentException("The path " + String.join(".", segments) + " is not a collection (§4.6.12)");
        };
    }

    // ---- paths (§4.4.4) -----------------------------------------------------------------------------------

    private Variable lookup(String name) {
        Variable variable = variables.get(name.toLowerCase(Locale.ROOT));
        return variable != null || parent == null ? variable : parent.lookup(name);
    }

    private Variable variable(String name) {
        Variable variable = lookup(name);
        if (variable == null) {
            throw new IllegalArgumentException("The identification variable " + name + " is not declared (§4.4.2)");
        }
        return variable;
    }

    /** What a path denotes: an entity, an attribute's column, the foreign key of a relationship, an element, an enum constant. */
    private Value path(Ast.Path path) {
        List<String> segments = path.segments();
        if (path.treatEntity() != null) {
            return treatedPath(path);
        }
        String head = segments.getFirst().toLowerCase(Locale.ROOT);
        Variable variable = lookup(segments.getFirst());
        if (variable == null && !resultVariables.containsKey(head) && lookup(Ast.IMPLICIT_VARIABLE) != null
                && (segments.size() > 1 || enumConstant(segments) == null)) {
            // 3.2: the attributes of the implicit identification variable, named without it
            Variable implicit = lookup(Ast.IMPLICIT_VARIABLE);
            if (implicit.type() != null && indexOf(implicit.type().model().attributes(), segments.getFirst()) >= 0) {
                variable = implicit;
                segments = implicit(segments);
            }

        }
        if (variable == null) {
            if (segments.size() == 1 && resultVariables.containsKey(head)) {
                return resultVariables.get(head);
            }
            var entity = unit.model().entity(String.join(".", segments));
            if (entity.isPresent()) {
                return new Constant(entity.get().javaType());
            }
            Object constant = segments.size() > 1 ? enumConstant(segments) : null;
            if (constant != null) {
                return new Constant(constant);
            }
            throw new IllegalArgumentException("The identification variable " + segments.getFirst() + " is not declared (§4.4.2)");
        }
        if (variable.element() != null) {
            return elementValue(variable, segments.subList(1, segments.size()));
        }
        if (segments.size() == 1) {
            return entityValue(variable.alias(), variable.type());
        }
        for (int s = 1; s < segments.size() - 1; s++) {
            int attribute = attribute(variable, segments.get(s));
            if (variable.type().model().attributes().get(attribute) instanceof EmbeddedAttribute) {
                return embeddedPath(variable, attribute, segments.subList(s + 1, segments.size()));
            }
            if (inJoinCondition && variable.type().model().attributes().get(attribute) instanceof AssociationAttribute association
                    && association.singleValued()) {
                // An ON path must not introduce a later/global inner join that removes an unmatched LEFT root.
                String name = "_noether_on_" + alias();
                while (lookup(name) != null) name += "_";
                List<String> remaining = new ArrayList<>(); remaining.add(name); remaining.addAll(segments.subList(s + 1, segments.size()));
                var correlated = new Ast.Binary(new Ast.Path(segments.subList(0, s + 1)), Ast.Op.EQ, new Ast.Path(List.of(name)));
                var select = new Ast.Select(false, List.of(new Ast.Item(new Ast.Path(remaining), null)),
                    List.of(new Ast.Range(entity(association.targetEntity()).model().entityName(), null, name, List.of())),
                    correlated, List.of(), null, List.of());
                return value(new Ast.Subquery(select));
            }
            variable = navigate(variable, attribute, Query.Join.Kind.INNER);
        }
        int attribute = attribute(variable, segments.getLast());
        return attributeValue(variable, attribute);
    }

    private Value attributeValue(Variable variable, int attribute) {
        AttributeModel model = variable.type().model().attributes().get(attribute);
        return switch (model) {
            case BasicAttribute basic -> basicValue(variable, attribute, basic);
            case AssociationAttribute association when association.singleValued() -> {
                var owned = variable.type().statements().reference(attribute);
                if (owned.isPresent() && owned.get().businessKey() && !selecting) {
                    var reference = owned.get();
                    MappedEntity target = entity(association.targetEntity());
                    String alias = alias();
                    Expression on = equal(referencedKey(alias, variable.type().statements(), reference), columns(variable, reference.columns()));
                    List<Expression> identity = key(alias, target).stream().<Expression>map(k -> new Expression.Subquery(
                        new Query(false, List.of(k), List.of(new Query.From(table(target), alias, List.of())),
                            on, List.of(), null, List.of(), null, null))).toList();
                    yield new Entity(target, identity, target.statements().keyColumns().stream().map(EntityStatements.Column::binder).toList());
                }
                if (owned.isPresent() && !owned.get().businessKey() && !selecting) { // compared by identity only
                    EntityStatements statements = variable.type().statements();
                    yield new Entity(entity(association.targetEntity()), columns(variable, owned.get().columns()),
                        binders(statements, owned.get().columns()));
                }
                if (!selecting && (association.owning() && variable.type().statements().collection(attribute).isPresent()
                        || !association.owning() && entity(association.targetEntity()).statements()
                            .collection(attribute(entity(association.targetEntity()), association.mappedBy())).isPresent())) {
                    Link.ByTable link = (Link.ByTable) collection(variable, attribute, association);
                    List<Expression> keys = new ArrayList<>();
                    for (Identifier column : link.memberColumns()) {
                        String row = alias();
                        keys.add(new Expression.Subquery(new Query(false, names(row, List.of(column)),
                            List.of(new Query.From(link.table(), row, List.of())),
                            equal(names(row, link.ownerColumns()), key(variable.alias(), variable.type())),
                            List.of(), null, List.of(), null, null)));
                    }
                    MappedEntity target = entity(association.targetEntity());
                    yield new Entity(target, List.copyOf(keys),
                        target.statements().keyColumns().stream().map(EntityStatements.Column::binder).toList());
                }
                Variable target = navigate(variable, attribute, selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT);
                yield entityValue(target.alias(), target.type());
            }
            case EmbeddedAttribute embedded -> embeddedEntity(variable, attribute, embedded.embeddable(), new int[0]);
            default -> throw new IllegalArgumentException("The path " + model.name() + " ends at a collection; it is "
                + "joined, or used with IS EMPTY, MEMBER OF or SIZE (§4.4.4)");
        };
    }

    private Value treatedPath(Ast.Path path) {
        List<String> segments = path.segments();
        int at = path.treatAt();
        if (at < 1 || at > segments.size()) {
            throw new IllegalArgumentException("TREAT has an invalid path (§4.4.9)");
        }
        Variable variable = variable(segments.getFirst());
        for (int s = 1; s < at; s++) {
            int attribute = attribute(variable, segments.get(s));
            if (s == at - 1) {
                if (!(variable.type().model().attributes().get(attribute) instanceof AssociationAttribute association)) {
                    throw new IllegalArgumentException("TREAT must apply to an entity-valued path (§4.4.9)");
                }
                MappedEntity target = treatedType(association.targetEntity(), path.treatEntity());
                variable = association.singleValued()
                    ? relationshipJoin(variable, attribute, association,
                        selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT, target)
                    : collectionJoin(variable, attribute, association,
                        selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT, target);
            } else {
                variable = navigate(variable, attribute, Query.Join.Kind.INNER);
            }
        }
        if (at == 1) {
            MappedEntity target = treatedType(variable.type().model().javaType(), path.treatEntity());
            variable = new Variable(target, variable.alias(), variable.root(), variable.owner(), variable.element(),
                variable.position(), variable.index(), variable.indexAlias());
            Expression restriction = inheritanceRestriction(variable);
            if (restriction != null) {
                correlations.add(restriction);
            }
        }
        if (segments.size() == at) {
            return entityValue(variable.alias(), variable.type());
        }
        if (variable.element() != null) {
            throw new IllegalArgumentException("TREAT cannot narrow an element collection value (§4.4.9)");
        }
        for (int s = at; s < segments.size() - 1; s++) {
            int attribute = attribute(variable, segments.get(s));
            if (variable.type().model().attributes().get(attribute) instanceof EmbeddedAttribute) {
                return embeddedPath(variable, attribute, segments.subList(s + 1, segments.size()));
            }
            variable = navigate(variable, attribute, Query.Join.Kind.INNER);
        }
        int attribute = attribute(variable, segments.getLast());
        AttributeModel model = variable.type().model().attributes().get(attribute);
        return switch (model) {
            case BasicAttribute basic -> basicValue(variable, attribute, basic);
            case AssociationAttribute association when association.singleValued() -> {
                Variable target = navigate(variable, attribute, selecting ? Query.Join.Kind.INNER : Query.Join.Kind.LEFT);
                yield entityValue(target.alias(), target.type());
            }
            case EmbeddedAttribute embedded -> embeddedEntity(variable, attribute, embedded.embeddable(), new int[0]);
            default -> throw new IllegalArgumentException("TREAT path ends at a collection (§4.4.9)");
        };
    }

    private MappedEntity treatedType(Class<?> declaredType, String typeName) {
        MappedEntity declared = entity(declaredType);
        MappedEntity treated = entityNamed(typeName);
        if (!declaredType.isAssignableFrom(treated.model().javaType())) {
            throw new IllegalArgumentException("TREAT target " + typeName + " is not a subtype of " + declaredType.getName() + " (§4.4.9)");
        }
        return treated;
    }

    /** The column of a basic attribute, or of the foreign key that holds it when an {@code @MapsId} maps it. */
    private Scalar basicValue(Variable variable, int attribute, BasicAttribute basic) {
        Scalar derived = derivedIdColumn(variable, attribute, List.of());
        return derived != null ? derived : column(variable, attribute, new int[0], basic.javaType());
    }

    /** The dotted name of an enum constant (§4.6.1), or {@code null}. */
    private Object enumConstant(List<String> segments) {
        String className = String.join(".", segments.subList(0, segments.size() - 1));
        try {
            Class<?> type = Class.forName(className, false, unit.loader());
            if (type.isEnum()) {
                for (Object constant : type.getEnumConstants()) {
                    if (((Enum<?>) constant).name().equals(segments.getLast())) {
                        return constant;
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            return null;
        }
        return null;
    }

    /** An element of an element collection, or an attribute of an embeddable one. */
    private Value elementValue(Variable variable, List<String> rest) {
        ElementCollectionMapping mapping = variable.element();
        AttributeModel element = mapping.model().element();
        if (element instanceof EmbeddedAttribute embedded) {
            EmbeddableModel model = embedded.embeddable();
            int[] prefix = new int[0];
            AttributeModel current = embedded;
            for (int p = 0; p < rest.size(); p++) {
                int index = indexOf(model.attributes(), rest.get(p));
                if (index < 0) {
                    throw new IllegalArgumentException(model.javaType().getName() + " has no attribute " + rest.get(p));
                }
                prefix = Arrays.copyOf(prefix, prefix.length + 1);
                prefix[prefix.length - 1] = index;
                current = model.attributes().get(index);
                if (current instanceof EmbeddedAttribute nested) {
                    if (p == rest.size() - 1) {
                        return embeddedElement(variable, mapping, nested.embeddable(), prefix);
                    }
                    model = nested.embeddable();
                } else if (p < rest.size() - 1) {
                    throw new IllegalArgumentException("The path goes through " + rest.get(p) + ", which is not an embeddable");
                }
            }
            if (rest.isEmpty()) {
                return embeddedElement(variable, mapping, embedded.embeddable(), prefix);
            }
            if (!(current instanceof BasicAttribute basic)) {
                throw new IllegalArgumentException("The path does not end at a basic embeddable attribute");
            }
            for (EntityStatements.Column column : mapping.columns()) {
                if (Arrays.equals(column.path(), prefix)) {
                    return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType());
                }
            }
            throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no column for " + rest);
        }
        if (!rest.isEmpty()) {
            throw new IllegalArgumentException("An element of " + mapping.model().name() + " is a value, it has no " + rest.getFirst());
        }
        BasicAttribute basic = (BasicAttribute) element;
        for (EntityStatements.Column column : mapping.columns()) {
            if (column.path().length == 0) {
                return new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType());
            }
        }
        throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no value column");
    }

    /** A path that ends in an embeddable: {@code e.address.city}. */
    private Value embeddedPath(Variable variable, int attribute, List<String> rest) {
        Scalar derived = derivedIdColumn(variable, attribute, rest);
        if (derived != null) {
            return derived;
        }
        EmbeddedAttribute embedded = (EmbeddedAttribute) variable.type().model().attributes().get(attribute);
        List<AttributeModel> components = embedded.embeddable().attributes();
        int[] path = new int[rest.size()];
        AttributeModel model = embedded;
        for (int p = 0; p < rest.size(); p++) {
            int index = indexOf(components, rest.get(p));
            if (index < 0) {
                throw new IllegalArgumentException(embedded.embeddable().javaType().getName() + " has no attribute " + rest.get(p));
            }
            path[p] = index;
            model = components.get(index);
            if (model instanceof AssociationAttribute association) {
                String dotted = embedded.name() + "." + String.join(".", rest.subList(0, p + 1));
                int relationship = attribute(variable, dotted);
                if (p + 1 == rest.size()) return attributeValue(variable, relationship);
                Variable target = navigate(variable, relationship, Query.Join.Kind.INNER);
                for (int s = p + 1; s < rest.size() - 1; s++) {
                    int next = attribute(target, rest.get(s));
                    if (target.type().model().attributes().get(next) instanceof EmbeddedAttribute) {
                        return embeddedPath(target, next, rest.subList(s + 1, rest.size()));
                    }
                    target = navigate(target, next, Query.Join.Kind.INNER);
                }
                return attributeValue(target, attribute(target, rest.getLast()));
            }
            if (model instanceof EmbeddedAttribute nested) {
                components = nested.embeddable().attributes();
            } else if (p < rest.size() - 1) {
                throw new IllegalArgumentException("The path goes through " + rest.get(p) + ", which is not an embeddable");
            }
        }
        if (!(model instanceof BasicAttribute basic)) {
            if (model instanceof EmbeddedAttribute nested) {
                return embeddedEntity(variable, attribute, nested.embeddable(), path);
            }
            throw new IllegalArgumentException("The path does not end at a basic or embedded attribute");
        }
        return column(variable, attribute, path, basic.javaType());
    }

    private Embedded embeddedEntity(Variable variable, int attribute, EmbeddableModel model, int[] prefix) {
        List<Scalar> fields = new ArrayList<>();
        List<int[]> paths = new ArrayList<>();
        flatten(model, new int[0], (basic, path) -> {
            int[] complete = Arrays.copyOf(prefix, prefix.length + path.length);
            System.arraycopy(path, 0, complete, prefix.length, path.length);
            fields.add(column(variable, attribute, complete, basic.javaType()));
            paths.add(path);
        });
        return new Embedded(model, unit.access(model), List.copyOf(fields), List.copyOf(paths));
    }

    private Compiled.EmbeddedItem embeddedItem(Embedded embedded) {
        int[] field = new int[1];
        return embeddedItem(embedded.model(), embedded.fields(), field);
    }

    private Compiled.EmbeddedItem embeddedItem(EmbeddableModel model, List<Scalar> fields, int[] field) {
        List<Compiled.EmbeddedPart> parts = new ArrayList<>();
        for (AttributeModel attribute : model.attributes()) {
            if (attribute instanceof BasicAttribute) {
                Scalar scalar = fields.get(field[0]++);
                parts.add(new Compiled.ScalarEmbedded(scalar.binder(), scalar.type()));
            } else if (attribute instanceof EmbeddedAttribute embedded) {
                parts.add(new Compiled.NestedEmbedded(embeddedItem(embedded.embeddable(), fields, field)));
            } else {
                throw NotYet.milestone("P7", "relationships in an embeddable result");
            }
        }
        return new Compiled.EmbeddedItem(model, unit.access(model), List.copyOf(parts));
    }

    private Embedded embeddedElement(Variable variable, ElementCollectionMapping mapping, EmbeddableModel model, int[] prefix) {
        List<Scalar> fields = new ArrayList<>();
        List<int[]> paths = new ArrayList<>();
        flatten(model, new int[0], (basic, path) -> {
            int[] complete = Arrays.copyOf(prefix, prefix.length + path.length);
            System.arraycopy(path, 0, complete, prefix.length, path.length);
            for (EntityStatements.Column column : mapping.columns()) {
                if (Arrays.equals(column.path(), complete)) {
                    fields.add(new Scalar(new Expression.Column(variable.alias(), column.name()), column.binder(), basic.javaType()));
                    paths.add(path);
                    return;
                }
            }
            throw new IllegalArgumentException("The element of " + mapping.model().name() + " has no column for "
                + Arrays.toString(complete));
        });
        return new Embedded(model, unit.access(model), List.copyOf(fields), List.copyOf(paths));
    }

    @FunctionalInterface
    private interface BasicField {
        void accept(BasicAttribute attribute, int[] path);
    }

    private static void flatten(EmbeddableModel model, int[] prefix, BasicField consumer) {
        for (int i = 0; i < model.attributes().size(); i++) {
            AttributeModel attribute = model.attributes().get(i);
            int[] path = Arrays.copyOf(prefix, prefix.length + 1);
            path[path.length - 1] = i;
            if (attribute instanceof BasicAttribute basic) {
                consumer.accept(basic, path);
            } else if (attribute instanceof EmbeddedAttribute embedded) {
                flatten(embedded.embeddable(), path, consumer);
            } else {
                throw NotYet.milestone("P7", "relationships in an embeddable comparison");
            }
        }
    }

    /**
     * A part of an embedded identifier that an {@code @MapsId} relationship maps (§2.4.1): it has no column of its own,
     * the foreign key of the relationship holds it — {@code d.id.empPK.firstName} for {@code @MapsId("empPK")},
     * {@code d.id.firstName} for an {@code @MapsId} of the whole identifier. {@code null} for any other path.
     */
    private Scalar derivedIdColumn(Variable variable, int attribute, List<String> rest) {
        EntityModel model = variable.type().model();
        AttributeModel idAttribute = switch (model.id()) {
            case io.vidocq.noether.jpa.core.model.IdModel.Embedded id -> id.attribute();
            case io.vidocq.noether.jpa.core.model.IdModel.Single id -> id.attribute();
            default -> null;
        };
        if (idAttribute == null || model.attributes().indexOf(idAttribute) != attribute) {
            return null;
        }
        for (int a = 0; a < model.attributes().size(); a++) {
            if (!(model.attributes().get(a) instanceof AssociationAttribute association) || association.mapsId() == null) {
                continue;
            }
            List<String> within;
            if (association.mapsId().isEmpty()) {
                within = rest;
            } else if (rest.getFirst().equals(association.mapsId())) {
                within = rest.subList(1, rest.size());
            } else {
                continue;
            }
            MappedEntity parent = entity(association.targetEntity());
            List<String> parentKey = keyNames(parent.model());
            int part = within.isEmpty() ? 0 : parentKey.indexOf(within.getFirst());
            var reference = variable.type().statements().reference(a);
            if (part < 0 || within.size() > 1 || reference.isEmpty()) {
                return null;
            }
            EntityStatements.Column column = variable.type().statements().columns().get(reference.get().columns()[part]);
            return new Scalar(new Expression.Column(tableAlias(variable, column.table()), unit.queryColumn(variable.type(), column)), column.binder(), null);
        }
        return null;
    }

    /** The names of the key parts of an entity, in the order of its key. */
    private static List<String> keyNames(EntityModel model) {
        return switch (model.id()) {
            case io.vidocq.noether.jpa.core.model.IdModel.Single single -> List.of(single.attribute().name());
            case io.vidocq.noether.jpa.core.model.IdModel.Embedded embedded ->
                embedded.attribute().embeddable().attributes().stream().map(AttributeModel::name).toList();
            case io.vidocq.noether.jpa.core.model.IdModel.ByIdClass byIdClass -> byIdClass.attributes().stream().map(AttributeModel::name).toList();
            case io.vidocq.noether.jpa.core.model.IdModel.Derived _ -> List.of();
        };
    }

    private static int indexOf(List<AttributeModel> attributes, String name) {
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** The column of attribute {@code attribute} (at {@code path} in its embeddable) of {@code variable}. */
    private Scalar column(Variable variable, int attribute, int[] path, Class<?> type) {
        for (EntityStatements.Column column : variable.type().statements().columns()) {
            if (column.attribute() == attribute && column.foreignKey() == null && Arrays.equals(column.path(), path)) {
                return new Scalar(new Expression.Column(tableAlias(variable, column.table()), unit.queryColumn(variable.type(), column)), column.binder(), type);
            }
        }
        throw new IllegalArgumentException("The attribute " + variable.type().model().attributes().get(attribute).name() + " of "
            + variable.type().model().entityName() + " has no column");
    }

    private int attribute(Variable variable, String name) {
        if (variable.type() == null) {
            throw new IllegalArgumentException("An element of " + variable.element().model().name() + " has no relationship " + name);
        }
        return attribute(variable.type(), name);
    }

    private static int attribute(MappedEntity type, String name) {
        int index = indexOf(type.model().attributes(), name);
        if (index < 0) {
            throw new IllegalArgumentException(type.model().entityName() + " has no persistent attribute " + name);
        }
        return index;
    }

    // ---- expressions (§4.6) -------------------------------------------------------------------------------

    private Value value(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Path path -> path(path);
            case Ast.CollectionElements elements -> {
                Variable joined = join(elements.path(), Ast.Join.Kind.INNER);
                yield joined.element() == null ? entityValue(joined.alias(), joined.type()) : elementValue(joined, List.of());
            }
            case Ast.MapKeyPath key -> mapKeyPath(key);
            case Ast.Literal literal -> literal.value() == null || literal.value() instanceof Boolean || literal.value() instanceof Number
                || literal.value() instanceof String ? new Scalar(new Expression.Literal(literal.value()), null,
                    literal.value() == null ? null : literal.value().getClass()) : new Constant(literal.value());
            case Ast.Parameter parameter -> bound(parameter, null);
            case Ast.Binary binary when arithmetic(binary.op()) -> {
                Value left = value(binary.left());
                Scalar right = bound(binary.right(), left);
                Scalar leftScalar = bound(left, right);
                yield new Scalar(new Binary(leftScalar.sql(), operator(binary.op()), right.sql()), null,
                    binary.op() == Ast.Op.CONCAT ? String.class : promote(binary.op(), leftScalar.type(), right.type()));
            }
            case Ast.Negate negate -> {
                Scalar operand = scalar(value(negate.operand()));
                yield new Scalar(new Binary(new Expression.Literal(0), Operator.MINUS, operand.sql()), null, operand.type());
            }
            case Ast.Aggregate aggregate -> aggregate(aggregate);
            case Ast.Function function -> function(function);
            case Ast.Cast cast -> cast(cast);
            case Ast.Case kase -> caseValue(kase);
            case Ast.Subquery subquery -> {
                List<Compiled.Item> items = new ArrayList<>();
                SelectStatement query = subquery(subquery.select(), items);
                yield new Scalar(new Expression.Subquery(query), binder(items), type(items.getFirst()));
            }
            case Ast.Constructor _ -> throw new IllegalArgumentException("A constructor expression is a select item only (§4.8.2)");
            default -> new Scalar(condition(expr), null, Boolean.class);
        };
    }

    private static ValueBinder binder(List<Compiled.Item> items) {
        return items.getFirst() instanceof Compiled.ValueItem value ? value.binder() : null;
    }

    private static boolean arithmetic(Ast.Op op) {
        return op == Ast.Op.PLUS || op == Ast.Op.MINUS || op == Ast.Op.TIMES || op == Ast.Op.DIVIDE || op == Ast.Op.CONCAT;
    }

    /** §4.8.6: the numeric type of an arithmetic result, the widest of its operands'. */
    static Class<?> promote(Ast.Op op, Class<?> left, Class<?> right) {
        if (left == null || right == null) {
            return left == null ? right : left;
        }
        List<Class<?>> order = List.of(Short.class, Integer.class, Long.class, BigInteger.class, Float.class, Double.class, BigDecimal.class);
        int l = order.indexOf(boxed(left));
        int r = order.indexOf(boxed(right));
        if (l < 0 || r < 0) {
            return null;
        }
        Class<?> widest = order.get(Math.max(Math.max(l, r), op == Ast.Op.DIVIDE ? 0 : 1));
        return widest == Short.class ? Integer.class : widest;
    }

    private static Scalar scalar(Value value) {
        if (value instanceof Scalar scalar) {
            return scalar;
        }
        throw new IllegalArgumentException("An entity is used where a value is expected");
    }

    private Scalar cast(Ast.Cast cast) {
        Class<?> javaType = switch (cast.type()) {
            case STRING -> String.class;
            case INTEGER -> Integer.class;
            case LONG -> Long.class;
            case FLOAT -> Float.class;
            case DOUBLE -> Double.class;
            case FIXED, BIGDECIMAL -> BigDecimal.class;
            case BIGINTEGER -> BigInteger.class;
        };
        Scalar operand = bound(cast.expression(), new Scalar(null, null, javaType));
        Expression.Cast.Type sqlType = switch (cast.type()) {
            case STRING -> Expression.Cast.Type.VARCHAR;
            case INTEGER -> Expression.Cast.Type.INTEGER;
            case LONG -> Expression.Cast.Type.BIGINT;
            case FLOAT -> Expression.Cast.Type.REAL;
            case DOUBLE -> Expression.Cast.Type.DOUBLE_PRECISION;
            case FIXED, BIGDECIMAL -> Expression.Cast.Type.DECIMAL;
            case BIGINTEGER -> Expression.Cast.Type.DECIMAL;
        };
        return new Scalar(new Expression.Cast(operand.sql(), sqlType), null, javaType);
    }

    /** §4.8.5: COUNT is a Long, AVG a Double, SUM a Long, a Double or the big number of its argument, MIN and MAX theirs. */
    private Scalar aggregate(Ast.Aggregate aggregate) {
        if (aggregate.argument() == null) {
            return new Scalar(new Expression.Aggregate("COUNT", false, null), null, Long.class);
        }
        Value argument = value(aggregate.argument());
        Expression sql = switch (argument) {
            case Entity entity -> {
                if (entity.key().size() > 1 && (aggregate.distinct() || !aggregate.function().equals("COUNT"))) {
                    throw NotYet.milestone("P7", aggregate.function() + " of an entity with a composite key");
                }
                yield entity.key().getFirst();
            }
            default -> bound(argument, null).sql();
        };
        Expression.Aggregate sqlAggregate = new Expression.Aggregate(aggregate.function(), aggregate.distinct(), sql);
        Scalar scalar = argument instanceof Scalar s ? s : null;
        return switch (aggregate.function()) {
            case "COUNT" -> new Scalar(sqlAggregate, null, Long.class);
            case "AVG" -> new Scalar(sqlAggregate, null, Double.class);
            case "SUM" -> new Scalar(sqlAggregate, null, sumType(scalar == null ? null : scalar.type()));
            default -> scalar != null ? new Scalar(sqlAggregate, scalar.binder(), scalar.type()) : new Scalar(sqlAggregate, null, null);
        };
    }

    private static Class<?> sumType(Class<?> argument) {
        Class<?> type = argument == null ? null : boxed(argument);
        if (type == null) {
            return null;
        }
        if (type == BigDecimal.class || type == BigInteger.class) {
            return type;
        }
        if (type == Float.class || type == Double.class) {
            return Double.class;
        }
        return Long.class;
    }

    /** The functions of the language (§4.6.17.2), typed as §4.8 says. */
    private Value function(Ast.Function function) {
        String name = function.name();
        List<Ast.Expr> arguments = function.arguments();
        return switch (name) {
            case "TYPE" -> {
                Ast.Path path = path(arguments.getFirst(), "TYPE");
                Variable variable = variable(path.segments().getFirst());
                for (String segment : path.segments().subList(1, path.segments().size())) {
                    variable = navigate(variable, attribute(variable, segment), Query.Join.Kind.INNER);
                }
                var inheritance = unit.inheritance(variable.type().model().javaType());
                Expression sql;
                if (inheritance.typeColumn() != null) {
                    sql = new Expression.Column(variable.alias(), unit.identifier(inheritance.typeColumn()));
                } else if (!inheritance.joined()) {
                    sql = new Expression.Literal(variable.type().model().entityName());
                } else {
                    List<Expression.When> whens = new ArrayList<>();
                    var candidates = inheritance.hierarchy().stream()
                        .filter(e -> !inheritance.abstractEntity(e))
                        .filter(e -> e.javaType() != inheritance.root().javaType())
                        .sorted(java.util.Comparator.comparingInt((EntityModel e) ->
                            unit.inheritance(e.javaType()).chain().size()).reversed()).toList();
                    for (EntityModel candidate : candidates) {
                        var table = entity(candidate.javaType()).statements().tables()
                            .get(unit.inheritance(candidate.javaType()).chain().size() - 1).select();
                        String alias = alias();
                        Query query = new Query(false, List.of(new Expression.Literal(1)),
                            List.of(new Query.From(table.table(), alias, List.of())),
                            equal(names(alias, table.conditions()), key(variable.alias(), variable.type())),
                            List.of(), null, List.of(), null, null);
                        whens.add(new Expression.When(new Expression.Exists(query, false),
                            new Expression.Literal(candidate.entityName())));
                    }
                    sql = whens.isEmpty() ? new Expression.Literal(inheritance.root().entityName())
                        : new Expression.Case(null, whens, new Expression.Literal(inheritance.root().entityName()));
                }
                yield new Scalar(sql, inheritance.binder(true), Class.class);
            }
            case "ALL", "ANY" -> {
                List<Compiled.Item> items = new ArrayList<>();
                SelectStatement query = subquery(((Ast.Subquery) arguments.getFirst()).select(), items);
                yield new Scalar(new Expression.Quantified(name, query), binder(items), type(items.getFirst()));
            }
            case "SIZE" -> {
                Translator child = child();
                Variable member = child.rows(path(arguments.getFirst(), "SIZE"));
                Query query = child.collectionQuery(new Expression.Aggregate("COUNT", false, null), member, null);
                yield new Scalar(new Expression.Subquery(query), null, Integer.class);
            }
            case "INDEX" -> {
                Variable variable = variable(path(arguments.getFirst(), "INDEX").segments().getFirst());
                if (variable.position() == null) {
                    throw new IllegalArgumentException("INDEX() takes the variable of a join over a list with an order column (§4.6.17.2.2)");
                }
                yield new Scalar(variable.position(), null, Integer.class);
            }
            case "KEY" -> mapKey(arguments.getFirst());
            case "VALUE" -> value(arguments.getFirst());
            case "ENTRY" -> {
                Value key = mapKey(arguments.getFirst());
                yield new MapEntry(key, value(arguments.getFirst()));
            }
            case "ID" -> {
                Entity entity = entityOf(value(arguments.getFirst()), "ID");
                if (entity.key().size() > 1) {
                    throw NotYet.milestone("P7", "ID() of an entity with a composite key");
                }
                yield new Scalar(entity.key().getFirst(), entity.binders().getFirst(), entity.type().statements().keyColumns().size() == 1
                    && entity.type().model().id() instanceof io.vidocq.noether.jpa.core.model.IdModel.Single single
                    ? single.attribute().javaType() : null);
            }
            case "VERSION" -> {
                Ast.Path path = path(arguments.getFirst(), "VERSION");
                Variable variable = variable(path.segments().getFirst());
                if (path.segments().size() > 1 || variable.type() == null || variable.type().model().version().isEmpty()) {
                    throw new IllegalArgumentException("VERSION() takes an identification variable of a versioned entity");
                }
                BasicAttribute version = variable.type().model().version().get();
                yield column(variable, variable.type().model().attributes().indexOf(version), new int[0], version.javaType());
            }
            case "CURRENT_DATE" -> new Scalar(new Expression.Function(name, List.of()), null, java.sql.Date.class);
            case "CURRENT_TIME" -> new Scalar(new Expression.Function(name, List.of()), null, java.sql.Time.class);
            case "CURRENT_TIMESTAMP" -> new Scalar(new Expression.Function(name, List.of()), null, java.sql.Timestamp.class);
            case "LOCAL_DATE" -> new Scalar(new Expression.Function(name, List.of()), null, java.time.LocalDate.class);
            case "LOCAL_TIME" -> new Scalar(new Expression.Function(name, List.of()), null, java.time.LocalTime.class);
            case "LOCAL_DATETIME" -> new Scalar(new Expression.Function(name, List.of()), null, java.time.LocalDateTime.class);
            case "TRIM" -> new Scalar(new Expression.Function(name, List.of(new Expression.Literal(((Ast.Literal) arguments.get(0)).value()),
                bound(arguments.get(1), null).sql(), bound(arguments.get(2), null).sql())), null, String.class);
            case "EXTRACT" -> {
                String field = (String) ((Ast.Literal) arguments.get(0)).value();
                if (field.equals("DATE") || field.equals("TIME")) {
                    yield new Scalar(new Expression.Cast(bound(arguments.get(1), null).sql(),
                        field.equals("DATE") ? Expression.Cast.Type.DATE : Expression.Cast.Type.TIME), null,
                        field.equals("DATE") ? java.time.LocalDate.class : java.time.LocalTime.class);
                }
                yield new Scalar(new Expression.Function(name, List.of(new Expression.Literal(field), bound(arguments.get(1), null).sql())),
                    null, field.equals("SECOND") ? Double.class : Integer.class);
            }
            case "CONCAT", "SUBSTRING", "LOWER", "UPPER", "LEFT", "RIGHT", "REPLACE" -> sqlFunction(name, arguments, String.class);
            case "LENGTH", "LOCATE", "MOD", "SIGN" -> sqlFunction(name, arguments, Integer.class);
            case "SQRT", "EXP", "LN", "POWER" -> sqlFunction(name, arguments, Double.class);
            case "ABS", "CEILING", "FLOOR", "ROUND" -> {
                Scalar first = bound(arguments.getFirst(), null);
                yield sqlFunction(name, arguments, first.type());
            }
            case "COALESCE", "NULLIF" -> {
                Value first = value(arguments.getFirst());
                Scalar like = bound(first, null);
                List<Expression> sql = new ArrayList<>();
                sql.add(like.sql());
                for (Ast.Expr argument : arguments.subList(1, arguments.size())) {
                    sql.add(bound(argument, like).sql());
                }
                yield new Scalar(new Expression.Function(name, sql), like.binder(), like.type());
            }
            default -> throw NotYet.milestone("P7", "the function " + name);
        };
    }

    private Value mapKey(Ast.Expr expression) {
        Ast.Path path = path(expression, "KEY");
        if (path.segments().size() != 1) {
            throw new IllegalArgumentException("KEY() takes the variable of a map-valued join (§4.6.17.2.3)");
        }
        Variable variable = variable(path.segments().getFirst());
        IndexMapping index = variable.index();
        if (index == null || index.index() instanceof CollectionIndex.ByPosition) {
            throw new IllegalArgumentException("KEY() takes the variable of a map-valued join (§4.6.17.2.3)");
        }
        return switch (index.index()) {
            case CollectionIndex.ByAttribute _ when index.keyAttribute() < 0 -> entityValue(variable.alias(), variable.type());
            case CollectionIndex.ByAttribute _ -> {
                AttributeModel key = variable.type().model().attributes().get(index.keyAttribute());
                if (key instanceof BasicAttribute basic) {
                    yield basicValue(variable, index.keyAttribute(), basic);
                }
                throw NotYet.milestone("P7", "map keys that are relationship attributes of their values");
            }
            case CollectionIndex.ByColumn key ->
                new Scalar(new Expression.Column(variable.indexAlias(), index.columns().getFirst()),
                    index.binders().isEmpty() ? null : index.binders().getFirst(), key.key().javaType());
            case CollectionIndex.ByEmbedded key -> {
                List<Scalar> fields = new ArrayList<>();
                List<int[]> paths = new ArrayList<>();
                flatten(key.key().embeddable(), new int[0], (basic, keyPath) -> {
                    EntityStatements.Column column = index.keyColumns().stream()
                        .filter(c -> Arrays.equals(c.path(), keyPath)).findFirst().orElseThrow();
                    fields.add(new Scalar(new Expression.Column(variable.indexAlias(), column.name()), column.binder(), basic.javaType()));
                    paths.add(keyPath);
                });
                yield new Embedded(key.key().embeddable(), unit.access(key.key().embeddable()), List.copyOf(fields), List.copyOf(paths));
            }
            case CollectionIndex.ByEntity key -> new Entity(entity(key.entity()),
                index.columns().stream().<Expression>map(column -> new Expression.Column(variable.indexAlias(), column)).toList(),
                index.binders());
            case CollectionIndex.ByPosition _ -> throw new IllegalArgumentException("KEY() does not take a list index");
            case CollectionIndex.Unsupported unsupported -> throw NotYet.milestone("P5", unsupported.feature());
        };
    }

    private Value mapKeyPath(Ast.MapKeyPath path) {
        Ast.Path expression = new Ast.Path(List.of(path.variable()));
        Value key = mapKey(expression);
        if (path.attributes().isEmpty()) return key;
        Entity entity = entityOf(key, "Navigating a map key");
        Variable collection = variable(path.variable());
        if (collection.owner() != this) return collection.owner().mapKeyPath(path);
        String name = "_noether_key_" + collection.alias();
        Variable target = navigations.get(name);
        if (target == null) {
            String alias = alias();
            target = new Variable(entity.type(), alias, collection.root(), this);
            Expression condition = equal(entity.key(), key(alias, entity.type()));
            Expression restriction = inheritanceRestriction(target);
            if (restriction != null) condition = new Binary(condition, Operator.AND, restriction);
            joins.get(collection.root()).add(new Query.Join(Query.Join.Kind.INNER, table(entity.type()), alias, condition));
            navigations.put(name, target);
        }
        while (lookup(name) != null && lookup(name) != target) name += "_";
        if (lookup(name) == null) define(name, target);
        List<String> segments = new ArrayList<>(); segments.add(name); segments.addAll(path.attributes());
        return path(new Ast.Path(segments));
    }

    private Scalar sqlFunction(String name, List<Ast.Expr> arguments, Class<?> type) {
        List<Expression> sql = new ArrayList<>();
        for (Ast.Expr argument : arguments) {
            sql.add(bound(argument, null).sql());
        }
        return new Scalar(new Expression.Function(name, sql), null, type);
    }

    private static Ast.Path path(Ast.Expr expr, String function) {
        if (expr instanceof Ast.Path path) {
            return path;
        }
        throw new IllegalArgumentException(function + "() takes a path (§4.6.17.2)");
    }

    private static Entity entityOf(Value value, String function) {
        if (value instanceof Entity entity) {
            return entity;
        }
        throw new IllegalArgumentException(function + "() takes an entity (§4.6.17.2)");
    }

    /** {@code CASE} (§4.6.17.4): typed and read as its first result that tells its type. */
    private Scalar caseValue(Ast.Case kase) {
        Scalar operand = kase.operand() == null ? null : bound(kase.operand(), null);
        List<Expression.When> whens = new ArrayList<>();
        Scalar like = null;
        for (Ast.When when : kase.whens()) {
            Expression condition = operand == null ? condition(when.when()) : bound(when.when(), operand).sql();
            Scalar then = bound(when.then(), like);
            like = like == null || like.type() == null ? then : like;
            whens.add(new Expression.When(condition, then.sql()));
        }
        Expression otherwise = kase.otherwise() == null ? null : bound(kase.otherwise(), like).sql();
        return new Scalar(new Expression.Case(operand == null ? null : operand.sql(), whens, otherwise),
            like == null ? null : like.binder(), like == null ? null : like.type());
    }

    // ---- conditions (§4.6) --------------------------------------------------------------------------------

    private Expression condition(Ast.Expr expr) {
        return switch (expr) {
            case Ast.Binary binary when binary.op() == Ast.Op.AND || binary.op() == Ast.Op.OR ->
                new Binary(condition(binary.left()), binary.op() == Ast.Op.AND ? Operator.AND : Operator.OR, condition(binary.right()));
            case Ast.Binary binary when !arithmetic(binary.op()) -> comparison(binary);
            case Ast.Not not -> new Expression.Not(condition(not.operand()));
            case Ast.IsNull isNull -> isNull(value(isNull.operand()), isNull.negated());
            case Ast.Between between -> {
                Scalar operand = scalar(value(between.operand()));
                yield new Expression.Between(operand.sql(), bound(between.low(), operand).sql(), bound(between.high(), operand).sql(),
                    between.negated());
            }
            case Ast.Like like -> {
                Scalar operand = scalar(value(like.operand()));
                yield new Expression.Like(operand.sql(), bound(like.pattern(), operand).sql(),
                    like.escape() == null ? null : bound(like.escape(), null).sql(), like.negated());
            }
            case Ast.In in -> in(in);
            case Ast.Exists exists -> new Expression.Exists(subquery(exists.select(), new ArrayList<>()), exists.negated());
            case Ast.IsEmpty isEmpty -> {
                Translator child = child();
                Variable member = child.rows(path(isEmpty.collection(), "IS EMPTY"));
                yield new Expression.Exists(child.collectionQuery(new Expression.Literal(1), member, null), !isEmpty.negated());
            }
            case Ast.MemberOf memberOf -> memberOf(memberOf);
            case Ast.Path _, Ast.Literal _, Ast.Parameter _, Ast.Function _, Ast.Cast _, Ast.Subquery _, Ast.Case _ ->
                scalar(value(expr)).sql();
            default -> throw NotYet.milestone("P7", expr.getClass().getSimpleName());
        };
    }

    /** A query over the rows of a collection {@link #rows} declared in this (child) translator. */
    private Query collectionQuery(Expression item, Variable member, Expression condition) {
        Expression where = condition;
        for (Expression correlation : correlations) {
            where = where == null ? correlation : new Binary(correlation, Operator.AND, where);
        }
        List<Query.From> from = new ArrayList<>();
        for (int r = 0; r < roots.size(); r++) {
            from.add(new Query.From(roots.get(r), rootAliases.get(r), joins.get(r)));
        }
        return new Query(false, List.of(item), from, where, List.of(), null, List.of(), null, null);
    }

    /** {@code value [NOT] MEMBER OF collection} (§4.6.13): a row of the collection holds it. */
    private Expression memberOf(Ast.MemberOf memberOf) {
        Translator child = child();
        Variable member = child.rows(path(memberOf.collection(), "MEMBER OF"));
        Value element = member.element() != null ? child.elementValue(member, List.of()) : entityValue(member.alias(), member.type());
        Expression equality = child.equality(memberOf.value(), element);
        return new Expression.Exists(child.collectionQuery(new Expression.Literal(1), member, equality), memberOf.negated());
    }

    /** {@code left = right}, an entity compared key part by key part. */
    private Expression equality(Ast.Expr left, Value right) {
        Value value = left instanceof Ast.Parameter || left instanceof Ast.Literal ? null : value(left);
        Value bound = value != null ? value : boundValue(left, right);
        return compare(bound, Operator.EQ, right);
    }

    /** {@code left op right}: values compared, or entities compared key part by key part (§4.6.11). */
    private Expression comparison(Ast.Binary binary) {
        Operator op = operator(binary.op());
        boolean deferLeftLiteral = binary.left() instanceof Ast.Literal
            && !(binary.right() instanceof Ast.Parameter || binary.right() instanceof Ast.Literal);
        Value left = binary.left() instanceof Ast.Parameter || deferLeftLiteral ? null : value(binary.left());
        Value right = binary.right() instanceof Ast.Parameter || binary.right() instanceof Ast.Literal
            ? boundValue(binary.right(), left) : value(binary.right());
        if (left == null) {
            left = boundValue(binary.left(), right);
        }
        if (left instanceof Constant constant) {
            left = bound(constant, right instanceof Scalar s ? s : null);
        }
        if (right instanceof Constant constant) {
            right = bound(constant, left instanceof Scalar s ? s : null);
        }
        return compare(left, op, right);
    }

    private static Expression compare(Value left, Operator op, Value right) {
        if (left instanceof Embedded || right instanceof Embedded) {
            if (!(left instanceof Embedded l) || !(right instanceof Embedded r) || l.fields().size() != r.fields().size()) {
                throw new IllegalArgumentException("An embeddable is compared with a value of a different type (§4.6.11)");
            }
            if (op != Operator.EQ && op != Operator.NE) {
                throw new IllegalArgumentException("Embeddables are compared with = and <> only (§4.6.11)");
            }
            Expression result = null;
            for (int i = 0; i < l.fields().size(); i++) {
                Expression part = new Binary(l.fields().get(i).sql(), op, r.fields().get(i).sql());
                result = result == null ? part : new Binary(result, op == Operator.EQ ? Operator.AND : Operator.OR, part);
            }
            return result;
        }
        if (left instanceof Entity || right instanceof Entity) {
            if (!(left instanceof Entity l) || !(right instanceof Entity r) || l.key().size() != r.key().size()) {
                throw new IllegalArgumentException("An entity is compared with something that is not an entity of its type");
            }
            if (op != Operator.EQ && op != Operator.NE) {
                throw new IllegalArgumentException("Entities are compared with = and <> only (§4.6.11)");
            }
            Expression result = null;
            for (int k = 0; k < l.key().size(); k++) {
                Expression part = new Binary(l.key().get(k), op, r.key().get(k));
                result = result == null ? part : new Binary(result, op == Operator.EQ ? Operator.AND : Operator.OR, part);
            }
            return result;
        }
        return new Binary(((Scalar) left).sql(), op, ((Scalar) right).sql());
    }

    /** {@code expr} where {@code like} is expected: a parameter or a literal takes its binder, or the key parts of its entity. */
    private Value boundValue(Ast.Expr expr, Value like) {
        if (like instanceof Embedded embedded) {
            if (expr instanceof Ast.Parameter parameter) {
                Object value = bindings == null ? null : bindings.apply(parameter);
                if (value != null && !embedded.model().javaType().isInstance(value)) {
                    throw new IllegalArgumentException("The parameter " + parameter + " is not a "
                        + embedded.model().javaType().getName());
                }
                List<Scalar> fields = new ArrayList<>();
                for (int i = 0; i < embedded.fields().size(); i++) {
                    Scalar field = embedded.fields().get(i);
                    Expression expression;
                    if (bindings == null) {
                        expression = slot(new Compiled.Slot(parameter, -1, null, -1, field.binder(), field.type(), null,
                            embedded.model().javaType()));
                    } else {
                        Object component = value == null ? null : embeddedValue(embedded.model(), value, embedded.paths().get(i));
                        expression = slot(Compiled.Slot.constant(component, field.binder(), field.type()));
                    }
                    fields.add(new Scalar(expression, field.binder(), field.type()));
                }
                return new Embedded(embedded.model(), embedded.access(), List.copyOf(fields), embedded.paths());
            }
            if (expr instanceof Ast.Literal literal && literal.value() == null) {
                List<Scalar> fields = embedded.fields().stream().map(field ->
                    new Scalar(slot(Compiled.Slot.constant(null, field.binder(), field.type())), field.binder(), field.type())).toList();
                return new Embedded(embedded.model(), embedded.access(), fields, embedded.paths());
            }
            throw new IllegalArgumentException("An embeddable is compared with an embeddable parameter or NULL (§4.6.11)");
        }
        if (expr instanceof Ast.Parameter parameter && like instanceof Entity entity) {
            List<Expression> key = new ArrayList<>();
            for (int part = 0; part < entity.key().size(); part++) {
                key.add(slot(new Compiled.Slot(parameter, -1, entity.type(), part, entity.binders().get(part),
                    entity.type().model().javaType(), null)));
            }
            return new Entity(entity.type(), key, entity.binders());
        }
        if (expr instanceof Ast.Literal literal && like instanceof Entity entity) {
            Object instance = literal.value();
            if (instance != null && !entity.type().model().javaType().isInstance(instance)) {
                throw new IllegalArgumentException("Entity literal has an incompatible type");
            }
            Object id = instance == null ? null : entity.type().id(instance);
            Object[] parts = id == null ? new Object[entity.key().size()] : entity.type().statements().keyValues(id);
            List<Expression> key = new ArrayList<>();
            for (int part = 0; part < parts.length; part++) {
                key.add(slot(Compiled.Slot.constant(parts[part], entity.binders().get(part),
                    parts[part] == null ? Object.class : parts[part].getClass())));
            }
            return new Entity(entity.type(), key, entity.binders());
        }

        return bound(expr, like instanceof Scalar scalar ? scalar : null);
    }

    private Object embeddedValue(EmbeddableModel model, Object instance, int[] path) {
        Object value = instance;
        EmbeddableModel current = model;
        for (int index : path) {
            if (value == null) {
                return null;
            }
            AttributeModel attribute = current.attributes().get(index);
            value = unit.access(current).get(value, index);
            if (attribute instanceof EmbeddedAttribute nested) {
                current = nested.embeddable();
            }
        }
        return value;
    }

    /** The SQL of {@code expr} where {@code like} is expected: a parameter or a constant takes its binder. */
    private Scalar bound(Ast.Expr expr, Value like) {
        Scalar with = like instanceof Scalar scalar ? scalar : null;
        if (expr instanceof Ast.Parameter parameter) {
            return new Scalar(slot(new Compiled.Slot(parameter, -1, null, -1, with == null ? null : with.binder(),
                with == null ? null : with.type(), null)), null, with == null ? null : with.type());
        }
        return bound(value(expr), with);
    }

    /** A value as a SQL scalar: a constant bound through the binder of {@code like}. */
    private Scalar bound(Value value, Scalar like) {
        return switch (value) {
            case Scalar scalar -> {
                if (scalar.sql() instanceof Expression.Literal literal && like != null && like.binder() != null
                        && like.binder().converted())
                    yield new Scalar(slot(Compiled.Slot.constant(literal.value(), like.binder(), like.type())), like.binder(), like.type());
                yield scalar;
            }
            case Constant constant -> new Scalar(slot(Compiled.Slot.constant(constant.value(), like == null ? null : like.binder(),
                like == null ? null : like.type())), like == null ? null : like.binder(),
                constant.value() == null ? null : constant.value().getClass());
            case Embedded _ -> throw new IllegalArgumentException("An embeddable must be compared as a whole (§4.6.11)");
            case MapEntry _ -> throw new IllegalArgumentException("ENTRY() is a select item (§4.6.17.2.3)");
            case Entity entity -> {
                if (entity.key().size() > 1) {
                    throw new IllegalArgumentException("An entity with a composite key is used where a value is expected");
                }
                yield new Scalar(entity.key().getFirst(), entity.binders().getFirst(), null);
            }
        };
    }

    private Expression isNull(Value value, boolean negated) {
        Expression result = null;
        for (Expression part : sql(value)) {
            Expression test = new Expression.IsNull(part, negated);
            result = result == null ? test : new Binary(result, negated ? Operator.OR : Operator.AND, test);
        }
        return result;
    }

    /** {@code IN} values, a collection-valued parameter expanded to its elements (§4.6.9), or a subquery. */
    private Expression in(Ast.In in) {
        Value operand = value(in.operand());
        if (in.values().size() == 1 && in.values().getFirst() instanceof Ast.Subquery subquery) {
            Expression left = switch (operand) {
                case Entity entity when entity.key().size() == 1 -> entity.key().getFirst();
                case Entity _ -> throw NotYet.milestone("P7", "IN a subquery for an entity with a composite key");
                default -> bound(operand, null).sql();
            };
            return new Expression.InQuery(left, subquery(subquery.select(), new ArrayList<>()), in.negated());
        }
        if (operand instanceof Entity) {
            throw NotYet.milestone("P7", "IN with entities");
        }
        Scalar like = (Scalar) bound(operand, null);
        List<Expression> values = new ArrayList<>();
        for (Ast.Expr value : in.values()) {
            if (value instanceof Ast.Parameter parameter) {
                Object bound = bindings == null ? null : bindings.apply(parameter);
                int size = bound instanceof Collection<?> collection ? collection.size() : -1;
                if (size == 0) {
                    return new Binary(new Expression.Literal(1), in.negated() ? Operator.EQ : Operator.NE, new Expression.Literal(1));
                }
                for (int element = 0; element < Math.max(size, 1); element++) {
                    values.add(slot(new Compiled.Slot(parameter, size < 0 ? -1 : element, null, -1, like.binder(), like.type(), null)));
                }
            } else {
                values.add(bound(value, like).sql());
            }
        }
        return new Expression.In(like.sql(), values, in.negated());
    }

    private static Expression slot(Compiled.Slot slot) {
        return new Expression.Parameter(slot);
    }

    private static Operator operator(Ast.Op op) {
        return switch (op) {
            case OR -> Operator.OR;
            case AND -> Operator.AND;
            case EQ -> Operator.EQ;
            case NE -> Operator.NE;
            case LT -> Operator.LT;
            case LE -> Operator.LE;
            case GT -> Operator.GT;
            case GE -> Operator.GE;
            case PLUS -> Operator.PLUS;
            case MINUS -> Operator.MINUS;
            case TIMES -> Operator.TIMES;
            case DIVIDE -> Operator.DIVIDE;
            case CONCAT -> Operator.CONCAT;
        };
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String alias() {
        return "t" + aliases[0]++;
    }

    private MappedEntity entity(Class<?> type) {
        return unit.entity(type).orElseThrow(() -> new IllegalArgumentException(type.getName() + " is not an entity of the unit"));
    }

    private Table table(MappedEntity type) {
        return unit.queryTable(type);
    }

    private Entity entityValue(String alias, MappedEntity type) {
        List<EntityStatements.Column> keyColumns = type.statements().keyColumns();
        return new Entity(type, key(alias, type), keyColumns.stream().map(EntityStatements.Column::binder).toList());
    }

    private List<Expression> key(String alias, MappedEntity type) {
        return type.statements().keyColumns().stream().<Expression>map(c -> new Expression.Column(alias, unit.queryColumn(type, c))).toList();
    }

    private static List<Expression> columns(String alias, EntityStatements statements, int[] indexes) {
        List<Expression> columns = new ArrayList<>();
        for (int index : indexes) {
            columns.add(new Expression.Column(alias, statements.columns().get(index).name()));
        }
        return columns;
    }

    private List<Expression> columns(Variable variable, int[] indexes) {
        List<Expression> columns = new ArrayList<>();
        for (int index : indexes) {
            EntityStatements.Column column = variable.type().statements().columns().get(index);
            columns.add(new Expression.Column(tableAlias(variable, column.table()), unit.queryColumn(variable.type(), column)));
        }
        return columns;
    }

    private static List<ValueBinder> binders(EntityStatements statements, int[] indexes) {
        List<ValueBinder> binders = new ArrayList<>();
        for (int index : indexes) {
            binders.add(statements.columns().get(index).binder());
        }
        return binders;
    }

    private static List<Expression> names(String alias, List<Identifier> names) {
        return names.stream().<Expression>map(n -> new Expression.Column(alias, n)).toList();
    }

    private static Expression equal(List<Expression> left, List<Expression> right) {
        Expression result = null;
        for (int k = 0; k < left.size(); k++) {
            Expression part = new Binary(left.get(k), Operator.EQ, right.get(k));
            result = result == null ? part : new Binary(result, Operator.AND, part);
        }
        return result;
    }

    private static Class<?> boxed(Class<?> type) {
        return MethodType.methodType(type).wrap().returnType();
    }
}
