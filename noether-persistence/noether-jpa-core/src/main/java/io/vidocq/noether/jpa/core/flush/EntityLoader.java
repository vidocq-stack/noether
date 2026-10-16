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
package io.vidocq.noether.jpa.core.flush;

import io.vidocq.noether.jpa.core.context.EntityKey;
import io.vidocq.noether.jpa.core.context.ManagedEntity;
import io.vidocq.noether.jpa.core.context.PersistenceContext;
import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.mapping.CollectionMapping;
import io.vidocq.noether.jpa.core.mapping.CompositeId;
import io.vidocq.noether.jpa.core.mapping.ElementCollectionMapping;
import io.vidocq.noether.jpa.core.mapping.IndexMapping;
import io.vidocq.noether.jpa.core.mapping.LazyCollection;
import io.vidocq.noether.jpa.core.mapping.EntityStatements;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.CollectionIndex;
import io.vidocq.noether.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.noether.jpa.core.model.EmbeddableModel;
import io.vidocq.noether.jpa.core.model.EmbeddedAttribute;
import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.core.spi.LazyReference;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import io.vidocq.noether.jpa.dialect.sql.Select;
import io.vidocq.noether.jpa.dialect.sql.Expression;
import io.vidocq.noether.jpa.dialect.sql.Query;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds managed instances from their rows (§3.2.8): the select by identifier of an entity, its columns read through
 * their binders, the state rebuilt (embeddables included) and written through the access, then the instance made
 * managed with its snapshot. Shares the SQL the flush engine rendered.
 *
 * <p>Eligible LAZY owning to-one foreign-key properties receive a generated managed reference; unsupported forms
 * remain eager (§2.10, §11.1.26). An inverse side is resolved from the owning side of its target. A LAZY collection
 * (a to-many relationship or an element collection) is a {@link LazyCollection} that {@link #initialize loads} its
 * elements on its first access; an EAGER one is loaded with its owner, from its join table, the foreign keys of its
 * targets or its collection table. The instance is managed before its relationships are resolved, so that a cycle
 * closes on it. Each public read is a {@link PersistenceContext#loading load}: its PostLoad callbacks run once the
 * outermost load has returned, after the collections it was initializing are complete (BUG-20261016-01).
 */
public final class EntityLoader {

    private final FlushEngine engine;
    private final MappedUnit unit;
    private final int batchSize;

    public EntityLoader(FlushEngine engine, MappedUnit unit) {
        this(engine, unit, 1);
    }

    public EntityLoader(FlushEngine engine, MappedUnit unit, int batchSize) {
        this.engine = engine;
        this.unit = unit;
        this.batchSize = batchSize;
    }

    /**
     * The managed instance of {@code type} with identity {@code id}, read through {@code connection}; {@code null} if no
     * row has it. If the context already manages that identity, its instance is returned.
     */
    public Object load(MappedEntity type, Object id, Connection connection, PersistenceContext context) {
        return load(type, id, connection, context, LockModeType.NONE, null);
    }

    /**
     * Like {@link #load(MappedEntity, Object, Connection, PersistenceContext)}, the primary row locked in a pessimistic
     * {@code mode} (§3.5.6), waiting at most {@code timeout} milliseconds ({@code null}: the database's timeout).
     */
    public Object load(MappedEntity type, Object id, Connection connection, PersistenceContext context, LockModeType mode,
            Integer timeout) {
        return context.loading(() -> read(type, id, connection, context, mode, timeout));
    }

    private Object read(MappedEntity type, Object id, Connection connection, PersistenceContext context, LockModeType mode,
            Integer timeout) {
        Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
        if (known.isPresent()) {
            if (LazyReference.unloaded(known.get().instance())) {
                LazyReference.initialize(known.get().instance());
            }
            return type.model().javaType().isInstance(known.get().instance())
                && known.get().status() == ManagedEntity.Status.MANAGED
                ? known.get().instance() : null;
        }
        MappedEntity concrete = concrete(type, id, connection);
        if (concrete == null) {
            return null;
        }
        type = concrete;
        Row row = row(type, id, connection, mode, timeout);
        if (row == null) {
            return null;
        }
        return managed(type, id, row, connection, context);
    }

    /**
     * The managed instance of {@code type} with identity {@code id}, built from the columns a fetch join query already
     * read for it ({@code values} and {@code nulls} indexed like {@link EntityStatements#columns()}): the instance the
     * context already manages, an unloaded reference of it initialized in place from them, else a new managed instance.
     * {@code type} is the concrete type of the row: the query only reads the columns of an entity without subclasses.
     */
    public Object load(MappedEntity type, Object id, Object[] values, boolean[] nulls, Connection connection,
            PersistenceContext context) {
        return context.loading(() -> read(type, id, values, nulls, connection, context));
    }

    private Object read(MappedEntity type, Object id, Object[] values, boolean[] nulls, Connection connection,
            PersistenceContext context) {
        Row row = new Row(values, nulls);
        Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), id));
        if (known.isEmpty()) {
            return managed(type, id, row, connection, context);
        }
        Object instance = known.get().instance();
        if (LazyReference.unloaded(instance)) {
            if (known.get().type() == type) {
                ((LazyReference) instance).$noetherState().hydrate(() -> hydrateReference(known.get(), row, connection, context));
            } else {
                LazyReference.initialize(instance);
            }
        }
        return instance;
    }

    /** Entity columns captured by a SQL fetch query, not a second mapping representation. */
    public record FetchedEntity(MappedEntity type, Object id, Object[] values, boolean[] nulls) {}

    /** One collection row; an absent LEFT row still records its owner. Entity elements carry their identifier. */
    public record FetchedElement(Object ownerId, Object element, Object[] index, boolean present) {}

    /**
     * Registers all identities before resolving relationships, then completes whole collections and snapshots before
     * any PostLoad callback. Existing managed state is preserved; an unloaded wrapper is completed in place.
     */
    public void loadCollection(List<FetchedEntity> rows, MappedEntity owner, int attribute, List<FetchedElement> elements,
            Connection connection, PersistenceContext context) {
        context.loading(() -> {
            loadRows(rows, owner, attribute, elements, connection, context, null);
            return null;
        });
    }

    private void loadRows(List<FetchedEntity> rows, MappedEntity owner, int attribute, List<FetchedElement> elements,
            Connection connection, PersistenceContext context, ManagedEntity initializing) {
        Map<ManagedEntity, Row> pending = new LinkedHashMap<>();
        for (FetchedEntity fetched : rows) {
            MappedEntity type = fetched.type();
            var known = context.find(new EntityKey(type.root(), fetched.id()));
            if (known.isPresent() && !LazyReference.unloaded(known.get().instance())) continue;
            Row row = new Row(fetched.values(), fetched.nulls());
            if (known.isPresent()) {
                ManagedEntity entry = known.get();
                if (entry == initializing) {
                    state(type, entry.instance(), row);
                    ((LazyReference) entry.instance()).$noetherState().complete();
                } else {
                    ((LazyReference) entry.instance()).$noetherState().hydrate(() -> state(type, entry.instance(), row));
                }
                pending.put(entry, row);
            } else {
                Object instance = type.access().instantiate();
                Object[] state = state(type, instance, row);
                if (type.keepsFoundIdentifier()) {
                    state[type.idAttributes()[0]] = fetched.id();
                    type.access().set(instance, type.idAttributes()[0], fetched.id());
                }
                pending.put(context.loaded(instance, type, fetched.id(), type.state().snapshot(state)), row);
            }
        }
        Map<Object, List<Indexed>> grouped = new LinkedHashMap<>();
        AttributeModel model = owner == null ? null : owner.model().attributes().get(attribute);
        MappedEntity target = model instanceof AssociationAttribute a ? entity(a.targetEntity()) : null;
        IndexMapping index = owner == null ? null : target == null ? owner.statements().elementCollection(attribute).orElseThrow().index()
            : indexOf(owner.statements().collection(attribute).orElseThrow());
        for (FetchedElement row : elements) {
            List<Indexed> group = grouped.computeIfAbsent(row.ownerId(), _ -> new ArrayList<>());
            if (!row.present()) continue;
            Object element = target == null ? row.element()
                : context.find(new EntityKey(target.root(), row.element())).orElseThrow().instance();
            group.add(new Indexed(element, index(index, row.index(), 0, connection, context)));
        }
        for (var item : pending.entrySet()) {
            ManagedEntity entry = item.getKey();
            MappedEntity type = entry.type();
            Object[] state = new Object[type.model().attributes().size()];
            type.access().read(entry.instance(), state);
            relationships(type, entry.key().id(), entry.instance(), state, item.getValue(), connection, context,
                null, type == owner && grouped.containsKey(entry.key().id()) ? attribute : -1);
            type.deriveId(entry.instance());
        }
        for (var group : grouped.entrySet()) {
            ManagedEntity entry = context.find(new EntityKey(owner.root(), group.getKey())).orElseThrow();
            Object current = owner.access().get(entry.instance(), attribute);
            if (!pending.containsKey(entry) && !LazyCollection.unloaded(current)) continue;
            Object value;
            if (model instanceof AssociationAttribute a) {
                value = assemble(a.javaType(), index, group.getValue(), target, a.orderBy(),
                    target.model().attributes(), target.access(), target::id, describe(a));
            } else {
                ElementCollectionAttribute e = (ElementCollectionAttribute) model;
                EmbeddedAttribute embedded = e.element() instanceof EmbeddedAttribute b ? b : null;
                value = assemble(e.javaType(), index, group.getValue(), null, e.orderBy(),
                    embedded == null ? List.of() : embedded.embeddable().attributes(),
                    embedded == null ? null : unit.access(embedded.embeddable()), Function.identity(), e.name());
            }
            Object baseline = owner.state().copy(attribute, value);
            if (LazyCollection.unloaded(current)) {
                LazyCollection lazy = (LazyCollection) current;
                if (entry.snapshot() != null && entry.snapshot()[attribute] == lazy) entry.snapshot()[attribute] = baseline;
                lazy.complete(value, baseline);
            } else {
                owner.access().set(entry.instance(), attribute, value);
            }
        }
        // No snapshot or callback sees a graph whose later rows/relationships are still missing.
        for (ManagedEntity entry : pending.keySet()) {
            Object[] state = new Object[entry.type().model().attributes().size()];
            entry.type().access().read(entry.instance(), state);
            context.updated(entry, entry.type().state().snapshot(state));
        }
        for (ManagedEntity entry : pending.keySet()) context.postLoad(() -> entry.type().callback("PostLoad", entry.instance()));
    }

    /** Makes a new instance of {@code type} managed from its {@code row}, then resolves its relationships. */
    private Object managed(MappedEntity type, Object id, Row row, Connection connection, PersistenceContext context) {
        Object instance = type.access().instantiate();
        Object[] state = state(type, instance, row);
        if (type.keepsFoundIdentifier()) {
            // the identifier object the application found it with, as other providers keep it (equal to the one read)
            int index = type.idAttributes()[0];
            state[index] = id;
            type.access().set(instance, index, id);
        }
        ManagedEntity entry = context.loaded(instance, type, id, type.state().snapshot(state));
        if (entry.instance() != instance) {
            return entry.instance();
        }
        relationships(type, id, instance, state, row, connection, context);
        type.deriveId(instance); // §2.4.1: the identifier attributes an @MapsId maps, from the parent now loaded
        type.access().read(instance, state);
        context.updated(entry, type.state().snapshot(state));
        context.postLoad(() -> type.callback("PostLoad", instance)); // §3.6.3: once the state is loaded
        return instance;
    }

    /** Hydrates the already registered reference, never allocating a second instance for its identity. */
    public void initializeReference(ManagedEntity entry, Connection connection, PersistenceContext context) {
        context.loading(() -> {
            readReference(entry, connection, context);
            return null;
        });
    }

    private void readReference(ManagedEntity entry, Connection connection, PersistenceContext context) {
        MappedEntity type = entry.type();
        if (batchSize > 1 && batchable(type)) {
            List<Object> ids = new ArrayList<>();
            ids.add(entry.key().id());
            int bound = bound(type.statements().keyColumns().size());
            for (ManagedEntity candidate : context.entries()) {
                if (ids.size() >= bound) break;
                if (candidate != entry && candidate.type() == type
                        && candidate.status() == ManagedEntity.Status.MANAGED
                        && LazyReference.unloaded(candidate.instance())) ids.add(candidate.key().id());
            }
            List<FetchedEntity> rows = entityRows(type, ids, connection);
            if (rows.stream().noneMatch(r -> Objects.equals(r.id(), entry.key().id()))) {
                throw new jakarta.persistence.EntityNotFoundException(
                    "The row of " + type.model().entityName() + " " + entry.key().id() + " no longer exists");
            }
            loadRows(rows, null, -1, List.of(), connection, context, entry);
            return;
        }
        Object instance = entry.instance();
        Row row = row(type, entry.key().id(), connection, LockModeType.NONE, null);
        if (row == null) throw new jakarta.persistence.EntityNotFoundException(
            "The row of " + type.model().entityName() + " " + entry.key().id() + " no longer exists");
        hydrateReference(entry, row, connection, context);
        ((LazyReference) instance).$noetherState().complete();
    }

    /** Writes {@code row} into the registered reference of {@code entry}, its snapshot and relationships included. */
    private void hydrateReference(ManagedEntity entry, Row row, Connection connection, PersistenceContext context) {
        MappedEntity type = entry.type();
        Object instance = entry.instance();
        Object[] state = state(type, instance, row);
        relationships(type, entry.key().id(), instance, state, row, connection, context);
        type.access().read(instance, state);
        context.updated(entry, type.state().snapshot(state));
        context.postLoad(() -> type.callback("PostLoad", instance));
    }

    private Object reference(AssociationAttribute association, MappedEntity target, Object key,
            Connection connection, PersistenceContext context) {
        boolean supported = unit.lazyReference(association) && context.referenceLoader() != null;
        if (!supported) return find(target, key, connection, context);
        var known = context.find(new EntityKey(target.root(), key));
        if (known.isPresent()) return known.get().instance();
        Object instance = target.access().instantiateLazy();
        if (instance == null) return find(target, key, connection, context);
        target.access().set(instance, target.idAttributes()[0], key);
        context.reference(instance, target, key);
        ((LazyReference) instance).$noetherState(new LazyReference.State(() -> context.referenceLoader().accept(instance)));
        return instance;
    }

    /** Whether a row of {@code type} has the identity {@code id}, without managing anything. */
    public boolean exists(MappedEntity type, Object id, Connection connection) {
        MappedEntity concrete = concrete(type, id, connection);
        return concrete != null && row(concrete, id, connection, LockModeType.NONE, null) != null;
    }

    /** Resolves the row's concrete entity before instantiating an abstract or polymorphic requested type (§2.14). */
    private MappedEntity concrete(MappedEntity requested, Object id, Connection connection) {
        var inheritance = unit.inheritance(requested.model().javaType());
        if (inheritance.discriminator() == null && inheritance.hierarchy().size() == 1) {
            return inheritance.abstractEntity(requested.model()) ? null : requested;
        }
        MappedEntity root = entity(inheritance.root().javaType());
        if (inheritance.discriminator() != null) {
            Row row = row(root, id, connection, LockModeType.NONE, null);
            if (row == null) {
                return null;
            }
            Object discriminator = null;
            List<EntityStatements.Column> columns = root.statements().columns();
            for (int c = 0; c < columns.size(); c++) {
                if (columns.get(c).attribute() < 0) {
                    discriminator = row.values()[c];
                }
            }
            Class<?> concrete = inheritance.concrete(discriminator);
            return requested.model().javaType().isAssignableFrom(concrete) ? entity(concrete) : null;
        }
        List<io.vidocq.noether.jpa.core.model.EntityModel> candidates = inheritance.hierarchy().stream()
            .filter(e -> requested.model().javaType().isAssignableFrom(e.javaType()))
            .filter(e -> !inheritance.abstractEntity(e))
            .sorted(Comparator.comparingInt((io.vidocq.noether.jpa.core.model.EntityModel e) ->
                unit.inheritance(e.javaType()).chain().size()).reversed()).toList();
        for (var candidate : candidates) {
            MappedEntity type = entity(candidate.javaType());
            if (row(type, id, connection, LockModeType.NONE, null) != null) {
                return type;
            }
        }
        return null;
    }

    /**
     * §3.2.5: overwrites the state of the managed {@code instance} with its row, and returns the new snapshot;
     * {@code null} if the row is gone.
     */
    public Object[] refresh(MappedEntity type, Object id, Object instance, Connection connection, PersistenceContext context) {
        return context.loading(() -> reread(type, id, instance, connection, context));
    }

    private Object[] reread(MappedEntity type, Object id, Object instance, Connection connection, PersistenceContext context) {
        Row row = row(type, id, connection, LockModeType.NONE, null);
        if (row == null) {
            return null;
        }
        Object[] previous = new Object[type.model().attributes().size()];
        type.access().read(instance, previous);
        Object[] state = state(type, instance, row);
        relationships(type, id, instance, state, row, connection, context, previous);
        type.access().read(instance, state);
        Object[] snapshot = type.state().snapshot(state);
        context.postLoad(() -> type.callback("PostLoad", instance)); // §3.6.3: after a refresh too
        return snapshot;
    }

    // ---- relationships (§2.10) ---------------------------------------------------------------------------

    /** An element of a collection read from the database, and its index: its map key or its position, if any. */
    private record Indexed(Object element, Object index) {
    }

    /**
     * Sets the relationships of {@code instance}, identity {@code id}, read from {@code row}: the instance an owned
     * foreign key references, the owner of an inverse one-to-one, the elements of a collection — through its join
     * table, or through the owning side of its target — with their keys or in their order.
     */
    private void relationships(MappedEntity type, Object id, Object instance, Object[] state, Row row, Connection connection,
            PersistenceContext context) {
        relationships(type, id, instance, state, row, connection, context, null);
    }

    /**
     * Like {@link #relationships(MappedEntity, Object, Object, Object[], Row, Connection, PersistenceContext)};
     * {@code previous}, the state of a refreshed instance, keeps a LAZY collection that was not loaded unloaded.
     */
    private void relationships(MappedEntity type, Object id, Object instance, Object[] state, Row row, Connection connection,
            PersistenceContext context, Object[] previous) {
        relationships(type, id, instance, state, row, connection, context, previous, -1);
    }

    private void relationships(MappedEntity type, Object id, Object instance, Object[] state, Row row, Connection connection,
            PersistenceContext context, Object[] previous, int fetchedCollection) {
        EntityStatements statements = type.statements();
        List<AttributeModel> attributes = type.model().attributes();
        for (int i = 0; i < attributes.size(); i++) {
            if (i == fetchedCollection) continue;
            Object value;
            if (attributes.get(i) instanceof AssociationAttribute association && association.singleValued()) {
                Optional<EntityStatements.Reference> owned = statements.reference(i);
                if (owned.isPresent()) {
                    Object key = statements.referencedKey(owned.get(), row.values());
                    value = key == null ? null : reference(association, entity(association.targetEntity()), key, connection, context);
                } else if (!association.owning()) {
                    List<Indexed> owners = inverse(association, null, id, statements, connection, context);
                    value = owners.isEmpty() ? null : owners.getFirst().element();
                } else {
                    CollectionMapping mapping = statements.collection(i).orElseThrow();
                    if (!(mapping instanceof CollectionMapping.JoinTable table)) continue;
                    List<Object[]> found = keys(table.targets(), table.ownerBinders(), statements.keyValues(id),
                        table.targetBinders(), describe(association), connection);
                    value = found.isEmpty() || Arrays.stream(found.getFirst()).allMatch(java.util.Objects::isNull)
                        ? null : find(entity(association.targetEntity()),
                        entity(association.targetEntity()).statements().key(found.getFirst()), connection, context);
                }
            } else if (attributes.get(i) instanceof AssociationAttribute || attributes.get(i) instanceof ElementCollectionAttribute) {
                if (previous != null && LazyCollection.unloaded(previous[i])
                        && ((LazyCollection) previous[i]).owner() == instance && lazy(type, i, context)) {
                    value = previous[i]; // §3.2.5: still not loaded, it will read the refreshed rows on its first access
                } else if (lazy(type, i, context) && (previous == null || !cascadesRefresh(attributes.get(i)))) {
                    value = LazyCollection.of(attributes.get(i).javaType(), instance, i, attributes.get(i).name(),
                        context.lazyLoader());
                } else {
                    value = collection(type, i, id, connection, context);
                    if (value == NOT_MAPPED) {
                        continue; // a shape not mapped yet: the collection keeps what the instance holds
                    }
                }
            } else {
                continue;
            }
            state[i] = value;
            type.access().set(instance, i, value);
        }
    }

    /** No value: a collection whose shape is not mapped yet keeps what the instance holds. */
    private static final Object NOT_MAPPED = new Object();

    /**
     * Whether the collection attribute {@code i} of {@code type} is left unloaded with its owner: declared LAZY (the
     * default of §11.1.26 for collections), mapped, of a declared interface a wrapper can be assigned to, not reached
     * through an embeddable, and the context can load it later.
     */
    private static boolean lazy(MappedEntity type, int i, PersistenceContext context) {
        if (context.lazyLoader() == null) return false;
        AttributeModel attribute = type.model().attributes().get(i);
        if (attribute.name().indexOf('.') >= 0 || !LazyCollection.supports(attribute.javaType())) return false;
        return switch (attribute) {
            case AssociationAttribute association -> !association.singleValued()
                && association.fetch() == jakarta.persistence.FetchType.LAZY
                && type.statements().collection(i).filter(m -> !(m instanceof CollectionMapping.Unsupported)).isPresent();
            case ElementCollectionAttribute elements -> elements.fetch() == jakarta.persistence.FetchType.LAZY
                && type.statements().elementCollection(i).isPresent();
            default -> false;
        };
    }

    private static boolean cascadesRefresh(AttributeModel attribute) {
        return attribute instanceof AssociationAttribute association
            && (association.cascade().contains(jakarta.persistence.CascadeType.REFRESH)
                || association.cascade().contains(jakarta.persistence.CascadeType.ALL));
    }

    /**
     * Loads the LAZY collection {@code lazy} of the managed {@code entry} through {@code connection} (§2.10): its elements
     * become those of the collection, and their snapshot the one of the owner where it still holds the unloaded
     * collection, so that the next flush compares them.
     */
    public void initialize(ManagedEntity entry, LazyCollection lazy, Connection connection, PersistenceContext context) {
        context.loading(() -> {
            complete(entry, lazy, connection, context);
            return null;
        });
    }

    private void complete(ManagedEntity entry, LazyCollection lazy, Connection connection, PersistenceContext context) {
        MappedEntity type = entry.type();
        int i = lazy.attribute();
        if (batchSize > 1 && type.access().get(entry.instance(), i) == lazy
                && batchable(type) && batchCollection(type, i, entry, lazy, connection, context)) return;
        Object value = collection(type, i, entry.key().id(), connection, context);
        if (value == NOT_MAPPED) {
            throw new PersistenceException("The collection " + lazy.attributeName() + " of " + type.model().entityName()
                + " is not mapped");
        }
        Object baseline = type.state().copy(i, value);
        Object[] snapshot = entry.snapshot();
        if (snapshot != null && snapshot[i] == lazy) {
            snapshot[i] = baseline;
        }
        lazy.complete(value, baseline);
    }

    /** Deliberately conservative: secondary tables and polymorphism keep the ordinary complete loader. */
    private boolean batchable(MappedEntity type) {
        return type.statements().tables().size() == 1
            && unit.inheritance(type.model().javaType()).hierarchy().size() == 1
            && type.statements().keyColumns().size() <= 512;
    }

    /** Both shipped databases allow at least 512 parameters; cap composite-key groups by their actual width. */
    private int bound(int width) {
        return Math.min(batchSize, 512 / Math.max(1, width));
    }

    private boolean batchCollection(MappedEntity type, int attribute, ManagedEntity requested, LazyCollection lazy,
            Connection connection, PersistenceContext context) {
        AttributeModel model = type.model().attributes().get(attribute);
        ElementCollectionMapping elements = type.statements().elementCollection(attribute).orElse(null);
        MappedEntity target = model instanceof AssociationAttribute a ? entity(a.targetEntity()) : null;
        Select select;
        List<ValueBinder> owners;
        List<ValueBinder> readers = new ArrayList<>();
        IndexMapping index;
        int elementWidth;
        if (elements != null) {
            select = elements.select();
            owners = elements.ownerBinders();
            index = elements.index();
            if (index != null) readers.addAll(index.binders());
            readers.addAll(elements.columns().stream().map(EntityStatements.Column::binder).toList());
            elementWidth = elements.columns().size();
        } else {
            if (target == null || !batchable(target)) return false;
            CollectionMapping mapping = type.statements().collection(attribute).orElse(null);
            index = indexOf(mapping);
            if (mapping instanceof CollectionMapping.JoinTable table) {
                select = table.targets();
                owners = table.ownerBinders();
                readers.addAll(table.targetBinders());
            } else if (mapping instanceof CollectionMapping.ForeignKey foreign) {
                select = foreign.targets();
                owners = foreign.ownerBinders();
                readers.addAll(foreign.targetBinders());
            } else if (mapping instanceof CollectionMapping.MappedBy inverse) {
                AssociationAttribute association = inverse.association();
                int owning = -1;
                for (int i = 0; i < target.model().attributes().size(); i++) {
                    if (target.model().attributes().get(i).name().equals(association.mappedBy())) owning = i;
                }
                var reference = target.statements().reference(owning).orElse(null);
                if (reference == null) return false; // inverse join tables retain the ordinary loader
                select = target.statements().ownersSelect(reference);
                owners = Arrays.stream(reference.columns()).mapToObj(c -> target.statements().columns().get(c).binder()).toList();
                readers.addAll(target.statements().keyColumns().stream().map(EntityStatements.Column::binder).toList());
                if (index != null && index.stored()) {
                    List<Identifier> columns = new ArrayList<>(select.columns());
                    columns.addAll(index.columns());
                    select = new Select(select.table(), columns, select.conditions());
                }
            } else return false;
            elementWidth = readers.size();
            if (index != null && index.stored()) readers.addAll(index.binders());
        }
        if (index != null && index.keyEntity() != null || owners.size() > 512) return false;

        List<ManagedEntity> group = new ArrayList<>();
        group.add(requested);
        for (ManagedEntity candidate : context.entries()) {
            if (group.size() >= bound(owners.size())) break;
            if (candidate == requested || candidate.type() != type || candidate.key() == null
                    || candidate.status() != ManagedEntity.Status.MANAGED
                    || LazyReference.unloaded(candidate.instance())) continue;
            Object value = type.access().get(candidate.instance(), attribute);
            if (LazyCollection.unloaded(value) && ((LazyCollection) value).owner() == candidate.instance()) group.add(candidate);
        }
        List<Object[]> keys = group.stream().map(e -> type.statements().keyValues(e.key().id())).toList();
        List<Row> rows = batchKeys(select, owners, keys, readers, connection);
        List<FetchedElement> fetched = new ArrayList<>();
        for (ManagedEntity entry : group) fetched.add(new FetchedElement(entry.key().id(), null, new Object[0], false));
        List<Object> targetIds = new ArrayList<>();
        for (Row row : rows) {
            int width = owners.size();
            Object ownerId = type.statements().key(Arrays.copyOf(row.values(), width));
            Object value;
            Object[] indexes;
            if (elements != null) {
                int indexWidth = elements.indexColumns().size();
                value = elements.element(Arrays.copyOfRange(row.values(), width + indexWidth, width + indexWidth + elementWidth),
                    Arrays.copyOfRange(row.nulls(), width + indexWidth, width + indexWidth + elementWidth));
                indexes = nullableValues(row, width, width + indexWidth);
            } else {
                Object[] id = nullableValues(row, width, width + elementWidth);
                if (Arrays.stream(id).allMatch(Objects::isNull)) continue;
                value = target.statements().key(id);
                targetIds.add(value);
                indexes = nullableValues(row, width + elementWidth, row.values().length);
            }
            fetched.add(new FetchedElement(ownerId, value, indexes, true));
        }
        if (target != null) {
            List<Object> missing = targetIds.stream().distinct().filter(id -> context.find(new EntityKey(target.root(), id))
                .map(e -> LazyReference.unloaded(e.instance())).orElse(true)).toList();
            for (int from = 0; from < missing.size(); from += bound(target.statements().keyColumns().size())) {
                List<Object> chunk = missing.subList(from, Math.min(missing.size(), from + bound(target.statements().keyColumns().size())));
                loadRows(entityRows(target, chunk, connection), null, -1, List.of(), connection, context, null);
            }
            // A dangling FK must not publish a partial collection or an uninitialized reference.
            for (Object id : targetIds) {
                var known = context.find(new EntityKey(target.root(), id));
                if (known.isEmpty() || LazyReference.unloaded(known.get().instance())) {
                    throw new jakarta.persistence.EntityNotFoundException("Missing collection target " + id);
                }
            }
        }
        loadCollection(List.of(), type, attribute, fetched, connection, context);
        return true;
    }

    /** Reads full entity statement columns, then lets the same D12 assembler register/hydrate every identity. */
    private List<FetchedEntity> entityRows(MappedEntity type, List<Object> ids, Connection connection) {
        EntityStatements statements = type.statements();
        var table = statements.tables().getFirst();
        List<ValueBinder> readers = Arrays.stream(table.selected()).mapToObj(c -> statements.columns().get(c).binder()).toList();
        List<Row> rows = batchKeys(table.select(),
            statements.keyColumns().stream().map(EntityStatements.Column::binder).toList(),
            ids.stream().map(statements::keyValues).toList(), readers, connection);
        List<FetchedEntity> result = new ArrayList<>();
        for (Row row : rows) {
            Object[] values = new Object[statements.columns().size()];
            boolean[] nulls = new boolean[values.length];
            Arrays.fill(nulls, true);
            for (int i = 0; i < table.selected().length; i++) {
                int c = table.selected()[i];
                values[c] = row.values()[statements.keyColumns().size() + i];
                nulls[c] = row.nulls()[statements.keyColumns().size() + i];
                if (nulls[c] && statements.columns().get(c).foreignKey() != null) values[c] = null;
            }
            result.add(new FetchedEntity(type, statements.key(Arrays.copyOf(row.values(), statements.keyColumns().size())), values, nulls));
        }
        return result;
    }

    private static Object[] nullableValues(Row row, int from, int to) {
        Object[] values = Arrays.copyOfRange(row.values(), from, to);
        for (int i = from; i < to; i++) if (row.nulls()[i]) values[i - from] = null;
        return values;
    }

    /** Reuses a mapped Select's table/columns/conditions; composite keys are ORs of complete key tuples. */
    private List<Row> batchKeys(Select select, List<ValueBinder> binders, List<Object[]> keys,
            List<ValueBinder> readers, Connection connection) {
        List<Expression> columns = new ArrayList<>();
        select.conditions().forEach(c -> columns.add(new Expression.Column("b", c)));
        select.columns().forEach(c -> columns.add(new Expression.Column("b", c)));
        Expression predicate = null;
        if (binders.size() == 1) {
            List<Expression> parameters = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) parameters.add(new Expression.Parameter(i));
            predicate = new Expression.In(new Expression.Column("b", select.conditions().getFirst()), parameters, false);
        } else {
            for (int i = 0; i < keys.size(); i++) {
                Expression tuple = null;
                for (int c = 0; c < binders.size(); c++) {
                    Expression comparison = new Expression.Binary(new Expression.Column("b", select.conditions().get(c)),
                        Expression.Operator.EQ, new Expression.Parameter(i * binders.size() + c));
                    tuple = tuple == null ? comparison : new Expression.Binary(tuple, Expression.Operator.AND, comparison);
                }
                predicate = predicate == null ? tuple : new Expression.Binary(predicate, Expression.Operator.OR, tuple);
            }
        }
        Query query = new Query(false, columns, List.of(new Query.From(select.table(), "b", List.of())), predicate,
            List.of(), null, List.of(), null, null);
        var rendered = engine.dialect().renderQuery(query);
        List<ValueBinder> allReaders = new ArrayList<>(binders);
        allReaders.addAll(readers);
        List<Row> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(rendered.sql())) {
            for (int p = 0; p < rendered.parameters().size(); p++) {
                int slot = (Integer) rendered.parameters().get(p).slot();
                binders.get(slot % binders.size()).bind(engine.dialect(), statement, p + 1,
                    keys.get(slot / binders.size())[slot % binders.size()]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Object[] values = new Object[allReaders.size()];
                    boolean[] nulls = new boolean[values.length];
                    for (int c = 0; c < values.length; c++) {
                        values[c] = allReaders.get(c).read(rows, c + 1);
                        nulls[c] = rows.wasNull();
                        // Preserve primitive binder defaults in state columns, as the ordinary row loader does.
                        if (nulls[c] && c < binders.size()) values[c] = null;
                    }
                    result.add(new Row(values, nulls));
                }
            }
        } catch (SQLException failure) {
            throw new PersistenceException("The lazy batch read failed: " + failure.getMessage(), failure);
        }
        return result;
    }

    /** The elements of the collection attribute {@code i} of the instance {@code id}, read from the database. */
    private Object collection(MappedEntity type, int i, Object id, Connection connection, PersistenceContext context) {
        EntityStatements statements = type.statements();
        AttributeModel attribute = type.model().attributes().get(i);
        if (attribute instanceof ElementCollectionAttribute) {
            Optional<ElementCollectionMapping> mapping = statements.elementCollection(i);
            return mapping.isEmpty() ? NOT_MAPPED : elements(mapping.get(), statements.keyValues(id), connection, context);
        }
        AssociationAttribute association = (AssociationAttribute) attribute;
        MappedEntity target = entity(association.targetEntity());
        CollectionMapping mapping = statements.collection(i).orElseThrow();
        List<Indexed> elements = switch (mapping) {
            case CollectionMapping.JoinTable table -> {
                List<ValueBinder> readers = new ArrayList<>(table.targetBinders());
                if (table.index() != null) {
                    readers.addAll(table.index().binders());
                }
                int width = table.targetBinders().size();
                List<Indexed> found = new ArrayList<>();
                for (Object[] columns : keys(table.targets(), table.ownerBinders(), statements.keyValues(id), readers,
                        describe(association), connection)) {
                    Object element = find(target, target.statements().key(Arrays.copyOf(columns, width)), connection, context);
                    found.add(new Indexed(element, index(table.index(), columns, width, connection, context)));
                }
                yield found;
            }
            case CollectionMapping.MappedBy inverse -> inverse(association, inverse.index(), id, statements, connection, context);
            case CollectionMapping.ForeignKey foreign -> {
                List<ValueBinder> readers = new ArrayList<>(foreign.targetBinders());
                if (foreign.index() != null) readers.addAll(foreign.index().binders());
                int width = foreign.targetBinders().size();
                List<Indexed> found = new ArrayList<>();
                for (var table : unit.collectionTables(target, foreign.table())) {
                    var select = new Select(table, foreign.targets().columns(), foreign.targets().conditions());
                    for (Object[] columns : keys(select, foreign.ownerBinders(), statements.keyValues(id),
                            readers, describe(association), connection)) {
                        Object element = find(target, target.statements().key(Arrays.copyOf(columns, width)), connection, context);
                        found.add(new Indexed(element, index(foreign.index(), columns, width, connection, context)));
                    }
                }
                yield found;
            }
            case CollectionMapping.Unsupported _ -> null;
        };
        if (elements == null) {
            return NOT_MAPPED;
        }
        return assemble(association.javaType(), indexOf(mapping), elements, target, association.orderBy(),
            target.model().attributes(), target.access(), target::id, describe(association));
    }

    private static IndexMapping indexOf(CollectionMapping mapping) {
        return switch (mapping) {
            case CollectionMapping.JoinTable table -> table.index();
            case CollectionMapping.MappedBy inverse -> inverse.index();
            case CollectionMapping.ForeignKey foreign -> foreign.index();
            case CollectionMapping.Unsupported _ -> null;
        };
    }

    /** The managed instance of {@code type} with identity {@code key}: the context's, else loaded; {@code null} if none. */
    private Object find(MappedEntity type, Object key, Connection connection, PersistenceContext context) {
        Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), key));
        if (known.isPresent()) LazyReference.initialize(known.get().instance());
        return known.isPresent() ? type.model().javaType().isInstance(known.get().instance()) ? known.get().instance() : null
            : load(type, key, connection, context);
    }

    /**
     * The instances on the other side of the inverse relationship {@code inverse} of the instance {@code id}: those whose
     * owning side, the attribute named by {@code mappedBy}, holds it — through their foreign key, or the rows of their
     * join table — with the index {@code index} the table of the target keeps for them, if any.
     */
    private List<Indexed> inverse(AssociationAttribute inverse, IndexMapping index, Object id, EntityStatements statements,
            Connection connection, PersistenceContext context) {
        MappedEntity owner = entity(inverse.targetEntity());
        var inheritance = unit.inheritance(owner.model().javaType());
        if (inheritance.tablePerClass()) {
            List<Indexed> result = new ArrayList<>();
            for (var candidate : inheritance.hierarchy()) {
                if (!inheritance.abstractEntity(candidate) && owner.model().javaType().isAssignableFrom(candidate.javaType())) {
                    result.addAll(inverse(inverse, index, id, statements, connection, context, entity(candidate.javaType())));
                }
            }
            return result;
        }
        return inverse(inverse, index, id, statements, connection, context, owner);
    }

    private List<Indexed> inverse(AssociationAttribute inverse, IndexMapping index, Object id, EntityStatements statements,
            Connection connection, PersistenceContext context, MappedEntity owner) {
        int attribute = -1;
        List<AttributeModel> attributes = owner.model().attributes();
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).name().equals(inverse.mappedBy())) {
                attribute = i;
            }
        }
        if (attribute < 0) {
            throw new PersistenceException("The relationship " + inverse.name() + " of " + inverse.declaringClass().getName()
                + " is mapped by " + inverse.mappedBy() + ", which " + owner.model().entityName() + " does not have");
        }
        EntityStatements ownerStatements = owner.statements();
        Object[] key = statements.keyValues(id);
        List<Object[]> rows;
        int width;
        Optional<EntityStatements.Reference> reference = ownerStatements.reference(attribute);
        Optional<CollectionMapping> collection = ownerStatements.collection(attribute);
        boolean stored = index != null && index.stored();
        if (reference.isPresent()) {
            List<ValueBinder> foreignKey = new ArrayList<>();
            for (int c : reference.get().columns()) {
                foreignKey.add(ownerStatements.columns().get(c).binder());
            }
            List<ValueBinder> readers = new ArrayList<>(ownerStatements.keyColumns().stream().map(EntityStatements.Column::binder).toList());
            width = readers.size();
            Select select = ownerStatements.ownersSelect(reference.get());
            if (stored) { // the index lives beside the foreign key, in the table of the target
                List<Identifier> columns = new ArrayList<>(select.columns());
                columns.addAll(index.columns());
                select = new Select(select.table(), columns, select.conditions());
                readers.addAll(index.binders());
            }
            rows = keys(select, foreignKey, key, readers, describe(inverse), connection);
        } else if (collection.isPresent() && collection.get() instanceof CollectionMapping.JoinTable table) {
            width = table.ownerBinders().size();
            rows = keys(table.owners(), table.targetBinders(), key, table.ownerBinders(), describe(inverse), connection);
        } else {
            return new ArrayList<>(); // owned through a shape not mapped yet
        }
        List<Indexed> owners = new ArrayList<>();
        for (Object[] row : rows) {
            Object element = find(owner, ownerStatements.key(Arrays.copyOf(row, width)), connection, context);
            if (element != null) {
                owners.add(new Indexed(element, stored ? index(index, row, width, connection, context) : null));
            }
        }
        return owners;
    }

    /** The index read from {@code columns}, from {@code from} on: the key entity it references, or the value read. */
    private Object index(IndexMapping index, Object[] columns, int from, Connection connection, PersistenceContext context) {
        if (index == null || !index.stored()) {
            return null;
        }
        if (index.index() instanceof CollectionIndex.ByEmbedded) {
            return index.embeddedKey(Arrays.copyOfRange(columns, from, from + index.columns().size()));
        }
        if (index.keyEntity() != null) {
            MappedEntity key = entity(index.keyEntity());
            Object[] values = Arrays.copyOfRange(columns, from, columns.length);
            return Arrays.stream(values).allMatch(Objects::isNull) ? null : find(key, key.statements().key(values), connection, context);
        }
        return columns[from];
    }

    /**
     * The value of a collection attribute declared {@code type} from the elements read: a map keyed by their index —
     * or by the attribute of a {@code @MapKey} — a list in the order of its positions, or a collection in the order of
     * its {@code @OrderBy}.
     *
     * @param target the entity of the elements of a relationship, {@code null} for an element collection
     */
    private Object assemble(Class<?> type, IndexMapping index, List<Indexed> read, MappedEntity target, String orderBy,
            List<AttributeModel> attributes, ManagedAccess access, Function<Object, Object> natural, String where) {
        // Element-collection rows may contain SQL NULL. Absent LEFT rows were removed before assembly.
        List<Indexed> elements = new ArrayList<>(target == null ? read
            : read.stream().filter(e -> e.element() != null).toList());
        if (Map.class.isAssignableFrom(type)) {
            Map<Object, Object> map = IndexMapping.newMap(type);
            for (Indexed element : elements) {
                Object key = element.index();
                if (index != null && !index.stored()) { // @MapKey: an attribute of the element, its identifier by default
                    key = index.keyAttribute() >= 0 ? target.access().get(element.element(), index.keyAttribute())
                        : target.idObject(element.element());
                }
                map.put(key, element.element());
            }
            return map;
        }
        List<Object> values;
        if (index != null && index.positional()) {
            elements.sort(Comparator.comparing(e -> (Integer) e.index(), Comparator.nullsLast(Comparator.naturalOrder())));
            values = new ArrayList<>(elements.stream().map(Indexed::element).toList());
        } else {
            values = new ArrayList<>(elements.stream().map(Indexed::element).toList());
            order(orderBy, attributes, access, natural, where, values);
        }
        Collection<Object> collection = CollectionMapping.newCollection(type);
        collection.addAll(values);
        return collection;
    }

    /** The rows {@code select} reads, its conditions bound to {@code values}, its columns read by {@code readers}. */
    private List<Object[]> keys(Select select, List<ValueBinder> binders, Object[] values, List<ValueBinder> readers, String where,
            Connection connection) {
        List<Object[]> keys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(engine.render(select))) {
            for (int k = 0; k < values.length; k++) {
                binders.get(k).bind(engine.dialect(), statement, k + 1, values[k]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Object[] key = new Object[readers.size()];
                    for (int k = 0; k < key.length; k++) {
                        key[k] = readers.get(k).read(rows, k + 1);
                        if (rows.wasNull()) key[k] = null;
                    }
                    keys.add(key);
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("The read of " + where + " failed: " + e.getMessage(), e);
        }
        return keys;
    }

    // ---- element collections (§2.7) ------------------------------------------------------------------------

    /**
     * The value of the element collection {@code mapping} of the owner {@code key}, read from its collection table: its
     * elements with their keys or positions, if any, then the element columns.
     */
    private Object elements(ElementCollectionMapping mapping, Object[] key, Connection connection, PersistenceContext context) {
        List<Indexed> elements = new ArrayList<>();
        List<EntityStatements.Column> columns = mapping.columns();
        IndexMapping index = mapping.index();
        int width = mapping.indexColumns().size();
        try (PreparedStatement select = connection.prepareStatement(engine.render(mapping.select()))) {
            for (int k = 0; k < key.length; k++) {
                mapping.ownerBinders().get(k).bind(engine.dialect(), select, k + 1, key[k]);
            }
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Object[] indexValues = new Object[width];
                    for (int c = 0; c < width; c++) {
                        indexValues[c] = index.binders().get(c).read(rows, c + 1);
                        if (rows.wasNull()) indexValues[c] = null;
                    }
                    Object[] values = new Object[columns.size()];
                    boolean[] nulls = new boolean[values.length];
                    for (int c = 0; c < values.length; c++) {
                        values[c] = columns.get(c).binder().read(rows, width + c + 1);
                        nulls[c] = rows.wasNull();
                    }
                    elements.add(new Indexed(mapping.element(values, nulls), index(index, indexValues, 0, connection, context)));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("The read of the element collection " + mapping.model().name() + " of "
                + mapping.model().declaringClass().getName() + " failed: " + e.getMessage(), e);
        }
        ElementCollectionAttribute model = mapping.model();
        String where = "the element collection " + model.name() + " of " + model.declaringClass().getName();
        if (model.element() instanceof EmbeddedAttribute embedded) {
            return assemble(model.javaType(), index, elements, null, model.orderBy(), embedded.embeddable().attributes(),
                unit.access(embedded.embeddable()), Function.identity(), where);
        }
        return assemble(model.javaType(), index, elements, null, model.orderBy(), List.of(), null, Function.identity(), where);
    }

    // ---- @OrderBy (§11.1.42) ------------------------------------------------------------------------------

    /**
     * Sorts {@code elements} by {@code orderBy}: a list of paths into the elements — attributes, through embeddables
     * by dots — each ascending unless {@code DESC}; without a path (an empty {@code @OrderBy}, or {@code ASC} / {@code DESC}
     * alone) by {@code natural}: the identifier of an entity, the value of a basic element.
     *
     * @param attributes the attributes of the elements, and {@code access} reads them; none for basic elements
     */
    private void order(String orderBy, List<AttributeModel> attributes, ManagedAccess access, Function<Object, Object> natural,
            String where, List<Object> elements) {
        if (orderBy == null || elements.size() < 2) {
            return;
        }
        Comparator<Object> comparator = null;
        for (String item : orderBy.isEmpty() ? new String[] {""} : orderBy.split(",")) {
            String[] parts = item.trim().split("\\s+");
            boolean pathless = parts[0].isEmpty() || parts[0].equalsIgnoreCase("ASC") || parts[0].equalsIgnoreCase("DESC");
            Function<Object, Object> key = pathless ? natural : path(parts[0], attributes, access, where);
            String direction = pathless ? parts[0] : parts.length > 1 ? parts[1] : "";
            Comparator<Object> by = (a, b) -> compare(key.apply(a), key.apply(b));
            if (direction.equalsIgnoreCase("DESC")) {
                by = by.reversed();
            }
            comparator = comparator == null ? by : comparator.thenComparing(by);
        }
        elements.sort(comparator);
    }

    /** Reads the attribute at the dotted {@code path} of an element, through its embeddables. */
    private Function<Object, Object> path(String path, List<AttributeModel> attributes, ManagedAccess access, String where) {
        int dot = path.indexOf('.');
        String name = dot < 0 ? path : path.substring(0, dot);
        int index = -1;
        for (int i = 0; i < attributes.size(); i++) {
            if (attributes.get(i).name().equals(name)) {
                index = i;
            }
        }
        if (index < 0 || dot >= 0 && !(attributes.get(index) instanceof EmbeddedAttribute)) {
            throw new PersistenceException("The @OrderBy of " + where + " names " + path + ", which its elements do not have");
        }
        int attribute = index;
        if (dot < 0) {
            return element -> element == null ? null : access.get(element, attribute);
        }
        EmbeddableModel embeddable = ((EmbeddedAttribute) attributes.get(index)).embeddable();
        Function<Object, Object> rest = path(path.substring(dot + 1), embeddable.attributes(), unit.access(embeddable), where);
        return element -> element == null ? null : rest.apply(access.get(element, attribute));
    }

    private static String describe(AssociationAttribute association) {
        return "the relationship " + association.name() + " of " + association.declaringClass().getName();
    }

    /** Compares two values of an attribute, {@code null} first; values that are not comparable keep their order. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        return a instanceof Comparable comparable ? comparable.compareTo(b) : 0;
    }

    private MappedEntity entity(Class<?> type) {
        return unit.entity(type).orElseThrow(() -> new PersistenceException(type.getName() + " is not an entity of the unit"));
    }

    /** The columns of a row, and which were SQL {@code NULL} (a primitive reads 0 from it). */
    private record Row(Object[] values, boolean[] nulls) {
    }

    private Row row(MappedEntity type, Object id, Connection connection, LockModeType mode, Integer timeout) {
        EntityStatements statements = type.statements();
        List<EntityStatements.Column> columns = statements.columns();
        Object[] key = statements.keyValues(id);
        Object[] values = new Object[columns.size()];
        boolean[] nulls = new boolean[values.length];
        Arrays.fill(nulls, true); // a missing secondary row reads as NULL columns
        FlushEngine.Sql sql = engine.sql(type);
        try {
            for (int t = 0; t < statements.tables().size(); t++) {
                EntityStatements.TableStatements table = statements.tables().get(t);
                String text = sql.tables().get(t).select();
                if (t == 0 && Locks.pessimistic(mode)) {
                    Locks.timeout(engine.dialect(), timeout, connection);
                    text = engine.dialect().render(table.select().locked(Locks.rowLock(mode), timeout != null && timeout == 0));
                }
                try (PreparedStatement select = connection.prepareStatement(text)) {
                    List<EntityStatements.Parameter> parameters = table.selectParameters();
                    for (int i = 0; i < parameters.size(); i++) {
                        columns.get(parameters.get(i).column()).binder().bind(engine.dialect(), select, i + 1, key[i]);
                    }
                    try (ResultSet row = select.executeQuery()) {
                        if (!row.next()) {
                            if (t == 0 || unit.inheritance(type.model().javaType()).joined()
                                    && t < unit.inheritance(type.model().javaType()).chain().size()) {
                                return null;
                            }
                            continue;
                        }
                        int[] selected = table.selected();
                        for (int i = 0; i < selected.length; i++) {
                            values[selected[i]] = columns.get(selected[i]).binder().read(row, i + 1);
                            nulls[selected[i]] = row.wasNull(); // the binder's last read is this column's
                            if (nulls[selected[i]] && columns.get(selected[i]).foreignKey() != null) {
                                values[selected[i]] = null;
                            }
                        }
                    }
                }
            }
            return new Row(values, nulls);
        } catch (SQLException e) {
            if (Locks.pessimistic(mode)) {
                throw Locks.failure(engine.dialect(), e, null);
            }
            throw new PersistenceException("The read of " + type.model().entityName() + " " + id + " failed: " + e.getMessage(), e);
        }
    }

    /** Writes the row into {@code instance}; what the row does not hold keeps its current value. */
    private static Object[] state(MappedEntity type, Object instance, Row row) {
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(instance, state);
        type.statements().hydrate(row.values(), row.nulls(), state);
        type.access().write(instance, state);
        return state;
    }
}
