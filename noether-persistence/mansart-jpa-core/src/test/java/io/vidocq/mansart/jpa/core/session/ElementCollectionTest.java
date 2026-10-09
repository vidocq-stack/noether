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

import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Sighting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Suspect;
import io.vidocq.mansart.jpa.core.model.build.fixtures.coll.Trait;
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

/** §2.7 element collections of basic and embeddable values, in their collection tables (§11.1.8, §11.1.16). */
class ElementCollectionTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:element-collection-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Suspect (id bigint primary key, name varchar(50), version int)");
            // §11.1.8 defaults: <entity>_<attribute>, joined by <entity>_<key column>; the value column is the attribute's
            ddl.execute("create table Suspect_aliases (Suspect_id bigint not null references Suspect(id), ALIAS varchar(50))");
            ddl.execute("create table SUSPECT_SIGHTINGS (SUSPECT bigint not null references Suspect(id), TOWN varchar(50), seen date)");
            ddl.execute("create table Suspect_traits (Suspect_id bigint not null references Suspect(id), traits varchar(20))");
        }
        emf = new PersistenceConfiguration("element-collection").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Suspect.class).managedClass(Sighting.class).property(PersistenceConfiguration.JDBC_URL, url)
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

    private Suspect persisted() {
        Suspect suspect = new Suspect(1, "Vidocq");
        suspect.aliases().add("Jules");
        suspect.aliases().add("Eugène");
        suspect.sightings().add(new Sighting("Arras", LocalDate.of(1796, 3, 1)));
        suspect.sightings().add(new Sighting("Paris", LocalDate.of(1809, 7, 20)));
        suspect.traits().add(Trait.CUNNING);
        inTransaction(() -> em.persist(suspect));
        return suspect;
    }

    @Test
    void basicValuesAreWrittenToTheDefaultTableAndReadBackInOrder() throws SQLException { // §11.1.42 @OrderBy: by value
        persisted();
        assertThat(scalar("select listagg(ALIAS, ',') within group (order by ALIAS) from Suspect_aliases where Suspect_id = 1"))
            .isEqualTo("Eugène,Jules");
        em.clear();
        assertThat(em.find(Suspect.class, 1L).aliases()).containsExactly("Eugène", "Jules");
    }

    @Test
    void embeddablesAreWrittenWithTheirOverridesAndReadBackInOrder() throws SQLException { // §11.1.4, §11.1.42
        persisted();
        assertThat(scalar("select count(*) from SUSPECT_SIGHTINGS where SUSPECT = 1 and TOWN in ('Arras', 'Paris')")).isEqualTo("2");
        em.clear();
        assertThat(em.find(Suspect.class, 1L).sightings()).extracting(Sighting::city).containsExactly("Paris", "Arras");
    }

    @Test
    void elementsTakeTheConversionOfTheirAttribute() throws SQLException { // §11.1.18 @Enumerated on the collection
        persisted();
        assertThat(scalar("select traits from Suspect_traits")).isEqualTo("CUNNING");
        em.clear();
        assertThat(em.find(Suspect.class, 1L).traits()).containsExactly(Trait.CUNNING);
    }

    @Test
    void aChangedCollectionIsRewrittenAndVersionsItsOwner() throws SQLException { // §3.4.2: not a relationship
        Suspect suspect = persisted();
        inTransaction(() -> {
            suspect.aliases().remove("Jules");
            suspect.aliases().add("Le Chef");
        });
        assertThat(scalar("select listagg(ALIAS, ',') within group (order by ALIAS) from Suspect_aliases")).isEqualTo("Eugène,Le Chef");
        assertThat(suspect.version()).isEqualTo(1);
        inTransaction(() -> { }); // nothing changed: nothing written
        assertThat(suspect.version()).isEqualTo(1);
    }

    @Test
    void anEmbeddableElementChangedInPlaceIsWritten() throws SQLException { // the snapshot holds the element's state
        Suspect suspect = persisted();
        inTransaction(() -> suspect.sightings().getFirst().city("Lyon"));
        assertThat(scalar("select count(*) from SUSPECT_SIGHTINGS where TOWN = 'Lyon'")).isEqualTo("1");
        assertThat(scalar("select count(*) from SUSPECT_SIGHTINGS")).isEqualTo("2");
    }

    @Test
    void removingTheOwnerDeletesItsElementsFirst() throws SQLException {
        Suspect suspect = persisted();
        inTransaction(() -> em.remove(suspect));
        assertThat(scalar("select count(*) from Suspect_aliases")).isEqualTo("0");
        assertThat(scalar("select count(*) from SUSPECT_SIGHTINGS")).isEqualTo("0");
        assertThat(scalar("select count(*) from Suspect")).isEqualTo("0");
    }

    @Test
    void aMergedCollectionIsACopy() { // §3.2.7.1: the managed instance shares nothing with the detached one
        persisted();
        em.clear();
        Suspect detached = new Suspect(1, "Vidocq");
        detached.sightings().add(new Sighting("Rouen", LocalDate.of(1800, 1, 1)));
        em.getTransaction().begin();
        Suspect managed = em.merge(detached);
        assertThat(managed.sightings()).isNotSameAs(detached.sightings());
        assertThat(managed.sightings().getFirst()).isNotSameAs(detached.sightings().getFirst());
        assertThat(managed.sightings()).extracting(Sighting::city).containsExactly("Rouen");
        em.getTransaction().commit();
    }
}
