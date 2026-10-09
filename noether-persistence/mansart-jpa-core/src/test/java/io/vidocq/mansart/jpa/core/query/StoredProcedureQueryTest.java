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

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.StoredProcedureParameter;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.StoredProcedureQuery;
import jakarta.persistence.TemporalType;
import java.util.Calendar;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, §3.11.12 and §10.4.2: stored procedures and their parameters. */
class StoredProcedureQueryTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() {
        String url = "jdbc:h2:mem:procedure-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        emf = new PersistenceConfiguration("procedure").provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(ProcedureFixture.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() {
        emf.close();
    }

    @Test
    void registeredParametersAreTypedAndMustBeBoundBeforeExecution() { // §3.11.12: register and bind procedure parameters
        StoredProcedureQuery query = em.createStoredProcedureQuery("proc");
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(2, String.class, ParameterMode.OUT);

        assertThat(query.getParameters()).hasSize(2);
        assertThat(query.getParameter(1).getParameterType()).isEqualTo(Integer.class);
        assertThat(query.getParameter(2).getParameterType()).isEqualTo(String.class);
        assertThatThrownBy(query::execute).isInstanceOf(IllegalStateException.class);
        query.setParameter(1, 42);
        assertThat(query.isBound(query.getParameter(1))).isTrue();
        assertThat(query.getParameterValue(1)).isEqualTo(42);
        assertThatThrownBy(() -> query.setParameter(2, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parametersCannotBeRegisteredTwiceOrAfterExecution() {
        StoredProcedureQuery query = em.createStoredProcedureQuery("proc");
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        assertThatThrownBy(() -> query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.OUT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aClosedEntityManagerClosesItsProcedureQueries() {
        StoredProcedureQuery query = em.createStoredProcedureQuery("proc");
        em.close();
        assertThatThrownBy(query::getParameters).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void executesAndMapsAProcedureResultSetAndKeepsItsParameterMetadata() {
        em.runWithConnection((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE ALIAS ADD_ONE AS 'int addOne(int value) { return value + 1; }'");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        StoredProcedureQuery query = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        query.setParameter(1, 41);

        assertThat(query.execute()).isTrue();
        assertThat(query.getResultList()).containsExactly(42);
        assertThat(query.hasMoreResults()).isFalse();
    }

    @Test
    void createsNamedProceduresFromManagedClassAnnotations() {
        StoredProcedureQuery query = em.createNamedStoredProcedureQuery("ProcedureFixture.increment");

        assertThat(query.getParameter(1).getParameterType()).isEqualTo(Integer.class);
        assertThat(query.getParameter(1).getPosition()).isEqualTo(1);
    }

    private void sql(String statementText) {
        em.runWithConnection((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute(statementText);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private void createAddOne() {
        sql("CREATE ALIAS ADD_ONE AS 'int addOne(int value) { return value + 1; }'");
    }

    @Test
    void quotedQualifiedRoutineNamesKeepDotsCaseAndEscapedQuotes() { // §2.15: all database objects
        sql("CREATE SCHEMA \"Mixed.Schema\"");
        sql("CREATE ALIAS \"Mixed.Schema\".\"Add\"\"One\" FOR "
            + "'io.vidocq.mansart.jpa.core.query.StoredProcedureQueryTest$ProcedureFixture.addOne'");
        StoredProcedureQuery query = em.createStoredProcedureQuery("\"Mixed.Schema\".\"Add\"\"One\"", Integer.class);
        query.registerStoredProcedureParameter("\"Input\"\"Value\"", Integer.class, ParameterMode.IN);
        query.setParameter("\"Input\"\"Value\"", 41);
        assertThat(query.getParameter("\"Input\"\"Value\"").getName()).isEqualTo("\"Input\"\"Value\"");
        assertThat(query.getSingleResult()).isEqualTo(42);
        assertThatThrownBy(() -> em.createStoredProcedureQuery("\"Mixed.Schema\".\"add\"\"one\"", Integer.class)
            .getSingleResult()).isInstanceOf(PersistenceException.class);
    }

    @Test
    void unitDefaultsQuoteNamedAndDynamicProcedureNames() {
        sql("CREATE SCHEMA \"MixedSchema\"");
        sql("CREATE ALIAS \"MixedSchema\".\"AddOne\" AS 'int addOne(int value) { return value + 1; }'");
        try (EntityManagerFactory delimited = new PersistenceConfiguration("delimited-procedure")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(ProcedureFixture.class).mappingFile("orm/delimited.xml")
                .property(PersistenceConfiguration.JDBC_URL, emf.getProperties().get(PersistenceConfiguration.JDBC_URL))
                .property(PersistenceConfiguration.JDBC_USER, "sa").createEntityManagerFactory();
             EntityManager manager = delimited.createEntityManager()) {
            StoredProcedureQuery dynamic = manager.createStoredProcedureQuery("MixedSchema.AddOne", Integer.class);
            dynamic.registerStoredProcedureParameter("InputValue", Integer.class, ParameterMode.IN);
            assertThat(dynamic.setParameter("InputValue", 41).getSingleResult()).isEqualTo(42);
            StoredProcedureQuery named = manager.createNamedStoredProcedureQuery("ProcedureFixture.delimited");
            assertThat(named.setParameter("InputValue", 9).getSingleResult()).isEqualTo(10);
        }
    }

    @Test
    void malformedOrInjectableRoutineNamesAreRejectedAtCreation() {
        for (String name : java.util.List.of("", "proc.", ".proc", "a..b", "\"unclosed", "\"a\"b\"",
                "\"a\".\"b", "\"\"", "proc(); DROP TABLE X", "proc --", "proc/*comment*/")) {
            assertThatThrownBy(() -> em.createStoredProcedureQuery(name))
                .as(name).isInstanceOf(IllegalArgumentException.class);
        }
        StoredProcedureQuery query = em.createStoredProcedureQuery("proc");
        assertThatThrownBy(() -> query.registerStoredProcedureParameter("\"unclosed", Integer.class, ParameterMode.IN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(query.getParameters()).isEmpty();
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        assertThat(query.getParameters()).hasSize(1);
    }

    @Test
    void delimitedSqlPunctuationCannotEscapeTheRoutineIdentifier() {
        sql("CREATE TABLE marker (id integer)");
        sql("CREATE ALIAS \"Safe\"\"); DROP TABLE marker; --\" FOR "
            + "'io.vidocq.mansart.jpa.core.query.StoredProcedureQueryTest$ProcedureFixture.addOne'");
        var query = em.createStoredProcedureQuery("\"Safe\"\"); DROP TABLE marker; --\"", Integer.class);
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        assertThat(query.setParameter(1, 41).getSingleResult()).isEqualTo(42);
        assertThat(em.createNativeQuery("SELECT COUNT(*) FROM marker").getSingleResult()).isEqualTo(0L);
    }

    @Test
    void procedureResultMappingsUseTheSameExactDelimitedJdbcLabelsAsNativeQueries() {
        sql("CREATE ALIAS QUOTED_RESULTS FOR "
            + "'io.vidocq.mansart.jpa.core.query.StoredProcedureQueryTest$ProcedureFixture.labels'");
        assertThat(em.createStoredProcedureQuery("QUOTED_RESULTS", "procedure-label").getSingleResult()).isEqualTo(42);
    }

    @Test
    void executesAgainWithNewInputsAndResetsThePreviousResults() { // §3.11.12: a query may be executed repeatedly
        createAddOne();
        StoredProcedureQuery query = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        query.setParameter(1, 41);
        assertThat(query.execute()).isTrue();
        assertThat(query.getResultList()).containsExactly(42);
        assertThat(query.hasMoreResults()).isFalse();

        query.setParameter(1, 9);
        assertThat(query.getResultList()).containsExactly(10);
        assertThat(query.execute()).isTrue();
        assertThat(query.getSingleResult()).isEqualTo(10);
    }

    @Test
    void aFailedExecutionLeavesTheQueryUnexecutedAndRetryable() {
        StoredProcedureQuery query = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        query.setParameter(1, 1);
        assertThatThrownBy(query::execute).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(query::getUpdateCount).isInstanceOf(IllegalStateException.class);

        createAddOne();
        assertThat(query.execute()).isTrue();
        assertThat(query.getResultList()).containsExactly(2);
    }

    @Test
    void parameterMethodsOfAClosedEntityManagerThrowIllegalState() {
        StoredProcedureQuery query = em.createStoredProcedureQuery("proc");
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        em.close();
        assertThatThrownBy(() -> query.setParameter(1, 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> query.setParameter(1, Calendar.getInstance(), TemporalType.DATE))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> query.getParameter(1, Integer.class)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theValueOfAnUnboundInputParameterIsIllegalState() { // Query.getParameterValue(Parameter)
        StoredProcedureQuery query = em.createStoredProcedureQuery("proc");
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        jakarta.persistence.Parameter<Integer> parameter = query.getParameter(1, Integer.class);
        assertThatThrownBy(() -> query.getParameterValue(parameter)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pagesAResultSetFromAPositiveFirstResultWithoutALimit() {
        sql("CREATE ALIAS NUMBERS AS 'ResultSet numbers(Connection c) throws SQLException {"
            + " return c.createStatement().executeQuery(\"SELECT X FROM SYSTEM_RANGE(1, 3)\"); }'");
        StoredProcedureQuery query = em.createStoredProcedureQuery("NUMBERS", Long.class);
        query.setFirstResult(1);
        assertThat(query.getResultList()).containsExactly(2L, 3L);
    }

    @Test
    void keepsANullScalarResult() {
        sql("CREATE ALIAS NOTHING AS 'Integer nothing() { return null; }'");
        StoredProcedureQuery query = em.createStoredProcedureQuery("NOTHING", Integer.class);
        assertThat(query.getResultList()).containsExactly((Object) null);
        assertThatThrownBy(() -> query.getResultList().add(1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theResultListIsNullWhenThePendingResultIsNotAResultSet() { // StoredProcedureQuery.getResultList
        createAddOne();
        StoredProcedureQuery query = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        query.setParameter(1, 1);
        query.execute();
        assertThat(query.hasMoreResults()).isFalse();
        assertThat(query.getResultList()).isNull();
        assertThat(query.getSingleResult()).isNull();
        assertThat(query.getSingleResultOrNull()).isNull();
    }

    @Test
    void trailingUnboundInputsAreLeftToTheirDatabaseDefaults() { // §3.11.12: defaults need no binding
        createAddOne();
        StoredProcedureQuery query = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
        query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        query.registerStoredProcedureParameter(2, Integer.class, ParameterMode.IN);
        query.setParameter(1, 1);
        assertThat(query.getResultList()).containsExactly(2);

        StoredProcedureQuery gap = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
        gap.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
        gap.registerStoredProcedureParameter(2, Integer.class, ParameterMode.IN);
        gap.setParameter(2, 1);
        assertThatThrownBy(gap::execute).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void temporalBindingsKeepTheBoundValueAndBindTheTemporalType() {
        sql("CREATE ALIAS ECHO_DATE AS 'java.sql.Date echo(java.sql.Date value) { return value; }'");
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 9);
        StoredProcedureQuery query = em.createStoredProcedureQuery("ECHO_DATE", java.sql.Date.class);
        query.registerStoredProcedureParameter(1, Calendar.class, ParameterMode.IN);
        query.setParameter(1, calendar, TemporalType.DATE);
        assertThat(query.getParameterValue(1)).isSameAs(calendar);
        assertThat(query.getResultList()).containsExactly(java.sql.Date.valueOf("2026-10-09"));

        StoredProcedureQuery sqlDate = em.createStoredProcedureQuery("ECHO_DATE", java.sql.Date.class);
        sqlDate.registerStoredProcedureParameter(1, java.sql.Date.class, ParameterMode.IN);
        sqlDate.setParameter(1, calendar.getTime(), TemporalType.DATE);
        assertThat(sqlDate.getResultList()).containsExactly(java.sql.Date.valueOf("2026-10-09"));
    }

    @Entity(name = "ProcedureFixture")
    @jakarta.persistence.SqlResultSetMapping(name = "procedure-label",
        columns = @jakarta.persistence.ColumnResult(name = "\"Value\"\"Label\"", type = Integer.class))
    @NamedStoredProcedureQuery(name = "ProcedureFixture.increment", procedureName = "ADD_ONE",
        parameters = @StoredProcedureParameter(name = "", type = Integer.class, mode = ParameterMode.IN))
    @NamedStoredProcedureQuery(name = "ProcedureFixture.delimited", procedureName = "MixedSchema.AddOne",
        resultClasses = Integer.class,
        parameters = @StoredProcedureParameter(name = "InputValue", type = Integer.class, mode = ParameterMode.IN))
    public static class ProcedureFixture {
        public static int addOne(int value) {
            return value + 1;
        }

        public static java.sql.ResultSet labels(java.sql.Connection connection) throws java.sql.SQLException {
            return connection.createStatement().executeQuery("SELECT 42 AS \"Value\"\"Label\", 9 AS \"value\"\"label\"");
        }

        @Id
        public Integer id;
    }
}
