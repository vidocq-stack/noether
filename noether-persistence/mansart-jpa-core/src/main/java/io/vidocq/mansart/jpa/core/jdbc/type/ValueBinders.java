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
package io.vidocq.mansart.jpa.core.jdbc.type;

import io.vidocq.mansart.jpa.core.access.Handles;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link ValueBinder}s of the basic attributes of one persistence unit (§2.8 and the 3.2 additions, §3.9
 * converters, §11.1.18 enums, §11.1.53 legacy temporals), with the standard JDBC 4.2 mappings. A dialect will be able
 * to replace the mapping of a type its database stores differently (UUID, Instant) once its SPI is settled (D4).
 *
 * <p>Converters are instantiated once per unit and shared by every attribute that uses them.
 */
public final class ValueBinders {

    @FunctionalInterface
    private interface Writer {
        void write(PreparedStatement statement, int index, Object value) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader {
        Object read(ResultSet results, int column) throws SQLException;
    }

    /** A JDBC mapping: {@code NULL} is bound with {@code sqlType}, any other value through {@code writer}. */
    private record Simple(int sqlType, Writer writer, Reader reader) implements ValueBinder {
        @Override
        public void bind(PreparedStatement statement, int index, Object value) throws SQLException {
            if (value == null) {
                statement.setNull(index, sqlType);
            } else {
                writer.write(statement, index, value);
            }
        }

        @Override
        public Object read(ResultSet results, int column) throws SQLException {
            return reader.read(results, column);
        }
    }

    /** A converted attribute: the converter stands between the attribute and the binder of its database type. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private record Converted(AttributeConverter converter, ValueBinder column) implements ValueBinder {
        @Override
        public void bind(PreparedStatement statement, int index, Object value) throws SQLException {
            column.bind(statement, index, converter.convertToDatabaseColumn(value));
        }

        @Override
        public Object read(ResultSet results, int column) throws SQLException {
            return converter.convertToEntityAttribute(this.column.read(results, column));
        }
    }

    /** An enum stored as the value of its {@code @EnumeratedValue} field (3.2). */
    private record ByEnumeratedValue(Class<?> type, Map<Object, Object> valueOf, Map<Object, Object> constantOf, ValueBinder column)
            implements ValueBinder {
        @Override
        public void bind(PreparedStatement statement, int index, Object value) throws SQLException {
            column.bind(statement, index, value == null ? null : valueOf.get(value));
        }

        @Override
        public Object read(ResultSet results, int column) throws SQLException {
            Object value = this.column.read(results, column);
            if (value == null) {
                return null;
            }
            Object constant = constantOf.get(value);
            if (constant == null) {
                throw new PersistenceException("The value " + value + " read from the database is not the enumerated value of a "
                    + "constant of " + type.getName());
            }
            return constant;
        }
    }

    private final ClassLoader loader;
    private final Map<Class<?>, AttributeConverter<?, ?>> converters = new ConcurrentHashMap<>();

    public ValueBinders(ClassLoader loader) {
        this.loader = loader;
    }

    /** The binder of {@code attribute}. */
    public ValueBinder of(BasicAttribute attribute) {
        Class<?> type = attribute.javaType();
        return switch (attribute.conversion()) {
            case ValueConversion.Converted(Class<?> converter, Class<?> databaseType) ->
                new Converted(converter(converter), of(databaseType));
            case ValueConversion.EnumOrdinal() -> ordinal(type);
            case ValueConversion.EnumString() -> byName(type);
            case ValueConversion.EnumByValue(String field, Class<?> valueType) -> byEnumeratedValue(type, field, valueType);
            case ValueConversion.Temporal(TemporalType temporal) -> legacyTemporal(type, temporal);
            case ValueConversion.None() -> of(type);
        };
    }

    /** The shared instance of a converter class. */
    public AttributeConverter<?, ?> converter(Class<?> converterClass) {
        return converters.computeIfAbsent(converterClass, c -> (AttributeConverter<?, ?>) Handles.newInstance(c));
    }

    // ---- types without conversion -----------------------------------------------------------------------

