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
import static org.assertj.core.api.Assertions.entry;

import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Sighting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Trait;
import io.vidocq.mansart.jpa.core.model.build.fixtures.idx.Affair;
import io.vidocq.mansart.jpa.core.model.build.fixtures.idx.Agent;
import io.vidocq.mansart.jpa.core.model.build.fixtures.idx.Bureau;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §2.7, §11.1.29-§11.1.42: maps and ordered lists, their keys and positions in a join, collection or target table. */
class IndexedCollectionTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:indexed-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Bureau (id bigint primary key, name varchar(50), version int)");
            // the indexes of the bureau's inverse relationships live in the agents' table: §11.1.33, §11.1.42 roster_ORDER
            ddl.execute("create table Agent (id bigint primary key, name varchar(50), bureau_id bigint references Bureau(id), "
                + "DESK varchar(10), roster_ORDER int)");
            ddl.execute("create table Affair (id bigint primary key)");
            ddl.execute("create table Affair_Agent (Affair_id bigint not null references Affair(id), "
                + "team_id bigint not null references Agent(id), team_ORDER int)");
            ddl.execute("create table AFFAIR_CONTACTS (Affair_id bigint not null references Affair(id), "
                + "contacts_id bigint not null references Agent(id), BUREAU bigint not null references Bureau(id))");
            ddl.execute("create table Affair_clues (Affair_id bigint not null references Affair(id), clues varchar(50), clues_ORDER int)");
            ddl.execute("create table Affair_sightings (Affair_id bigint not null references Affair(id), PLACE varchar(20), "
                + "city varchar(50), seen date)");
            ddl.execute("create table Affair_notes (Affair_id bigint not null references Affair(id), notes_KEY varchar(20), "
                + "notes varchar(50))");
        }
        emf = new PersistenceConfiguration("indexed").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Bureau.class).managedClass(Agent.class).managedClass(Affair.class).managedClass(Sighting.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
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

    private Bureau bureau() {
        Bureau bureau = new Bureau(1, "Sûreté");
        Agent vidocq = bureau.hire(new Agent(11, "Vidocq"), "D1");
        Agent coco = bureau.hire(new Agent(12, "Coco"), "D2");
        inTransaction(() -> {
            em.persist(bureau);
            em.persist(vidocq);
            em.persist(coco);
        });
        return bureau;
    }

    @Test
    void aMapKeyedByAnAttributeOfItsElementsNeedsNoColumn() { // §11.1.31 @MapKey
        bureau();
        em.clear();
        Bureau loaded = em.find(Bureau.class, 1L);
        assertThat(loaded.byName()).containsOnlyKeys("Vidocq", "Coco");
        assertThat(loaded.byName().get("Coco").name()).isEqualTo("Coco");
    }

    @Test
    void theInverseSideWritesTheKeysAndPositionsItsTargetTableHolds() throws SQLException { // §11.1.33, §11.1.42
        Bureau bureau = bureau();
        assertThat(scalar("select DESK from Agent where id = 12")).isEqualTo("D2");
        assertThat(scalar("select roster_ORDER from Agent where id = 12")).isEqualTo("1");
        assertThat(bureau.version()).isZero(); // still the inverse side: the owner is not versioned by it
        em.clear();
        Bureau loaded = em.find(Bureau.class, 1L);
        assertThat(loaded.byDesk()).containsOnlyKeys("D1", "D2");
        assertThat(loaded.roster()).extracting(Agent::name).containsExactly("Vidocq", "Coco");
    }

    @Test
    void aReorderedListWritesItsPositionsAgain() throws SQLException { // §11.1.42
        Bureau bureau = bureau();
        inTransaction(() -> bureau.roster().addFirst(bureau.roster().removeLast()));
        assertThat(scalar("select roster_ORDER from Agent where id = 12")).isEqualTo("0");
        em.clear();
        assertThat(em.find(Bureau.class, 1L).roster()).extracting(Agent::name).containsExactly("Coco", "Vidocq");
    }

    @Test
    void anOrderedManyToManyKeepsItsPositionsInItsJoinTable() throws SQLException {
        Bureau bureau = bureau();
        Affair affair = new Affair(1);
        affair.team().add(bureau.roster().get(1));
        affair.team().add(bureau.roster().get(0));
        inTransaction(() -> em.persist(affair));
        assertThat(scalar("select team_ORDER from Affair_Agent where team_id = 12")).isEqualTo("0");
        inTransaction(() -> affair.team().add(affair.team().removeFirst())); // only the order changes
        assertThat(scalar("select team_ORDER from Affair_Agent where team_id = 12")).isEqualTo("1");
        em.clear();
        assertThat(em.find(Affair.class, 1L).team()).extracting(Agent::name).containsExactly("Vidocq", "Coco");
    }

    @Test
    void aManyToManyKeyedByAnEntityKeepsItsKeyInItsJoinTable() throws SQLException { // §11.1.35 @MapKeyJoinColumn
        Bureau bureau = bureau();
        Affair affair = new Affair(1);
        affair.contacts().put(bureau, bureau.roster().get(1));
        inTransaction(() -> em.persist(affair));
        assertThat(scalar("select BUREAU from AFFAIR_CONTACTS where contacts_id = 12")).isEqualTo("1");
        em.clear();
        Affair loaded = em.find(Affair.class, 1L);
        Bureau key = em.find(Bureau.class, 1L);
        assertThat(loaded.contacts()).containsOnlyKeys(key);
        assertThat(loaded.contacts().get(key).name()).isEqualTo("Coco");
    }

    @Test
    void elementCollectionsKeepTheirPositionsAndKeys() throws SQLException { // §2.7: keys by default in <attribute>_KEY
        Affair affair = new Affair(1);
        affair.clues().add("a hat");
        affair.clues().add("a cane");
        affair.sightings().put("market", new Sighting("Arras", LocalDate.of(1796, 3, 1)));
        affair.notes().put(Trait.CUNNING, "very");
        inTransaction(() -> em.persist(affair));
        assertThat(scalar("select clues_ORDER from Affair_clues where clues = 'a cane'")).isEqualTo("1");
        assertThat(scalar("select notes_KEY from Affair_notes")).isEqualTo("CUNNING");
        em.clear();
        Affair loaded = em.find(Affair.class, 1L);
        assertThat(loaded.clues()).containsExactly("a hat", "a cane");
        assertThat(loaded.sightings().get("market").city()).isEqualTo("Arras");
        assertThat(loaded.notes()).containsExactly(entry(Trait.CUNNING, "very"));
    }

    @Test
    void aMergedMapHoldsTheManagedInstancesAndCopiedValues() { // §3.2.7.1, without cascade
        bureau();
        inTransaction(() -> em.persist(new Affair(1)));
        em.clear();
        Affair detached = new Affair(1);
        detached.contacts().put(new Bureau(1, "a detached copy"), new Agent(12, "a detached copy"));
        Sighting sighting = new Sighting("Rouen", LocalDate.of(1800, 1, 1));
        detached.sightings().put("port", sighting);
        em.getTransaction().begin();
        Affair managed = em.merge(detached);
        assertThat(managed.contacts()).containsOnlyKeys(em.find(Bureau.class, 1L));
        assertThat(managed.contacts().values()).singleElement().isSameAs(em.find(Agent.class, 12L));
        assertThat(managed.sightings()).isNotSameAs(detached.sightings());
        assertThat(managed.sightings().get("port")).isNotSameAs(sighting).extracting(Sighting::city).isEqualTo("Rouen");
        em.getTransaction().commit();
    }

    @Test
    void aChangedKeyOfAnElementCollectionIsWritten() throws SQLException {
        Affair affair = new Affair(1);
        affair.notes().put(Trait.CUNNING, "very");
        inTransaction(() -> em.persist(affair));
        inTransaction(() -> affair.notes().put(Trait.PATIENT, affair.notes().remove(Trait.CUNNING)));
        assertThat(scalar("select notes_KEY from Affair_notes")).isEqualTo("PATIENT");
    }
}
