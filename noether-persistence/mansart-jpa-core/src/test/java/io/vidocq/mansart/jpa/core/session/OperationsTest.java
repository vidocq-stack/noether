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

import io.vidocq.mansart.jpa.core.model.build.fixtures.Crew;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Sailor;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.RollbackException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.2: persist, remove, merge, refresh and detach, with their cascades (§3.2.2 to §3.2.6). */
class OperationsTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:operations-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            // relationship columns come with P5: the rows hold the basic attributes only
            ddl.execute("create table Crew (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Sailor (id bigint primary key, name varchar(50))");
        }
        emf = new PersistenceConfiguration("operations").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Crew.class).managedClass(Sailor.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private long count(String table) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet rows = query.executeQuery("select count(*) from " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void insertCrew(long id, String name, int version) throws SQLException {
        try (Statement insert = database.createStatement()) {
            insert.executeUpdate("insert into Crew values (" + id + ", '" + name + "', " + version + ")");
        }
    }

    private void inTransaction(Runnable work) {
        em.getTransaction().begin();
        work.run();
        em.getTransaction().commit();
    }

    // ---- persist (§3.2.2) ---------------------------------------------------------------------------------

    @Test
    void aNewInstanceBecomesManagedAndItsGraphFollowsTheCascade() throws SQLException {
        Crew crew = new Crew(1, "Brigade");
        new Sailor(10, "Coco", crew);
        new Sailor(11, "Lacenaire", crew);
        inTransaction(() -> {
            em.persist(crew);
            assertThat(em.contains(crew)).isTrue();
            assertThat(em.contains(crew.sailors().get(1))).isTrue(); // CascadeType.ALL
        });
        assertThat(count("Crew")).isEqualTo(1);
        assertThat(count("Sailor")).isEqualTo(2);
    }

    @Test
    void persistingAManagedInstanceIgnoresItAndARemovedOneBecomesManagedAgain() throws SQLException {
        Crew crew = new Crew(1, "Brigade");
        inTransaction(() -> {
            em.persist(crew);
            em.persist(crew);
            em.remove(crew);
            em.persist(crew);
        });
        assertThat(count("Crew")).isEqualTo(1);
    }

    @Test
    void persistingTheIdentityOfAnExistingRowFailsAtTheLatestAtCommit() throws SQLException {
        insertCrew(1, "Brigade", 0);
        em.getTransaction().begin();
        em.persist(new Crew(1, "Other"));
        assertThatThrownBy(() -> em.getTransaction().commit()).isInstanceOf(RollbackException.class)
            .hasCauseInstanceOf(EntityExistsException.class);
    }

    @Test
    void persistingSomethingThatIsNotAnEntityIsRefused() {
        assertThatThrownBy(() -> em.persist("not an entity")).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- remove (§3.2.3) ----------------------------------------------------------------------------------

    @Test
    void aManagedInstanceIsDeletedAndTheRemoveCascades() throws SQLException {
        Crew crew = new Crew(1, "Brigade");
        new Sailor(10, "Coco", crew);
        inTransaction(() -> em.persist(crew));
        inTransaction(() -> {
            em.remove(crew);
            assertThat(em.contains(crew)).isFalse();
        });
        assertThat(count("Crew")).isZero();
        assertThat(count("Sailor")).isZero();
    }

    @Test
    void aNewInstanceIsIgnoredAndADetachedOneRefused() throws SQLException {
        insertCrew(1, "Brigade", 0);
        em.getTransaction().begin();
        em.remove(new Crew(2, "never stored")); // new: ignored
        assertThatThrownBy(() -> em.remove(new Crew(1, "detached"))).isInstanceOf(IllegalArgumentException.class);
        em.getTransaction().rollback();
    }

    // ---- merge (§3.2.7.1) ---------------------------------------------------------------------------------

    @Test
    void aDetachedInstanceIsCopiedOntoTheManagedOne() throws SQLException {
        insertCrew(1, "Brigade", 0);
        Crew detached = em.find(Crew.class, 1L);
        em.clear();
        detached.name("Sûreté");
        em.getTransaction().begin();
        Crew managed = em.merge(detached);
        assertThat(managed).isNotSameAs(detached);
        assertThat(managed.name()).isEqualTo("Sûreté");
        assertThat(em.contains(managed)).isTrue();
        assertThat(em.contains(detached)).isFalse();
        em.getTransaction().commit();
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("select name from Crew")) {
            row.next();
            assertThat(row.getString(1)).isEqualTo("Sûreté");
        }
    }

    @Test
    void aNewInstanceIsMergedAsANewManagedCopy() throws SQLException {
        Crew crew = new Crew(1, "Brigade");
        inTransaction(() -> {
            Crew managed = em.merge(crew);
            assertThat(managed).isNotSameAs(crew);
            assertThat(em.contains(managed)).isTrue();
        });
        assertThat(count("Crew")).isEqualTo(1);
    }

    @Test
    void aManagedInstanceMergesIntoItselfAndARemovedOneIsRefused() {
        Crew crew = new Crew(1, "Brigade");
        em.getTransaction().begin();
        em.persist(crew);
        assertThat(em.merge(crew)).isSameAs(crew);
        em.remove(crew);
        assertThatThrownBy(() -> em.merge(crew)).isInstanceOf(IllegalArgumentException.class);
        em.getTransaction().rollback();
    }

    @Test
    void aStaleDetachedInstanceIsAnOptimisticLockFailure() throws SQLException { // §3.4.2
        insertCrew(1, "Brigade", 0);
        Crew stale = em.find(Crew.class, 1L);
        em.clear();
        try (Statement other = database.createStatement()) {
            other.executeUpdate("update Crew set version = 3 where id = 1");
        }
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.merge(stale)).isInstanceOf(OptimisticLockException.class);
    }

    // ---- refresh (§3.2.5) and detach (§3.2.6) -------------------------------------------------------------

    @Test
    void refreshOverwritesTheStateWithTheRow() throws SQLException {
        insertCrew(1, "Brigade", 0);
        Crew crew = em.find(Crew.class, 1L);
        crew.name("changed in memory");
        em.refresh(crew);
        assertThat(crew.name()).isEqualTo("Brigade");
        try (Statement other = database.createStatement()) {
            other.executeUpdate("delete from Crew");
        }
        assertThatThrownBy(() -> em.refresh(crew)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void onlyAManagedInstanceIsRefreshed() {
        assertThatThrownBy(() -> em.refresh(new Crew(1, "Brigade"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDetachedInstanceIsNoLongerWritten() throws SQLException {
        Crew crew = new Crew(1, "Brigade");
        new Sailor(10, "Coco", crew);
        inTransaction(() -> {
            em.persist(crew);
            em.detach(crew); // cascades to the sailor: nothing is written
            assertThat(em.contains(crew.sailors().getFirst())).isFalse();
        });
        assertThat(count("Crew")).isZero();
        assertThat(count("Sailor")).isZero();
    }
}