    private ValueBinder of(Class<?> type) {
        ValueBinder binder = standard(type);
        if (binder != null) {
            return binder;
        }
        if (type.isEnum()) {
            return ordinal(type);
        }
        if (Serializable.class.isAssignableFrom(type)) {
            return serialized(type);
        }
        throw new PersistenceException("Mansart cannot store a value of type " + type.getName() + ": it is neither a basic type, "
            + "serializable, nor converted (§2.8)");
    }

    private static ValueBinder standard(Class<?> type) {
        boolean primitive = type.isPrimitive();
        if (type == String.class) {
            return new Simple(Types.VARCHAR, (s, i, v) -> s.setString(i, (String) v), ResultSet::getString);
        }
        if (type == int.class || type == Integer.class) {
            return new Simple(Types.INTEGER, (s, i, v) -> s.setInt(i, (Integer) v),
                (r, c) -> nullable(r, r.getInt(c), primitive));
        }
        if (type == long.class || type == Long.class) {
            return new Simple(Types.BIGINT, (s, i, v) -> s.setLong(i, (Long) v),
                (r, c) -> nullable(r, r.getLong(c), primitive));
        }
        if (type == boolean.class || type == Boolean.class) {
            return new Simple(Types.BOOLEAN, (s, i, v) -> s.setBoolean(i, (Boolean) v),
                (r, c) -> nullable(r, r.getBoolean(c), primitive));
        }
        if (type == short.class || type == Short.class) {
            return new Simple(Types.SMALLINT, (s, i, v) -> s.setShort(i, (Short) v),
                (r, c) -> nullable(r, r.getShort(c), primitive));
        }
        if (type == byte.class || type == Byte.class) {
            return new Simple(Types.TINYINT, (s, i, v) -> s.setByte(i, (Byte) v),
                (r, c) -> nullable(r, r.getByte(c), primitive));
        }
        if (type == double.class || type == Double.class) {
            return new Simple(Types.DOUBLE, (s, i, v) -> s.setDouble(i, (Double) v),
                (r, c) -> nullable(r, r.getDouble(c), primitive));
        }
        if (type == float.class || type == Float.class) {
            return new Simple(Types.REAL, (s, i, v) -> s.setFloat(i, (Float) v),
                (r, c) -> nullable(r, r.getFloat(c), primitive));
        }
        if (type == char.class || type == Character.class) {
            return new Simple(Types.CHAR, (s, i, v) -> s.setString(i, String.valueOf((char) (Character) v)), (r, c) -> {
                String value = r.getString(c);
                if (value == null || value.isEmpty()) {
                    return primitive ? '\0' : null;
                }
                return value.charAt(0);
            });
        }
        if (type == BigDecimal.class) {
            return new Simple(Types.NUMERIC, (s, i, v) -> s.setBigDecimal(i, (BigDecimal) v), ResultSet::getBigDecimal);
        }
        if (type == BigInteger.class) {
            return new Simple(Types.NUMERIC, (s, i, v) -> s.setBigDecimal(i, new BigDecimal((BigInteger) v)), (r, c) -> {
                BigDecimal value = r.getBigDecimal(c);
                return value == null ? null : value.toBigIntegerExact();
            });
        }
        if (type == byte[].class) {
            return new Simple(Types.VARBINARY, (s, i, v) -> s.setBytes(i, (byte[]) v), ResultSet::getBytes);
        }
        if (type == Byte[].class) {
            return new Simple(Types.VARBINARY, (s, i, v) -> s.setBytes(i, unboxed((Byte[]) v)), (r, c) -> boxed(r.getBytes(c)));
        }
        if (type == char[].class) {
            return new Simple(Types.VARCHAR, (s, i, v) -> s.setString(i, new String((char[]) v)), (r, c) -> {
                String value = r.getString(c);
                return value == null ? null : value.toCharArray();
            });
        }
        if (type == Character[].class) {
            return new Simple(Types.VARCHAR, (s, i, v) -> s.setString(i, unboxed((Character[]) v)), (r, c) -> {
                String value = r.getString(c);
                return value == null ? null : value.chars().mapToObj(ch -> (char) ch).toArray(Character[]::new);
            });
        }
        return temporal(type);
    }

