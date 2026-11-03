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
package io.vidocq.noether.jpa.core.jdbc.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.model.AccessKind;
import io.vidocq.noether.jpa.core.model.BasicAttribute;
import io.vidocq.noether.jpa.core.model.ColumnModel;
import io.vidocq.noether.jpa.core.model.ValueConversion;
import io.vidocq.noether.jpa.core.model.build.fixtures.Money;
import io.vidocq.noether.jpa.dialect.Dialect;
import io.vidocq.noether.jpa.dialect.StandardDialect;
import jakarta.persistence.FetchType;
import jakarta.persistence.PersistenceException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Serialized values are read back from database bytes, which are untrusted input (security audit
 * F-02): deserialization must be protected by a JEP 290 ObjectInputFilter. Legitimate values
 * (the declared type, JDK object graphs) still round-trip; unknown classes, over-deep graphs and
 * over-sized arrays are rejected with a PersistenceException.
 */
class ValueBindersSerializationFilterTest {

    private static final Dialect ANSI = new StandardDialect() {
        @Override
        public String name() {
            return "ansi";
        }
    };

    private final ValueBinders binders = new ValueBinders(getClass().getClassLoader());

    private static BasicAttribute attribute(Class<?> type) {
        return new BasicAttribute("v", type, AccessKind.FIELD, Object.class, ColumnModel.defaultFor("v"), !type.isPrimitive(),
            FetchType.EAGER, false, new ValueConversion.None(), false);
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }

    /** Reads raw column bytes through the binder of the declared type, as a database read would. */
    private Object readRaw(Class<?> declaredType, byte[] bytes) throws SQLException {
        ValueBinder binder = binders.of(attribute(declaredType));
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:filter" + System.nanoTime());
                Statement statement = connection.createStatement()) {
            statement.execute("create table t (v blob)");
            try (PreparedStatement insert = connection.prepareStatement("insert into t values (?)")) {
                insert.setBytes(1, bytes);
                insert.executeUpdate();
            }
            try (ResultSet results = statement.executeQuery("select v from t")) {
                assertThat(results.next()).isTrue();
                return binder.read(results, 1);
            }
        }
    }

    private Object roundTrip(Class<?> type, Object value) throws SQLException {
        ValueBinder binder = binders.of(attribute(type));
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:rt" + System.nanoTime());
                Statement statement = connection.createStatement()) {
            statement.execute("create table t (v blob)");
            try (PreparedStatement insert = connection.prepareStatement("insert into t values (?)")) {
                binder.bind(ANSI, insert, 1, value);
                insert.executeUpdate();
            }
            try (ResultSet results = statement.executeQuery("select v from t")) {
                assertThat(results.next()).isTrue();
                return binder.read(results, 1);
            }
        }
    }

    @Test
    void aValueOfTheDeclaredTypeStillRoundTrips() throws SQLException {
        assertThat(roundTrip(Money.class, new Money(1999))).isEqualTo(new Money(1999));
    }

    @Test
    void aJdkObjectGraphIsAccepted() throws SQLException {
        List<Object> graph = new ArrayList<>(List.of("text", 42, new HashMap<>(java.util.Map.of("k", "v"))));
        assertThat(roundTrip(ArrayList.class, graph)).isEqualTo(graph);
    }

    /** A serializable class outside the allowlist: not JDK, not the declared type, not its package. */
    static class EvilPayload implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void aPayloadWithAClassOutsideTheAllowlistIsRejected() throws Exception {
        byte[] payload = serialize(new EvilPayload());

        assertThatThrownBy(() -> readRaw(Money.class, payload))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("deserialize");
    }

    @Test
    void anOverDeepObjectGraphIsRejected() throws Exception {
        Object deep = "leaf";
        for (int i = 0; i < 100; i++) {
            deep = new ArrayList<>(List.of(deep));
        }
        byte[] payload = serialize(deep);

        assertThatThrownBy(() -> readRaw(ArrayList.class, payload)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void anOverSizedArrayIsRejected() throws Exception {
        byte[] payload = serialize(new byte[2_000_000]);

        assertThatThrownBy(() -> readRaw(Money.class, payload)).isInstanceOf(PersistenceException.class);
    }
}
