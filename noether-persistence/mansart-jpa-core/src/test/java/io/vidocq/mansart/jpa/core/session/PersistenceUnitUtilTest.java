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

import io.vidocq.mansart.jpa.core.model.build.fixtures.did.InformantId;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Informant;
import io.vidocq.mansart.jpa.core.model.build.fixtures.did.Officer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Emp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceUnitUtil;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §7.11 PersistenceUnitUtil: identifiers, versions, classes and load states of the entities of a unit. */
class PersistenceUnitUtilTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;
    private PersistenceUnitUtil util;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:unit-util-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Dept (id bigint primary key, name varchar(50))");
            ddl.execute("create table Desk (id bigint primary key, location varchar(50))");
            ddl.execute("create table Emp (id bigint primary key, name varchar(50), version int, dept_id bigint, MGR bigint, "
                + "DESK_ID bigint)");
            ddl.execute("create table Officer (id bigint primary key, name varchar(50))");
            ddl.execute("create table Informant (alias varchar(50), handler_id bigint, tip varchar(50), primary key (alias, handler_id))");
        }
        emf = new PersistenceConfiguration("unit-util").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).managedClass(Officer.class)
            .managedClass(Informant.class).property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
        util = emf.getPersistenceUnitUtil();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    @Test
    void theIdentifierIsTheOneTheApplicationSees() { // §7.11: null until the entity has one
        Emp clerk = new Emp(2, "Clerk", null);
        assertThat(util.getIdentifier(clerk)).isEqualTo(2L);
        Officer officer = new Officer(7, "Vidocq");
        Informant informant = new Informant("Coco", officer, "the inn");
        assertThat(util.getIdentifier(informant)).isEqualTo(new InformantId("Coco", 7));
        assertThat(util.getIdentifier(new Informant("Coco", null, "nobody's"))).isNull();
    }

    @Test
    void theVersionIsReadFromTheInstance() {
        Emp clerk = new Emp(2, "Clerk", null);
        em.getTransaction().begin();
        em.persist(clerk);
        em.getTransaction().commit();
        assertThat(util.getVersion(clerk)).isEqualTo(0);
        assertThat(util.getVersion(new Dept(1, "Sales"))).isNull(); // no version attribute
    }

    @Test
    void everythingLoadedWithItsOwnerIsLoaded() { // LAZY is a hint: Mansart loads relationships with their owner
        Dept sales = new Dept(1, "Sales");
        em.getTransaction().begin();
        em.persist(sales);
        em.persist(new Emp(2, "Clerk", sales));
        em.getTransaction().commit();
        em.clear();
        Emp clerk = em.find(Emp.class, 2L);
        assertThat(util.isLoaded(clerk)).isTrue();
        assertThat(util.isLoaded(clerk, "manager")).isTrue();
        util.load(clerk, "dept"); // nothing left to load
        util.load(clerk);
        assertThat(util.isLoaded(clerk, "dept")).isTrue();
        assertThat(Persistence.getPersistenceUtil().isLoaded(clerk, "dept")).isTrue();
    }

    @Test
    void classesAreTheEntityClassesThemselves() { // no proxies
        Emp clerk = new Emp(2, "Clerk", null);
        assertThat(util.getClass(clerk)).isEqualTo(Emp.class);
        assertThat(util.isInstance(clerk, Emp.class)).isTrue();
        assertThat(util.isInstance(clerk, Dept.class)).isFalse();
    }

    @Test
    void anObjectThatIsNotAnEntityOfTheUnitIsRefused() { // §7.11: IllegalArgumentException
        assertThatThrownBy(() -> util.getIdentifier("not an entity")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> util.getVersion(this)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> util.isInstance(new Emp(2, "Clerk", null), String.class))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> util.isLoaded(new Emp(2, "Clerk", null), "nickname")).isInstanceOf(IllegalArgumentException.class);
    }
}
