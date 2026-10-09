/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedStoredProcedureQuery;
import jakarta.persistence.ParameterMode;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.StoredProcedureParameter;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Provider-owned regression, not an official TCK test; uses the runner's existing PostgreSQL driver. */
@EnabledIfSystemProperty(named = "p10.jdbc.url", matches = ".+")
class DelimitedProcedureTest {

    @Test
    void unitDefaultsQuoteDynamicAndNamedProcedureArgumentTargets() throws Exception { // §2.15, §12.2.1.3
        String url = System.getProperty("p10.jdbc.url");
        String user = System.getProperty("p10.jdbc.user", "p10");
        String password = System.getProperty("p10.jdbc.password", "p10");
        try (var connection = DriverManager.getConnection(url, user, password);
             var ddl = connection.createStatement()) {
            ddl.execute("CREATE SCHEMA \"P10MixedSchema\"");
            try {
                ddl.execute("CREATE PROCEDURE \"P10MixedSchema\".\"AddOne\" "
                    + "(IN \"InputValue\" integer, INOUT \"OutputValue\" integer) LANGUAGE plpgsql AS $$ "
                    + "BEGIN \"OutputValue\" := \"InputValue\" + 1; END $$");
                try (var factory = new PersistenceConfiguration("p10-postgresql-default")
                        .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                        .managedClass(ProcedureFixture.class).mappingFile("orm/delimited.xml")
                        .property(PersistenceConfiguration.JDBC_URL, url)
                        .property(PersistenceConfiguration.JDBC_USER, user)
                        .property(PersistenceConfiguration.JDBC_PASSWORD, password)
                        .createEntityManagerFactory();
                     var em = factory.createEntityManager()) {
                    var dynamic = em.createStoredProcedureQuery("P10MixedSchema.AddOne");
                    dynamic.registerStoredProcedureParameter("OutputValue", Integer.class, ParameterMode.INOUT);
                    dynamic.registerStoredProcedureParameter("InputValue", Integer.class, ParameterMode.IN);
                    dynamic.setParameter("OutputValue", 0).setParameter("InputValue", 41);
                    dynamic.execute();
                    assertEquals(42, dynamic.getOutputParameterValue("OutputValue"));
                    assertEquals("InputValue", dynamic.getParameter("InputValue").getName());

                    var named = em.createNamedStoredProcedureQuery("p10-default");
                    named.setParameter("InputValue", 9).setParameter("OutputValue", 0);
                    named.execute();
                    assertEquals(10, named.getOutputParameterValue("OutputValue"));
                }
            } finally {
                ddl.execute("DROP SCHEMA \"P10MixedSchema\" CASCADE");
            }
        }
    }

    @Test
    void quotedProceduresAndNamedArgumentsExecuteAgainstRealPostgreSql() throws Exception { // §2.15, §3.11.12
        String url = System.getProperty("p10.jdbc.url");
        String user = System.getProperty("p10.jdbc.user", "p10");
        String password = System.getProperty("p10.jdbc.password", "p10");
        try (var connection = DriverManager.getConnection(url, user, password);
             var ddl = connection.createStatement()) {
            ddl.execute("CREATE SCHEMA \"P10.MixedSchema\"");
            try {
                ddl.execute("CREATE PROCEDURE \"P10.MixedSchema\".\"Add\"\"One\" "
                    + "(IN \"Input\"\"Value\" integer, INOUT \"Output.Value\" integer) LANGUAGE plpgsql AS $$ "
                    + "BEGIN \"Output.Value\" := \"Input\"\"Value\" + 1; END $$");
                try (var factory = new PersistenceConfiguration("p10-postgresql")
                        .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                        .managedClass(ProcedureFixture.class)
                        .property(PersistenceConfiguration.JDBC_URL, url)
                        .property(PersistenceConfiguration.JDBC_USER, user)
                        .property(PersistenceConfiguration.JDBC_PASSWORD, password)
                        .createEntityManagerFactory();
                     var em = factory.createEntityManager()) {
                    var dynamic = em.createStoredProcedureQuery("\"P10.MixedSchema\".\"Add\"\"One\"");
                    // Reversed registration proves the quoted SQL target names, while JDBC binds by ordinal.
                    dynamic.registerStoredProcedureParameter("\"Output.Value\"", Integer.class, ParameterMode.INOUT);
                    dynamic.registerStoredProcedureParameter("\"Input\"\"Value\"", Integer.class, ParameterMode.IN);
                    dynamic.setParameter("\"Output.Value\"", 0).setParameter("\"Input\"\"Value\"", 41);
                    dynamic.execute();
                    assertEquals(42, dynamic.getOutputParameterValue("\"Output.Value\""));
                    assertEquals("\"Input\"\"Value\"", dynamic.getParameter("\"Input\"\"Value\"").getName());

                    var named = em.createNamedStoredProcedureQuery("p10-quoted");
                    named.setParameter("\"Input\"\"Value\"", 9).setParameter("\"Output.Value\"", 0);
                    named.execute();
                    assertEquals(10, named.getOutputParameterValue("\"Output.Value\""));
                    assertThrows(PersistenceException.class, () ->
                        em.createStoredProcedureQuery("\"P10.MixedSchema\".\"add\"\"one\"").execute());
                }
            } finally {
                ddl.execute("DROP SCHEMA \"P10.MixedSchema\" CASCADE");
            }
        }
    }

    @Entity
    @NamedStoredProcedureQuery(name = "p10-quoted", procedureName = "\"P10.MixedSchema\".\"Add\"\"One\"",
        parameters = {
            @StoredProcedureParameter(name = "\"Input\"\"Value\"", type = Integer.class, mode = ParameterMode.IN),
            @StoredProcedureParameter(name = "\"Output.Value\"", type = Integer.class, mode = ParameterMode.INOUT)})
    @NamedStoredProcedureQuery(name = "p10-default", procedureName = "P10MixedSchema.AddOne",
        parameters = {
            @StoredProcedureParameter(name = "InputValue", type = Integer.class, mode = ParameterMode.IN),
            @StoredProcedureParameter(name = "OutputValue", type = Integer.class, mode = ParameterMode.INOUT)})
    public static class ProcedureFixture {
        @Id public Integer id;
    }
}
