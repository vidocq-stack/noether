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

import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Sighting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Suspect;
import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Trait;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Club;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Coach;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Player;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Skill;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Team;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §4.6: the expressions of the query language — functions, cases, subqueries, collections, literals. */
class JpqlExpressionTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;
    private Skill tracking;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:jpql-expressions-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Team (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Player (id bigint primary key, name varchar(50), team_id bigint references Team(id))");
            ddl.execute("create table Skill (id bigint primary key, label varchar(50))");
            ddl.execute("create table Coach (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Club (id bigint primary key)");
            ddl.execute("create table Player_Skill (players_id bigint, skills_id bigint)");
            ddl.execute("create table Coach_Player (Coach_id bigint, trainees_id bigint)");
            ddl.execute("create table CLUB_SKILLS (CLUB bigint, SKILL bigint)");
            ddl.execute("create table Suspect (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Suspect_aliases (Suspect_id bigint, ALIAS varchar(50))");
            ddl.execute("create table SUSPECT_SIGHTINGS (SUSPECT bigint, TOWN varchar(50), seen date)");
            ddl.execute("create table Suspect_traits (Suspect_id bigint, traits varchar(20))");
        }
        emf = new PersistenceConfiguration("jpql-expressions").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Team.class).managedClass(Player.class).managedClass(Skill.class).managedClass(Coach.class)
            .managedClass(Club.class).managedClass(Suspect.class).managedClass(Sighting.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
        Team brigade = new Team(1, "Brigade");
        Team surete = new Team(2, "Sûreté");
        Player vidocq = brigade.sign(new Player(11, "Vidocq"));
        Player coco = brigade.sign(new Player(12, "Coco"));
        Skill disguise = new Skill(1, "disguise");
        tracking = new Skill(2, "tracking");
        vidocq.skills().add(disguise);
        vidocq.skills().add(tracking);
        coco.skills().add(tracking);
        Coach coach = new Coach(1, "Chief");
        coach.trainees().add(coco);
        Suspect suspect = new Suspect(1, "Vidocq");
        suspect.aliases().add("Jules");
        suspect.aliases().add("Eugène");
        suspect.traits().add(Trait.CUNNING);
        suspect.sightings().add(new Sighting("Arras", LocalDate.of(1796, 3, 1)));
        em.getTransaction().begin();
        List.of(brigade, surete, disguise, tracking, coach, suspect).forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private <T> List<T> list(String jpql, Class<T> type) {
        return em.createQuery(jpql, type).getResultList();
    }

    @Test
    void stringFunctions() { // §4.6.17.2.1
        assertThat(em.createQuery("SELECT UPPER(p.name), LOWER(p.name), LENGTH(p.name), CONCAT(p.name, '!', 'x'), "
            + "SUBSTRING(p.name, 2, 3), TRIM(BOTH 'V' FROM p.name), LOCATE('cq', p.name), LEFT(p.name, 2), "
            + "REPLACE(p.name, 'cq', 'k') FROM Player p WHERE p.id = 11", Object[].class).getSingleResult())
            .containsExactly("VIDOCQ", "vidocq", 6, "Vidocq!x", "ido", "idocq", 5, "Vi", "Vidok");
        assertThat(list("SELECT p.name FROM Player p WHERE LOCATE('c', p.name, 3) = 3", String.class)).containsExactly("Coco");
    }

    @Test
    void arithmeticFunctions() { // §4.6.17.2.2
        assertThat(em.createQuery("SELECT MOD(p.id, 5), ABS(p.id - 20), SQRT(p.id * 0 + 16) FROM Player p WHERE p.id = 12",
            Object[].class).getSingleResult()).containsExactly(2, 8L, 4.0);
    }

    @Test
    void casesCoalesceAndNullif() { // §4.6.17.4
        assertThat(list("SELECT CASE WHEN p.id > 11 THEN 'young' ELSE 'old' END FROM Player p ORDER BY p.id", String.class))
            .containsExactly("old", "young");
        assertThat(list("SELECT CASE p.name WHEN 'Coco' THEN 1 ELSE 0 END FROM Player p ORDER BY p.id", Integer.class))
            .containsExactly(0, 1);
        assertThat(list("SELECT COALESCE(NULLIF(p.name, 'Coco'), 'anonymous') FROM Player p ORDER BY p.id", String.class))
            .containsExactly("Vidocq", "anonymous");
    }

    @Test
    void constructorExpressions() { // §4.8.2
        assertThat(list("SELECT NEW io.vidocq.mansart.jpa.core.query.Summary(p.name, p.id) FROM Player p ORDER BY p.id", Summary.class))
            .containsExactly(new Summary("Vidocq", 11), new Summary("Coco", 12));
    }

    @Test
    void subqueries() { // §4.5.10, §4.6.15, §4.6.16
        assertThat(list("SELECT t.name FROM Team t WHERE EXISTS (SELECT p FROM Player p WHERE p.team = t)", String.class))
            .containsExactly("Brigade");
        assertThat(list("SELECT t.name FROM Team t WHERE NOT EXISTS (SELECT p FROM t.players p WHERE p.name = 'Coco')", String.class))
            .containsExactly("Sûreté");
        assertThat(list("SELECT p.name FROM Player p WHERE p.id IN (SELECT q.id FROM Player q WHERE q.name LIKE 'V%')", String.class))
            .containsExactly("Vidocq");
        assertThat(list("SELECT p.name FROM Player p WHERE p.id >= ALL (SELECT q.id FROM Player q)", String.class))
            .containsExactly("Coco");
        assertThat(list("SELECT p.name FROM Player p WHERE p.id = (SELECT MIN(q.id) FROM Player q)", String.class))
            .containsExactly("Vidocq");
    }

    @Test
    void collectionExpressions() { // §4.6.12, §4.6.13, §4.6.17.2.2 SIZE
        assertThat(list("SELECT t.name FROM Team t WHERE t.players IS EMPTY", String.class)).containsExactly("Sûreté");
        assertThat(em.createQuery("SELECT p.name FROM Player p WHERE :skill MEMBER OF p.skills ORDER BY p.id", String.class)
            .setParameter("skill", tracking).getResultList()).containsExactly("Vidocq", "Coco");
        assertThat(list("SELECT t.name, SIZE(t.players) FROM Team t ORDER BY t.id", Object[].class))
            .containsExactly(new Object[] {"Brigade", 2}, new Object[] {"Sûreté", 0});
        assertThat(list("SELECT s.name FROM Suspect s WHERE 'Jules' MEMBER OF s.aliases AND SIZE(s.aliases) = 2", String.class))
            .containsExactly("Vidocq");
        assertThat(list("SELECT c.name FROM Coach c WHERE c.trainees IS NOT EMPTY", String.class)).containsExactly("Chief");
    }

    @Test
    void joinsOverElementCollectionsAndTheirLiterals() { // §4.4.5, enum and date literals (§4.6.1)
        assertThat(list("SELECT s.name FROM Suspect s JOIN s.traits t WHERE t = io.vidocq.mansart.jpa.core.model.build.fixtures.coll"
            + ".Trait.CUNNING", String.class)).containsExactly("Vidocq");
        assertThat(list("SELECT a FROM Suspect s JOIN s.aliases a ORDER BY a", String.class)).containsExactly("Eugène", "Jules");
        assertThat(list("SELECT g.city FROM Suspect s JOIN s.sightings g WHERE g.seen < {d '1800-01-01'} AND g.seen < CURRENT_DATE "
            + "AND EXTRACT(YEAR FROM g.seen) = 1796", String.class)).containsExactly("Arras");
    }

    @Test
    void temporalParametersAreBoundAsTheirTemporalTypeSays() { // §3.11.4: Calendar and Date with a TemporalType
        java.util.Calendar before = new java.util.GregorianCalendar(1800, java.util.Calendar.JANUARY, 1);
        assertThat(em.createQuery("SELECT g.city FROM Suspect s JOIN s.sightings g WHERE g.seen < :before", String.class)
            .setParameter("before", before, jakarta.persistence.TemporalType.DATE).getResultList()).containsExactly("Arras");
        assertThat(em.createQuery("SELECT g.city FROM Suspect s JOIN s.sightings g WHERE g.seen < ?1", String.class)
            .setParameter(1, before.getTime(), jakarta.persistence.TemporalType.DATE).getResultList()).containsExactly("Arras");
    }

    @Test
    void orderingOfNullsAndIdentifiers() { // 3.1 NULLS FIRST, 3.2 ID()
        assertThat(list("SELECT p.name FROM Player p LEFT JOIN p.team t ORDER BY t.name NULLS FIRST, p.id", String.class))
            .containsExactly("Vidocq", "Coco");
        assertThat(list("SELECT ID(p) FROM Player p WHERE p.name = 'Coco'", Long.class)).containsExactly(12L);
    }

    @Test
    void setOperationsAndCast() { // Jakarta Persistence 3.2
        assertThat(list("SELECT p.name FROM Player p UNION SELECT t.name FROM Team t", String.class))
            .containsExactlyInAnyOrder("Vidocq", "Coco", "Brigade", "Sûreté");
        assertThat(list("SELECT p.name FROM Player p INTERSECT SELECT t.name FROM Team t", String.class)).isEmpty();
        assertThat(list("SELECT p.name FROM Player p EXCEPT SELECT t.name FROM Team t", String.class))
            .containsExactlyInAnyOrder("Vidocq", "Coco");
        assertThat(list("SELECT CAST(p.id AS STRING) FROM Player p ORDER BY p.id", String.class)).containsExactly("11", "12");
        assertThat(list("SELECT CAST(p.id AS INTEGER) FROM Player p ORDER BY p.id", Integer.class)).containsExactly(11, 12);
        var castParameter = em.createQuery("SELECT CAST(:id AS LONG) FROM Player p WHERE p.id = 11", Long.class);
        assertThat(castParameter.getParameter("id").getParameterType()).isEqualTo(Long.class);
        assertThat(castParameter.setParameter("id", 12L).getSingleResult()).isEqualTo(12L);
        assertThat(em.createQuery("SELECT p.name FROM Player p UNION ALL SELECT p.name FROM Player p ORDER BY p.name", String.class)
            .setMaxResults(2).getResultList()).containsExactly("Coco", "Coco");
    }
}
