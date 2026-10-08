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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ColumnModel;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Grade;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Level;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Money;
import io.vidocq.mansart.jpa.core.model.build.fixtures.MoneyConverter;
import io.vidocq.mansart.jpa.core.model.build.fixtures.UpperCaseConverter;
import jakarta.persistence.FetchType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Year;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValueBindersTest {

    private final ValueBinders binders = new ValueBinders(getClass().getClassLoader());

    private static BasicAttribute attribute(Class<?> type, ValueConversion conversion) {
        return new BasicAttribute("v", type, AccessKind.FIELD, Object.class, ColumnModel.defaultFor("v"), !type.isPrimitive(),
            FetchType.EAGER, false, conversion, false);
    }

    private Object roundTrip(String sqlType, Class<?> type, Object value) throws SQLException {
        return roundTrip(sqlType, attribute(type, new ValueConversion.None()), value);
    }

    private Object roundTrip(String sqlType, BasicAttribute attribute, Object value) throws SQLException {
        ValueBinder binder = binders.of(attribute);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:"); Statement statement = connection.createStatement()) {
            statement.execute("create table t (v " + sqlType + ")");
            try (PreparedStatement insert = connection.prepareStatement("insert into t values (?)")) {
                binder.bind(insert, 1, value);
                insert.executeUpdate();
            }
            try (ResultSet results = statement.executeQuery("select v from t")) {
                assertThat(results.next()).isTrue();
                return binder.read(results, 1);
            }
        }
    }

    /** The raw content of the column after {@code value} was bound, to check what reached the database. */
    private Object stored(String sqlType, BasicAttribute attribute, Object value) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:"); Statement statement = connection.createStatement()) {
            statement.execute("create table t (v " + sqlType + ")");
            try (PreparedStatement insert = connection.prepareStatement("insert into t values (?)")) {
                binders.of(attribute).bind(insert, 1, value);
                insert.executeUpdate();
            }
            try (ResultSet results = statement.executeQuery("select v from t")) {
                results.next();
                return results.getObject(1);
            }
        }
    }

    @Test
    void stringsNumbersAndBooleans() throws SQLException {
        assertThat(roundTrip("varchar(20)", String.class, "Vidocq")).isEqualTo("Vidocq");
        assertThat(roundTrip("int", int.class, 42)).isEqualTo(42);
        assertThat(roundTrip("int", Integer.class, -1)).isEqualTo(-1);
        assertThat(roundTrip("bigint", long.class, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(roundTrip("bigint", Long.class, 5L)).isEqualTo(5L);
        assertThat(roundTrip("smallint", short.class, (short) 7)).isEqualTo((short) 7);
        assertThat(roundTrip("tinyint", Byte.class, (byte) 3)).isEqualTo((byte) 3);
        assertThat(roundTrip("real", float.class, 1.5f)).isEqualTo(1.5f);
        assertThat(roundTrip("double precision", Double.class, 2.25d)).isEqualTo(2.25d);
        assertThat(roundTrip("boolean", boolean.class, true)).isEqualTo(true);
        assertThat(roundTrip("numeric(20,4)", BigDecimal.class, new BigDecimal("12.3400"))).isEqualTo(new BigDecimal("12.3400"));
        assertThat(roundTrip("numeric(40)", BigInteger.class, new BigInteger("123456789012345678901234567890")))
            .isEqualTo(new BigInteger("123456789012345678901234567890"));
    }

    @Test
    void sqlNullIsNullForAWrapperAndTheDefaultForAPrimitive() throws SQLException {
        assertThat(roundTrip("int", Integer.class, null)).isNull();
        assertThat(roundTrip("int", int.class, null)).isEqualTo(0);
        assertThat(roundTrip("boolean", Boolean.class, null)).isNull();
        assertThat(roundTrip("boolean", boolean.class, null)).isEqualTo(false);
        assertThat(roundTrip("varchar(20)", String.class, null)).isNull();
        assertThat(roundTrip("char(1)", char.class, null)).isEqualTo('\0');
        assertThat(roundTrip("uuid", UUID.class, null)).isNull();
        assertThat(roundTrip("date", LocalDate.class, null)).isNull();
        assertThat(roundTrip("timestamp", Instant.class, null)).isNull();
        assertThat(roundTrip("varbinary(20)", byte[].class, null)).isNull();
    }

    @Test
    void charactersAndArrays() throws SQLException {
        assertThat(roundTrip("char(1)", char.class, 'x')).isEqualTo('x');
        assertThat(roundTrip("char(1)", Character.class, 'y')).isEqualTo('y');
        assertThat(roundTrip("varchar(10)", char[].class, "abc".toCharArray())).isEqualTo("abc".toCharArray());
        assertThat(roundTrip("varchar(10)", Character[].class, new Character[] {'d', 'e'})).isEqualTo(new Character[] {'d', 'e'});
        assertThat(roundTrip("varbinary(10)", byte[].class, new byte[] {1, 2, 3})).isEqualTo(new byte[] {1, 2, 3});
        assertThat(roundTrip("varbinary(10)", Byte[].class, new Byte[] {4, 5})).isEqualTo(new Byte[] {4, 5});
    }

    @Test
    void javaTimeTypesYearAndUuid() throws SQLException {
        LocalDate date = LocalDate.of(1811, 10, 8);
        LocalTime time = LocalTime.of(23, 59, 58);
        LocalDateTime dateTime = LocalDateTime.of(1827, 6, 1, 12, 30, 15, 123_000_000);
        OffsetDateTime offset = OffsetDateTime.of(2026, 10, 8, 9, 0, 0, 0, ZoneOffset.ofHours(2));
        Instant instant = Instant.parse("2026-10-08T07:00:00.123456Z");
        UUID uuid = UUID.randomUUID();

        assertThat(roundTrip("date", LocalDate.class, date)).isEqualTo(date);
        assertThat(roundTrip("time", LocalTime.class, time)).isEqualTo(time);
        assertThat(roundTrip("timestamp(6)", LocalDateTime.class, dateTime)).isEqualTo(dateTime);
        assertThat(((OffsetDateTime) roundTrip("timestamp(6) with time zone", OffsetDateTime.class, offset)).isEqual(offset)).isTrue();
        assertThat(roundTrip("timestamp(6)", Instant.class, instant)).isEqualTo(instant);
        assertThat(roundTrip("int", Year.class, Year.of(1775))).isEqualTo(Year.of(1775));
        assertThat(roundTrip("uuid", UUID.class, uuid)).isEqualTo(uuid);
    }

    @Test
    void legacyTemporalTypesFollowTheirTemporalType() throws SQLException {
        Calendar noon = Calendar.getInstance();
        noon.clear();
        noon.set(2026, Calendar.OCTOBER, 8, 12, 34, 56);
        Calendar midnight = Calendar.getInstance();
        midnight.clear();
        midnight.set(2026, Calendar.OCTOBER, 8);

        Object asDate = roundTrip("date", attribute(Date.class, new ValueConversion.Temporal(TemporalType.DATE)), noon.getTime());
        assertThat(asDate).isExactlyInstanceOf(Date.class).isEqualTo(midnight.getTime());
        Object asTimestamp = roundTrip("timestamp", attribute(Date.class, new ValueConversion.Temporal(TemporalType.TIMESTAMP)),
            noon.getTime());
        assertThat(asTimestamp).isExactlyInstanceOf(Date.class).isEqualTo(noon.getTime());
        Object asTime = roundTrip("time", attribute(Date.class, new ValueConversion.Temporal(TemporalType.TIME)), noon.getTime());
        Calendar timeOfDay = Calendar.getInstance();
        timeOfDay.setTime((Date) asTime);
        assertThat(asTime).isExactlyInstanceOf(Date.class);
        assertThat(LocalTime.of(timeOfDay.get(Calendar.HOUR_OF_DAY), timeOfDay.get(Calendar.MINUTE), timeOfDay.get(Calendar.SECOND)))
            .isEqualTo(LocalTime.of(12, 34, 56));

        Object calendar = roundTrip("timestamp", attribute(Calendar.class, new ValueConversion.Temporal(TemporalType.TIMESTAMP)), noon);
        assertThat(((Calendar) calendar).getTimeInMillis()).isEqualTo(noon.getTimeInMillis());

        Timestamp timestamp = Timestamp.valueOf("2026-10-08 12:00:00.123456789");
        assertThat(roundTrip("timestamp(9)", Timestamp.class, timestamp)).isEqualTo(timestamp);
    }

    @Test
    void enumsByOrdinalByNameAndByEnumeratedValue() throws SQLException {
        BasicAttribute ordinal = attribute(Level.class, new ValueConversion.EnumOrdinal());
        BasicAttribute string = attribute(Level.class, new ValueConversion.EnumString());
        BasicAttribute byValue = attribute(Grade.class, new ValueConversion.EnumByValue("code", String.class));

        assertThat(stored("int", ordinal, Level.GOLD)).isEqualTo(2);
        assertThat(roundTrip("int", ordinal, Level.GOLD)).isEqualTo(Level.GOLD);
        assertThat(stored("varchar(10)", string, Level.SILVER)).isEqualTo("SILVER");
        assertThat(roundTrip("varchar(10)", string, Level.SILVER)).isEqualTo(Level.SILVER);
        assertThat(stored("varchar(10)", byValue, Grade.B)).isEqualTo("b");
        assertThat(roundTrip("varchar(10)", byValue, Grade.B)).isEqualTo(Grade.B);
        assertThat(roundTrip("int", ordinal, null)).isNull();
        assertThat(roundTrip("varchar(10)", byValue, null)).isNull();
    }

    @Test
    void anUnknownEnumValueInTheDatabaseIsAPersistenceException() throws SQLException {
        BasicAttribute ordinal = attribute(Level.class, new ValueConversion.EnumOrdinal());
        ValueBinder binder = binders.of(ordinal);
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:"); Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery("select 7")) {
            results.next();
            assertThatThrownBy(() -> binder.read(results, 1)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("7").hasMessageContaining(Level.class.getName());
        }
    }

    @Test
    void convertersStandBetweenTheAttributeAndTheColumn() throws SQLException {
        BasicAttribute money = attribute(Money.class, new ValueConversion.Converted(MoneyConverter.class, Long.class));
        BasicAttribute code = attribute(String.class, new ValueConversion.Converted(UpperCaseConverter.class, String.class));

        assertThat(stored("bigint", money, new Money(1999))).isEqualTo(1999L);
        assertThat(roundTrip("bigint", money, new Money(1999))).isEqualTo(new Money(1999));
        assertThat(roundTrip("bigint", money, null)).isNull();
        assertThat(roundTrip("varchar(10)", code, "abc")).isEqualTo("ABC");
    }

    @Test
    void aSerializableValueWithoutConversionIsStoredSerialized() throws SQLException {
        assertThat(stored("varbinary(1000)", attribute(Money.class, new ValueConversion.None()), new Money(5))).isInstanceOf(byte[].class);
        assertThat(roundTrip("varbinary(1000)", Money.class, new Money(5))).isEqualTo(new Money(5));
    }

    @Test
    void aTypeThatCannotBeStoredIsRejected() {
        assertThatThrownBy(() -> binders.of(attribute(Thread.class, new ValueConversion.None())))
            .isInstanceOf(PersistenceException.class).hasMessageContaining(Thread.class.getName());
    }

    @Test
    void oneConverterInstanceServesTheWholeUnit() {
        BasicAttribute money = attribute(Money.class, new ValueConversion.Converted(MoneyConverter.class, Long.class));
        assertThat(binders.converter(MoneyConverter.class)).isSameAs(binders.converter(MoneyConverter.class));
        assertThat(binders.of(money)).isNotNull();
    }
}
