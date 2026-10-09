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

import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Emp;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Meeting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Room;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.RoomKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §2.10 single-valued relationships: foreign key columns written and read back, the graph loaded at once. */
class ToOneTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:to-one-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
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
        emf = new PersistenceConfiguration("to-one").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).managedClass(Room.class)
            .managedClass(Meeting.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private String scalar(String sql) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery(sql)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    private void inTransaction(Runnable work) {
        em.getTransaction().begin();
        work.run();
        em.getTransaction().commit();
    }

    @Test
    void theForeignKeyIsWrittenAndTheTargetInsertedFirst() throws SQLException { // §2.10.3.1: dept_id by default
        Dept sales = new Dept(1, "Sales");
        inTransaction(() -> {
            em.persist(new Emp(10, "Vidocq", sales)); // persisted before its department: the inserts are ordered
            em.persist(sales);
        });
        assertThat(scalar("select dept_id from Emp where id = 10")).isEqualTo("1");
    }

    @Test
    void aReferenceToAnInstanceInsertedLaterIsFixedAfterTheInserts() throws SQLException { // same entity, either order
        Emp chief = new Emp(1, "Chief", null);
        Emp clerk = new Emp(2, "Clerk", null);
        clerk.manager(chief);
        chief.manager(clerk); // a cycle: one of the two keys is written by an update
        inTransaction(() -> {
            em.persist(clerk);
            em.persist(chief);
        });
        assertThat(scalar("select MGR from Emp where id = 2")).isEqualTo("1");
        assertThat(scalar("select MGR from Emp where id = 1")).isEqualTo("2");
    }

    @Test
    void instancesReferencingEachOtherAreRemovedTogether() throws SQLException { // the foreign keys are cleared first
        Emp chief = new Emp(1, "Chief", null);
        Emp clerk = new Emp(2, "Clerk", null);
        clerk.manager(chief);
        chief.manager(clerk);
        inTransaction(() -> {
            em.persist(chief);
            em.persist(clerk);
        });
        inTransaction(() -> {
            em.remove(chief);
            em.remove(clerk);
        });
        assertThat(scalar("select count(*) from Emp")).isEqualTo("0");
    }

    @Test
    void theTargetIsLoadedWithItsOwnerOncePerIdentity() { // FetchType.LAZY is a hint: the target is loaded at once
        Dept sales = new Dept(1, "Sales");
        Emp chief = new Emp(1, "Chief", sales);
        Emp clerk = new Emp(2, "Clerk", sales);
        clerk.manager(chief);
        chief.manager(clerk);
        inTransaction(() -> {
            em.persist(sales);
            em.persist(chief);
            em.persist(clerk);
        });
        em.clear();
        Emp loaded = em.find(Emp.class, 2L);
        assertThat(loaded.dept().name()).isEqualTo("Sales");
        assertThat(loaded.manager().name()).isEqualTo("Chief");
        assertThat(loaded.manager().manager()).isSameAs(loaded); // the cycle closes on the managed instance
        assertThat(loaded.manager().dept()).isSameAs(loaded.dept());
        assertThat(em.contains(loaded.dept())).isTrue();
    }

    @Test
    void aChangedOrClearedReferenceIsWritten() throws SQLException {
        Dept sales = new Dept(1, "Sales");
        Dept audit = new Dept(2, "Audit");
        Emp clerk = new Emp(2, "Clerk", sales);
        inTransaction(() -> {
            em.persist(sales);
            em.persist(audit);
            em.persist(clerk);
        });
        inTransaction(() -> clerk.dept(audit));
        assertThat(scalar("select dept_id from Emp where id = 2")).isEqualTo("2");
        assertThat(scalar("select version from Emp where id = 2")).isEqualTo("1"); // an owned relationship is versioned
        inTransaction(() -> clerk.dept(null));
        assertThat(scalar("select dept_id from Emp where id = 2")).isNull();
    }

    @Test
    void aRefreshReadsTheReferenceTheRowHoldsNow() throws SQLException { // §3.2.5
        Dept sales = new Dept(1, "Sales");
        Emp clerk = new Emp(2, "Clerk", sales);
        inTransaction(() -> {
            em.persist(sales);
            em.persist(new Dept(2, "Audit"));
            em.persist(clerk);
        });
        try (Statement update = database.createStatement()) {
            update.executeUpdate("update Emp set dept_id = 2 where id = 2");
        }
        em.refresh(clerk);
        assertThat(clerk.dept()).isSameAs(em.find(Dept.class, 2L));
    }

    @Test
    void theInverseSideOfAOneToOneIsLoadedFromTheOwnersKey() { // §2.10.1: mappedBy
        Desk desk = new Desk(7, "window");
        Emp clerk = new Emp(2, "Clerk", null);
        clerk.desk(desk);
        inTransaction(() -> em.persist(clerk)); // the desk by cascade
        em.clear();
        Desk loaded = em.find(Desk.class, 7L);
        assertThat(loaded.emp().name()).isEqualTo("Clerk");
        assertThat(loaded.emp().desk()).isSameAs(loaded);
    }

    @Test
    void anOrphanIsRemovedWhenItsOwnerLetsItGoOrIsRemoved() throws SQLException { // §2.9, §3.2.3
        Emp clerk = new Emp(2, "Clerk", null);
        clerk.desk(new Desk(7, "window"));
        inTransaction(() -> em.persist(clerk));
        inTransaction(() -> clerk.desk(null));
        assertThat(scalar("select count(*) from Desk")).isEqualTo("0");
        Emp other = new Emp(3, "Other", null);
        other.desk(new Desk(8, "door"));
        inTransaction(() -> em.persist(other));
        inTransaction(() -> em.remove(other)); // orphanRemoval cascades the remove
        assertThat(scalar("select count(*) from Desk")).isEqualTo("0");
    }

    @Test
    void aForeignKeyOfSeveralColumnsFollowsTheTargetKey() throws SQLException { // §11.1.25 referencedColumnName
        Room room = new Room(new RoomKey("B", 12), 20);
        inTransaction(() -> {
            em.persist(new Meeting(1, "kick-off", room));
            em.persist(room);
        });
        assertThat(scalar("select BLDG || '-' || NUM from Meeting")).isEqualTo("B-12");
        em.clear();
        assertThat(em.find(Meeting.class, 1L).room().seats()).isEqualTo(20);
    }

    @Test
    void aMergedInstanceReferencesTheManagedTargetOfTheSameIdentity() { // §3.2.7.1, without cascade
        Dept sales = new Dept(1, "Sales");
        inTransaction(() -> {
            em.persist(sales);
            em.persist(new Emp(2, "Clerk", sales));
        });
        em.clear();
        Emp detached = new Emp(2, "Clerk again", new Dept(1, "a detached copy"));
        em.getTransaction().begin();
        Emp managed = em.merge(detached);
        assertThat(managed.dept()).isSameAs(em.find(Dept.class, 1L));
        assertThat(managed.dept().name()).isEqualTo("Sales");
        em.getTransaction().commit();
    }
}
