/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** §2.7–2.8 and §11.1.31: embedded values are not entities or single-valued embedded paths. */
class InverseElementCollectionTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private PersistenceConfiguration configuration(boolean xml, String fixture, Class<?>... types) {
        var configuration = new PersistenceConfiguration("inverse-elements")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL,
                "jdbc:h2:mem:inverse-elements-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create");
        for (var type : types) configuration.managedClass(type);
        if (xml) configuration.mappingFile("orm/inverse-elements-" + fixture + ".xml");
        return configuration;
    }

    private void refusesCollectionPath(boolean xml, String fixture, String path, Class<?>... types) {
        var ddl = new java.io.StringWriter();
        assertThatThrownBy(() -> {
            try (var ignored = configuration(xml, fixture, types)
                    .property("jakarta.persistence.schema-generation.scripts.action", "create")
                    .property("jakarta.persistence.schema-generation.scripts.create-target", ddl)
                    .createEntityManagerFactory()) {}
        }).isInstanceOf(PersistenceException.class)
            .hasStackTraceContaining("mappedBy").hasStackTraceContaining(path)
            .hasStackTraceContaining("element collection");
        assertThat(ddl.toString()).isEmpty();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void listPathIsRefusedBeforeSchemaOrLoading(boolean xml) {
        refusesCollectionPath(xml, "list", "values.target", ListOwner.class, ListTarget.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mapValuePathIsNotASingleEmbeddedAttribute(boolean xml) {
        refusesCollectionPath(xml, "map", "values.target", MapOwner.class, MapTarget.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nestedCollectionPathIsRefused(boolean xml) {
        refusesCollectionPath(xml, "nested", "details.values.target", NestedOwner.class, NestedTarget.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void uniqueElementForeignKeyDoesNotEstablishAnInverseEntityOneToOne(boolean xml) {
        refusesCollectionPath(xml, "exclusive", "values.target", ExclusiveOwner.class, ExclusiveTarget.class);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void relationshipCannotTargetAnEmbeddableInsteadOfItsContainingEntity(boolean xml) {
        assertThatThrownBy(() -> {
            try (var ignored = configuration(xml, "value-target", ValueTarget.class, ListValue.class)
                    .createEntityManagerFactory()) {}
        }).isInstanceOf(PersistenceException.class)
            .hasStackTraceContaining("value").hasStackTraceContaining("entity");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void collectionEmbeddableCannotBeTheInverseSide(boolean xml) {
        assertThatThrownBy(() -> {
            try (var ignored = configuration(xml, "inverse-value", InverseOwner.class,
                    InverseTarget.class).createEntityManagerFactory()) {}
        }).isInstanceOf(PersistenceException.class)
            .hasStackTraceContaining("owning").hasStackTraceContaining("collection");
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ordinarySingleEmbeddedMappedByStillLoadsEntityIdentities(boolean xml) {
        try (var factory = configuration(xml, "single", SingleOwner.class, SingleTarget.class)
                .createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var target = new SingleTarget();
            target.id = 10L;
            var owner = new SingleOwner();
            owner.id = 1L;
            owner.detail = new SingleValue();
            owner.detail.target = target;
            em.getTransaction().begin();
            em.persist(target);
            em.persist(owner);
            em.getTransaction().commit();
            em.clear();
            var loaded = em.find(SingleTarget.class, 10L);
            assertThat(loaded.owners).hasSize(1);
            assertThat(loaded.owners.getFirst()).isSameAs(em.find(SingleOwner.class, 1L));
            assertThat(loaded.owners.getFirst().detail.target).isSameAs(loaded);
        }
    }

    @Entity public static class ListOwner {
        @Id public Long id;
        @ElementCollection @OrderColumn public List<ListValue> values;
    }
    @Embeddable public static class ListValue { @ManyToOne public ListTarget target; }
    @Entity public static class ListTarget {
        @Id public Long id;
        @OneToMany(mappedBy = "values.target") public List<ListOwner> owners;
    }
    @Entity public static class MapOwner {
        @Id public Long id;
        @ElementCollection @MapKeyColumn public Map<String, MapValue> values;
    }
    @Embeddable public static class MapValue { @ManyToOne public MapTarget target; }
    @Entity public static class MapTarget {
        @Id public Long id;
        @OneToMany(mappedBy = "values.target") public List<MapOwner> owners;
    }
    @Entity public static class NestedOwner {
        @Id public Long id;
        @Embedded public Details details;
    }
    @Embeddable public static class Details { @ElementCollection public List<NestedValue> values; }
    @Embeddable public static class NestedValue { @ManyToOne public NestedTarget target; }
    @Entity public static class NestedTarget {
        @Id public Long id;
        @OneToMany(mappedBy = "details.values.target") public List<NestedOwner> owners;
    }
    @Entity public static class ExclusiveOwner {
        @Id public Long id;
        @ElementCollection public List<ExclusiveValue> values;
    }
    @Embeddable public static class ExclusiveValue { @OneToOne public ExclusiveTarget target; }
    @Entity public static class ExclusiveTarget {
        @Id public Long id;
        @OneToOne(mappedBy = "values.target") public ExclusiveOwner owner;
    }
    @Entity public static class ValueTarget {
        @Id public Long id;
        @OneToOne(mappedBy = "target") public ListValue value;
    }
    @Entity public static class InverseOwner {
        @Id public Long id;
        @ElementCollection public List<InverseValue> values;
    }
    @Embeddable public static class InverseValue {
        @OneToOne(mappedBy = "owner") public InverseTarget target;
    }
    @Entity public static class InverseTarget {
        @Id public Long id;
        @OneToOne public InverseOwner owner;
    }
    @Entity public static class SingleOwner {
        @Id public Long id;
        @Embedded public SingleValue detail;
    }
    @Embeddable public static class SingleValue { @ManyToOne public SingleTarget target; }
    @Entity public static class SingleTarget {
        @Id public Long id;
        @OneToMany(mappedBy = "detail.target") public List<SingleOwner> owners = new ArrayList<>();
    }
}
