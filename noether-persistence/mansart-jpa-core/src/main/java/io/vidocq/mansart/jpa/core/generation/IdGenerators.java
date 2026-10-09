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
package io.vidocq.mansart.jpa.core.generation;

import io.vidocq.mansart.jpa.core.jdbc.ConnectionSource;
import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.model.GenerationModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.SequenceGeneratorModel;
import io.vidocq.mansart.jpa.core.model.TableGeneratorModel;
import io.vidocq.mansart.jpa.dialect.Dialect;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import io.vidocq.mansart.jpa.dialect.sql.Increment;
import io.vidocq.mansart.jpa.dialect.sql.Insert;
import io.vidocq.mansart.jpa.dialect.sql.NextValue;
import io.vidocq.mansart.jpa.dialect.sql.Select;
import io.vidocq.mansart.jpa.dialect.sql.Table;
import jakarta.persistence.GenerationType;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The identifier generators of a factory (§11.1.20, §11.1.51, §11.1.52): one per sequence or table row, shared by its
 * entity managers. Values are handed out from blocks of {@code allocationSize} without a lock: a block is an immutable
 * range swapped by compare-and-set, and the database round trip that fetches the next block runs outside any critical
 * section — two threads that fetch at once each keep their own block (identifiers may have gaps, never duplicates).
 *
 * <p>{@code AUTO} is a table generator on the defaults of the reference implementation — table {@code SEQUENCE},
 * columns {@code SEQ_NAME} and {@code SEQ_COUNT}, row {@code SEQ_GEN}, blocks of 50 — or {@code UUID} for a
 * {@code UUID} identifier; a sequence without a name is the sequence named after its generator.
 */
public final class IdGenerators {

    private static final int DEFAULT_ALLOCATION = 50;

    /** A source of identifier values. */
    private interface Generator {
        Object next(Connection caller, Class<?> type);
    }

    /** Fetches a new block: its first and last values. */
    @FunctionalInterface
    private interface Fetch {
        long[] block(Connection caller) throws SQLException;
    }

    /** Hands out the values of blocks, lock-free. */
    private static final class Blocks implements Generator {
        private record Block(long next, long last) {
        }

        private final AtomicReference<Block> current = new AtomicReference<>();
        private final Fetch fetch;

        Blocks(Fetch fetch) {
            this.fetch = fetch;
        }

        @Override
        public Object next(Connection caller, Class<?> type) {
            return convert(nextValue(caller), type);
        }

        private long nextValue(Connection caller) {
            while (true) {
                Block block = current.get();
                if (block != null && block.next() <= block.last()) {
                    if (current.compareAndSet(block, new Block(block.next() + 1, block.last()))) {
                        return block.next();
                    }
                    continue;
                }
                long[] fresh;
                try {
                    fresh = fetch.block(caller); // the round trip, outside any critical section
                } catch (SQLException e) {
                    throw new PersistenceException("Unable to generate an identifier: " + e.getMessage(), e);
                }
                current.compareAndSet(block, new Block(fresh[0] + 1, fresh[1])); // else another block won: keep ours
                return fresh[0];
            }
        }
    }

    private final Dialect dialect;
    private final ConnectionSource connections;
    private final java.util.function.Function<String, Identifier> identifiers;
    private final Map<String, Generator> generators = new ConcurrentHashMap<>();

    public IdGenerators(Dialect dialect, ConnectionSource connections) {
        this(dialect, connections, Identifier::of);
    }

    public IdGenerators(Dialect dialect, ConnectionSource connections,
            java.util.function.Function<String, Identifier> identifiers) {
        this.dialect = dialect;
        this.connections = connections;
        this.identifiers = identifiers;
    }

    /**
     * Whether the identifier of {@code type} is generated before the insert: by a sequence, a table or as a UUID
     * ({@code IDENTITY} identifiers are generated by the insert itself).
     */
    public static boolean generatesBeforeInsert(MappedEntity type) {
        return type.model().id() instanceof IdModel.Single single
            && single.generation().map(g -> g.strategy() != GenerationType.IDENTITY).orElse(false);
    }

    /** A new identifier for an instance of {@code type}; {@code caller} is the connection a sequence is read through. */
    public Object next(MappedEntity type, Connection caller) {
        IdModel.Single id = (IdModel.Single) type.model().id();
        Class<?> idType = id.attribute().javaType();
        GenerationModel generation = id.generation().orElseThrow();
        return generator(generation, idType).next(caller, idType);
    }

    private Generator generator(GenerationModel generation, Class<?> idType) {
        return switch (generation.strategy()) {
            case UUID -> uuid();
            case SEQUENCE -> sequence(generation.sequence().orElseGet(() -> defaultSequence(generation)));
            case TABLE -> table(generation.table().orElseGet(() -> defaultTable(generation.generatorName())));
            case AUTO -> idType == UUID.class ? uuid()
                : generation.sequence().isPresent() ? sequence(generation.sequence().get())
                : table(generation.table().orElseGet(() -> defaultTable(null)));
            case IDENTITY -> throw new IllegalStateException("An IDENTITY identifier is generated by the insert");
        };
    }

