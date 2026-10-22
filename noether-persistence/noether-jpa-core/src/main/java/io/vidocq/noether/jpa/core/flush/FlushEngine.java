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

import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.context.ManagedEntity;
import io.vidocq.noether.jpa.core.context.PersistenceContext;
import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.mapping.CollectionMapping;
import io.vidocq.noether.jpa.core.mapping.ElementCollectionMapping;
import io.vidocq.noether.jpa.core.mapping.IndexMapping;
import io.vidocq.noether.jpa.core.mapping.EntityStatements;
import io.vidocq.noether.jpa.core.mapping.LazyCollection;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.mapping.StatePolicy;
import io.vidocq.noether.jpa.dialect.Dialect;
import io.vidocq.noether.jpa.dialect.sql.Statement;
import io.vidocq.noether.jpa.dialect.sql.Insert;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Synchronises a persistence context with the database (§3.2.4): the new instances are inserted, the changed ones
 * updated (their snapshot tells, no enhancement), the removed ones deleted. Statements run in a deterministic order —
 * inserts, entities after those they reference, then updates, then deletes in reverse — and consecutive statements of
 * an entity share one batch. A versioned entity is updated and deleted only if its row still has the version it was
 * read with (§3.4.2), else {@link OptimisticLockException}. Foreign keys that no order satisfies (a cycle, an instance
 * of the same entity met later) are inserted {@code NULL} and written once every row exists, and cleared before the
 * deletes of rows that reference each other: the constraints never need to be deferred. Join rows and the rows of
 * element collections are written once every row of the flush exists, and deleted before their owner.
 *
 * <p>One engine per factory, shared by its entity managers: its state is the dialect and the SQL it rendered, cached
 * without lock contention ({@link ConcurrentHashMap}, pure computation). A flush runs on the calling thread, on the
 * connection of the transaction: blocking JDBC on a virtual thread, no lock held.
 */
public final class FlushEngine {

    /** The SQL of a table of an entity, rendered once by the dialect. */
    record TableSql(String insert, String update, String delete, String select) {
    }

    /** The SQL of an entity, its primary table first. */
    record Sql(List<TableSql> tables, String[] generatedKey) {
        String select() {
            return tables.getFirst().select();
        }
    }

    /** An instance to write, with the state read from it. */
    private record Work(ManagedEntity entry, Object[] state) {
        MappedEntity type() {
            return entry.type();
        }
    }

    /** A foreign key written alone, by an update: after the inserts (a cycle), or {@code NULL} before the deletes. */
    private record ReferenceWrite(Work work, EntityStatements.Reference reference) {
    }

    private record CollectionKeyWrite(Work owner, CollectionMapping.ForeignKey mapping, Object index) {
    }

    private static final Comparator<Work> INSERT_ORDER = Comparator.<Work>comparingInt(w -> w.type().rank())
        .thenComparingLong(w -> w.entry().sequence());
    private static final Comparator<Work> DELETE_ORDER = INSERT_ORDER.reversed();

    private final Dialect dialect;
    private final int batchSize;
    private final Map<MappedEntity, Sql> sql = new ConcurrentHashMap<>();
    private final Map<Statement, String> rendered = new ConcurrentHashMap<>();

