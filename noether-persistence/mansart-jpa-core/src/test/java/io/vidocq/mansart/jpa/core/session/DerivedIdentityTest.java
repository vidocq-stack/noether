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

import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Badge;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Dossier;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Informant;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.InformantId;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Officer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Profile;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Record;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.SuspectId;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Witness;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.WitnessKey;
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

/** §2.4.1 derived identities: the identifier of a dependent holds the key of its parent, in its foreign key columns. */
class DerivedIdentityTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:derived-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Officer (id bigint primary key, name varchar(50))");
            ddl.execute("create table Informant (alias varchar(50), handler_id bigint references Officer(id), tip varchar(50), "
                + "primary key (alias, handler_id))");
            ddl.execute("create table Witness (name varchar(50), officer_id bigint references Officer(id), primary key (name, officer_id))");
            ddl.execute("create table Dossier (OFFICER_ID bigint primary key references Officer(id), summary varchar(50))");
            ddl.execute("create table Badge (ID bigint primary key references Officer(id))");
            ddl.execute("create table Profile (first varchar(20), last varchar(20), alias varchar(50), primary key (first, last))");
            ddl.execute("create table CriminalRecord (FIRST varchar(20), LAST varchar(20), convictions int, primary key (FIRST, LAST), "
                + "foreign key (FIRST, LAST) references Profile (first, last))");
        }
        emf = new PersistenceConfiguration("derived").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Officer.class).managedClass(Informant.class).managedClass(Witness.class).managedClass(WitnessKey.class)
            .managedClass(Dossier.class).managedClass(Badge.class).managedClass(Profile.class).managedClass(Record.class)
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

    private Officer officer() {
        Officer officer = new Officer(7, "Vidocq");
        inTransaction(() -> em.persist(officer));
        return officer;
    }

    @Test
    void anIdClassPartIsTheKeyOfTheParent() throws SQLException { // example 1a
        Officer officer = officer();
        inTransaction(() -> em.persist(new Informant("Coco", officer, "the inn")));
        assertThat(scalar("select handler_id from Informant where alias = 'Coco'")).isEqualTo("7");
        em.clear();
        Informant found = em.find(Informant.class, new InformantId("Coco", 7));
        assertThat(found.tip()).isEqualTo("the inn");
        assertThat(found.handler()).isSameAs(em.find(Officer.class, 7L));
        assertThat(em.find(Informant.class, new InformantId("Coco", 8))).isNull();
    }

    @Test
    void aMapsIdPartOfAnEmbeddedIdIsDerivedFromTheParent() throws SQLException { // example 1b
        Officer officer = officer();
        Witness witness = new Witness("Flambard", officer);
        inTransaction(() -> em.persist(witness));
        assertThat(witness.key().officerId()).isEqualTo(7); // assigned by the provider
        assertThat(scalar("select officer_id from Witness")).isEqualTo("7");
        em.clear();
        Witness found = em.find(Witness.class, new WitnessKey("Flambard", 7));
        assertThat(found.officer().name()).isEqualTo("Vidocq");
        assertThat(found.key()).isEqualTo(new WitnessKey("Flambard", 7));
    }

    @Test
    void aRelationshipIdentifierIsTheKeyOfTheParent() throws SQLException { // example 4a
        Officer officer = officer();
        inTransaction(() -> em.persist(new Dossier(officer, "closed")));
        assertThat(scalar("select OFFICER_ID from Dossier")).isEqualTo("7");
        em.clear();
        Dossier found = em.find(Dossier.class, 7L); // the identifier of the officer
        assertThat(found.summary()).isEqualTo("closed");
        assertThat(found.officer().name()).isEqualTo("Vidocq");
    }

    @Test
    void aMapsIdIdentifierTakesTheKeyOfTheParent() throws SQLException { // example 4b
        Officer officer = officer();
        Badge badge = new Badge(officer);
        inTransaction(() -> em.persist(badge));
        assertThat(badge.id()).isEqualTo(7);
        assertThat(scalar("select ID from Badge")).isEqualTo("7");
        em.clear();
        Badge found = em.find(Badge.class, 7L);
        assertThat(found.id()).isEqualTo(7);
        assertThat(found.officer().name()).isEqualTo("Vidocq");
    }

    @Test
    void aDependentSharingTheIdClassOfItsParentIsFoundByIt() throws SQLException { // example 5a
        Profile profile = new Profile("Eugène", "Vidocq", "le Chef");
        inTransaction(() -> {
            em.persist(profile);
            em.persist(new Record(profile, 3));
        });
        assertThat(scalar("select FIRST || ' ' || LAST from CriminalRecord")).isEqualTo("Eugène Vidocq");
        em.clear();
        Record found = em.find(Record.class, new SuspectId("Eugène", "Vidocq"));
        assertThat(found.convictions()).isEqualTo(3);
        assertThat(found.profile().alias()).isEqualTo("le Chef");
    }

    @Test
    void aDependentIsUpdatedAndRemovedByItsDerivedKey() throws SQLException {
        Officer officer = officer();
        Informant informant = new Informant("Coco", officer, "the inn");
        inTransaction(() -> em.persist(informant));
        em.clear();
        inTransaction(() -> em.remove(em.find(Informant.class, new InformantId("Coco", 7))));
        assertThat(scalar("select count(*) from Informant")).isEqualTo("0");
    }
}