    private static Generator uuid() {
        return (caller, type) -> type == String.class ? UUID.randomUUID().toString() : UUID.randomUUID();
    }

    private static SequenceGeneratorModel defaultSequence(GenerationModel generation) {
        String name = generation.generatorName() != null ? generation.generatorName() : "SEQ_GEN_SEQUENCE";
        return new SequenceGeneratorModel(name, null, null, null, 1, DEFAULT_ALLOCATION);
    }

    private static TableGeneratorModel defaultTable(String generatorName) {
        return new TableGeneratorModel(generatorName, null, null, null, null, null, null, 1, DEFAULT_ALLOCATION);
    }

    /** A sequence incremented by {@code allocationSize}: the value read starts a block of that size. */
    private Generator sequence(SequenceGeneratorModel model) {
        String name = model.sequenceName() != null ? model.sequenceName() : model.name();
        String sql = dialect.render(new NextValue(identifier(name), identifier(model.schema()), identifier(model.catalog())));
        int allocation = Math.max(1, model.allocationSize());
        return generators.computeIfAbsent("sequence:" + sql, k -> new Blocks(caller -> {
            try (PreparedStatement next = caller.prepareStatement(sql); ResultSet value = next.executeQuery()) {
                value.next();
                long first = value.getLong(1);
                return new long[] {first, first + allocation - 1};
            }
        }));
    }

    /**
     * A row of a generator table moved forward by {@code allocationSize} in a transaction of its own (a rollback of the
     * caller must not hand the same values out again); a missing row is created at {@code initialValue}.
     */
    private Generator table(TableGeneratorModel model) {
        Table table = new Table(identifier(model.table() != null ? model.table() : "SEQUENCE"), identifier(model.schema()),
            identifier(model.catalog()));
        Identifier key = identifier(model.pkColumnName() != null ? model.pkColumnName() : "SEQ_NAME");
        Identifier value = identifier(model.valueColumnName() != null ? model.valueColumnName() : "SEQ_COUNT");
        String row = model.pkColumnValue() != null ? model.pkColumnValue() : model.name() != null ? model.name() : "SEQ_GEN";
        String increment = dialect.render(new Increment(table, value, key));
        String select = dialect.render(new Select(table, List.of(value), List.of(key)));
        String insert = dialect.render(new Insert(table, List.of(key, value), null));
        int allocation = Math.max(1, model.allocationSize());
        int initial = model.initialValue();
        return generators.computeIfAbsent("table:" + increment + "/" + row, k -> new Blocks(caller -> {
            try (Connection own = connections.acquire()) {
                own.setAutoCommit(false);
                try {
                    long[] block = tableBlock(own, increment, select, insert, row, initial, allocation);
                    own.commit();
                    return block;
                } catch (SQLException | RuntimeException e) {
                    own.rollback();
                    throw e;
                }
            }
        }));
    }

    private static long[] tableBlock(Connection own, String increment, String select, String insert, String row, int initial,
            int allocation) throws SQLException {
        try (PreparedStatement move = own.prepareStatement(increment)) {
            move.setLong(1, allocation);
            move.setString(2, row);
            if (move.executeUpdate() == 0) {
                try (PreparedStatement create = own.prepareStatement(insert)) {
                    create.setString(1, row);
                    create.setLong(2, (long) initial - 1 + allocation);
                    create.executeUpdate();
                }
                return new long[] {initial, (long) initial + allocation - 1};
            }
        }
        try (PreparedStatement read = own.prepareStatement(select)) {
            read.setString(1, row);
            try (ResultSet value = read.executeQuery()) {
                value.next();
                long last = value.getLong(1);
                return new long[] {last - allocation + 1, last};
            }
        }
    }

    private Identifier identifier(String name) {
        return name == null || name.isBlank() ? null : identifiers.apply(name);
    }

    /** A generated value as the identifier type: §2.4 integral types, their wrappers, BigInteger, BigDecimal. */
    private static Object convert(long value, Class<?> type) {
        if (type == long.class || type == Long.class) {
            return value;
        }
        if (type == int.class || type == Integer.class) {
            return Math.toIntExact(value);
        }
        if (type == short.class || type == Short.class) {
            return (short) Math.toIntExact(value);
        }
        if (type == BigInteger.class) {
            return BigInteger.valueOf(value);
        }
        if (type == BigDecimal.class) {
            return BigDecimal.valueOf(value);
        }
        if (type == String.class) {
            return Long.toString(value);
        }
        throw new PersistenceException("A generated identifier cannot be a " + type.getName());
    }
}
