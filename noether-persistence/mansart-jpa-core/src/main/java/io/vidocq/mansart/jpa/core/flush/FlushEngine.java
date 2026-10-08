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

import io.vidocq.mansart.jpa.core.context.ManagedEntity;
import io.vidocq.mansart.jpa.core.context.PersistenceContext;
import io.vidocq.mansart.jpa.core.mapping.EntityStatements;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.dialect.Dialect;
import jakarta.persistence.EntityExistsException;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Synchronises a persistence context with the database (§3.2.4): the new instances are inserted, the changed ones
 * updated (their snapshot tells, no enhancement), the removed ones deleted. Statements run in a deterministic order —
 * inserts, entities after those they reference, then updates, then deletes in reverse — and consecutive statements of
 * an entity share one batch. A versioned entity is updated and deleted only if its row still has the version it was
 * read with (§3.4.2), else {@link OptimisticLockException}.
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

    private static final Comparator<Work> INSERT_ORDER = Comparator.<Work>comparingInt(w -> w.type().rank())
        .thenComparingLong(w -> w.entry().sequence());
    private static final Comparator<Work> DELETE_ORDER = INSERT_ORDER.reversed();

    private final Dialect dialect;
    private final int batchSize;
    private final Map<MappedEntity, Sql> sql = new ConcurrentHashMap<>();

    public FlushEngine(Dialect dialect, int batchSize) {
        this.dialect = dialect;
        this.batchSize = Math.max(1, batchSize);
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Writes the changes of {@code context} through {@code connection}, then records them in the context. */
    public void flush(PersistenceContext context, Connection connection) {
        List<Work> inserts = new ArrayList<>();
        List<Work> updates = new ArrayList<>();
        List<Work> deletes = new ArrayList<>();
        for (ManagedEntity entry : context.entries()) {
            MappedEntity type = entry.type();
            switch (entry.status()) {
                case MANAGED -> {
                    Object[] state = read(type, entry.instance());
                    if (!entry.inserted()) {
                        inserts.add(new Work(entry, state));
                    } else if (type.state().dirty(entry.snapshot(), state)) {
                        updates.add(new Work(entry, state));
                    }
                }
                case REMOVED -> {
                    if (entry.inserted()) {
                        deletes.add(new Work(entry, read(type, entry.instance())));
                    } else {
                        context.deleted(entry); // removed before its insert: the database never saw it
                    }
                }
            }
        }
        inserts.sort(INSERT_ORDER);
        deletes.sort(DELETE_ORDER);
        for (List<Work> group : groups(inserts)) {
            insert(group, context, connection);
        }
        for (List<Work> group : groups(updates)) {
            update(group, context, connection);
        }
        for (List<Work> group : groups(deletes)) {
            delete(group, context, connection);
        }
    }

    // ---- inserts ------------------------------------------------------------------------------------------

    private void insert(List<Work> group, PersistenceContext context, Connection connection) {
        MappedEntity type = group.getFirst().type();
        EntityStatements statements = type.statements();
        Sql sql = sql(type);
        int version = versionAttribute(type);
        try {
            if (sql.generatedKey() != null) {
                int id = type.idAttributes()[0];
                EntityStatements.Column idColumn = statements.columns().stream().filter(EntityStatements.Column::id).findFirst()
                    .orElseThrow();
                try (PreparedStatement statement = connection.prepareStatement(sql.tables().getFirst().insert(), sql.generatedKey())) {
                    for (Work work : group) {
                        initialVersion(type, work, version);
                        bind(statement, statements.insertParameters(), statements, statements.values(work.state()), null);
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
                    int pending = 0;
                    for (Work work : group) {
                        initialVersion(type, work, version);
                        bind(statement, statements.insertParameters(), statements, statements.values(work.state()), null);
                        statement.addBatch();
                        if (++pending == batchSize) {
                            statement.executeBatch();
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
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
        }
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

    private static void bind(PreparedStatement statement, List<EntityStatements.Parameter> parameters, EntityStatements statements,
            Object[] values, Object[] snapshot) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            EntityStatements.Parameter parameter = parameters.get(i);
            EntityStatements.Column column = statements.columns().get(parameter.column());
            Object value = parameter.previous() ? snapshot[column.attribute()] : values[parameter.column()];
            column.binder().bind(statement, i + 1, value);
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
