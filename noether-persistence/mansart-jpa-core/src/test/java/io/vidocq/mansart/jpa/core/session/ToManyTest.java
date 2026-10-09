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

import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Club;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Coach;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Player;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Skill;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Team;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §2.10 collection-valued relationships: the foreign keys of the elements, join tables, loading, changes, orphans. */
class ToManyTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:to-many-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Team (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Player (id bigint primary key, name varchar(50), team_id bigint references Team(id))");
            ddl.execute("create table Skill (id bigint primary key, label varchar(50))");
            ddl.execute("create table Coach (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Club (id bigint primary key)");
            // §11.1.27 defaults: the tables of both sides, owner first; §11.1.25: the inverse attribute, or the entity
            ddl.execute("create table Player_Skill (players_id bigint not null references Player(id), "
                + "skills_id bigint not null references Skill(id), primary key (players_id, skills_id))");
            ddl.execute("create table Coach_Player (Coach_id bigint not null references Coach(id), "
                + "trainees_id bigint not null references Player(id), primary key (Coach_id, trainees_id))");
            ddl.execute("create table CLUB_SKILLS (CLUB bigint not null references Club(id), SKILL bigint not null references Skill(id))");
        }
        emf = new PersistenceConfiguration("to-many").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Team.class).managedClass(Player.class).managedClass(Skill.class).managedClass(Coach.class)
            .managedClass(Club.class).property(PersistenceConfiguration.JDBC_URL, url)
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

    private static List<String> names(Iterable<Player> players) {
        List<String> names = new ArrayList<>();
        players.forEach(p -> names.add(p.name()));
        return names;
    }

    @Test
    void aCollectionOfAClassOutsideTheUnitFailsTheFactory() { // §2.10: the target of a relationship is an entity
        PersistenceConfiguration incomplete = new PersistenceConfiguration("incomplete")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider").managedClass(Team.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:incomplete").property(PersistenceConfiguration.JDBC_USER, "sa");
        assertThatThrownBy(incomplete::createEntityManagerFactory).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("players").hasMessageContaining(Player.class.getName());
    }

    @Test
    void aOneToManyIsLoadedFromTheForeignKeysOfItsElementsInItsOrder() { // §2.10.2, §11.1.42 @OrderBy
        Team team = new Team(1, "Brigade");
        team.sign(new Player(11, "Vidocq"));
        team.sign(new Player(12, "Coco"));
        inTransaction(() -> em.persist(team)); // the players by cascade
        em.clear();
        Team loaded = em.find(Team.class, 1L);
        assertThat(names(loaded.players())).containsExactly("Coco", "Vidocq");
        assertThat(loaded.players()).allSatisfy(p -> assertThat(p.team()).isSameAs(loaded));
    }

    @Test
    void theInverseSideDoesNotVersionItsOwner() throws SQLException { // §3.4.2: only owned relationships are versioned
        Team team = new Team(1, "Brigade");
        inTransaction(() -> em.persist(team));
        inTransaction(() -> team.sign(new Player(11, "Vidocq")));
        assertThat(scalar("select team_id from Player where id = 11")).isEqualTo("1");
        assertThat(scalar("select version from Team")).isEqualTo("0");
    }

    @Test
    void anElementLeftOutOfACollectionWithOrphanRemovalIsRemoved() throws SQLException { // §2.9
        Team team = new Team(1, "Brigade");
        Player coco = team.sign(new Player(11, "Coco"));
        team.sign(new Player(12, "Vidocq"));
        inTransaction(() -> em.persist(team));
        inTransaction(() -> team.players().remove(coco));
        assertThat(scalar("select count(*) from Player")).isEqualTo("1");
        assertThat(em.contains(coco)).isFalse();
    }

    @Test
    void aUnidirectionalOneToManyWritesAndReadsItsJoinTable() throws SQLException { // §2.10.5.1
        Player coco = new Player(11, "Coco");
        Player vidocq = new Player(12, "Vidocq");
        Coach coach = new Coach(1, "Chief");
        coach.trainees().add(coco);
        coach.trainees().add(vidocq);
        inTransaction(() -> {
            em.persist(coach); // its trainees are persisted after it: the join rows wait for both
            em.persist(coco);
            em.persist(vidocq);
        });
        assertThat(scalar("select count(*) from Coach_Player where Coach_id = 1")).isEqualTo("2");
        em.clear();
        assertThat(names(em.find(Coach.class, 1L).trainees())).containsExactlyInAnyOrder("Coco", "Vidocq");
    }

    @Test
    void theChangesOfAnOwnedCollectionAreWrittenAndVersioned() throws SQLException { // §3.4.2
        Player coco = new Player(11, "Coco");
        Player vidocq = new Player(12, "Vidocq");
        Player flambard = new Player(13, "Flambard");
        Coach coach = new Coach(1, "Chief");
        coach.trainees().add(coco);
        coach.trainees().add(vidocq);
        inTransaction(() -> {
            em.persist(coco);
            em.persist(vidocq);
            em.persist(flambard);
            em.persist(coach);
        });
        inTransaction(() -> {
            coach.trainees().remove(coco);
            coach.trainees().add(flambard);
        });
        assertThat(scalar("select listagg(trainees_id, ',') within group (order by trainees_id) from Coach_Player"))
            .isEqualTo("12,13");
        assertThat(coach.version()).isEqualTo(1);
        assertThat(scalar("select count(*) from Player")).isEqualTo("3"); // no orphan removal there
    }

    @Test
    void removingTheOwnerDeletesItsJoinRowsFirst() throws SQLException {
        Player coco = new Player(11, "Coco");
        Coach coach = new Coach(1, "Chief");
        coach.trainees().add(coco);
        inTransaction(() -> {
            em.persist(coco);
            em.persist(coach);
        });
        inTransaction(() -> em.remove(coach));
        assertThat(scalar("select count(*) from Coach_Player")).isEqualTo("0");
        assertThat(scalar("select count(*) from Player")).isEqualTo("1");
    }

    @Test
    void aManyToManyIsReadFromBothSides() throws SQLException { // §2.10.4
        Skill disguise = new Skill(1, "disguise");
        Skill tracking = new Skill(2, "tracking");
        Player vidocq = new Player(11, "Vidocq");
        vidocq.skills().add(disguise);
        vidocq.skills().add(tracking);
        inTransaction(() -> {
            em.persist(disguise);
            em.persist(tracking);
            em.persist(vidocq);
        });
        assertThat(scalar("select count(*) from Player_Skill where players_id = 11")).isEqualTo("2");
        em.clear();
        Skill loaded = em.find(Skill.class, 1L);
        assertThat(names(loaded.players())).containsExactly("Vidocq");
        assertThat(loaded.players().iterator().next().skills()).extracting(Skill::label)
            .containsExactlyInAnyOrder("disguise", "tracking");
        assertThat(loaded.players().iterator().next().skills()).contains(loaded);
    }

    @Test
    void aJoinTableIsNamedAsWritten() throws SQLException { // §11.1.27
        Skill disguise = new Skill(1, "disguise");
        Club club = new Club(1);
        club.taught().add(disguise);
        inTransaction(() -> {
            em.persist(disguise);
            em.persist(club);
        });
        assertThat(scalar("select SKILL from CLUB_SKILLS where CLUB = 1")).isEqualTo("1");
        em.clear();
        assertThat(em.find(Club.class, 1L).taught()).extracting(Skill::label).containsExactly("disguise");
    }

    @Test
    void aCollectionSetOnAManagedInstanceIsWrittenAtCommit() throws SQLException { // the shape of the TCK's uni1XMTest1
        Player coco = new Player(11, "Coco");
        Coach coach = new Coach(1, "Chief");
        inTransaction(() -> {
            em.persist(coco);
            em.persist(coach);
            Vector<Player> trainees = new Vector<>();
            trainees.add(coco);
            coach.trainees(trainees);
            em.merge(coach);
        });
        assertThat(scalar("select trainees_id from Coach_Player where Coach_id = 1")).isEqualTo("11");
    }

    @Test
    void aMergedCollectionHoldsTheManagedInstances() { // §3.2.7.1, without cascade
        Player coco = new Player(11, "Coco");
        inTransaction(() -> {
            em.persist(coco);
            em.persist(new Coach(1, "Chief"));
        });
        em.clear();
        Coach detached = new Coach(1, "Chief");
        detached.trainees().add(new Player(11, "a detached copy"));
        em.getTransaction().begin();
        Coach managed = em.merge(detached);
        assertThat(managed.trainees()).singleElement().isSameAs(em.find(Player.class, 11L));
        assertThat(managed.trainees()).isNotSameAs(detached.trainees());
        em.getTransaction().commit();
    }
}
