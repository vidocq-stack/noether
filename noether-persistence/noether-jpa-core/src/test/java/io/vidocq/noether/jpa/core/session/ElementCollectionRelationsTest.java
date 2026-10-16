/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** §2.7: the collection row, not the owner entity or target, owns an element's to-one FK. */
class ElementCollectionRelationsTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private EntityManagerFactory factory(boolean xml, Class<?>... extra) {
        return configuration(xml, extra).createEntityManagerFactory();
    }

    private PersistenceConfiguration configuration(boolean xml, Class<?>... extra) {
        var configuration = new PersistenceConfiguration("element-relations")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Owner.class).managedClass(Value.class).managedClass(Target.class)
            .property(PersistenceConfiguration.JDBC_URL,
                "jdbc:h2:mem:element-relations-" + DATABASES.incrementAndGet() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create");
        if (xml) configuration.mappingFile("orm/element-collection-relations.xml");
        for (Class<?> type : extra) configuration.managedClass(type);
        return configuration;
    }

    private void transaction(EntityManager em, Runnable work) {
        em.getTransaction().begin();
        work.run();
        em.getTransaction().commit();
    }

    private Owner owner() {
        var owner = new Owner();
        owner.id = 1L;
        var target = new Target(10L, "first");
        owner.values.add(new Value("equal", target));
        owner.values.add(new Value("equal", target));
        owner.values.add(new Value("null", null));
        owner.bag.add(new Value("equal", target));
        owner.bag.add(new Value("equal", target));
        owner.keyed.put("a", new Value("equal", target));
        owner.keyed.put("b", new Value("equal", target));
        return owner;
    }

    private void roundTrip(boolean xml) {
        try (var factory = factory(xml); var em = factory.createEntityManager()) {
            var owner = owner();
            transaction(em, () -> em.persist(owner));
            em.clear();
            var found = em.find(Owner.class, 1L);
            assertThat(factory.getPersistenceUnitUtil().isLoaded(found, "values")).isFalse();
            assertThat(found.values).hasSize(3);
            assertThat(found.values.get(0)).isNotSameAs(found.values.get(1));
            assertThat(found.values.get(0).target).isSameAs(found.values.get(1).target)
                .isSameAs(em.find(Target.class, 10L));
            assertThat(found.values.get(2).target).isNull();
            assertThat(found.bag).hasSize(2);
            assertThat(found.keyed).containsOnlyKeys("a", "b");
            assertThat(found.keyed.get("a").target).isSameAs(found.values.get(0).target);
            transaction(em, () -> {
                found.values.get(1).target = new Target(20L, "second");
                found.keyed.remove("a");
                found.bag.remove(0);
            });
            assertThat(found.version).isEqualTo(1);
            transaction(em, () -> {});
            assertThat(found.version).isEqualTo(1);
            em.clear();
            var reloaded = em.find(Owner.class, 1L);
            assertThat(reloaded.values).extracting(v -> v.target == null ? null : v.target.id)
                .containsExactly(10L, 20L, null);
            assertThat(reloaded.bag).hasSize(1);
            assertThat(reloaded.keyed).containsOnlyKeys("b");
            transaction(em, () -> em.remove(reloaded));
            em.clear();
            assertThat(em.find(Owner.class, 1L)).isNull();
            assertThat(em.find(Target.class, 10L)).isNotNull();
            assertThat(em.find(Target.class, 20L)).isNotNull();
        }
    }

    @Test void annotationRowsKeepDuplicatesPositionsKeysAndManagedIdentity() { roundTrip(false); }
    @Test void metadataCompleteXmlAndValueOverridesHaveParity() { roundTrip(true); }

    @Test void mergeCopiesElementsAndMergesTheirTargets() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var detached = owner();
            transaction(em, () -> em.persist(detached));
            em.clear();
            detached.values.getFirst().target.name = "changed";
            em.getTransaction().begin();
            var merged = em.merge(detached);
            assertThat(merged.values.getFirst()).isNotSameAs(detached.values.getFirst());
            assertThat(merged.values.getFirst().target).isNotSameAs(detached.values.getFirst().target)
                .isSameAs(merged.keyed.get("a").target);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Target.class, 10L).name).isEqualTo("changed");
        }
    }

    @Test void refreshCascadesThroughLoadedElements() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = owner();
            transaction(em, () -> em.persist(owner));
            owner.values.getFirst().target.name = "discard";
            em.refresh(owner);
            assertThat(owner.values.getFirst().target.name).isEqualTo("first");
        }
    }

    @Test void refreshCascadeReachesPreviouslyManagedTargetsThroughUnloadedElements() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = new Owner();
            owner.id = 1L;
            owner.values.add(new Value("only-lazy", new Target(10L, "original")));
            transaction(em, () -> em.persist(owner));
            em.clear();
            var found = em.find(Owner.class, 1L);
            var target = em.find(Target.class, 10L);
            assertThat(factory.getPersistenceUnitUtil().isLoaded(found, "values")).isFalse();
            target.name = "discard";
            em.refresh(found);
            assertThat(target.name).isEqualTo("original");
            assertThat(found.values.getFirst().target).isSameAs(target);
        }
    }

    @Test void noCascadeReferenceIsResolvedDuringMergeAndTransientReferenceIsRejected() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = owner();
            var other = new Target(30L, "other");
            owner.values.getFirst().other = other;
            transaction(em, () -> { em.persist(other); em.persist(owner); });
            em.clear();
            transaction(em, () -> {
                var merged = em.merge(owner);
                assertThat(merged.values.getFirst().other).isSameAs(em.find(Target.class, 30L));
            });
            em.getTransaction().begin();
            em.find(Owner.class, 1L).values.getFirst().other = new Target(40L, "new");
            assertThatThrownBy(em::flush).isInstanceOf(IllegalStateException.class);
            em.getTransaction().rollback();
        }
    }

    @Test void basicCollectionJoinPathsRemainQueryableButAssociationNavigationIsExplicitlyRefused() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            transaction(em, () -> em.persist(owner()));
            assertThat(em.createQuery("select v.label from ElementRelationOwner o join o.values v order by v.label",
                String.class).getResultList()).containsExactly("equal", "equal", "null");
            var cb = em.getCriteriaBuilder();
            var query = cb.createQuery(String.class);
            var root = query.from(Owner.class);
            query.select(root.joinList("values").get("label"));
            assertThat(em.createQuery(query).getResultList()).hasSize(3);
            assertThatThrownBy(() -> em.createQuery(
                "select v.target.name from ElementRelationOwner o join o.values v").getResultList())
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> em.createQuery(
                "select v from ElementRelationOwner o join o.values v").getResultList())
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void inverseOneToOneIsRejectedAtBootstrap() {
        assertThatThrownBy(() -> factory(false, InvalidOwner.class, InverseValue.class))
            .hasStackTraceContaining("collection").hasStackTraceContaining("owning");
    }

    @Test void owningOneToOneIsUniqueAndCascadeRemoveDoesNotTreatElementsAsEntities() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = owner();
            owner.values.getFirst().exclusive = new Target(50L, "exclusive");
            transaction(em, () -> em.persist(owner));
            em.clear();
            var found = em.find(Owner.class, 1L);
            assertThat(found.values.getFirst().exclusive).isSameAs(em.find(Target.class, 50L));
            transaction(em, () -> found.values.removeFirst());
            assertThat(em.find(Target.class, 50L)).isNotNull(); // no orphanRemoval
            transaction(em, () -> found.values.getFirst().exclusive = em.find(Target.class, 50L));
            em.clear();
            transaction(em, () -> em.remove(em.find(Owner.class, 1L)));
            em.clear();
            assertThat(em.find(Target.class, 50L)).isNull();
            assertThat(em.find(Target.class, 10L)).isNotNull();
        }
    }

    @Test void uniqueOneToOneForeignKeyRejectsTwoRowsReferencingTheSameTarget() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = owner();
            var exclusive = new Target(50L, "exclusive");
            owner.values.get(0).exclusive = exclusive;
            owner.values.get(1).exclusive = exclusive;
            em.getTransaction().begin();
            em.persist(owner);
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
            em.getTransaction().rollback();
        }
    }

    @Test void nullRowsAndNullReferencesAndReferenceOnlyRowsKeepTheirPositions() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = new Owner();
            owner.id = 1L;
            owner.values.add(null);
            owner.values.add(new Value(null, new Target(10L, "target")));
            owner.values.add(null);
            transaction(em, () -> em.persist(owner));
            em.clear();
            var found = em.find(Owner.class, 1L);
            assertThat(found.values).hasSize(3);
            assertThat(found.values.get(0)).isNull();
            assertThat(found.values.get(1).target).isSameAs(em.find(Target.class, 10L));
            assertThat(found.values.get(2)).isNull();
        }
    }

    @Test void fetchJoinAndGraphsUseCompleteOrdinaryLoading() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            transaction(em, () -> em.persist(owner()));
            em.clear();
            var fetched = em.createQuery(
                "select distinct o from ElementRelationOwner o left join fetch o.values", Owner.class).getSingleResult();
            assertThat(factory.getPersistenceUnitUtil().isLoaded(fetched, "values")).isTrue();
            assertThat(fetched.values).hasSize(3);
            assertThat(fetched.values.getFirst().target).isSameAs(em.find(Target.class, 10L));
            em.clear();
            var graph = em.createEntityGraph(Owner.class);
            graph.addSubgraph("values").addAttributeNodes("target");
            var found = em.find(Owner.class, 1L, Map.of("jakarta.persistence.fetchgraph", graph));
            assertThat(found.values).hasSize(3);
            assertThat(found.values.getFirst().target).isSameAs(em.find(Target.class, 10L));
        }
    }

    @Test void duplicateBagRowsWithDifferentTargetsAreNotComparedByValueEquality() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = owner();
            owner.bag.get(1).target = new Target(20L, "second");
            transaction(em, () -> em.persist(owner));
            em.clear();
            var found = em.find(Owner.class, 1L);
            assertThat(found.bag).extracting(v -> v.target.id).containsExactlyInAnyOrder(10L, 20L);
            transaction(em, () -> found.bag.removeIf(v -> v.target.id == 10L));
            em.clear();
            assertThat(em.find(Owner.class, 1L).bag).extracting(v -> v.target.id).containsExactly(20L);
        }
    }

    @Test void schemaEmitsCollectionOwnedBigintForeignKeysAfterTablesAndXmlOverrideNames() {
        for (boolean xml : List.of(false, true)) {
            var ddl = new java.io.StringWriter();
            try (var factory = configuration(xml)
                .property("jakarta.persistence.schema-generation.scripts.action", "create")
                .property("jakarta.persistence.schema-generation.scripts.create-target", ddl)
                .createEntityManagerFactory()) {
                String script = ddl.toString().toLowerCase(java.util.Locale.ROOT);
                String target = xml ? "xml_target_id" : "target_id";
                String map = xml ? "xml_map_target" : "map_target";
                assertThat(script).contains(target + " bigint").contains(map + " bigint");
                assertThat(script).contains("foreign key (" + target + ") references elementrelationtarget (id)")
                    .contains("foreign key (" + map + ") references elementrelationtarget (id)");
                assertThat(script.indexOf("alter table element_relation_values"))
                    .isGreaterThan(script.lastIndexOf("create table"));
            }
        }
    }

    @Test void nestedRecordElementsUseGeneratedAccessAndRebuildReferencesDuringMerge() {
        try (var factory = factory(false, RecordOwner.class, RecordValue.class, NestedValue.class);
             var em = factory.createEntityManager()) {
            var owner = new RecordOwner();
            owner.id = 1L;
            owner.elements.add(new RecordValue("record", new NestedValue(new Target(70L, "nested"))));
            transaction(em, () -> em.persist(owner));
            em.clear();
            var found = em.find(RecordOwner.class, 1L);
            assertThat(found.elements.getFirst().nested().target()).isSameAs(em.find(Target.class, 70L));
            em.clear();
            transaction(em, () -> {
                var merged = em.merge(found);
                assertThat(merged.elements.getFirst().nested().target()).isSameAs(em.find(Target.class, 70L));
            });
        }
    }

    @Test void unsupportedCompositeBusinessMapsIdOrphanAndCollectionMappingsAreExplicitlyRefused() {
        for (Class<?> owner : List.of(CompositeOwner.class, BusinessOwner.class, MapsOwner.class,
                OrphanOwner.class, NestedCollectionOwner.class, ReadOnlyOwner.class, ToManyOwner.class, JoinTableOwner.class)) {
            assertThatThrownBy(() -> factory(false, owner, CompositeTarget.class, CompositeKey.class))
                .isInstanceOf(PersistenceException.class).hasStackTraceContaining("collection");
        }
    }

    @Test void lazyCollectionRemainsUnloadedAfterTargetOnlyChangesAndClosedAccessIsExplicit() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            transaction(em, () -> em.persist(owner()));
            em.clear();
            var found = em.find(Owner.class, 1L);
            transaction(em, () -> em.find(Target.class, 10L).name = "target-only");
            assertThat(found.version).isZero();
            assertThat(factory.getPersistenceUnitUtil().isLoaded(found, "values")).isFalse();
            em.clear();
            assertThatThrownBy(() -> found.values.size()).isInstanceOf(PersistenceException.class);
        }
    }

    @Test void collectionBatchSettingRetainsCompleteIdentityPreservingFallback() {
        try (var factory = configuration(false)
            .property("io.vidocq.noether.jpa.fetch.batch-size", 16).createEntityManagerFactory();
             var em = factory.createEntityManager()) {
            var first = owner();
            var second = new Owner();
            second.id = 2L;
            second.values.add(new Value("second", first.values.getFirst().target));
            transaction(em, () -> { em.persist(first); em.persist(second); });
            em.clear();
            var a = em.find(Owner.class, 1L);
            var b = em.find(Owner.class, 2L);
            assertThat(a.values).hasSize(3);
            assertThat(b.values).hasSize(1);
            assertThat(a.values.getFirst().target).isSameAs(b.values.getFirst().target);
        }
    }

    @Test void sharedCacheRestoresDistinctElementOccurrencesAndOneTargetIdentity() {
        try (var factory = configuration(false).sharedCacheMode(SharedCacheMode.ALL).createEntityManagerFactory()) {
            try (var em = factory.createEntityManager()) {
                transaction(em, () -> em.persist(owner()));
                em.clear();
                assertThat(em.find(Owner.class, 1L).values).hasSize(3);
            }
            try (var em = factory.createEntityManager()) {
                var found = em.find(Owner.class, 1L);
                assertThat(found.values).hasSize(3);
                assertThat(found.values.get(0)).isNotSameAs(found.values.get(1));
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target)
                    .isSameAs(em.find(Target.class, 10L));
                transaction(em, () -> found.values.getFirst().target = new Target(20L, "second"));
            }
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(Owner.class, 1L).values).extracting(v -> v.target == null ? null : v.target.id)
                    .containsExactly(20L, 10L, null);
            }
        }
    }

    @Test void detachCascadeReachesTargetsOfLoadedElements() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            var owner = owner();
            owner.values.getFirst().exclusive = new Target(50L, "exclusive");
            transaction(em, () -> em.persist(owner));
            var target = owner.values.getFirst().exclusive;
            em.detach(owner);
            assertThat(em.contains(target)).isFalse();
            assertThat(em.contains(owner.values.getFirst().target)).isTrue();
        }
    }

    @Test void generatedTargetsExistBeforeCollectionRowsAndFlushCascadesNewElements() {
        try (var factory = factory(false, GeneratedOwner.class, RequiredValue.class, GeneratedTarget.class);
             var em = factory.createEntityManager()) {
            var owner = new GeneratedOwner();
            owner.id = 1L;
            owner.elements.add(new RequiredValue());
            transaction(em, () -> em.persist(owner));
            assertThat(owner.elements.getFirst().target.id).isPositive();
            em.clear();
            var found = em.find(GeneratedOwner.class, 1L);
            transaction(em, () -> found.elements.add(new RequiredValue()));
            em.clear();
            assertThat(em.find(GeneratedOwner.class, 1L).elements).hasSize(2);
        }
    }

    @Test void optionalFalseAndJoinNullableFalseAreEnforcedByTheCollectionTable() {
        try (var factory = factory(false, GeneratedOwner.class, RequiredValue.class, GeneratedTarget.class);
             var em = factory.createEntityManager()) {
            var owner = new GeneratedOwner();
            owner.id = 1L;
            var value = new RequiredValue();
            value.target = null;
            owner.elements.add(value);
            em.getTransaction().begin();
            em.persist(owner);
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class);
            em.getTransaction().rollback();
        }
    }

    @Test void storedMapKeysAndListPositionsAreQueryableWithoutEntityIdentityForTheElements() {
        try (var factory = factory(false); var em = factory.createEntityManager()) {
            transaction(em, () -> em.persist(owner()));
            assertThat(em.createQuery(
                "select key(v) from ElementRelationOwner o join o.keyed v order by key(v)", String.class)
                .getResultList()).containsExactly("a", "b");
            assertThat(em.createQuery(
                "select index(v) from ElementRelationOwner o join o.values v order by index(v)", Integer.class)
                .getResultList()).containsExactly(0, 1, 2);
        }
    }

    @Test void unsupportedOwnerKeysAndIndexesFailInsteadOfSilentlyDroppingRelationships() {
        for (Class<?> owner : List.of(DerivedOwner.class, InvalidIndexOwner.class)) {
            assertThatThrownBy(() -> factory(false, owner))
                .isInstanceOf(PersistenceException.class).hasStackTraceContaining("element collection");
        }
    }

    @Entity(name = "ElementRelationOwner")
    public static class Owner {
        @Id public Long id;
        @Version public int version;
        @ElementCollection @OrderColumn(name = "position")
        @CollectionTable(name = "element_relation_values", joinColumns = @JoinColumn(name = "owner_id"))
        public List<Value> values = new ArrayList<>();
        @ElementCollection(fetch = FetchType.EAGER)
        @CollectionTable(name = "element_relation_bag", joinColumns = @JoinColumn(name = "owner_id"))
        public List<Value> bag = new ArrayList<>();
        @ElementCollection @MapKeyColumn(name = "entry_key")
        @CollectionTable(name = "element_relation_map", joinColumns = @JoinColumn(name = "owner_id"))
        @AssociationOverride(name = "value.target", joinColumns = @JoinColumn(name = "map_target"))
        public Map<String, Value> keyed = new LinkedHashMap<>();
    }

    @Embeddable
    public static class Value {
        public String label;
        @ManyToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE, CascadeType.REFRESH}, fetch = FetchType.LAZY)
        @JoinColumn(name = "target_id")
        public Target target;
        @ManyToOne @JoinColumn(name = "other_id") public Target other;
        @OneToOne(cascade = CascadeType.ALL) @JoinColumn(name = "exclusive_id") public Target exclusive;
        public Value() {}
        Value(String label, Target target) { this.label = label; this.target = target; }
        @Override public boolean equals(Object other) { return other instanceof Value v && java.util.Objects.equals(label, v.label); }
        @Override public int hashCode() { return java.util.Objects.hashCode(label); }
    }

    @Entity(name = "ElementRelationTarget")
    public static class Target {
        @Id public Long id;
        public String name;
        public Target() {}
        Target(Long id, String name) { this.id = id; this.name = name; }
    }

    @Entity public static class InvalidOwner {
        @Id public Long id;
        @ElementCollection public List<InverseValue> elements = new ArrayList<>();
    }
    @Embeddable public static class InverseValue {
        @OneToOne(mappedBy = "other") public Target target;
    }

    @Entity public static class RecordOwner {
        @Id public Long id;
        @ElementCollection @OrderColumn public List<RecordValue> elements = new ArrayList<>();
    }
    @Embeddable public record RecordValue(String label, @Embedded NestedValue nested) {}
    @Embeddable public record NestedValue(
        @ManyToOne(cascade = CascadeType.ALL) @JoinColumn(name = "nested_target") Target target) {}
    @Entity public static class CompositeOwner {
        @Id public Long id;
        @ElementCollection public List<CompositeValue> elements;
    }
    @Embeddable public static class CompositeValue { @ManyToOne public CompositeTarget target; }
    @Entity public static class CompositeTarget { @EmbeddedId public CompositeKey id; }
    @Embeddable public static class CompositeKey implements java.io.Serializable {
        public long first;
        public long second;
    }
    @Entity public static class BusinessOwner {
        @Id public Long id;
        @ElementCollection public List<BusinessValue> elements;
    }
    @Embeddable public static class BusinessValue {
        @ManyToOne @JoinColumn(referencedColumnName = "name") public Target target;
    }
    @Entity public static class MapsOwner {
        @Id public Long id;
        @ElementCollection public List<MapsValue> elements;
    }
    @Embeddable public static class MapsValue { @MapsId @ManyToOne public Target target; }
    @Entity public static class OrphanOwner {
        @Id public Long id;
        @ElementCollection public List<OrphanValue> elements;
    }
    @Embeddable public static class OrphanValue { @OneToOne(orphanRemoval = true) public Target target; }
    @Entity public static class NestedCollectionOwner {
        @Id public Long id;
        @ElementCollection public List<NestedCollectionValue> elements;
    }
    @Embeddable public static class NestedCollectionValue { @ElementCollection public List<String> names; }
    @Entity public static class ReadOnlyOwner {
        @Id public Long id;
        @ElementCollection public List<ReadOnlyValue> elements;
    }
    @Embeddable public static class ReadOnlyValue {
        @ManyToOne @JoinColumn(insertable = false, updatable = false) public Target target;
    }
    @Entity public static class ToManyOwner {
        @Id public Long id;
        @ElementCollection public List<ToManyValue> elements;
    }
    @Embeddable public static class ToManyValue { @OneToMany public List<Target> targets; }
    @Entity public static class JoinTableOwner {
        @Id public Long id;
        @ElementCollection public List<JoinTableValue> elements;
    }
    @Embeddable public static class JoinTableValue {
        @ManyToOne @JoinTable(name = "unsupported_element_join") public Target target;
    }
    @Entity public static class GeneratedOwner {
        @Id public Long id;
        @ElementCollection @OrderColumn public List<RequiredValue> elements = new ArrayList<>();
    }
    @Embeddable public static class RequiredValue {
        @ManyToOne(cascade = CascadeType.ALL, optional = false)
        @JoinColumn(name = "required_target", nullable = false) public GeneratedTarget target = new GeneratedTarget();
    }
    @Entity public static class GeneratedTarget {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
    }
    @Entity public static class DerivedOwner {
        @Id @ManyToOne public Target parent;
        @ElementCollection public List<Value> elements;
    }
    @Entity public static class InvalidIndexOwner {
        @Id public Long id;
        @ElementCollection @MapKey(name = "label") public Map<String, Value> elements;
    }
}
