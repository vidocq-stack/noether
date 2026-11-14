/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.model.build.fixtures.Crew;
import io.vidocq.noether.jpa.core.model.build.fixtures.Sailor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TemporalType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Calendar;
import java.util.Date;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.11.15: the scalar result of a native query is converted to the result class it is asked for. */
class NativeQueryResultsTest {

    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() {
        emf = new PersistenceConfiguration("native-results").provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Crew.class).managedClass(Sailor.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:native-results")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() {
        em.close();
        emf.close();
    }

    private Object single(String sql, Class<?> resultClass) {
        return em.createNativeQuery(sql, resultClass).getSingleResult();
    }

    @Test
    void aNumberIsConvertedToTheNumericResultClass() {
        assertThat(single("SELECT CAST(7 AS BIGINT)", Integer.class)).isEqualTo(7);
        assertThat(single("SELECT 7", Long.class)).isEqualTo(7L);
        assertThat(single("SELECT 7", Short.class)).isEqualTo((short) 7);
        assertThat(single("SELECT 7", Byte.class)).isEqualTo((byte) 7);
        assertThat(single("SELECT 7", Float.class)).isEqualTo(7f);
        assertThat(single("SELECT 7", Double.class)).isEqualTo(7.0);
        assertThat(single("SELECT 7", BigDecimal.class)).isEqualTo(new BigDecimal("7"));
        assertThat(single("SELECT 7", BigInteger.class)).isEqualTo(BigInteger.valueOf(7));
    }

    @Test
    void aNumberIsGivenAsTextToAStringResultClass() {
        assertThat(single("SELECT 7", String.class)).isEqualTo("7");
        assertThat(single("SELECT 'seven'", String.class)).isEqualTo("seven");
    }

    @Test
    void aResultTheResultClassCannotHoldIsRefused() {
        Query query = em.createNativeQuery("SELECT 7", Boolean.class);
        assertThatThrownBy(query::getSingleResult)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("cannot be converted to java.lang.Boolean");
    }

    @Test
    void namedParametersAreRefusedWhateverTheirTemporalType() { // §3.11.17: native queries have positional parameters
        Query query = em.createNativeQuery("SELECT ?1");
        Calendar calendar = Calendar.getInstance();
        Date date = new Date();
        assertThatThrownBy(() -> query.setParameter("when", calendar, TemporalType.DATE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positional parameters only, not :when");
        assertThatThrownBy(() -> query.setParameter("when", date, TemporalType.TIMESTAMP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positional parameters only, not :when");
        assertThatThrownBy(() -> query.getParameterValue("when"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positional parameters only, not :when");
    }
}