    private static ValueBinder temporal(Class<?> type) {
        if (type == LocalDate.class) {
            return object(Types.DATE, LocalDate.class);
        }
        if (type == LocalTime.class) {
            return object(Types.TIME, LocalTime.class);
        }
        if (type == LocalDateTime.class) {
            return object(Types.TIMESTAMP, LocalDateTime.class);
        }
        if (type == OffsetDateTime.class) {
            return object(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTime.class);
        }
        if (type == OffsetTime.class) {
            return object(Types.TIME_WITH_TIMEZONE, OffsetTime.class);
        }
        if (type == UUID.class) {
            return object(Types.OTHER, UUID.class);
        }
        if (type == Instant.class) {
            // not a JDBC 4.2 type: a timestamp, read back in the same time zone as it was written
            return new Simple(Types.TIMESTAMP, (s, i, v) -> s.setTimestamp(i, Timestamp.from((Instant) v)), (r, c) -> {
                Timestamp value = r.getTimestamp(c);
                return value == null ? null : value.toInstant();
            });
        }
        if (type == Year.class) {
            return new Simple(Types.INTEGER, (s, i, v) -> s.setInt(i, ((Year) v).getValue()), (r, c) -> {
                int value = r.getInt(c);
                return r.wasNull() ? null : Year.of(value);
            });
        }
        if (type == java.sql.Date.class) {
            return new Simple(Types.DATE, (s, i, v) -> s.setDate(i, (java.sql.Date) v), ResultSet::getDate);
        }
        if (type == java.sql.Time.class) {
            return new Simple(Types.TIME, (s, i, v) -> s.setTime(i, (java.sql.Time) v), ResultSet::getTime);
        }
        if (type == Timestamp.class) {
            return new Simple(Types.TIMESTAMP, (s, i, v) -> s.setTimestamp(i, (Timestamp) v), ResultSet::getTimestamp);
        }
        if (type == Date.class) {
            return legacyTemporal(Date.class, TemporalType.TIMESTAMP);
        }
        if (type == Calendar.class) {
            return legacyTemporal(Calendar.class, TemporalType.TIMESTAMP);
        }
        return null;
    }

    private static ValueBinder object(int sqlType, Class<?> type) {
        return new Simple(sqlType, (s, i, v) -> s.setObject(i, v), (r, c) -> r.getObject(c, type));
    }

    /** {@code java.util.Date} and {@code Calendar} (§11.1.53): read back as their own class, not a {@code java.sql} one. */
    private static ValueBinder legacyTemporal(Class<?> type, TemporalType temporal) {
        Writer writer = switch (temporal) {
            case DATE -> (s, i, v) -> s.setDate(i, new java.sql.Date(millis(v)));
            case TIME -> (s, i, v) -> s.setTime(i, new java.sql.Time(millis(v)));
            case TIMESTAMP -> (s, i, v) -> s.setTimestamp(i, timestamp(v));
        };
        Reader millis = switch (temporal) {
            case DATE -> (r, c) -> {
                java.sql.Date value = r.getDate(c);
                return value == null ? null : value.getTime();
            };
            case TIME -> (r, c) -> {
                java.sql.Time value = r.getTime(c);
                return value == null ? null : value.getTime();
            };
            case TIMESTAMP -> (r, c) -> {
                Timestamp value = r.getTimestamp(c);
                return value == null ? null : value.getTime();
            };
        };
        int sqlType = switch (temporal) {
            case DATE -> Types.DATE;
            case TIME -> Types.TIME;
            case TIMESTAMP -> Types.TIMESTAMP;
        };
        boolean calendar = Calendar.class.isAssignableFrom(type);
        return new Simple(sqlType, writer, (r, c) -> {
            Long value = (Long) millis.read(r, c);
            if (value == null) {
                return null;
            }
            if (calendar) {
                Calendar result = Calendar.getInstance();
                result.setTimeInMillis(value);
                return result;
            }
            return new Date(value);
        });
    }

    private static long millis(Object value) {
        return value instanceof Calendar calendar ? calendar.getTimeInMillis() : ((Date) value).getTime();
    }

