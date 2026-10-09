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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.TransactionRequiredException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §4.10: bulk updates and deletes, run in the database, around the persistence context. */
class BulkQueryTest {
    @Test
    void jpqlAndCriteriaLiteralsUseAttributeConverters() throws SQLException { // §3.9: query values use the conversion.
        ConvertedAmount entity = new ConvertedAmount(); entity.id = 1; entity.amount = "1#2.0";
        em.getTransaction().begin(); em.persist(entity); em.getTransaction().commit();
        assertThat(run("update ConvertedAmount e set e.amount='5#4.0' where e.amount='1#2.0'")).isEqualTo(1);
        assertThat(scalar("select amount from ConvertedAmount where id=1")).isEqualTo("54.0");
        var builder = emf.getCriteriaBuilder();
        var update = builder.createCriteriaUpdate(ConvertedAmount.class);
        var root = update.from(ConvertedAmount.class);
        update.set("amount", "7#6.0").where(builder.equal(root.get("amount"), "5#4.0"));
        em.getTransaction().begin();
        assertThat(em.createQuery(update).executeUpdate()).isEqualTo(1);
        em.getTransaction().commit();
        assertThat(scalar("select amount from ConvertedAmount where id=1")).isEqualTo("76.0");
    }

    @jakarta.persistence.Entity(name="ConvertedAmount")
    public static class ConvertedAmount {
        @jakarta.persistence.Id public long id;
        @jakarta.persistence.Convert(converter=NumberText.class) public String amount;
        public ConvertedAmount() {}
    }
    @jakarta.persistence.Converter
    public static class NumberText implements jakarta.persistence.AttributeConverter<String,Double> {
        public NumberText() {}
        @Override public Double convertToDatabaseColumn(String value) {
            return value==null?null:Double.valueOf(value.replace("#",""));
        }
        @Override public String convertToEntityAttribute(Double value) { return value==null?null:value.toString(); }
    }

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:bulk-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Dept (id bigint primary key, name varchar(50))");
            ddl.execute("create table Desk (id bigint primary key, location varchar(50))");
            ddl.execute("create table ConvertedAmount (id bigint primary key, amount double)");
            ddl.execute("create table Emp (id bigint primary key, name varchar(50), version int, dept_id bigint references Dept(id), "
                + "MGR bigint references Emp(id), DESK_ID bigint references Desk(id))");
        }
        emf = new PersistenceConfiguration("bulk").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Desk.class).managedClass(Emp.class).managedClass(ConvertedAmount.class)
            .property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
        em = emf.createEntityManager();
        Dept sales = new Dept(1, "Sales");
        em.getTransaction().begin();
        List.of(sales, new Dept(2, "Audit"), new Emp(1, "Chief", sales), new Emp(2, "Clerk", sales), new Emp(3, "Loner", null))
            .forEach(em::persist);
        em.getTransaction().commit();
        em.clear();
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

    private int run(String jpql, Object... parameters) {
        em.getTransaction().begin();
        var query = em.createQuery(jpql);
        for (int i = 0; i < parameters.length; i += 2) {
            query.setParameter((String) parameters[i], parameters[i + 1]);
        }
        int count = query.executeUpdate();
        em.getTransaction().commit();
        return count;
    }

    @Test
    void anUpdateSetsItsValuesAndCountsItsRows() throws SQLException { // §4.10
        assertThat(run("UPDATE Emp e SET e.name = CONCAT(e.name, '!'), e.version = e.version + 1 WHERE e.dept IS NOT NULL")).isEqualTo(2);
        assertThat(scalar("select name from Emp where id = 2")).isEqualTo("Clerk!");
        assertThat(scalar("select version from Emp where id = 2")).isEqualTo("1");
        assertThat(scalar("select name from Emp where id = 3")).isEqualTo("Loner");
    }

    @Test
    void anUpdateSetsRelationshipsByTheirKeys() throws SQLException { // §4.10: SET e.dept = :dept, SET e.dept = NULL
        Dept audit = em.find(Dept.class, 2L);
        assertThat(run("update Emp e set e.dept = :dept where e.id = 3", "dept", audit)).isEqualTo(1);
        assertThat(scalar("select dept_id from Emp where id = 3")).isEqualTo("2");
        assertThat(run("UPDATE Emp SET dept = NULL WHERE name = 'Chief'")).isEqualTo(1); // the implicit variable (3.2)
        assertThat(scalar("select dept_id from Emp where id = 1")).isNull();
    }

    @Test
    void aDeleteRemovesTheRowsItSelects() throws SQLException { // §4.10, with a subquery
        assertThat(run("DELETE FROM Emp e WHERE e.dept IS NULL OR e.id IN (SELECT x.id FROM Emp x WHERE x.name = :n)", "n", "Clerk"))
            .isEqualTo(2);
        assertThat(scalar("select count(*) from Emp")).isEqualTo("1");
        assertThat(run("delete from Dept d where d.name = 'Audit'")).isEqualTo(1);
    }

    @Test
    void theContextIsFlushedFirstAndABulkStatementNeedsATransaction() throws SQLException { // §3.11.6, §4.10
        em.getTransaction().begin();
        em.persist(new Emp(4, "Newcomer", null));
        assertThat(em.createQuery("DELETE FROM Emp e WHERE e.name = 'Newcomer'").executeUpdate()).isEqualTo(1);
        em.getTransaction().rollback();
        em.clear();
        assertThatThrownBy(() -> em.createQuery("DELETE FROM Emp e").executeUpdate()).isInstanceOf(TransactionRequiredException.class);
        assertThatThrownBy(() -> em.createQuery("DELETE FROM Emp e").getResultList()).isInstanceOf(IllegalStateException.class);
        assertThat(scalar("select count(*) from Emp")).isEqualTo("3");
    }
}
