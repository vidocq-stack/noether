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
package io.vidocq.mansart.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Emp;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Meeting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Room;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.RoomKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.TypedQuery;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §4, §3.11: Jakarta Persistence queries translated to SQL and their results read as the specification says. */
class JpqlQueryTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;
    private Dept sales;
    private Dept audit;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:jpql-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Dept (id bigint primary key, name varchar(50))");
            ddl.execute("create table Desk (id bigint primary key, location varchar(50))");
            ddl.execute("create table Emp (id bigint primary key, name varchar(50), version int, dept_id bigint references Dept(id), "
                + "MGR bigint references Emp(id), DESK_ID bigint references Desk(id))");
            ddl.execute("create table Room (building varchar(10), number int, seats int, primary key (building, number))");
            ddl.execute("create table Meeting (id bigint primary key, topic varchar(50), BLDG varchar(10) not null, NUM int not null, "
                + "foreign key (BLDG, NUM) references Room (building, number))");
        }
        emf = new PersistenceConfiguration("jpql").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).managedClass(Room.class)
            .managedClass(Meeting.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
        sales = new Dept(1, "Sales");
        audit = new Dept(2, "Audit");
        Emp chief = new Emp(1, "Chief", sales);
        Emp clerk = new Emp(2, "Clerk", sales);
        clerk.manager(chief);
        Emp auditor = new Emp(3, "Auditor", audit);
        auditor.manager(chief);
        Emp loner = new Emp(4, "Loner", null);
        Room room = new Room(new RoomKey("B", 12), 20);
        em.getTransaction().begin();
        List.of(sales, audit, chief, clerk, auditor, loner, room, new Meeting(1, "kick-off", room)).forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private static List<String> names(List<Emp> employees) {
        return employees.stream().map(Emp::name).toList();
    }

    @Test
    void entitiesAreReadAsTheManagedInstancesOfTheirIdentity() { // §4.8, §3.11
        List<Emp> employees = em.createQuery("SELECT e FROM Emp e ORDER BY e.name", Emp.class).getResultList();
        assertThat(names(employees)).containsExactly("Auditor", "Chief", "Clerk", "Loner");
        assertThat(employees.get(2)).isSameAs(em.find(Emp.class, 2L));
        assertThat(employees.get(2).manager()).isSameAs(employees.get(1));
    }

    @Test
    void namedAndPositionalParametersAreBoundThroughTheBindersOfTheirAttributes() { // §4.6.4
        assertThat(em.createQuery("SELECT e FROM Emp e WHERE e.name = :name", Emp.class).setParameter("name", "Clerk")
            .getSingleResult().id()).isEqualTo(2);
        assertThat(em.createQuery("SELECT e FROM Emp e WHERE e.id > ?1 AND e.id < ?2", Emp.class).setParameter(1, 1L)
            .setParameter(2, 4L).getResultList()).hasSize(2);
    }

    @Test
    void scalarAndMultipleResults() { // §4.8: one item is the result, several an Object[]
        assertThat(em.createQuery("SELECT e.name FROM Emp e WHERE e.id = 2", String.class).getSingleResult()).isEqualTo("Clerk");
        List<Object[]> rows = em.createQuery("SELECT e.name, e.dept FROM Emp e WHERE e.id = 2", Object[].class).getResultList();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row[0]).isEqualTo("Clerk");
            assertThat(row[1]).isSameAs(em.find(Dept.class, 1L));
        });
    }

    @Test
    void singleValuedPathsAreJoinedAndJoinsDeclared() { // §4.4.4, §4.4.5
        assertThat(names(em.createQuery("SELECT e FROM Emp e WHERE e.dept.name = 'Sales' ORDER BY e.id", Emp.class).getResultList()))
            .containsExactly("Chief", "Clerk");
        assertThat(em.createQuery("SELECT d.name FROM Emp e JOIN e.dept d WHERE e.manager.name = :boss ORDER BY d.name", String.class)
            .setParameter("boss", "Chief").getResultList()).containsExactly("Audit", "Sales");
        assertThat(em.createQuery("SELECT e.name, m.name FROM Emp e LEFT JOIN e.manager m WHERE e.id IN (1, 4)", Object[].class)
            .getResultList()).allSatisfy(row -> assertThat(row[1]).isNull());
        assertThat(em.createQuery("SELECT e.dept FROM Emp e WHERE e.id = 4").getResultList()).isEmpty(); // navigation is an inner join
    }

    @Test
    void entitiesAreComparedByTheirKeys() { // §4.6.11, composite keys included
        assertThat(names(em.createQuery("SELECT e FROM Emp e WHERE e.dept = :dept ORDER BY e.id", Emp.class).setParameter("dept", sales)
            .getResultList())).containsExactly("Chief", "Clerk");
        assertThat(names(em.createQuery("SELECT e FROM Emp e WHERE e.dept IS NULL", Emp.class).getResultList())).containsExactly("Loner");
        Room room = em.find(Room.class, new RoomKey("B", 12));
        assertThat(em.createQuery("SELECT m.topic FROM Meeting m WHERE m.room = :room", String.class).setParameter("room", room)
            .getSingleResult()).isEqualTo("kick-off");
        assertThat(em.createQuery("SELECT m FROM Meeting m JOIN m.room r WHERE r.seats > 10", Meeting.class).getSingleResult().room())
            .isSameAs(room);
    }

    @Test
    void thePredicatesOfTheLanguage() { // §4.6.8 to §4.6.11
        assertThat(names(em.createQuery("SELECT e FROM Emp e WHERE e.id BETWEEN 2 AND 3 AND e.name LIKE 'C%'", Emp.class)
            .getResultList())).containsExactly("Clerk");
        assertThat(names(em.createQuery("SELECT e FROM Emp e WHERE e.id IN :ids ORDER BY e.id", Emp.class)
            .setParameter("ids", List.of(1L, 3L)).getResultList())).containsExactly("Chief", "Auditor");
        assertThat(names(em.createQuery("SELECT e FROM Emp e WHERE NOT (e.id < 4 OR e.name = 'Chief')", Emp.class).getResultList()))
            .containsExactly("Loner");
    }

    @Test
    void aggregatesHaveTheTypesOfTheSpecification() { // §4.8.5: COUNT is a Long, MAX the type of its argument
        assertThat(em.createQuery("SELECT COUNT(e) FROM Emp e", Long.class).getSingleResult()).isEqualTo(4L);
        assertThat(em.createQuery("SELECT MAX(e.id) FROM Emp e WHERE e.dept IS NOT NULL").getSingleResult()).isEqualTo(3L);
        assertThat(em.createQuery("SELECT e.dept.name, COUNT(e) FROM Emp e GROUP BY e.dept.name HAVING COUNT(e) > 1",
            Object[].class).getSingleResult()).containsExactly("Sales", 2L);
    }

    @Test
    void pagingAndStreams() { // §3.11.5
        TypedQuery<Emp> query = em.createQuery("SELECT e FROM Emp e ORDER BY e.id", Emp.class).setFirstResult(1).setMaxResults(2);
        assertThat(names(query.getResultList())).containsExactly("Clerk", "Auditor");
        assertThat(query.getResultStream().map(Emp::name).toList()).containsExactly("Clerk", "Auditor");
    }

    @Test
    void singleResults() { // §3.11: NoResultException, NonUniqueResultException
        assertThatThrownBy(() -> em.createQuery("SELECT e FROM Emp e WHERE e.id = 99").getSingleResult())
            .isInstanceOf(NoResultException.class);
        assertThatThrownBy(() -> em.createQuery("SELECT e FROM Emp e").getSingleResult()).isInstanceOf(NonUniqueResultException.class);
        assertThat(em.createQuery("SELECT e FROM Emp e WHERE e.id = 99").getSingleResultOrNull()).isNull();
    }

    @Test
    void theContextIsFlushedBeforeAQueryInATransaction() { // §3.10.8 FlushModeType.AUTO
        em.getTransaction().begin();
        em.persist(new Emp(5, "Newcomer", null));
        em.find(Emp.class, 2L).dept(audit);
        assertThat(em.createQuery("SELECT COUNT(e) FROM Emp e WHERE e.dept.name = 'Audit'", Long.class).getSingleResult()).isEqualTo(2L);
        assertThat(em.createQuery("SELECT e.name FROM Emp e WHERE e.id = 5", String.class).getSingleResult()).isEqualTo("Newcomer");
        em.getTransaction().rollback();
    }

    @Test
    void aQueryOfAClosedEntityManagerRefusesEverything() { // §3.11, PERSISTENCE:SPEC:608
        var query = em.createQuery("SELECT e FROM Emp e WHERE e.name = :n");
        var nativeQuery = em.createNativeQuery("DELETE FROM Emp");
        em.close();
        assertThatThrownBy(query::getFirstResult).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> query.setParameter("n", "x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(query::getResultList).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(nativeQuery::getMaxResults).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mistakesAreFoundWhenTheQueryIsCreated() { // §3.11: IllegalArgumentException from createQuery
        assertThatThrownBy(() -> em.createQuery("SELECT x FROM Nothing x")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Nothing");
        assertThatThrownBy(() -> em.createQuery("SELECT e.nickname FROM Emp e")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nickname");
        assertThatThrownBy(() -> em.createQuery("SELECT e.name FROM Emp e", Integer.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> em.createQuery("SELECT e FROM Emp e WHERE e.name = :n").setParameter("other", "x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> em.createQuery("SELECT e FROM Emp e WHERE e.name = :n").getResultList())
            .isInstanceOf(IllegalStateException.class); // §3.11: a parameter not bound
    }
}
