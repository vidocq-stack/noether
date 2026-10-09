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
package io.vidocq.mansart.jpa.core.flush;

import io.vidocq.mansart.jpa.core.context.EntityKey;
import io.vidocq.mansart.jpa.core.context.ManagedEntity;
import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.mapping.CollectionMapping;
import io.vidocq.mansart.jpa.core.mapping.CompositeId;
import io.vidocq.mansart.jpa.core.mapping.ElementCollectionMapping;
import io.vidocq.mansart.jpa.core.mapping.IndexMapping;
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Select;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds managed instances from their rows (§3.2.8): the select by identifier of an entity, its columns read through
 * their binders, the state rebuilt (embeddables included) and written through the access, then the instance made
 * managed with its snapshot. Shares the SQL the flush engine rendered.
 *
 * <p>Relationships are loaded with their owner (§2.10; {@code FetchType.LAZY} is a hint, §11.1.26): the owning side of a
 * single-valued one from its foreign key, a collection from its join table, an inverse side from the owning side of its
 * target. The instance is managed before its relationships are resolved, so that a cycle closes on it.
 */
public final class EntityLoader {

    private final FlushEngine engine;
    private final MappedUnit unit;

    public EntityLoader(FlushEngine engine, MappedUnit unit) {
        this.engine = engine;
        this.unit = unit;
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
        Row row = row(type, id, connection, mode, timeout);
        if (row == null) {
            return null;
        }
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
        type.callback("PostLoad", instance); // §3.6.3: once the state is loaded
        return instance;
    }

    /** Whether a row of {@code type} has the identity {@code id}, without managing anything. */
    public boolean exists(MappedEntity type, Object id, Connection connection) {
        return row(type, id, connection, LockModeType.NONE, null) != null;
    }

    /**
     * §3.2.5: overwrites the state of the managed {@code instance} with its row, and returns the new snapshot;
     * {@code null} if the row is gone.
     */
    public Object[] refresh(MappedEntity type, Object id, Object instance, Connection connection, PersistenceContext context) {
        Row row = row(type, id, connection, LockModeType.NONE, null);
        if (row == null) {
            return null;
        }
        Object[] state = state(type, instance, row);
        relationships(type, id, instance, state, row, connection, context);
        Object[] snapshot = type.state().snapshot(state);
        type.callback("PostLoad", instance); // §3.6.3: after a refresh too
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
        EntityStatements statements = type.statements();
        List<AttributeModel> attributes = type.model().attributes();
        for (int i = 0; i < attributes.size(); i++) {
            Object value;
            if (attributes.get(i) instanceof ElementCollectionAttribute elements) {
                Optional<ElementCollectionMapping> mapping = statements.elementCollection(i);
                if (mapping.isEmpty()) {
                    continue; // a shape not mapped yet: the collection keeps what the instance holds
                }
                value = elements(mapping.get(), statements.keyValues(id), connection, context);
            } else if (!(attributes.get(i) instanceof AssociationAttribute association)) {
                continue;
            } else if (association.singleValued()) {
                Optional<EntityStatements.Reference> owned = statements.reference(i);
                if (owned.isPresent()) {
                    Object key = statements.referencedKey(owned.get(), row.values());
                    value = key == null ? null : find(entity(association.targetEntity()), key, connection, context);
                } else if (!association.owning()) {
                    List<Indexed> owners = inverse(association, null, id, statements, connection, context);
                    value = owners.isEmpty() ? null : owners.getFirst().element();
                } else {
                    continue; // through a join table: later in P5
                }
            } else {
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
                    case CollectionMapping.Unsupported _ -> null;
                };
                if (elements == null) {
                    continue; // a shape not mapped yet: the collection keeps what the instance holds
                }
                value = assemble(association.javaType(), indexOf(mapping), elements, target, association.orderBy(),
                    target.model().attributes(), target.access(), target::id, describe(association));
            }
            state[i] = value;
            type.access().set(instance, i, value);
        }
    }

    private static IndexMapping indexOf(CollectionMapping mapping) {
        return switch (mapping) {
            case CollectionMapping.JoinTable table -> table.index();
            case CollectionMapping.MappedBy inverse -> inverse.index();
            case CollectionMapping.Unsupported _ -> null;
        };
    }

    /** The managed instance of {@code type} with identity {@code key}: the context's, else loaded; {@code null} if none. */
    private Object find(MappedEntity type, Object key, Connection connection, PersistenceContext context) {
        Optional<ManagedEntity> known = context.find(new EntityKey(type.root(), key));
        return known.isPresent() ? known.get().instance() : load(type, key, connection, context);
    }

    /**
     * The instances on the other side of the inverse relationship {@code inverse} of the instance {@code id}: those whose
     * owning side, the attribute named by {@code mappedBy}, holds it — through their foreign key, or the rows of their
     * join table — with the index {@code index} the table of the target keeps for them, if any.
     */
    private List<Indexed> inverse(AssociationAttribute inverse, IndexMapping index, Object id, EntityStatements statements,
            Connection connection, PersistenceContext context) {
        MappedEntity owner = entity(inverse.targetEntity());
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
            owners.add(new Indexed(element, stored ? index(index, row, width, connection, context) : null));
        }
        return owners;
    }

    /** The index read from {@code columns}, from {@code from} on: the key entity it references, or the value read. */
    private Object index(IndexMapping index, Object[] columns, int from, Connection connection, PersistenceContext context) {
        if (index == null || !index.stored()) {
            return null;
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
        List<Indexed> elements = new ArrayList<>(read.stream().filter(e -> e.element() != null).toList());
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
                            if (t == 0) {
                                return null;
                            }
                            continue;
                        }
                        int[] selected = table.selected();
                        for (int i = 0; i < selected.length; i++) {
                            values[selected[i]] = columns.get(selected[i]).binder().read(row, i + 1);
                            nulls[selected[i]] = row.wasNull(); // the binder's last read is this column's
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
