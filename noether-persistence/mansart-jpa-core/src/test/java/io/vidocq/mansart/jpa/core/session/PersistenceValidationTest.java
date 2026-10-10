/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.validation.Validation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, §3.7: callback validation, configured groups and factory ownership. */
class PersistenceValidationTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private EntityManagerFactory emf;
    private Connection database;

    @AfterEach
    void close() throws Exception {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void defaultRemoveGroupsAndExplicitEmptyGroupsDoNotValidate() throws Exception {
        String url = "jdbc:h2:mem:validation-default-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        createSchema();
        emf = factory(url, java.util.Map.of());
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            ValidatedEntry invalid = new ValidatedEntry(69L, "valid");
            invalid.defaultValue = null;
            assertThatThrownBy(() -> em.persist(invalid)).isInstanceOf(ConstraintViolationException.class);
            assertThat(em.getTransaction().getRollbackOnly()).isTrue();
            em.getTransaction().rollback();
            em.getTransaction().begin();
            ValidatedEntry entry = new ValidatedEntry(70L, "valid");
            em.persist(entry);
            em.getTransaction().commit();
            em.getTransaction().begin();
            entry.defaultValue = null;
            entry.name = "updated";
            assertThatThrownBy(em::flush).isInstanceOf(ConstraintViolationException.class);
            assertThat(em.getTransaction().getRollbackOnly()).isTrue();
            em.getTransaction().rollback();
            em.getTransaction().begin();
            entry = em.find(ValidatedEntry.class, 70L);
            entry.defaultValue = null;
            em.remove(entry);
            em.getTransaction().commit();
        }
        emf.close();
        emf = factory(url, java.util.Map.of(
            "jakarta.persistence.validation.group.pre-persist", "",
            "jakarta.persistence.validation.group.pre-update", new Class<?>[0],
            "jakarta.persistence.validation.group.pre-remove", java.util.List.of()));
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            ValidatedEntry entry = new ValidatedEntry(71L, null);
            entry.defaultValue = null;
            em.persist(entry);
            em.getTransaction().commit();
            em.getTransaction().begin();
            entry.name = "updated";
            em.flush();
            em.remove(entry);
            em.getTransaction().commit();
        }
    }

    @Test
    void resolvesCommaSeparatedGroupsAndRejectsInvalidGroupsAtBootstrap() throws Exception {
        String url = "jdbc:h2:mem:validation-strings-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        createSchema();
        emf = factory(url, java.util.Map.of("jakarta.persistence.validation.group.pre-persist",
            " " + PersistChecks.class.getName() + ", jakarta.validation.groups.Default "));
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            assertThatThrownBy(() -> em.persist(new ValidatedEntry(72L, null)))
                .isInstanceOf(ConstraintViolationException.class);
            em.getTransaction().rollback();
        }
        emf.close();
        assertThatThrownBy(() -> factory(url, java.util.Map.of(
            "jakarta.persistence.validation.group.pre-remove", "missing.Group")))
            .isInstanceOf(jakarta.persistence.PersistenceException.class);
        assertThatThrownBy(() -> factory(url, java.util.Map.of(
            "jakarta.persistence.validation.group.pre-update", String.class)))
            .isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    @Test
    void validatesPrePersistUpdateAndRemoveUsingConfiguredGroupsAndRollsBackOnViolation() throws Exception {
        String url = "jdbc:h2:mem:validation-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        createSchema();
        emf = factory(url, MapBuilder.group(PersistChecks.class));
        EntityManager em = emf.createEntityManager();

        em.getTransaction().begin();
        assertThatThrownBy(() -> em.persist(new ValidatedEntry(1L, null)))
            .isInstanceOf(ConstraintViolationException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();

        em.getTransaction().begin();
        em.persist(new ValidatedEntry(2L, "valid"));
        em.getTransaction().commit();

        em.clear();
        ValidatedEntry loaded = em.find(ValidatedEntry.class, 2L);
        em.getTransaction().begin();
        loaded.name = null;
        assertThatThrownBy(em::flush).isInstanceOf(ConstraintViolationException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();

        em.getTransaction().begin();
        ValidatedEntry removal = new ValidatedEntry(3L, "valid");
        em.persist(removal);
        em.getTransaction().commit();
        em.getTransaction().begin();
        removal.name = null;
        assertThatThrownBy(() -> em.remove(removal)).isInstanceOf(ConstraintViolationException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
        em.close();
    }

    @Test
    void aSuppliedValidatorFactoryRemainsOwnedByItsCaller() throws Exception {
        String url = "jdbc:h2:mem:validation-owned-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        createSchema();
        ValidatorFactory supplied = Validation.buildDefaultValidatorFactory();
        emf = factory(url, MapBuilder.factory(supplied));
        emf.close();
        assertThat(supplied.getValidator()).isNotNull();
        supplied.close();
    }

    @Test
    void validatesAssociationPropertyButDoesNotCascadeIntoAssociatedEntity() throws Exception {
        String url = "jdbc:h2:mem:validation-association-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        createSchema();
        emf = factory(url, MapBuilder.group(AssociationChecks.class));
        EntityManager em = emf.createEntityManager();
        try (Statement insert = database.createStatement()) {
            insert.executeUpdate("insert into ValidationRelated (id, name) values (40, null)");
        }
        em.getTransaction().begin();
        ValidationRelated related = em.find(ValidationRelated.class, 40L);
        em.persist(new ValidatedEntry(4L, "valid", related));
        em.getTransaction().commit();

        em.getTransaction().begin();
        assertThatThrownBy(() -> em.persist(new ValidatedEntry(5L, "valid", null)))
            .isInstanceOf(ConstraintViolationException.class);
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
        em.close();
    }

    private EntityManagerFactory factory(String url, java.util.Map<String, Object> validationProperties) {
        PersistenceConfiguration configuration = new PersistenceConfiguration("validation")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(ValidatedEntry.class).managedClass(ValidationRelated.class)
            .property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .validationMode(jakarta.persistence.ValidationMode.CALLBACK);
        validationProperties.forEach(configuration::property);
        return configuration.createEntityManagerFactory();
    }

    private void createSchema() throws Exception {
        try (Statement ddl = database.createStatement()) {
            ddl.execute("create table ValidatedEntry (id bigint primary key, name varchar(100), related_id bigint)");
            ddl.execute("create table ValidationRelated (id bigint primary key, name varchar(100))");
        }
    }

    @Entity
    public static class ValidatedEntry {
        @Id
        public Long id;
        @NotNull(groups = PersistChecks.class)
        public String name;
        @NotNull
        @jakarta.persistence.Transient
        public String defaultValue = "valid";
        @NotNull(groups = AssociationChecks.class)
        @Valid
        @ManyToOne
        public ValidationRelated related;

        public ValidatedEntry() {
        }

        ValidatedEntry(Long id, String name) {
            this(id, name, null);
        }

        ValidatedEntry(Long id, String name, ValidationRelated related) {
            this.id = id;
            this.name = name;
            this.related = related;
        }
    }

    @Entity
    public static class ValidationRelated {
        @Id
        public Long id;
        @NotNull(groups = AssociationChecks.class)
        public String name;

        public ValidationRelated() {
        }

        ValidationRelated(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    interface PersistChecks {
    }

    interface AssociationChecks {
    }

    private static final class MapBuilder {
        static java.util.Map<String, Object> group(Class<?> group) {
            return java.util.Map.of("jakarta.persistence.validation.group.pre-persist",
                new Class<?>[] { group }, "jakarta.persistence.validation.group.pre-update",
                new Class<?>[] { group }, "jakarta.persistence.validation.group.pre-remove",
                new Class<?>[] { group });
        }

        static java.util.Map<String, Object> factory(ValidatorFactory factory) {
            return java.util.Map.of("jakarta.persistence.validation.factory", factory);
        }
    }
}