    public FlushEngine(Dialect dialect, int batchSize) {
        this.dialect = dialect;
        this.batchSize = Math.max(1, batchSize);
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Writes the changes of {@code context} through {@code connection}, then records them in the context. */
    public void flush(PersistenceContext context, Connection connection) {
        flush(context, connection, ignored -> {
        });
    }

    /** Flushes after callbacks; {@code validateUpdate} runs only for an entity that is actually being updated. */
    public void flush(PersistenceContext context, Connection connection, Consumer<ManagedEntity> validateUpdate) {
        List<Work> inserts = new ArrayList<>();
        List<Work> updates = new ArrayList<>();
        List<Work> deletes = new ArrayList<>();
        List<ManagedEntity> checks = new ArrayList<>();
        List<Work> indexes = new ArrayList<>();
        for (ManagedEntity entry : context.entries()) {
            if (io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(entry.instance())) continue;
            MappedEntity type = entry.type();
            if (entry.key() != null) {
                type.validateMapsId(entry.instance(), entry.key().id());
            }
            switch (entry.status()) {
                case MANAGED -> {
                    Object[] state = read(type, entry.instance());
                    if (!entry.inserted()) {
                        inserts.add(new Work(entry, state));
                    } else if (type.state().dirty(entry.snapshot(), state)) {
                        // §3.6.3: PreUpdate may change the instance, its changes are written with the others
                        type.callback("PreUpdate", entry.instance());
                        type.validateMapsId(entry.instance(), entry.key().id());
                        validateUpdate.accept(entry);
                        updates.add(new Work(entry, read(type, entry.instance())));
                    } else if (Locks.forcesIncrement(entry.lockMode())) {
                        updates.add(new Work(entry, state)); // §3.5: a new version, without a change
                    } else {
                        if (entry.lockMode() == LockModeType.OPTIMISTIC || entry.lockMode() == LockModeType.READ) {
                            checks.add(entry);
                        }
                        if (!type.statements().inverseIndexes().isEmpty()) {
                            indexes.add(new Work(entry, state)); // an inverse index changes nothing of its owner's row
                        }
                    }
                }
                case REMOVED -> {
                    if (entry.inserted()) {
                        deletes.add(new Work(entry, read(type, entry.instance())));
                    } else {
                        context.deleted(entry); // removed before its insert: the database never saw it
                        type.callback("PostRemove", entry.instance());
                    }
                }
            }
        }
        inserts.sort(INSERT_ORDER);
        orderRequiredInserts(inserts);
        deletes.sort(DELETE_ORDER);
        orderRequiredDeletes(deletes);
        Set<Object> pending = identitySet();
        Set<Object> removed = identitySet();
        deletes.forEach(work -> removed.add(work.entry().instance()));
        inserts.forEach(work -> pending.add(work.entry().instance()));
        Map<Object, List<CollectionKeyWrite>> collectionInserts = new IdentityHashMap<>();
        for (ManagedEntity entry : context.entries()) {
            if (io.vidocq.noether.jpa.core.spi.LazyReference.unloaded(entry.instance())) continue;
            if (entry.status() != ManagedEntity.Status.MANAGED) continue;
            Work owner = new Work(entry, read(entry.type(), entry.instance()));
            for (CollectionMapping mapping : owner.type().statements().collections()) {
                if (!(mapping instanceof CollectionMapping.ForeignKey foreign)) continue;
                Object current = owner.state()[foreign.attribute()];
                if (LazyCollection.unloaded(current)) continue; // not loaded: no new member to find in it
                List<Object> members = CollectionMapping.elements(current);
                List<Object> keys = CollectionMapping.keys(current);
                for (int e = 0; e < members.size(); e++) {
                    if (pending.contains(members.get(e))) {
                        collectionInserts.computeIfAbsent(members.get(e), _ -> new ArrayList<>())
                            .add(new CollectionKeyWrite(owner, foreign, keys.get(e)));
                    }
                }
            }
        }
        List<ReferenceWrite> later = new ArrayList<>();
        for (List<Work> group : groups(inserts)) {
            insert(group, context, connection, pending, later, collectionInserts);
        }
        writeReferences(later, false, connection);
        for (Work work : updates) clearChangedToOneTables(work, connection);
        for (Work work : deletes) deleteJoinRows(work, connection);
        for (Work work : updates) writeCollectionKeys(work, work.entry().snapshot(), connection, removed, true);
        for (Work work : deletes) clearCollectionKeys(work, connection, removed);
        for (Work work : inserts) { // the join rows reference rows of both sides: every row exists now
            writeJoinRows(work, null, connection);
            writeCollectionKeys(work, null, connection, removed, false);
            writeElements(work, true, connection);
            writeInverseIndexes(work, null, connection);
        }
        for (List<Work> group : groups(updates)) {
            for (Work work : group) {
                writeJoinRows(work, work.entry().snapshot(), connection);
                writeCollectionKeys(work, work.entry().snapshot(), connection, removed, false);
                writeElements(work, false, connection);
                writeInverseIndexes(work, work.entry().snapshot(), connection);
            }
            update(group, context, connection);
        }
        for (Work work : indexes) {
            if (writeInverseIndexes(work, work.entry().snapshot(), connection)) {
                context.updated(work.entry(), work.type().state().snapshot(work.state()));
            }
        }
        writeReferences(referencesBetween(deletes), true, connection);
        for (Work work : deletes) {
            deleteJoinRows(work, connection);
            for (ElementCollectionMapping elements : work.type().statements().elementCollections()) {
                deleteElements(work, elements, connection);
            }
        }
        for (List<Work> group : groups(deletes)) {
            delete(group, context, connection);
        }
        // §3.5.5 OPTIMISTIC: the rows read under that lock and not written must not have changed since
        Locks locks = new Locks(this);
        for (ManagedEntity entry : checks) {
            locks.checkVersion(entry.type(), entry, connection);
        }
    }

    // ---- inserts ------------------------------------------------------------------------------------------

    /** Required business and MapsId foreign keys must never be deferred to SQL NULL. */
    private static void orderRequiredInserts(List<Work> inserts) {
        if (inserts.stream().noneMatch(w -> w.type().statements().references().stream()
                .anyMatch(r -> requiredReference(w.type(), r)))) return;
        List<Work> remaining = new ArrayList<>(inserts);
        Set<Object> pending = identitySet();
        remaining.forEach(w -> pending.add(w.entry().instance()));
        inserts.clear();
        while (!remaining.isEmpty()) {
            Work next = remaining.stream().filter(w -> w.type().statements().references().stream()
                .filter(r -> requiredReference(w.type(), r))
                .noneMatch(r -> w.state()[r.attribute()] != w.entry().instance()
                    && pending.contains(w.state()[r.attribute()]))).findFirst().orElseThrow(() ->
                        new PersistenceException("Cannot insert a non-null business-key cycle or MapsId cycle without deferred database constraints"));
            remaining.remove(next);
            pending.remove(next.entry().instance());
            inserts.add(next);
        }
    }

    private static boolean requiredReference(MappedEntity type, EntityStatements.Reference reference) {
        var association = (AssociationAttribute) type.model().attributes().get(reference.attribute());
        if (association.mapsId() != null) return true;
        if (!reference.businessKey()) return false;
        return !association.optional() || association.joinColumns().stream().anyMatch(j -> !j.nullable());
    }

    private static void orderRequiredDeletes(List<Work> deletes) {
        if (deletes.stream().noneMatch(w -> w.type().statements().references().stream()
                .anyMatch(r -> requiredReference(w.type(), r)))) return;
        List<Work> remaining = new ArrayList<>(deletes);
        deletes.clear();
        while (!remaining.isEmpty()) {
            Work next = remaining.stream().filter(candidate -> remaining.stream().noneMatch(owner -> owner != candidate
                && owner.type().statements().references().stream()
                    .filter(r -> requiredReference(owner.type(), r))
                    .anyMatch(r -> owner.entry().snapshot()[r.attribute()] == candidate.entry().instance())))
                .findFirst().orElseThrow(() -> new PersistenceException("Cannot delete a non-null business-key cycle or MapsId cycle"));
            remaining.remove(next);
            deletes.add(next);
        }
    }

    /**
     * Inserts the rows of {@code group}. A foreign key referencing an instance of {@code pending}, whose row comes later
     * in this flush (a cycle, or an instance of the same entity met later), is inserted {@code NULL} and added to
     * {@code later}, written once every row exists.
     */
    private void insert(List<Work> group, PersistenceContext context, Connection connection, Set<Object> pending,
            List<ReferenceWrite> later, Map<Object, List<CollectionKeyWrite>> collectionInserts) {
        MappedEntity type = group.getFirst().type();
        EntityStatements statements = type.statements();
        Sql sql = sql(type);
        int version = versionAttribute(type);
        if (group.stream().anyMatch(w -> collectionInserts.containsKey(w.entry().instance()))) {
            for (Work work : group) {
                initialVersion(type, work, version);
                insertWithCollectionKeys(work, connection, pending, later,
                    collectionInserts.getOrDefault(work.entry().instance(), List.of()));
                context.inserted(work.entry(), type.id(work.entry().instance()), type.state().snapshot(work.state()));
                type.callback("PostPersist", work.entry().instance());
            }
            return;
        }
        try {
            if (sql.generatedKey() != null) {
                int id = type.idAttributes()[0];
                EntityStatements.Column idColumn = statements.columns().stream().filter(EntityStatements.Column::id).findFirst()
                    .orElseThrow();
                try (PreparedStatement statement = connection.prepareStatement(sql.tables().getFirst().insert(), sql.generatedKey())) {
                    for (Work work : group) {
                        initialVersion(type, work, version);
                        bind(statement, statements.insertParameters(), statements, insertValues(work, pending, later), null);
                        pending.remove(work.entry().instance());
                        statement.executeUpdate();
                        try (ResultSet keys = statement.getGeneratedKeys()) {
                            if (!keys.next()) {
                                throw new PersistenceException("The database returned no generated identifier for "
                                    + type.model().entityName());
                            }
                            Object value = idColumn.binder().read(keys, 1);
                            type.access().set(work.entry().instance(), id, value);
                            work.state()[id] = value;
                        }
                    }
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(sql.tables().getFirst().insert())) {
                    int batched = 0;
                    for (Work work : group) {
                        initialVersion(type, work, version);
                        bind(statement, statements.insertParameters(), statements, insertValues(work, pending, later), null);
                        pending.remove(work.entry().instance()); // a batch runs its rows in order
                        statement.addBatch();
                        if (++batched == batchSize) {
                            statement.executeBatch();
                            batched = 0;
                        }
                    }
                    if (batched > 0) {
                        statement.executeBatch();
                    }
                }
            }
            // the rows of the secondary tables, keyed by the identifier now known
            for (int t = 1; t < sql.tables().size(); t++) {
                EntityStatements.TableStatements table = statements.tables().get(t);
                try (PreparedStatement statement = connection.prepareStatement(sql.tables().get(t).insert())) {
                    for (Work work : group) {
                        bind(statement, table.insertParameters(), statements, statements.values(work.state()), null);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
            }
        } catch (SQLException e) {
            if (dialect.isDuplicateKey(e)) {
                throw new EntityExistsException("An instance of " + type.model().entityName() + " with the same identifier "
                    + "already exists in the database: " + e.getMessage(), e);
            }
            throw failure("insert", type, e);
        }
        for (Work work : group) {
            context.inserted(work.entry(), type.id(work.entry().instance()), type.state().snapshot(work.state()));
            type.callback("PostPersist", work.entry().instance());
        }
    }

    private void insertWithCollectionKeys(Work work, Connection connection, Set<Object> pending, List<ReferenceWrite> later,
            List<CollectionKeyWrite> writes) {
        MappedEntity type = work.type();
        EntityStatements statements = type.statements();
        Object[] values = insertValues(work, pending, later);
        for (int t = 0; t < statements.tables().size(); t++) {
            var table = statements.tables().get(t);
            List<Identifier> columns = new ArrayList<>(table.insert().columns());
            List<io.vidocq.noether.jpa.core.jdbc.type.ValueBinder> binders = new ArrayList<>();
            List<Object> parameters = new ArrayList<>();
            for (var parameter : table.insertParameters()) {
                binders.add(statements.columns().get(parameter.column()).binder());
                parameters.add(values[parameter.column()]);
            }
            for (CollectionKeyWrite write : writes) {
                var foreign = write.mapping();
                if (!write.owner().type().statements().targetTable(work.entry().instance(),
                        foreign.association().targetEntity(), foreign.table()).equals(table.insert().table())) continue;
                boolean deferred = pending.contains(write.owner().entry().instance())
                    && foreign.joins().stream().allMatch(j -> j.nullable());
                Object[] owner = write.owner().type().statements().keyValues(
                    write.owner().type().id(write.owner().entry().instance()));
                for (int k = 0; k < foreign.joins().size(); k++) {
                    if (deferred || !foreign.joins().get(k).insertable() || columns.contains(foreign.ownerColumns().get(k))) continue;
                    columns.add(foreign.ownerColumns().get(k));
                    binders.add(foreign.ownerBinders().get(k));
                    parameters.add(owner[k]);
                }
                if (foreign.index() != null && foreign.index().insertable()) {
                    Object[] index = foreign.index().values(write.index(), write.owner().type().statements());
                    for (int k = 0; k < index.length; k++) {
                        if (columns.contains(foreign.index().columns().get(k))) continue;
                        columns.add(foreign.index().columns().get(k));
                        binders.add(foreign.index().binders().get(k));
                        parameters.add(index[k]);
                    }
                }
            }
            Insert insert = new Insert(table.insert().table(), columns, table.insert().generatedKey());
            try (PreparedStatement statement = insert.generatedKey() == null
                    ? connection.prepareStatement(render(insert))
                    : connection.prepareStatement(render(insert), sql(type).generatedKey())) {
                for (int p = 0; p < parameters.size(); p++) binders.get(p).bind(dialect, statement, p + 1, parameters.get(p));
                statement.executeUpdate();
                if (insert.generatedKey() != null) {
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new PersistenceException("No generated identifier for " + type.model().entityName());
                        var idColumn = statements.keyColumns().getFirst();
                        Object id = idColumn.binder().read(keys, 1);
                        type.access().set(work.entry().instance(), type.idAttributes()[0], id);
                        work.state()[type.idAttributes()[0]] = id;
                        values = statements.values(work.state());
                    }
                }
            } catch (SQLException e) {
                if (dialect.isDuplicateKey(e)) {
                    throw new EntityExistsException("An instance of " + type.model().entityName() + " with the same identifier "
                        + "already exists in the database: " + e.getMessage(), e);
                }
                throw failure("insert", type, e);
            }
        }
        pending.remove(work.entry().instance());
    }

    /** The column values of the row of {@code work}, foreign keys to rows not inserted yet left {@code NULL}. */
    private static Object[] insertValues(Work work, Set<Object> pending, List<ReferenceWrite> later) {
        if (work.type().hasMapsId()) {
            work.type().deriveId(work.entry().instance());
            if (work.type().id(work.entry().instance()) == null) {
                throw new PersistenceException("@MapsId requires an assigned parent identity and all remaining identifier parts");
            }
            work.type().access().read(work.entry().instance(), work.state());
        }
        EntityStatements statements = work.type().statements();
        Object[] values = statements.values(work.state());
        for (EntityStatements.Reference reference : statements.references()) {
            Object target = work.state()[reference.attribute()];
            if (target != null && pending.contains(target)
                    && !(reference.businessKey() && target == work.entry().instance())) {
                for (int c : reference.columns()) {
                    values[c] = null;
                }
                later.add(new ReferenceWrite(work, reference));
            }
        }
        return values;
    }

    /**
     * The foreign keys of rows about to be deleted that reference rows deleted before them in the same flush (a cycle,
     * or an order the ranks cannot give): written {@code NULL} first, so that the deletes satisfy the constraints. A
     * row deleted before the row it references keeps its key: a {@code NOT NULL} foreign key stays valid (BUG-20261009-19).
     */
    private static List<ReferenceWrite> referencesBetween(List<Work> deletes) {
        if (deletes.size() < 2) {
            return List.of();
        }
        Map<Object, Integer> order = new IdentityHashMap<>();
        for (int i = 0; i < deletes.size(); i++) {
            order.put(deletes.get(i).entry().instance(), i);
        }
        List<ReferenceWrite> writes = new ArrayList<>();
        for (int i = 0; i < deletes.size(); i++) {
            Work work = deletes.get(i);
            Object[] row = work.entry().snapshot(); // what the row holds
            for (EntityStatements.Reference reference : work.type().statements().references()) {
                Object target = row[reference.attribute()];
                Integer targetOrder = target == null || target == work.entry().instance() ? null : order.get(target);
                if (targetOrder != null && targetOrder < i) {
                    writes.add(new ReferenceWrite(work, reference));
                }
            }
        }
        return writes;
    }

    /** Writes foreign keys alone: their current values, or {@code NULL} ({@code clear}). */
    private void writeReferences(List<ReferenceWrite> writes, boolean clear, Connection connection) {
        for (ReferenceWrite write : writes) {
            MappedEntity type = write.work().type();
            EntityStatements statements = type.statements();
            Object[] values = statements.values(write.work().state());
            if (clear) {
                for (int c : write.reference().columns()) {
                    values[c] = null;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(render(statements.referenceUpdate(write.reference())))) {
                bind(statement, statements.referenceUpdateParameters(write.reference()), statements, values, null);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure("update of a foreign key", type, e);
            }
        }
    }

    // ---- join tables (§2.10, §11.1.27) --------------------------------------------------------------------

    /**
     * Writes the join rows of the owned collections of {@code work}: the elements gained since {@code snapshot} are
     * inserted, those lost deleted ({@code snapshot} {@code null}: a new owner, every element is inserted).
     */
    private void writeJoinRows(Work work, Object[] snapshot, Connection connection) {
        MappedEntity type = work.type();
        EntityStatements statements = type.statements();
        for (CollectionMapping mapping : statements.collections()) {
            if (!(mapping instanceof CollectionMapping.JoinTable table)) {
                continue;
            }
            if (table.association().singleValued()) {
                writeToOneTable(work, snapshot, table, connection);
                continue;
            }
            Object[] owner = statements.keyValues(type.id(work.entry().instance()));
            Object current = work.state()[table.attribute()];
            try {
                if (snapshot != null && !type.state().collectionChanged(table.attribute(), snapshot[table.attribute()], current)) {
                    continue; // unchanged, an unloaded LAZY collection among them
                }
                List<Object> before = CollectionMapping.elements(
                    snapshot == null ? null : StatePolicy.snapshotValue(snapshot[table.attribute()]));
                Set<Object> unique = identitySet();
                boolean repeated = before.stream().anyMatch(element -> !unique.add(element));
                if (table.indexed() || repeated) {
                    // A target-key delete cannot remove just one occurrence of a duplicate bag row.
                    if (snapshot != null) {
                        try (PreparedStatement delete = connection.prepareStatement(render(table.deleteOwner()))) {
                            bindOwner(delete, table.ownerBinders(), owner);
                            delete.executeUpdate();
                        }
                    }
                    joinRows(render(table.insert()), table, owner, CollectionMapping.elements(current),
                        table.indexed() ? CollectionMapping.keys(current) : null,
                        statements, connection);
                    continue;
                }
                List<Object> now = CollectionMapping.elements(current);
                joinRows(render(table.deleteRow()), table, owner, minus(before, now), null, statements, connection);
                joinRows(render(table.insert()), table, owner, minus(now, before), null, statements, connection);
            } catch (SQLException e) {
                throw failure("write of the join table of " + table.association().name() + " of", type, e);
            }
        }
    }

    private void writeToOneTable(Work work, Object[] snapshot, CollectionMapping.JoinTable table, Connection connection) {
        Object current = work.state()[table.attribute()];
        Object previous = snapshot == null ? null : snapshot[table.attribute()];
        if (snapshot != null && previous == current || snapshot == null && current == null) return;
        EntityStatements statements = work.type().statements();
        Object[] owner = statements.keyValues(work.type().id(work.entry().instance()));
        Object[] target = current == null ? null : statements.targetKey(current, table.association().targetEntity());
        if (current != null && target == null) throw new PersistenceException("The relationship " + table.association().name()
            + " references an entity without an identifier");
        try {
            if (snapshot != null) {
                if (current == null && table.targetJoins().stream().allMatch(j -> j.updatable())) {
                    try (PreparedStatement statement = connection.prepareStatement(render(table.deleteOwner()))) {
                        bindOwner(statement, table.ownerBinders(), owner);
                        statement.executeUpdate();
                    }
                    return;
                }
                List<Identifier> assignments = new ArrayList<>();
                for (int k = 0; k < table.targetColumns().size(); k++) {
                    if (table.targetJoins().get(k).updatable()) assignments.add(table.targetColumns().get(k));
                }
                if (assignments.isEmpty()) return;
                var update = new io.vidocq.noether.jpa.dialect.sql.Update(table.table(), assignments, table.ownerColumns());
                try (PreparedStatement statement = connection.prepareStatement(render(update))) {
                    int parameter = 1;
                    for (int k = 0; k < table.targetColumns().size(); k++) {
                        if (table.targetJoins().get(k).updatable()) {
                            table.targetBinders().get(k).bind(dialect, statement, parameter++, target == null ? null : target[k]);
                        }
                    }
                    for (int k = 0; k < owner.length; k++) table.ownerBinders().get(k).bind(dialect, statement, parameter++, owner[k]);
                    if (statement.executeUpdate() != 0 || current == null) return;
                }
            }
            List<Identifier> columns = new ArrayList<>();
            List<io.vidocq.noether.jpa.core.jdbc.type.ValueBinder> binders = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (int k = 0; k < owner.length; k++) {
                if (!table.ownerJoins().get(k).insertable()) continue;
                columns.add(table.ownerColumns().get(k)); binders.add(table.ownerBinders().get(k)); values.add(owner[k]);
            }
            for (int k = 0; k < target.length; k++) {
                if (!(snapshot == null ? table.targetJoins().get(k).insertable() : table.targetJoins().get(k).updatable())) continue;
                columns.add(table.targetColumns().get(k)); binders.add(table.targetBinders().get(k)); values.add(target[k]);
            }
            if (columns.isEmpty()) return;
            try (PreparedStatement statement = connection.prepareStatement(render(new Insert(table.table(), columns, null)))) {
                for (int p = 0; p < values.size(); p++) binders.get(p).bind(dialect, statement, p + 1, values.get(p));
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw failure("write of the join table of " + table.association().name() + " of", work.type(), e);
        }
    }

    private void clearChangedToOneTables(Work work, Connection connection) {
        for (CollectionMapping mapping : work.type().statements().collections()) {
            if (!(mapping instanceof CollectionMapping.JoinTable table) || !table.association().singleValued()
                    || !table.targetJoins().stream().allMatch(j -> j.updatable())
                    || work.state()[table.attribute()] == work.entry().snapshot()[table.attribute()]) continue;
            try (PreparedStatement statement = connection.prepareStatement(render(table.deleteOwner()))) {
                bindOwner(statement, table.ownerBinders(),
                    work.type().statements().keyValues(work.type().id(work.entry().instance())));
                statement.executeUpdate();
            } catch (SQLException e) {
                throw failure("unlink of the join table of " + table.association().name() + " of", work.type(), e);
            }
        }
    }

    private void writeCollectionKeys(Work work, Object[] snapshot, Connection connection, Set<Object> removed, boolean clearPhase) {
        for (CollectionMapping mapping : work.type().statements().collections()) {
            if (!(mapping instanceof CollectionMapping.ForeignKey foreign)) continue;
            Object current = work.state()[foreign.attribute()];
            if (snapshot != null && !work.type().state().collectionChanged(foreign.attribute(),
                    snapshot[foreign.attribute()], current)) continue;
            List<Object> now = CollectionMapping.elements(current);
            List<Object> before = CollectionMapping.elements(
                snapshot == null ? null : StatePolicy.snapshotValue(snapshot[foreign.attribute()]));
            if (clearPhase) {
                collectionKeys(work, foreign, minus(before, now).stream().filter(e -> !removed.contains(e)).toList(),
                    null, false, true, connection);
                continue;
            }
            // Rewrite retained members too: list positions and stored map keys may have changed.
            collectionKeys(work, foreign, now, CollectionMapping.keys(current), snapshot == null, false, connection);
        }
    }

    private void clearCollectionKeys(Work work, Connection connection, Set<Object> removed) {
        for (CollectionMapping mapping : work.type().statements().collections()) {
            if (mapping instanceof CollectionMapping.ForeignKey foreign) {
                // a LAZY collection not loaded is loaded here: its members are the rows whose key is cleared
                collectionKeys(work, foreign, CollectionMapping.elements(
                        StatePolicy.snapshotValue(work.entry().snapshot()[foreign.attribute()])).stream()
                        .filter(e -> !removed.contains(e)).toList(),
                    null, false, true, connection);
            }
        }
    }

    private void collectionKeys(Work work, CollectionMapping.ForeignKey foreign, List<Object> elements, List<Object> keys,
            boolean inserted, boolean clear, Connection connection) {
        var update = foreign.update(inserted);
        if (update == null || elements.isEmpty()) return;
        EntityStatements statements = work.type().statements();
        Object[] owner = statements.keyValues(work.type().id(work.entry().instance()));
        for (var group : targetTables(statements, elements, foreign.association().targetEntity(), update.table()).entrySet()) {
            var concrete = new io.vidocq.noether.jpa.dialect.sql.Update(group.getKey(), update.assignments(), update.conditions());
            try (PreparedStatement statement = connection.prepareStatement(render(concrete))) {
                for (int e : group.getValue()) {
                    int parameter = 1;
                    for (int k = 0; k < foreign.joins().size(); k++) {
                        var join = foreign.joins().get(k);
                        if (inserted ? join.insertable() : join.updatable()) {
                            foreign.ownerBinders().get(k).bind(dialect, statement, parameter++, clear ? null : owner[k]);
                        }
                    }
                    if (foreign.index() != null && (inserted ? foreign.index().insertable() : foreign.index().updatable())) {
                        if (clear) {
                            for (var binder : foreign.index().binders()) binder.bind(dialect, statement, parameter++, null);
                        } else {
                            parameter = bindIndex(statement, parameter, foreign.index(), keys.get(e), statements);
                        }
                    }
                    Object[] target = statements.targetKey(elements.get(e), foreign.association().targetEntity());
                    if (target == null) throw new PersistenceException("An element of " + foreign.association().name() + " has no identifier");
                    for (int k = 0; k < target.length; k++) {
                        foreign.targetBinders().get(k).bind(dialect, statement, parameter++, target[k]);
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (SQLException e) {
                throw failure("write of the collection foreign key of " + foreign.association().name() + " of", work.type(), e);
            }
        }
    }

    private static Map<io.vidocq.noether.jpa.dialect.sql.Table, List<Integer>> targetTables(EntityStatements statements,
            List<Object> elements, Class<?> target, io.vidocq.noether.jpa.dialect.sql.Table table) {
        Map<io.vidocq.noether.jpa.dialect.sql.Table, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int e = 0; e < elements.size(); e++) {
            groups.computeIfAbsent(statements.targetTable(elements.get(e), target, table), _ -> new ArrayList<>()).add(e);
        }
        return groups;
    }

    /**
     * One batch of {@code sql} — the owner key, the element key, then its index ({@code keys}: the map key or the
     * position of each element, {@code null} for none) — per element.
     */
    private void joinRows(String sql, CollectionMapping.JoinTable table, Object[] owner, List<Object> elements, List<Object> keys,
            EntityStatements statements, Connection connection) throws SQLException {
        if (elements.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int e = 0; e < elements.size(); e++) {
                Object element = elements.get(e);
                Object[] target = statements.targetKey(element, table.association().targetEntity());
                if (target == null) {
                    throw new PersistenceException("An element of " + table.association().name() + " of "
                        + table.association().declaringClass().getName() + " has no identifier");
                }
                int index = 1;
                for (int k = 0; k < owner.length; k++) {
                    table.ownerBinders().get(k).bind(dialect, statement, index++, owner[k]);
                }
                for (int k = 0; k < target.length; k++) {
                    table.targetBinders().get(k).bind(dialect, statement, index++, target[k]);
                }
                if (keys != null) {
                    bindIndex(statement, index, table.index(), keys.get(e), statements);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Binds the index columns for {@code key}, a map key or a position, from parameter {@code index}; returns the next. */
    private int bindIndex(PreparedStatement statement, int index, IndexMapping mapping, Object key, EntityStatements statements)
            throws SQLException {
        Object[] values = mapping.values(key, statements);
        for (int k = 0; k < values.length; k++) {
            mapping.binders().get(k).bind(dialect, statement, index++, values[k]);
        }
        return index;
    }

    // ---- the indexes an inverse side writes (§11.1.33, §11.1.42) --------------------------------------------

    /**
     * Writes the keys or positions that the inverse collections of {@code work} keep in the table of their elements:
     * all of them for a new owner ({@code snapshot} {@code null}) unless they are not insertable, those of a collection
     * changed since the snapshot unless they are not updatable. Whether anything was written.
     */
    private boolean writeInverseIndexes(Work work, Object[] snapshot, Connection connection) {
        MappedEntity type = work.type();
        boolean written = false;
        for (CollectionMapping.MappedBy inverse : type.statements().inverseIndexes()) {
            Object current = work.state()[inverse.attribute()];
            boolean write = snapshot == null ? inverse.index().insertable()
                : inverse.index().updatable() && type.state().collectionChanged(inverse.attribute(), snapshot[inverse.attribute()], current);
            if (!write) {
                continue;
            }
            List<Object> elements = CollectionMapping.elements(current);
            if (elements.isEmpty()) {
                continue;
            }
            List<Object> keys = CollectionMapping.keys(current);
            Class<?> target = inverse.association().targetEntity();
            for (var group : targetTables(type.statements(), elements, target, inverse.indexUpdate().table()).entrySet()) {
                var update = new io.vidocq.noether.jpa.dialect.sql.Update(group.getKey(),
                    inverse.indexUpdate().assignments(), inverse.indexUpdate().conditions());
                try (PreparedStatement statement = connection.prepareStatement(render(update))) {
                    for (int e : group.getValue()) {
                        int index = bindIndex(statement, 1, inverse.index(), keys.get(e), type.statements());
                        Object[] key = type.statements().targetKey(elements.get(e), target);
                        for (int k = 0; k < key.length; k++) {
                            inverse.targetBinders().get(k).bind(dialect, statement, index++, key[k]);
                        }
                        statement.addBatch();
                    }
                    statement.executeBatch();
                } catch (SQLException e) {
                    throw failure("write of the index of " + inverse.association().name() + " of", type, e);
                }
            }
            written = true;
        }
        return written;
    }

    /** Deletes every join row of the owned collections of a row about to be deleted. */
    private void deleteJoinRows(Work work, Connection connection) {
        MappedEntity type = work.type();
        EntityStatements statements = type.statements();
        for (CollectionMapping mapping : statements.collections()) {
            if (mapping instanceof CollectionMapping.JoinTable table) {
                Object[] owner = statements.keyValues(work.entry().key().id());
                try (PreparedStatement statement = connection.prepareStatement(render(table.deleteOwner()))) {
                    for (int k = 0; k < owner.length; k++) {
                        table.ownerBinders().get(k).bind(dialect, statement, k + 1, owner[k]);
                    }
                    statement.executeUpdate();
                } catch (SQLException e) {
                    throw failure("delete of the join rows of " + table.association().name() + " of", type, e);
                }
            }
        }
    }

    // ---- element collections (§2.7, §11.1.8) ---------------------------------------------------------------

    /**
     * Writes the element collections of {@code work}: all of them for a new owner ({@code inserted}), else those that
     * changed since the snapshot, written again whole — their elements have no identity to update one row by.
     */
    private void writeElements(Work work, boolean inserted, Connection connection) {
        MappedEntity type = work.type();
        List<ElementCollectionMapping> mappings = type.statements().elementCollections();
        if (mappings.isEmpty()) {
            return;
        }
        List<Integer> changes = inserted ? List.of() : type.state().changes(work.entry().snapshot(), work.state());
        for (ElementCollectionMapping elements : mappings) {
            if (!inserted && !changes.contains(elements.attribute())) {
                continue;
            }
            if (!inserted) {
                deleteElements(work, elements, connection);
            }
            List<Object> values = CollectionMapping.elements(work.state()[elements.attribute()]);
            if (values.isEmpty()) {
                continue;
            }
            Object[] owner = type.statements().keyValues(type.id(work.entry().instance()));
            try (PreparedStatement statement = connection.prepareStatement(render(elements.insert()))) {
                List<Object> keys = CollectionMapping.keys(work.state()[elements.attribute()]);
                for (int v = 0; v < values.size(); v++) {
                    Object value = values.get(v);
                    int index = bindOwner(statement, elements.ownerBinders(), owner);
                    if (elements.index() != null) {
                        index = bindIndex(statement, index, elements.index(), keys.get(v), type.statements());
                    }
                    Object[] columns = elements.values(value, type.statements()::targetKey);
                    for (int c = 0; c < columns.length; c++) {
                        elements.columns().get(c).binder().bind(dialect, statement, index++, columns[c]);
                    }
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (SQLException e) {
                throw failure("write of the element collection " + elements.model().name() + " of", type, e);
            }
        }
    }

    /** Deletes the rows of the element collection {@code elements} of the owner of {@code work}. */
    private void deleteElements(Work work, ElementCollectionMapping elements, Connection connection) {
        MappedEntity type = work.type();
        Object[] owner = type.statements().keyValues(work.entry().key().id());
        try (PreparedStatement statement = connection.prepareStatement(render(elements.deleteOwner()))) {
            bindOwner(statement, elements.ownerBinders(), owner);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw failure("delete of the element collection " + elements.model().name() + " of", type, e);
        }
    }

    /** Binds the key of an owner from parameter 1; returns the next parameter index. */
    private int bindOwner(PreparedStatement statement, List<ValueBinder> binders, Object[] owner) throws SQLException {
        for (int k = 0; k < owner.length; k++) {
            binders.get(k).bind(dialect, statement, k + 1, owner[k]);
        }
        return owner.length + 1;
    }

    /** The elements of {@code from} that {@code removed} does not hold, compared by identity, as many times as left. */
    private static List<Object> minus(List<Object> from, List<Object> removed) {
        Map<Object, Integer> counts = new IdentityHashMap<>();
        removed.forEach(element -> counts.merge(element, 1, Integer::sum));
        List<Object> rest = new ArrayList<>();
        for (Object element : from) {
            Integer count = counts.get(element);
            if (count == null || count == 0) {
                rest.add(element);
            } else {
                counts.put(element, count - 1);
            }
        }
        return rest;
    }

    /** The SQL of a statement, rendered once by the dialect. */
    String render(Statement statement) {
        return rendered.computeIfAbsent(statement, dialect::render);
    }

    private static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    // ---- updates ------------------------------------------------------------------------------------------

    private void update(List<Work> group, PersistenceContext context, Connection connection) {
        MappedEntity type = group.getFirst().type();
        EntityStatements statements = type.statements();
        Sql sql = sql(type);
        int version = versionAttribute(type);
        for (Work work : group) {
            if (version >= 0) {
                Object next = nextVersion(type, work.entry().snapshot()[version]);
                type.access().set(work.entry().instance(), version, next);
                work.state()[version] = next;
            }
        }
        try {
            for (int t = 0; t < sql.tables().size(); t++) {
                EntityStatements.TableStatements table = statements.tables().get(t);
                if (sql.tables().get(t).update() == null) {
                    continue; // nothing in that table can change
                }
                try (PreparedStatement statement = connection.prepareStatement(sql.tables().get(t).update())) {
                    for (Work work : group) {
                        bind(statement, table.updateParameters(), statements, statements.values(work.state()), work.entry().snapshot());
                        statement.addBatch();
                    }
                    int[] counts = statement.executeBatch();
                    if (t == 0) {
                        check(counts, group, version >= 0, "updated"); // the version lives in the primary row
                    }
                }
            }
        } catch (SQLException e) {
            throw failure("update", type, e);
        }
        for (Work work : group) {
            context.updated(work.entry(), type.state().snapshot(work.state()));
            type.callback("PostUpdate", work.entry().instance());
            LockModeType mode = work.entry().lockMode();
            if (Locks.forcesIncrement(mode)) {
                // incremented once per transaction: the lock stays, without forcing another version
                context.lock(work.entry(), mode == LockModeType.PESSIMISTIC_FORCE_INCREMENT ? LockModeType.PESSIMISTIC_WRITE
                    : LockModeType.OPTIMISTIC);
            }
        }
    }

    // ---- deletes ------------------------------------------------------------------------------------------

    private void delete(List<Work> group, PersistenceContext context, Connection connection) {
        MappedEntity type = group.getFirst().type();
        EntityStatements statements = type.statements();
        Sql sql = sql(type);
        try {
            // the rows of the secondary tables first: they reference the primary row
            for (int t = sql.tables().size() - 1; t >= 0; t--) {
                EntityStatements.TableStatements table = statements.tables().get(t);
                try (PreparedStatement statement = connection.prepareStatement(sql.tables().get(t).delete())) {
                    for (Work work : group) {
                        bind(statement, table.deleteParameters(), statements, statements.values(work.state()), work.entry().snapshot());
                        statement.addBatch();
                    }
                    int[] counts = statement.executeBatch();
                    if (t == 0) {
                        check(counts, group, versionAttribute(type) >= 0, "deleted");
                    }
                }
            }
        } catch (SQLException e) {
            throw failure("delete", type, e);
        }
        for (Work work : group) {
            context.deleted(work.entry());
            type.callback("PostRemove", work.entry().instance());
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Consecutive works of the same entity: one statement, one batch. */
    private static List<List<Work>> groups(List<Work> works) {
        List<List<Work>> groups = new ArrayList<>();
        for (Work work : works) {
            if (groups.isEmpty() || groups.getLast().getFirst().type() != work.type()) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(work);
        }
        return groups;
    }

    Sql sql(MappedEntity type) {
        return sql.computeIfAbsent(type, t -> {
            EntityStatements statements = t.statements();
            List<TableSql> tables = new ArrayList<>();
            for (EntityStatements.TableStatements table : statements.tables()) {
                tables.add(new TableSql(dialect.render(table.insert()), table.update() == null ? null : dialect.render(table.update()),
                    dialect.render(table.delete()), dialect.render(table.select())));
            }
            var key = statements.insert().generatedKey();
            return new Sql(List.copyOf(tables), key == null ? null : new String[] {dialect.generatedKeyName(key)});
        });
    }

    private static Object[] read(MappedEntity type, Object instance) {
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(instance, state);
        return state;
    }

    private void bind(PreparedStatement statement, List<EntityStatements.Parameter> parameters, EntityStatements statements,
            Object[] values, Object[] snapshot) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            EntityStatements.Parameter parameter = parameters.get(i);
            EntityStatements.Column column = statements.columns().get(parameter.column());
            Object value = parameter.previous() ? snapshot[column.attribute()] : values[parameter.column()];
            column.binder().bind(dialect, statement, i + 1, value);
        }
    }

    /** §3.4.2: a versioned row that matched nothing was changed or deleted by another transaction. */
    private static void check(int[] counts, List<Work> group, boolean versioned, String what) {
        if (!versioned) {
            return;
        }
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) { // Statement.SUCCESS_NO_INFO (-2): the driver cannot tell, nothing to check
                Object instance = group.get(i).entry().instance();
                throw new OptimisticLockException("The row of " + group.get(i).type().model().entityName() + " "
                    + group.get(i).entry().key().id() + " was not " + what + ": another transaction changed or deleted it", null,
                    instance);
            }
        }
    }

    private static int versionAttribute(MappedEntity type) {
        return type.model().version().map(v -> type.model().attributes().indexOf(v)).orElse(-1);
    }

    /** A version still {@code null} at insert starts at 0, or now for a timestamp (§3.4.2). */
    private static void initialVersion(MappedEntity type, Work work, int version) {
        if (version >= 0 && work.state()[version] == null) {
            Object initial = nextVersion(type, null);
            type.access().set(work.entry().instance(), version, initial);
            work.state()[version] = initial;
        }
    }

    /**
     * The version after {@code current}: numbers count, timestamps take the time, truncated to the millisecond that
     * every database keeps (a finer one would not match the row read back).
     */
    private static Object nextVersion(MappedEntity type, Object current) {
        Class<?> versionType = type.model().version().orElseThrow().javaType();
        // numbers wrap around rather than Math.addExact: optimistic locking only compares the version for equality,
        // and an exception would stop an entity updated 2^31 times
        if (versionType == int.class || versionType == Integer.class) {
            return current == null ? 0 : (Integer) current + 1;
        }
        if (versionType == long.class || versionType == Long.class) {
            return current == null ? 0L : (Long) current + 1;
        }
        if (versionType == short.class || versionType == Short.class) {
            return current == null ? (short) 0 : (short) ((Short) current + 1);
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (versionType == Timestamp.class) {
            return Timestamp.from(now);
        }
        if (versionType == Instant.class) {
            return now;
        }
        if (versionType == LocalDateTime.class) {
            return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        }
        throw new PersistenceException("The version attribute of " + type.model().entityName() + " has type "
            + versionType.getName() + ", which is not a version type (§2.2: int, short, long, their wrappers, Timestamp)");
    }

    private static PersistenceException failure(String what, MappedEntity type, SQLException e) {
        return new PersistenceException("The " + what + " of " + type.model().entityName() + " failed: " + e.getMessage(), e);
    }
}
