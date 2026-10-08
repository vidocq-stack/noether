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
package io.vidocq.mansart.jpa.core.mapping;

import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinders;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.PersistenceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * How the state of a managed class is kept in a snapshot and compared at flush (§3.2.4), attribute by attribute, without
 * enhancement nor interception: immutable values are shared, mutable ones (arrays, dates, calendars) copied,
 * embeddables kept as their own state, converted values compared as the database stores them, other serializable
 * values compared serialized. Built once per entity; immutable, shared by every persistence context.
 */
public final class StatePolicy {

    /** Keeps a value in a snapshot, and tells whether a current value is the same. */
    private interface Copier {
        Object copy(Object value);

        boolean same(Object snapshot, Object current);
    }

    private static final Copier IMMUTABLE = new Copier() {
        @Override
        public Object copy(Object value) {
            return value;
        }

        @Override
        public boolean same(Object snapshot, Object current) {
            return Objects.equals(snapshot, current);
        }
    };

    /** Relationships, element collections and attributes left to a mapping file: their reference (P5 and later). */
    private static final Copier REFERENCE = new Copier() {
        @Override
        public Object copy(Object value) {
            return value;
        }

        @Override
        public boolean same(Object snapshot, Object current) {
            return snapshot == current;
        }
    };

    private static final Copier ARRAY = new Copier() {
        @Override
        public Object copy(Object value) {
            return switch (value) {
                case null -> null;
                case byte[] bytes -> bytes.clone();
                case char[] chars -> chars.clone();
                case Object[] objects -> objects.clone();
                default -> value;
            };
        }

        @Override
        public boolean same(Object snapshot, Object current) {
            return Objects.deepEquals(snapshot, current);
        }
    };

    private static final Copier MUTABLE_TEMPORAL = new Copier() {
        @Override
        public Object copy(Object value) {
            return switch (value) {
                case null -> null;
                case Date date -> date.clone();
                case Calendar calendar -> calendar.clone();
                default -> value;
            };
        }

        @Override
        public boolean same(Object snapshot, Object current) {
            return Objects.equals(snapshot, current);
        }
    };

    private static final Copier DECIMAL = new Copier() {
        @Override
        public Object copy(Object value) {
            return value;
        }

        @Override
        public boolean same(Object snapshot, Object current) {
            return snapshot == null ? current == null : current != null && ((BigDecimal) snapshot).compareTo((BigDecimal) current) == 0;
        }
    };

    private static final Copier SERIALIZED = new Copier() {
        @Override
        public Object copy(Object value) {
            return value == null ? null : serialize(value);
        }

        @Override
        public boolean same(Object snapshot, Object current) {
            return snapshot == null ? current == null : current != null && Arrays.equals((byte[]) snapshot, serialize(current));
        }
    };

    /** Basic types whose instances cannot change; {@code java.time} types are added by package. */
    private static final Set<Class<?>> IMMUTABLE_TYPES = Set.of(String.class, Boolean.class, Character.class, Byte.class,
        Short.class, Integer.class, Long.class, Float.class, Double.class, BigInteger.class, UUID.class, java.time.Year.class);

    private final Copier[] copiers;

    private StatePolicy(Copier[] copiers) {
        this.copiers = copiers;
    }

    /** The policy of {@code attributes}; {@code embeddables} gives the access of each embeddable they embed. */
    static StatePolicy of(List<AttributeModel> attributes, Function<EmbeddableModel, ManagedAccess> embeddables,
            ValueBinders converters) {
        Copier[] copiers = new Copier[attributes.size()];
        for (int i = 0; i < copiers.length; i++) {
            copiers[i] = copier(attributes.get(i), embeddables, converters);
        }
        return new StatePolicy(copiers);
    }

    /** A snapshot of {@code state}, as read from an instance: independent of it from now on. */
    public Object[] snapshot(Object[] state) {
        Object[] snapshot = new Object[state.length];
        for (int i = 0; i < state.length; i++) {
            snapshot[i] = copiers[i].copy(state[i]);
        }
        return snapshot;
    }

    /** The indexes of the attributes whose current value differs from the snapshot, in order. */
    public List<Integer> changes(Object[] snapshot, Object[] state) {
        List<Integer> changes = new ArrayList<>();
        for (int i = 0; i < state.length; i++) {
            if (!copiers[i].same(snapshot[i], state[i])) {
                changes.add(i);
            }
        }
        return changes;
    }

    /** Whether any attribute differs from the snapshot; stops at the first change. */
    public boolean dirty(Object[] snapshot, Object[] state) {
        for (int i = 0; i < state.length; i++) {
            if (!copiers[i].same(snapshot[i], state[i])) {
                return true;
            }
        }
        return false;
    }

    private static Copier copier(AttributeModel attribute, Function<EmbeddableModel, ManagedAccess> embeddables,
            ValueBinders converters) {
        return switch (attribute) {
            case BasicAttribute basic -> basic.conversion() instanceof ValueConversion.Converted(Class<?> converter, Class<?> column)
                ? converted(converters.converter(converter), byType(column))
                : byType(basic.javaType());
            case EmbeddedAttribute embedded -> embedded(embedded.embeddable(), embeddables, converters);
            default -> REFERENCE;
        };
    }

    private static Copier byType(Class<?> type) {
        if (type.isArray()) {
            return ARRAY;
        }
        if (Date.class.isAssignableFrom(type) || Calendar.class.isAssignableFrom(type)) {
            return MUTABLE_TEMPORAL;
        }
        if (type == BigDecimal.class) {
            return DECIMAL;
        }
        if (type.isPrimitive() || type.isEnum() || type.isRecord() || IMMUTABLE_TYPES.contains(type)
                || Temporal.class.isAssignableFrom(type) && type.getPackageName().equals("java.time")) {
            return IMMUTABLE;
        }
        return Serializable.class.isAssignableFrom(type) ? SERIALIZED : REFERENCE;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Copier converted(AttributeConverter converter, Copier column) {
        return new Copier() {
            @Override
            public Object copy(Object value) {
                return column.copy(converter.convertToDatabaseColumn(value));
            }

            @Override
            public boolean same(Object snapshot, Object current) {
                return column.same(snapshot, converter.convertToDatabaseColumn(current));
            }
        };
    }

    private static Copier embedded(EmbeddableModel embeddable, Function<EmbeddableModel, ManagedAccess> embeddables,
            ValueBinders converters) {
        ManagedAccess access = embeddables.apply(embeddable);
        StatePolicy nested = of(embeddable.attributes(), embeddables, converters);
        int size = embeddable.attributes().size();
        return new Copier() {
            @Override
            public Object copy(Object value) {
                if (value == null) {
                    return null;
                }
                Object[] state = new Object[size];
                access.read(value, state);
                return nested.snapshot(state);
            }

            @Override
            public boolean same(Object snapshot, Object current) {
                if (snapshot == null || current == null) {
                    return snapshot == current;
                }
                Object[] state = new Object[size];
                access.read(current, state);
                return !nested.dirty((Object[]) snapshot, state);
            }
        };
    }

    private static byte[] serialize(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (IOException e) {
            throw new PersistenceException("Mansart cannot serialize a value of " + value.getClass().getName(), e);
        }
        return bytes.toByteArray();
    }
}
