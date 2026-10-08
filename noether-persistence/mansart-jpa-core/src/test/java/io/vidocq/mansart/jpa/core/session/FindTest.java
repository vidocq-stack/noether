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
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Geo;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Grade;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Level;
import io.vidocq.mansart.jpa.core.model.build.fixtures.OrderLine;
import io.vidocq.mansart.jpa.core.model.build.fixtures.OrderLineKey;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Ticket;
import io.vidocq.mansart.jpa.core.model.build.fixtures.TicketKey;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Venue;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.PersistenceConfiguration;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.2.8 find and getReference: an instance built from its row, managed once per identity. */
class FindTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:find-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Customer (id bigint primary key, name varchar(50), E_MAIL varchar(120), visits int, "
                + "balance decimal(19,2), credit decimal(12,2), since date, legacy timestamp, birthDate date, token uuid, level int, "
                + "preferredLevel varchar(10), grade varchar(5), notes varchar(1000), picture varbinary(100), version int, "
                + "biography varchar(1000))");
            ddl.execute("insert into Customer (id, name, E_MAIL, visits, balance, since, token, level, preferredLevel, grade, "
                + "picture, version) values (1, 'Vidocq', 'v@surete.fr', 3, 12.50, '1811-10-08', "
                + "'6f1c2d1e-0000-4000-8000-000000000001', 2, 'SILVER', 'b', X'0102', 4)");
            ddl.execute("create table Ticket (office varchar(10), number int, subject varchar(50), primary key (office, number))");
            ddl.execute("insert into Ticket values ('Paris', 36, 'quai des Orfèvres')");
            ddl.execute("create table OrderLine (orderId bigint, line int, quantity int, primary key (orderId, line))");
            ddl.execute("insert into OrderLine values (7, 1, 3)");
            ddl.execute("create table Venue (id bigint primary key, name varchar(50), street varchar(50), lat double precision, "
                + "lon double precision)");
            ddl.execute("insert into Venue values (1, 'Sûreté', 'rue de Jérusalem', 48.85, 2.35), (2, 'Nowhere', null, null, null)");
        }
        emf = new PersistenceConfiguration("find").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Customer.class).managedClass(Ticket.class).managedClass(OrderLine.class).managedClass(Venue.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private MappedEntity type(Class<?> entity) {
        return emf.unwrap(EntityManagerFactoryImpl.class).mapping().entity(entity).orElseThrow();
    }

    private Object attribute(Object instance, String name) {
        MappedEntity type = type(instance.getClass());
        return type.access().get(instance, type.model().attributes().indexOf(type.model().attribute(name).orElseThrow()));
    }

    @Test
    void aRowBecomesAManagedInstanceWithEveryBasicType() {
        Customer customer = em.find(Customer.class, 1L);
        assertThat(attribute(customer, "name")).isEqualTo("Vidocq");
        assertThat(attribute(customer, "email")).isEqualTo("v@surete.fr");
        assertThat(attribute(customer, "visits")).isEqualTo(3);
        assertThat(attribute(customer, "balance")).isEqualTo(new BigDecimal("12.50"));
        assertThat(attribute(customer, "since")).isEqualTo(LocalDate.of(1811, 10, 8));
        assertThat(attribute(customer, "token")).isEqualTo(UUID.fromString("6f1c2d1e-0000-4000-8000-000000000001"));
        assertThat(attribute(customer, "level")).isEqualTo(Level.GOLD);
        assertThat(attribute(customer, "preferredLevel")).isEqualTo(Level.SILVER);
        assertThat(attribute(customer, "grade")).isEqualTo(Grade.B);
        assertThat((byte[]) attribute(customer, "picture")).containsExactly(1, 2);
        assertThat(attribute(customer, "version")).isEqualTo(4);
        assertThat(em.contains(customer)).isTrue();
    }

    @Test
    void aSecondFindReturnsTheManagedInstance() { // §3.2.8: one managed instance per identity
        assertThat(em.find(Customer.class, 1L)).isSameAs(em.find(Customer.class, 1L));
    }

    @Test
    void aMissingRowIsNull() {
        assertThat(em.find(Customer.class, 99L)).isNull();
    }

    @Test
    void anInvalidIdentifierOrClassIsRefused() { // §3.2.8: IllegalArgumentException
        assertThatThrownBy(() -> em.find(Customer.class, "one")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> em.find(Customer.class, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> em.find(String.class, 1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compositeIdentifiersAreGivenAsTheirIdClassOrEmbeddable() {
        var embedded = (io.vidocq.mansart.jpa.core.model.IdModel.Embedded) type(Ticket.class).model().id();
        var keyAccess = emf.unwrap(EntityManagerFactoryImpl.class).mapping().access(embedded.attribute().embeddable());
        TicketKey key = (TicketKey) keyAccess.instantiate();
        keyAccess.write(key, new Object[] {"Paris", 36}); // the fixture has no constructor
        Ticket ticket = em.find(Ticket.class, key);
        assertThat(attribute(ticket, "subject")).isEqualTo("quai des Orfèvres");

        OrderLineKey line = new OrderLineKey();
        line.orderId = 7;
        line.line = 1;
        assertThat(attribute(em.find(OrderLine.class, line), "quantity")).isEqualTo(3);
    }

    @Test
    void embeddedValuesAreBuiltAndAllNullColumnsAreANullValue() {
        Venue venue = em.find(Venue.class, 1L);
        assertThat(venue.spot().street()).isEqualTo("rue de Jérusalem");
        assertThat(venue.spot().geo()).isEqualTo(new Geo(48.85, 2.35));
        assertThat(venue.notes()).isNotNull(); // a field the row does not hold keeps its initial value
        assertThat(em.find(Venue.class, 2L).spot()).isNull();
    }

    @Test
    void aLoadedInstanceChangedIsWrittenAtCommit() throws SQLException {
        em.getTransaction().begin();
        Customer customer = em.find(Customer.class, 1L);
        MappedEntity type = type(Customer.class);
        type.access().set(customer, type.model().attributes().indexOf(type.model().attribute("name").orElseThrow()), "Eugène");
        em.getTransaction().commit();
        try (Statement query = database.createStatement(); var row = query.executeQuery("select name, version from Customer where id = 1")) {
            row.next();
            assertThat(row.getString(1)).isEqualTo("Eugène");
            assertThat(row.getInt(2)).isEqualTo(5);
        }
    }

    @Test
    void getReferenceToAMissingRowIsAnEntityNotFoundException() { // §3.2.8: allowed when getReference is called
        assertThat(em.getReference(Customer.class, 1L)).isSameAs(em.find(Customer.class, 1L));
        assertThatThrownBy(() -> em.getReference(Customer.class, 99L)).isInstanceOf(EntityNotFoundException.class);
    }

}