    private static Timestamp timestamp(Object value) {
        return value instanceof Date date ? Timestamp.from(date.toInstant()) : new Timestamp(millis(value));
    }

    // ---- enums (§11.1.18, @EnumeratedValue) -------------------------------------------------------------

    private static ValueBinder ordinal(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        return new Simple(Types.INTEGER, (s, i, v) -> s.setInt(i, ((Enum<?>) v).ordinal()), (r, c) -> {
            int ordinal = r.getInt(c);
            if (r.wasNull()) {
                return null;
            }
            if (ordinal < 0 || ordinal >= constants.length) {
                throw new PersistenceException("The ordinal " + ordinal + " read from the database is not one of " + type.getName());
            }
            return constants[ordinal];
        });
    }

    private static ValueBinder byName(Class<?> type) {
        Map<String, Object> constants = new HashMap<>();
        for (Object constant : type.getEnumConstants()) {
            constants.put(((Enum<?>) constant).name(), constant);
        }
        return new Simple(Types.VARCHAR, (s, i, v) -> s.setString(i, ((Enum<?>) v).name()), (r, c) -> {
            String name = r.getString(c);
            if (name == null) {
                return null;
            }
            Object constant = constants.get(name);
            if (constant == null) {
                throw new PersistenceException("The name " + name + " read from the database is not a constant of " + type.getName());
            }
            return constant;
        });
    }

    private ValueBinder byEnumeratedValue(Class<?> type, String field, Class<?> valueType) {
        MethodHandle getter = Handles.getter(type, field, valueType);
        Map<Object, Object> valueOf = new HashMap<>();
        Map<Object, Object> constantOf = new HashMap<>();
        for (Object constant : type.getEnumConstants()) {
            Object value;
            try {
                value = getter.invokeExact(constant);
            } catch (Throwable e) {
                throw new PersistenceException("Mansart cannot read the enumerated value of " + constant, e);
            }
            valueOf.put(constant, value);
            if (constantOf.putIfAbsent(value, constant) != null) {
                throw new PersistenceException("Two constants of " + type.getName() + " share the enumerated value " + value);
            }
        }
        return new ByEnumeratedValue(type, Map.copyOf(valueOf), Map.copyOf(constantOf), of(boxed(valueType)));
    }

    // ---- serialized values (§2.8) -----------------------------------------------------------------------

    private ValueBinder serialized(Class<?> type) {
        return new Simple(Types.VARBINARY, (s, i, v) -> s.setBytes(i, serialize(v)), (r, c) -> {
            byte[] bytes = r.getBytes(c);
            return bytes == null ? null : type.cast(deserialize(bytes));
        });
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

    private Object deserialize(byte[] bytes) {
        try (ObjectInputStream in = new UnitObjectInputStream(new ByteArrayInputStream(bytes), loader)) {
            return in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new PersistenceException("Mansart cannot deserialize a value read from the database", e);
        }
    }

    /** Resolves the classes of serialized values with the loader of the persistence unit. */
    private static final class UnitObjectInputStream extends ObjectInputStream {
        private final ClassLoader loader;

        UnitObjectInputStream(InputStream in, ClassLoader loader) throws IOException {
            super(in);
            this.loader = loader;
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass description) throws IOException, ClassNotFoundException {
            try {
                return Class.forName(description.getName(), false, loader);
            } catch (ClassNotFoundException e) {
                return super.resolveClass(description);
            }
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static Object nullable(ResultSet results, Object value, boolean primitive) throws SQLException {
        return primitive || !results.wasNull() ? value : null;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        return switch (type.getName()) {
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            case "boolean" -> Boolean.class;
            case "float" -> Float.class;
            default -> Double.class;
        };
    }

    private static byte[] unboxed(Byte[] values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static Byte[] boxed(byte[] values) {
        if (values == null) {
            return null;
        }
        Byte[] result = new Byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static String unboxed(Character[] values) {
        StringBuilder result = new StringBuilder(values.length);
        for (Character value : values) {
            result.append(value.charValue());
        }
        return result.toString();
    }
}
