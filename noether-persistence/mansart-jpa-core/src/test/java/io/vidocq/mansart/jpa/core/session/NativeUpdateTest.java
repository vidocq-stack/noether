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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SqlResultSetMapping;
import jakarta.persistence.ColumnResult;
import jakarta.persistence.ConstructorResult;
import jakarta.persistence.EntityResult;
import jakarta.persistence.FieldResult;
import jakarta.persistence.TransactionRequiredException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.11 native queries, update part (brought forward from P7): SQL passed to the database as written. */
class NativeUpdateTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:native-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table Crew (id bigint primary key, name varchar(50), version int)");
            ddl.execute("create table Sailor (id bigint primary key, name varchar(50), crew_id bigint references Crew(id), "
                + "formerCrew_id bigint references Crew(id))");
            ddl.execute("insert into Crew values (1, 'Brigade', 0), (2, 'Sûreté', 0)");
        }
        emf = new PersistenceConfiguration("native").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Crew.class).managedClass(Sailor.class).property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .managedClass(NativeResults.class)
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        emf.close();
        database.close();
    }

    private String name(long id) throws SQLException {
        try (Statement query = database.createStatement(); ResultSet row = query.executeQuery("select name from Crew where id = " + id)) {
            return row.next() ? row.getString(1) : null;
        }
    }

    @Test
    void aNativeDeleteReturnsItsCount() throws SQLException {
        em.getTransaction().begin();
        assertThat(em.createNativeQuery("DELETE FROM Crew").executeUpdate()).isEqualTo(2);
        em.getTransaction().commit();
        assertThat(name(1)).isNull();
    }

    @Test
    void positionalParametersAreBound() throws SQLException {
        em.getTransaction().begin();
        int count = em.createNativeQuery("UPDATE Crew SET name = ?1 WHERE id = ?2").setParameter(1, "Brigade de sûreté")
            .setParameter(2, 1L).executeUpdate();
        em.getTransaction().commit();
        assertThat(count).isEqualTo(1);
        assertThat(name(1)).isEqualTo("Brigade de sûreté");
    }

    @Test
    void anUpdateNeedsATransaction() { // §3.11.6
        assertThatThrownBy(() -> em.createNativeQuery("DELETE FROM Crew").executeUpdate())
            .isInstanceOf(TransactionRequiredException.class);
    }

    @Test
    void thePersistenceContextIsFlushedFirst() { // FlushModeType.AUTO
        em.getTransaction().begin();
        em.persist(new Crew(3, "Brigade mobile"));
        assertThat(em.createNativeQuery("UPDATE Crew SET name = 'renamed' WHERE id = 3").executeUpdate()).isEqualTo(1);
        em.getTransaction().rollback();
    }

    @Test
    void nativeQueriesReadScalarAndTupleResults() {
        assertThat(em.createNativeQuery("SELECT name FROM Crew ORDER BY id").getResultList())
            .containsExactly("Brigade", "Sûreté");
        assertThat(em.createNativeQuery("SELECT id, name FROM Crew ORDER BY id").getResultList())
            .containsExactly(new Object[] {1L, "Brigade"}, new Object[] {2L, "Sûreté"});
        assertThat(em.createNativeQuery("SELECT name FROM Crew WHERE id = ?1").setParameter(1, 2L).getSingleResult())
            .isEqualTo("Sûreté");
    }

    @Test
    void nativeQueryUsesColumnResultMapping() {
        assertThat(em.createNativeQuery("SELECT name FROM Crew ORDER BY id", "crew-names").getResultList())
            .containsExactly("Brigade", "Sûreté");
    }

    @Test
    void nativeQueryUsesConstructorResultMapping() {
        assertThat(em.createNativeQuery("SELECT id, name FROM Crew ORDER BY id", "crew-records").getResultList())
            .containsExactly(new CrewName(1, "Brigade"), new CrewName(2, "Sûreté"));
    }

    @Test
    void nativeQueryUsesEntityResultFieldAliases() {
        assertThat(em.createNativeQuery("SELECT id AS crew_id FROM Crew WHERE id = 1", "crew-entities").getSingleResult())
            .isSameAs(em.find(Crew.class, 1L));
    }

    @Test
    void explicitDelimitedResultNamesUseExactJdbcLabelsWithoutQuoteDelimiters() { // §2.15
        assertThat(em.createNativeQuery("SELECT 42 AS \"Value\"\"Label\", 9 AS \"value\"\"label\"", "quoted-column")
            .getSingleResult()).isEqualTo(42);
        assertThat(em.createNativeQuery("SELECT 1 AS \"Key\", 'Brigade' AS \"Name\"", "quoted-constructor")
            .getSingleResult()).isEqualTo(new CrewName(1, "Brigade"));
        assertThat(em.createNativeQuery("SELECT id AS \"CrewKey\" FROM Crew WHERE id = 1", "quoted-entity")
            .getSingleResult()).isSameAs(em.find(Crew.class, 1L));
        assertThatThrownBy(() -> em.createNativeQuery("SELECT 1 AS \"crewkey\"", "quoted-entity").getSingleResult())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("CrewKey");
        assertThatThrownBy(() -> em.createNativeQuery("SELECT 42 AS \"value\"\"label\"", "quoted-column").getSingleResult())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("Value");
    }

    @Test
    void unquotedResultNamesRejectAmbiguousFoldedLabelsAndDoNotMatchHiddenColumnNames() {
        assertThatThrownBy(() -> em.createNativeQuery("SELECT 1 AS \"name\", 2 AS \"NAME\"", "crew-names").getSingleResult())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("Ambiguous");
        assertThatThrownBy(() -> em.createNativeQuery("SELECT name AS other FROM Crew", "crew-names").getResultList())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("no column");
    }

    @Test
    void unitDefaultsApplyToScalarAndConstructorResultLabels() {
        try (EntityManagerFactory delimited = new PersistenceConfiguration("delimited-native")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(NativeResults.class).mappingFile("orm/delimited.xml")
                .property(PersistenceConfiguration.JDBC_URL, emf.getProperties().get(PersistenceConfiguration.JDBC_URL))
                .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
             EntityManager manager = delimited.createEntityManager()) {
            assertThat(manager.createNativeQuery("SELECT 'exact' AS \"name\", 'folded' AS \"NAME\"", "crew-names")
                .getSingleResult()).isEqualTo("exact");
            assertThatThrownBy(() -> manager.createNativeQuery("SELECT 'wrong' AS \"NAME\"", "crew-names").getSingleResult())
                .isInstanceOf(PersistenceException.class).hasMessageContaining("no column");
            assertThat(manager.createNativeQuery("SELECT 1 AS \"id\", 'Brigade' AS \"name\"", "crew-records")
                .getSingleResult()).isEqualTo(new CrewName(1, "Brigade"));
        }
    }

    @Test
    void nonKeyFieldAliasesAlsoRequireExactDelimitedLabels() {
        assertThat(em.createNativeQuery("SELECT id AS \"CrewKey\", name AS \"CrewName\" FROM Crew WHERE id = 1",
            "quoted-fields").getSingleResult()).isSameAs(em.find(Crew.class, 1L));
        assertThatThrownBy(() -> em.createNativeQuery("SELECT id AS \"CrewKey\", name AS \"crewname\" FROM Crew WHERE id = 1",
            "quoted-fields").getSingleResult()).isInstanceOf(PersistenceException.class).hasMessageContaining("CrewName");
    }

    @Test
    void aFailedStatementMarksTheTransactionForRollback() { // §3.12
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.createNativeQuery("DELETE FROM NoSuchTable").executeUpdate())
            .isInstanceOf(PersistenceException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }

    @SqlResultSetMapping(name = "crew-names", columns = @ColumnResult(name = "name", type = String.class))
    @SqlResultSetMapping(name = "crew-records", classes = @ConstructorResult(targetClass = CrewName.class,
        columns = {@ColumnResult(name = "id", type = long.class), @ColumnResult(name = "name", type = String.class)}))
    @SqlResultSetMapping(name = "crew-entities", entities = @EntityResult(entityClass = Crew.class,
        fields = @FieldResult(name = "id", column = "crew_id")))
    @SqlResultSetMapping(name = "quoted-column", columns = @ColumnResult(name = "\"Value\"\"Label\"", type = Integer.class))
    @SqlResultSetMapping(name = "quoted-constructor", classes = @ConstructorResult(targetClass = CrewName.class,
        columns = {@ColumnResult(name = "\"Key\"", type = long.class), @ColumnResult(name = "\"Name\"", type = String.class)}))
    @SqlResultSetMapping(name = "quoted-entity", entities = @EntityResult(entityClass = Crew.class,
        fields = @FieldResult(name = "id", column = "\"CrewKey\"")))
    @SqlResultSetMapping(name = "quoted-fields", entities = @EntityResult(entityClass = Crew.class,
        fields = {@FieldResult(name = "id", column = "\"CrewKey\""), @FieldResult(name = "name", column = "\"CrewName\"")}))
    static class NativeResults {
    }

    public record CrewName(long id, String name) {
    }
}
