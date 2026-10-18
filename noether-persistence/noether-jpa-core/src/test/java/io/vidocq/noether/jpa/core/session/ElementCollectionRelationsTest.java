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

    @Test void associationNavigationAndProjectionsWorkFromCollectionEmbeddables() {
        for (boolean xml : List.of(false, true)) {
            try (var factory = factory(xml); var em = factory.createEntityManager()) {
                transaction(em, () -> em.persist(owner()));
                assertThat(em.createQuery("select v.label from ElementRelationOwner o join o.values v order by v.label",
                    String.class).getResultList()).containsExactly("equal", "equal", "null");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(String.class);
                var root = query.from(Owner.class);
                query.select(root.joinList("values").get("label"));
                assertThat(em.createQuery(query).getResultList()).hasSize(3);
                assertThat(em.createQuery(
                    "select v.target.name from ElementRelationOwner o join o.values v order by v.target.name", String.class)
                    .getResultList()).containsExactly("first", "first");
                assertThat(em.createQuery(
                    "select v.target from ElementRelationOwner o join o.values v order by v.label", Target.class)
                    .getResultList()).containsExactly(em.find(Target.class, 10L), em.find(Target.class, 10L), null);
                assertThat(em.createQuery(
                    "select t.name from ElementRelationOwner o join o.values v join v.target t order by t.name", String.class)
                    .getResultList()).containsExactly("first", "first");
                assertThat(em.createQuery(
                    "select t.name from ElementRelationOwner o join o.values v left join v.target t", String.class)
                    .getResultList()).containsExactlyInAnyOrder("first", "first", null);
                assertThat(em.createQuery(
                    "select v.label from ElementRelationOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", em.find(Target.class, 10L)).getResultList()).containsExactly("equal", "equal");
                var targetProjection = cb.createQuery(Target.class);
                var targetRoot = targetProjection.from(Owner.class);
                targetProjection.select(targetRoot.joinList("values").get("target"));
                assertThat(em.createQuery(targetProjection).getResultList()).hasSize(3).containsNull();
                var navigation = cb.createQuery(String.class);
                var navigationRoot = navigation.from(Owner.class);
                navigation.select(navigationRoot.joinList("values").get("target").get("name"));
                assertThat(em.createQuery(navigation).getResultList()).containsExactly("first", "first");
                var explicitJoin = cb.createQuery(String.class);
                var joinRoot = explicitJoin.from(Owner.class);
                var element = joinRoot.joinList("values");
                explicitJoin.select(element.join("target").get("name"));
                assertThat(em.createQuery(explicitJoin).getResultList()).containsExactly("first", "first");
                var projected = em.createQuery(
                    "select v from ElementRelationOwner o join o.values v order by v.label", Value.class).getResultList();
                assertThat(projected).extracting(value -> value.label).containsExactly("equal", "equal", "null");
                assertThat(projected).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(10L, 10L, null);
                assertThat(projected.get(0)).isNotSameAs(projected.get(1));
                assertThat(projected.get(0).target).isSameAs(projected.get(1).target).isSameAs(em.find(Target.class, 10L));
                var embeddedProjection = cb.createQuery(Value.class);
                var embeddedRoot = embeddedProjection.from(Owner.class);
                embeddedProjection.select(embeddedRoot.joinList("values"));
                var criteriaValues = em.createQuery(embeddedProjection).getResultList();
                assertThat(criteriaValues).hasSize(3);
                assertThat(criteriaValues.get(0).target).isSameAs(em.find(Target.class, 10L));
                assertThat(criteriaValues.get(2).target).isNull();
                assertThat(em.createQuery(
                    "select v from ElementRelationOwner o join o.keyed v", Value.class).getResultList())
                    .hasSize(2).allSatisfy(value -> assertThat(value.target).isSameAs(em.find(Target.class, 10L)));
            }
        }
    }

    @Test void associationNavigationSupportsNestedCollectionEmbeddables() {
        try (var factory = factory(false, RecordOwner.class, RecordValue.class, NestedValue.class);
                var em = factory.createEntityManager()) {
            var owner = new RecordOwner();
            owner.id = 2L;
            owner.elements.add(new RecordValue("nested", new NestedValue(new Target(70L, "record target"))));
            transaction(em, () -> em.persist(owner));
            assertThat(em.createQuery(
                "select e.nested.target.name from RecordOwner r join r.elements e", String.class).getResultList())
                .containsExactly("record target");
            assertThat(em.createQuery(
                "select e.nested.target from RecordOwner r join r.elements e", Target.class).getResultList())
                .containsExactly(em.find(Target.class, 70L));
            var projected = em.createQuery(
                "select e from RecordOwner r join r.elements e", RecordValue.class).getSingleResult();
            assertThat(projected).isEqualTo(owner.elements.getFirst());
            assertThat(projected).isNotSameAs(owner.elements.getFirst());
            assertThat(projected.nested().target()).isSameAs(em.find(Target.class, 70L));
        }
    }

    @Test void compositeTargetIdentityIsPreservedInElementCollectionRowsAndQueries() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, CompositeOwner.class, CompositeValue.class, CompositeTarget.class,
                CompositeKey.class, IdClassTarget.class, IdClassKey.class, IdClassValue.class);
            if (xml) configuration.mappingFile("orm/element-collection-composite.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var target = new CompositeTarget();
                target.id = new CompositeKey(3, 19);
                var idClassTarget = new IdClassTarget();
                idClassTarget.region = 7;
                idClassTarget.serial = 23;
                var owner = new CompositeOwner();
                owner.id = 5L;
                var value = new CompositeValue();
                value.label = "embedded";
                value.target = target;
                value.oneTarget = target;
                var nullValue = new CompositeValue();
                nullValue.label = "null";
                owner.elements = new ArrayList<>(List.of(value, nullValue));
                var idClassValue = new IdClassValue();
                idClassValue.target = idClassTarget;
                owner.idClassElements = new ArrayList<>(List.of(idClassValue));

                transaction(em, () -> {
                    em.persist(target);
                    em.persist(idClassTarget);
                    em.persist(owner);
                });
                em.clear();

                var found = em.find(CompositeOwner.class, 5L);
                var managedTarget = em.find(CompositeTarget.class, new CompositeKey(3, 19));
                assertThat(found.elements).hasSize(2);
                assertThat(found.elements.getFirst().target).isSameAs(managedTarget);
                assertThat(found.elements.getFirst().oneTarget).isSameAs(managedTarget);
                assertThat(found.elements.getLast().target).isNull();
                assertThat(found.elements.getLast().oneTarget).isNull();
                assertThat(em.createQuery(
                    "select v.target from CompositeOwner o join o.elements v order by v.label", CompositeTarget.class)
                    .getResultList()).containsExactly(managedTarget, null);
                assertThat(em.createQuery(
                    "select v.target.id.serial from CompositeOwner o join o.elements v", Long.class).getResultList())
                    .containsExactly(19L);
                var projection = em.createQuery(
                    "select v from CompositeOwner o join o.elements v order by v.label", CompositeValue.class).getResultList();
                assertThat(projection).hasSize(2);
                assertThat(projection.getFirst().target).isSameAs(managedTarget);
                assertThat(projection.getFirst().oneTarget).isSameAs(managedTarget);
                assertThat(projection.getLast().target).isNull();
                var managedIdClassTarget = em.find(IdClassTarget.class, new IdClassKey(7, 23));
                assertThat(em.find(CompositeOwner.class, 5L).idClassElements.getFirst().target).isSameAs(managedIdClassTarget);
                assertThat(em.createQuery(
                    "select v.target from CompositeOwner o join o.idClassElements v", IdClassTarget.class).getSingleResult())
                    .isSameAs(managedIdClassTarget);
                if (xml) {
                    transaction(em, () -> em.createNativeQuery(
                        "UPDATE composite_target_elements SET target_serial = NULL WHERE owner_id = 5").executeUpdate());
                    em.clear();
                    assertThatThrownBy(() -> em.find(CompositeOwner.class, 5L).elements.size())
                        .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
                }
            }
        }
    }

    // §2.4.2 and §2.7: an element-row FK may target a simple identity derived through MapsId.
    @Test void derivedSimpleIdentityTargetIsPreservedInElementCollectionRowsAndQueries() throws Exception {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, MapsIdElementOwner.class, MapsIdElementValue.class,
                MapsIdElementTarget.class, MapsIdElementParent.class);
            if (xml) configuration.mappingFile("orm/element-collection-mapsid.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var target = new MapsIdElementTarget();
                target.parent = new MapsIdElementParent("parent");
                var owner = new MapsIdElementOwner();
                owner.id = 6L;
                owner.values = new ArrayList<>(List.of(new MapsIdElementValue(target)));

                transaction(em, () -> {
                    em.persist(target);
                    em.persist(owner);
                });
                assertThat(target.id).isEqualTo(target.parent.id).isPositive();
                assertThat(em.createNativeQuery(
                    "select target_fk from derived_owner_values where owner_id = 6").getSingleResult())
                    .isEqualTo(target.id);

                em.clear();
                var found = em.find(MapsIdElementOwner.class, 6L);
                var managedTarget = em.find(MapsIdElementTarget.class, target.id);
                assertThat(found.values).hasSize(1);
                assertThat(found.values.getFirst().target).isSameAs(managedTarget);
                assertThat(managedTarget.parent).isSameAs(em.find(MapsIdElementParent.class, target.id));
                assertThat(em.createQuery(
                    "select v.target from MapsIdElementOwner o join o.values v", MapsIdElementTarget.class).getSingleResult())
                    .isSameAs(managedTarget);
            }
        }
    }

    // §2.4.2 and §2.7: a directly derived EmbeddedId component stays in the element FK tuple.
    @Test void derivedEmbeddedIdentityTargetIsPreservedInElementCollectionRowsAndQueries() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, DerivedCompositeOwner.class, DerivedCompositeValue.class,
                DerivedCompositeTarget.class, DerivedCompositeKey.class, MapsIdElementParent.class);
            if (xml) configuration.mappingFile("orm/element-collection-composite-mapsid.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var target = new DerivedCompositeTarget();
                target.id = new DerivedCompositeKey(0, "derived-key");
                target.parent = new MapsIdElementParent("parent");
                var owner = new DerivedCompositeOwner();
                owner.id = 8L;
                var value = new DerivedCompositeValue();
                value.target = target;
                owner.values = new ArrayList<>(List.of(value));

                transaction(em, () -> {
                    em.persist(target.parent);
                    em.persist(target);
                    em.persist(owner);
                });
                assertThat(target.id.parentId).isEqualTo(target.parent.id).isPositive();
                Object[] row = (Object[]) em.createNativeQuery(
                    "select derived_parent_fk, derived_code from derived_composite_values where owner_id = 8")
                    .getSingleResult();
                assertThat(row).containsExactly(target.parent.id, "derived-key");

                em.clear();
                var managedTarget = em.find(DerivedCompositeTarget.class,
                    new DerivedCompositeKey(target.parent.id, "derived-key"));
                var found = em.find(DerivedCompositeOwner.class, 8L);
                assertThat(found.values.getFirst().target).isSameAs(managedTarget);
                assertThat(managedTarget.parent).isSameAs(em.find(MapsIdElementParent.class, target.parent.id));
                assertThat(em.createQuery(
                    "select v.target.id.code from DerivedCompositeOwner o join o.values v", String.class)
                    .getSingleResult()).isEqualTo("derived-key");
                assertThat(em.createQuery(
                    "select v.target from DerivedCompositeOwner o join o.values v", DerivedCompositeTarget.class)
                    .getSingleResult()).isSameAs(managedTarget);
            }
        }
    }

    // §2.4.1.3 example 1a and §2.7: an IdClass relationship identifier is part of the row FK tuple.
    @Test void derivedIdClassIdentityTargetIsPreservedInElementCollectionRowsAndQueries() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, IdClassDerivedOwner.class, IdClassDerivedValue.class,
                IdClassDerivedTarget.class, IdClassDerivedKey.class, MapsIdElementParent.class);
            if (xml) configuration.mappingFile("orm/element-collection-idclass-derived.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var parent = new MapsIdElementParent("parent");
                var target = new IdClassDerivedTarget();
                target.parent = parent;
                target.code = "idclass-key";
                var owner = new IdClassDerivedOwner();
                owner.id = 9L;
                var value = new IdClassDerivedValue();
                value.target = target;
                owner.values = new ArrayList<>(List.of(value));

                transaction(em, () -> {
                    em.persist(parent);
                    em.persist(target);
                    em.persist(owner);
                });
                Object[] row = (Object[]) em.createNativeQuery(
                    "select derived_parent_fk, derived_code from idclass_derived_values where owner_id = 9")
                    .getSingleResult();
                assertThat(row).containsExactly(parent.id, "idclass-key");

                em.clear();
                var managedTarget = em.find(IdClassDerivedTarget.class, new IdClassDerivedKey(parent.id, "idclass-key"));
                var found = em.find(IdClassDerivedOwner.class, 9L);
                assertThat(found.values.getFirst().target).isSameAs(managedTarget);
                assertThat(managedTarget.parent).isSameAs(em.find(MapsIdElementParent.class, parent.id));
                assertThat(em.createQuery(
                    "select v.target.code from IdClassDerivedOwner o join o.values v", String.class)
                    .getSingleResult()).isEqualTo("idclass-key");
                assertThat(em.createQuery(
                    "select v.target from IdClassDerivedOwner o join o.values v", IdClassDerivedTarget.class)
                    .getSingleResult()).isSameAs(managedTarget);
            }
        }
    }

    // §2.4.2 and §2.7: each relationship-valued IdClass part contributes its parent's identity to the element FK.
    @Test void multipleIdClassParentIdentitiesKeepPhysicalTuplesAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, MultiParentOwner.class, MultiParentValue.class,
                MultiParentTarget.class, MultiParentKey.class, MapsIdElementParent.class);
            if (xml) configuration.mappingFile("orm/element-collection-multiple-idclass-parents.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var source = new MapsIdElementParent("source");
                var destination = new MapsIdElementParent("destination");
                var outward = new MultiParentTarget(source, destination, "route");
                var inward = new MultiParentTarget(destination, source, "route");
                var owner = new MultiParentOwner();
                owner.id = 10L;
                owner.values.add(new MultiParentValue("first", outward));
                owner.values.add(new MultiParentValue("duplicate", outward));
                owner.values.add(new MultiParentValue("null", null));
                owner.values.add(new MultiParentValue("reverse", inward));
                transaction(em, () -> {
                    em.persist(source);
                    em.persist(destination);
                    em.persist(outward);
                    em.persist(inward);
                    em.persist(owner);
                });
                assertThat(source.id).isPositive().isNotEqualTo(destination.id);
                var outwardKey = new MultiParentKey(source.id, destination.id, "route");
                var inwardKey = new MultiParentKey(destination.id, source.id, "route");
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(outward)).isEqualTo(outwardKey);
                List<?> rows = em.createNativeQuery(
                    "select element_source, element_destination, element_code from multi_parent_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.get(0)).containsExactly(source.id, destination.id, "route");
                assertThat((Object[]) rows.get(1)).containsExactly(source.id, destination.id, "route");
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly(destination.id, source.id, "route");
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into multi_parent_values (owner_id, value_position, element_source, element_destination, element_code)"
                        + " values (10, 4, ?, ?, 'missing')")
                    .setParameter(1, source.id).setParameter(2, destination.id).executeUpdate())
                    .isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();

                em.clear();
                factory.getCache().evictAll();
                var found = em.find(MultiParentOwner.class, 10L);
                var managedOutward = em.find(MultiParentTarget.class, outwardKey);
                var managedInward = em.find(MultiParentTarget.class, inwardKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedOutward, managedOutward, null, managedInward);
                assertThat(found.values.get(0).target).isSameAs(found.values.get(1).target).isSameAs(managedOutward);
                assertThat(managedOutward.source).isSameAs(managedInward.destination);
                assertThat(managedOutward.destination).isSameAs(managedInward.source);
                assertThat(em.createQuery(
                    "select v.target from MultiParentOwner o join o.values v order by index(v)", MultiParentTarget.class)
                    .getResultList()).containsExactly(managedOutward, managedOutward, null, managedInward);
                assertThat(em.createQuery(
                    "select v.target.source.name from MultiParentOwner o join o.values v order by index(v)", String.class)
                    .getResultList()).containsExactly("source", "source", "destination");
                assertThat(em.createQuery(
                    "select v.label from MultiParentOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedInward).getResultList()).containsExactly("reverse");
                var cb = em.getCriteriaBuilder();
                var criteria = cb.createQuery(MultiParentTarget.class);
                var values = criteria.from(MultiParentOwner.class).joinList("values");
                criteria.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(criteria).getResultList())
                    .containsExactly(managedOutward, managedOutward, null, managedInward);
                var projected = em.createQuery(
                    "select v from MultiParentOwner o join o.values v order by index(v)", MultiParentValue.class)
                    .getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedOutward);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedInward;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(MultiParentOwner.class, 10L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null
                    : factory.getPersistenceUnitUtil().getIdentifier(value.target))
                    .containsExactly(inwardKey, null, inwardKey);
                em.clear();
                reloaded.values.getFirst().target = outward;
                transaction(em, () -> {
                    var merged = em.merge(reloaded);
                    assertThat(merged.values.getFirst().target).isSameAs(em.find(MultiParentTarget.class, outwardKey));
                });
                em.clear();
                transaction(em, () -> em.remove(em.find(MultiParentOwner.class, 10L)));
                assertThat(em.find(MultiParentTarget.class, outwardKey)).isNotNull();
                assertThat(em.find(MultiParentTarget.class, inwardKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from multi_parent_values").getSingleResult())
                    .isEqualTo(0L);

                var partial = new MultiParentOwner();
                partial.id = 11L;
                partial.values.add(new MultiParentValue("partial", em.find(MultiParentTarget.class, outwardKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update multi_parent_values set element_destination = null where owner_id = 11")
                    .executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(MultiParentOwner.class, 11L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    // §2.4.2.1 and §2.8: relationship-named IdClass fields use the composite parent key types.
    @Test void compositeIdClassParentsKeepFlattenedElementKeysAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, CompositeLinkOwner.class, CompositeLinkValue.class,
                CompositeLinkTarget.class, CompositeLinkKey.class, CompositeTarget.class, CompositeKey.class,
                IdClassTarget.class, IdClassKey.class);
            if (xml) configuration.mappingFile("orm/element-collection-idclass-composite-parents.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var embedded = new CompositeTarget();
                embedded.id = new CompositeKey(3, 19);
                var idClass = new IdClassTarget();
                idClass.region = 7;
                idClass.serial = 23;
                var first = new CompositeLinkTarget(embedded, idClass, "first");
                var second = new CompositeLinkTarget(embedded, idClass, "second");
                var owner = new CompositeLinkOwner();
                owner.id = 12L;
                owner.values.add(new CompositeLinkValue("first", first));
                owner.values.add(new CompositeLinkValue("duplicate", first));
                owner.values.add(new CompositeLinkValue("null", null));
                owner.values.add(new CompositeLinkValue("second", second));
                transaction(em, () -> {
                    em.persist(embedded);
                    em.persist(idClass);
                    em.persist(first);
                    em.persist(second);
                    em.persist(owner);
                });
                var firstKey = new CompositeLinkKey(embedded.id, new IdClassKey(7, 23), "first");
                var secondKey = new CompositeLinkKey(embedded.id, new IdClassKey(7, 23), "second");
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(first)).isEqualTo(firstKey);
                List<?> rows = em.createNativeQuery(
                    "select element_embedded_region, element_embedded_serial, element_idclass_region,"
                        + " element_idclass_serial, element_code from composite_link_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly(3L, 19L, 7L, 23L, "first");
                assertThat((Object[]) rows.get(1)).containsExactly(3L, 19L, 7L, 23L, "first");
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly(3L, 19L, 7L, 23L, "second");
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into composite_link_values (owner_id, value_position, element_embedded_region,"
                        + " element_embedded_serial, element_idclass_region, element_idclass_serial, element_code)"
                        + " values (12, 4, 3, 19, 7, 23, 'missing')").executeUpdate())
                    .isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();

                var found = em.find(CompositeLinkOwner.class, 12L);
                var managedFirst = em.find(CompositeLinkTarget.class, firstKey);
                var managedSecond = em.find(CompositeLinkTarget.class, secondKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedFirst, managedFirst, null, managedSecond);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedFirst);
                assertThat(managedFirst.embedded).isSameAs(em.find(CompositeTarget.class, embedded.id));
                assertThat(managedFirst.idClass).isSameAs(em.find(IdClassTarget.class, new IdClassKey(7, 23)));
                assertThat(managedSecond.embedded).isSameAs(managedFirst.embedded);
                assertThat(managedSecond.idClass).isSameAs(managedFirst.idClass);
                assertThat(em.createQuery(
                    "select v.target from CompositeLinkOwner o join o.values v order by index(v)", CompositeLinkTarget.class)
                    .getResultList()).containsExactly(managedFirst, managedFirst, null, managedSecond);
                assertThat(em.createQuery(
                    "select v.target.embedded.id.serial from CompositeLinkOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(19L, 19L, 19L);
                assertThat(em.createQuery(
                    "select v.target.idClass.region from CompositeLinkOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(7L, 7L, 7L);
                assertThat(em.createQuery(
                    "select v.label from CompositeLinkOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedSecond).getResultList()).containsExactly("second");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(CompositeLinkTarget.class);
                var values = query.from(CompositeLinkOwner.class).joinList("values");
                query.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(query).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond);
                var projected = em.createQuery(
                    "select v from CompositeLinkOwner o join o.values v order by index(v)", CompositeLinkValue.class)
                    .getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedFirst);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedSecond;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(CompositeLinkOwner.class, 12L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null
                    : factory.getPersistenceUnitUtil().getIdentifier(value.target))
                    .containsExactly(secondKey, null, secondKey);
                em.clear();
                reloaded.values.getFirst().target = first;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(CompositeLinkTarget.class, firstKey)));
                em.clear();
                factory.getCache().evictAll();
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(
                    em.find(CompositeLinkOwner.class, 12L).values.getFirst().target)).isEqualTo(firstKey);
                var refreshed = em.find(CompositeLinkOwner.class, 12L);
                refreshed.values.getFirst().target = em.find(CompositeLinkTarget.class, secondKey);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(CompositeLinkTarget.class, firstKey));
                transaction(em, () -> em.remove(em.find(CompositeLinkOwner.class, 12L)));
                assertThat(em.find(CompositeLinkTarget.class, firstKey)).isNotNull();
                assertThat(em.find(CompositeLinkTarget.class, secondKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from composite_link_values").getSingleResult()).isEqualTo(0L);

                var partial = new CompositeLinkOwner();
                partial.id = 13L;
                partial.values.add(new CompositeLinkValue("partial", em.find(CompositeLinkTarget.class, firstKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update composite_link_values set element_embedded_serial = null where owner_id = 13").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(CompositeLinkOwner.class, 13L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    // §2.4.2.1 and §2.8: distinct MapsId relationships derive separate BASIC embedded key components.
    @Test void multipleMapsIdComponentsKeepDerivedElementTuplesAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, MultiMapsOwner.class, MultiMapsValue.class,
                MultiMapsTarget.class, MultiMapsKey.class, MapsIdElementParent.class);
            if (xml) configuration.mappingFile("orm/element-collection-multiple-mapsid.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var source = new MapsIdElementParent("source");
                var destination = new MapsIdElementParent("destination");
                var outward = new MultiMapsTarget(source, destination, "route");
                var inward = new MultiMapsTarget(destination, source, "route");
                var owner = new MultiMapsOwner();
                owner.id = 16L;
                owner.values.add(new MultiMapsValue("first", outward));
                owner.values.add(new MultiMapsValue("duplicate", outward));
                owner.values.add(new MultiMapsValue("null", null));
                owner.values.add(new MultiMapsValue("reverse", inward));
                transaction(em, () -> {
                    em.persist(outward);
                    em.persist(inward);
                    em.persist(owner);
                });
                assertThat(source.id).isPositive().isNotEqualTo(destination.id);
                var outwardKey = new MultiMapsKey(source.id, destination.id, "route");
                var inwardKey = new MultiMapsKey(destination.id, source.id, "route");
                assertThat(outward.id).isEqualTo(outwardKey);
                assertThat(inward.id).isEqualTo(inwardKey);
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(outward)).isEqualTo(outwardKey);
                List<?> rows = em.createNativeQuery(
                    "select element_source, element_destination, element_code from multi_maps_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly(source.id, destination.id, "route");
                assertThat((Object[]) rows.get(1)).containsExactly(source.id, destination.id, "route");
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly(destination.id, source.id, "route");
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into multi_maps_values (owner_id, value_position, element_source, element_destination, element_code)"
                        + " values (16, 4, ?, ?, 'missing')")
                    .setParameter(1, source.id).setParameter(2, destination.id).executeUpdate())
                    .isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();
                var found = em.find(MultiMapsOwner.class, 16L);
                var managedOutward = em.find(MultiMapsTarget.class, outwardKey);
                var managedInward = em.find(MultiMapsTarget.class, inwardKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedOutward, managedOutward, null, managedInward);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedOutward);
                assertThat(managedOutward.source).isSameAs(managedInward.destination);
                assertThat(managedOutward.destination).isSameAs(managedInward.source);
                assertThat(em.createQuery(
                    "select v.target from MultiMapsOwner o join o.values v order by index(v)", MultiMapsTarget.class)
                    .getResultList()).containsExactly(managedOutward, managedOutward, null, managedInward);
                assertThat(em.createQuery(
                    "select v.target.id.sourceId from MultiMapsOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(source.id, source.id, destination.id);
                assertThat(em.createQuery(
                    "select v.target.destination.name from MultiMapsOwner o join o.values v order by index(v)", String.class)
                    .getResultList()).containsExactly("destination", "destination", "source");
                assertThat(em.createQuery(
                    "select v.label from MultiMapsOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedInward).getResultList()).containsExactly("reverse");
                var cb = em.getCriteriaBuilder();
                var criteria = cb.createQuery(MultiMapsTarget.class);
                var values = criteria.from(MultiMapsOwner.class).joinList("values");
                criteria.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(criteria).getResultList())
                    .containsExactly(managedOutward, managedOutward, null, managedInward);
                var projected = em.createQuery(
                    "select v from MultiMapsOwner o join o.values v order by index(v)", MultiMapsValue.class)
                    .getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedOutward);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedInward;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(MultiMapsOwner.class, 16L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(inwardKey, null, inwardKey);
                em.clear();
                reloaded.values.getFirst().target = outward;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(MultiMapsTarget.class, outwardKey)));
                em.clear();
                factory.getCache().evictAll();
                var refreshed = em.find(MultiMapsOwner.class, 16L);
                assertThat(refreshed.values.getFirst().target.id).isEqualTo(outwardKey);
                refreshed.values.getFirst().target = em.find(MultiMapsTarget.class, inwardKey);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(MultiMapsTarget.class, outwardKey));
                transaction(em, () -> em.remove(refreshed));
                assertThat(em.find(MultiMapsTarget.class, outwardKey)).isNotNull();
                assertThat(em.find(MultiMapsTarget.class, inwardKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from multi_maps_values").getSingleResult()).isEqualTo(0L);
                var partial = new MultiMapsOwner();
                partial.id = 17L;
                partial.values.add(new MultiMapsValue("partial", em.find(MultiMapsTarget.class, outwardKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update multi_maps_values set element_destination = null where owner_id = 17").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(MultiMapsOwner.class, 17L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    @Test void multipleMapsIdTargetsKeepSharedCacheIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, MultiMapsOwner.class, MultiMapsValue.class,
                MultiMapsTarget.class, MultiMapsKey.class, MapsIdElementParent.class).sharedCacheMode(SharedCacheMode.ALL);
            if (xml) configuration.mappingFile("orm/element-collection-multiple-mapsid.xml");
            try (var factory = configuration.createEntityManagerFactory()) {
                MultiMapsKey key;
                try (var em = factory.createEntityManager()) {
                    var target = new MultiMapsTarget(new MapsIdElementParent("source"),
                        new MapsIdElementParent("destination"), "cached");
                    var owner = new MultiMapsOwner();
                    owner.id = 18L;
                    owner.values.add(new MultiMapsValue("first", target));
                    owner.values.add(new MultiMapsValue("duplicate", target));
                    transaction(em, () -> { em.persist(target); em.persist(owner); });
                    key = target.id;
                    em.clear();
                    assertThat(em.find(MultiMapsOwner.class, 18L).values).hasSize(2);
                }
                assertThat(factory.getCache().contains(MultiMapsOwner.class, 18L)).isTrue();
                try (var em = factory.createEntityManager()) {
                    var found = em.find(MultiMapsOwner.class, 18L);
                    var target = em.find(MultiMapsTarget.class, key);
                    assertThat(found.values.getFirst().target).isSameAs(found.values.getLast().target).isSameAs(target);
                    assertThat(target.source).isSameAs(em.find(MapsIdElementParent.class, key.sourceId));
                    assertThat(target.destination).isSameAs(em.find(MapsIdElementParent.class, key.destinationId));
                }
            }
        }
    }

    @Test void multipleMapsIdElementTargetsKeepOverlapAndPolymorphicParentRefusals() {
        assertThatThrownBy(() -> configuration(false, MultiMapsOwner.class, MultiMapsValue.class,
            MultiMapsTarget.class, MultiMapsKey.class, MapsIdElementParent.class)
            .mappingFile("orm/element-collection-overlapping-mapsid.xml").createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("overlapping identifier mappings");
        assertThatThrownBy(() -> factory(false, MultiMapsOwner.class, MultiMapsValue.class,
            MultiMapsTarget.class, MultiMapsKey.class, MapsIdElementParent.class, MapsParentSubtype.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("requires an eligible primary-key target");
    }

    // §2.4.2.1 and §2.8: a whole embedded identity may be shared through OneToOne MapsId.
    @Test void wholeCompositeMapsIdTargetKeepsPhysicalElementKeysAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, SharedCompositeOwner.class, SharedCompositeValue.class,
                SharedCompositeTarget.class, CompositeTarget.class, CompositeKey.class);
            if (xml) configuration.mappingFile("orm/element-collection-shared-composite-mapsid.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var firstParent = new CompositeTarget();
                firstParent.id = new CompositeKey(3, 19);
                var secondParent = new CompositeTarget();
                secondParent.id = new CompositeKey(19, 3);
                var first = new SharedCompositeTarget(firstParent);
                var second = new SharedCompositeTarget(secondParent);
                var owner = new SharedCompositeOwner();
                owner.id = 19L;
                owner.values.add(new SharedCompositeValue("first", first));
                owner.values.add(new SharedCompositeValue("duplicate", first));
                owner.values.add(new SharedCompositeValue("null", null));
                owner.values.add(new SharedCompositeValue("second", second));
                transaction(em, () -> { em.persist(first); em.persist(second); em.persist(owner); });
                assertThat(first.id).isEqualTo(firstParent.id);
                assertThat(second.id).isEqualTo(secondParent.id);
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(first)).isEqualTo(firstParent.id);
                List<?> rows = em.createNativeQuery(
                    "select element_region, element_serial from shared_composite_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly(3L, 19L);
                assertThat((Object[]) rows.get(1)).containsExactly(3L, 19L);
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly(19L, 3L);
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into shared_composite_values (owner_id, value_position, element_region, element_serial)"
                        + " values (19, 4, 3, 3)").executeUpdate())
                    .isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();
                var found = em.find(SharedCompositeOwner.class, 19L);
                var managedFirst = em.find(SharedCompositeTarget.class, firstParent.id);
                var managedSecond = em.find(SharedCompositeTarget.class, secondParent.id);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedFirst, managedFirst, null, managedSecond);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedFirst);
                assertThat(managedFirst.parent).isSameAs(em.find(CompositeTarget.class, firstParent.id));
                assertThat(managedSecond.parent).isSameAs(em.find(CompositeTarget.class, secondParent.id));
                assertThat(em.createQuery(
                    "select v.target from SharedCompositeOwner o join o.values v order by index(v)", SharedCompositeTarget.class)
                    .getResultList()).containsExactly(managedFirst, managedFirst, null, managedSecond);
                assertThat(em.createQuery(
                    "select v.target.id.serial from SharedCompositeOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(19L, 19L, 3L);
                assertThat(em.createQuery(
                    "select v.target.parent.id.region from SharedCompositeOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(3L, 3L, 19L);
                assertThat(em.createQuery(
                    "select v.label from SharedCompositeOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedSecond).getResultList()).containsExactly("second");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(SharedCompositeTarget.class);
                var values = query.from(SharedCompositeOwner.class).joinList("values");
                query.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(query).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond);
                var projected = em.createQuery(
                    "select v from SharedCompositeOwner o join o.values v order by index(v)", SharedCompositeValue.class)
                    .getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedFirst);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedSecond;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(SharedCompositeOwner.class, 19L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(secondParent.id, null, secondParent.id);
                em.clear();
                reloaded.values.getFirst().target = first;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(SharedCompositeTarget.class, firstParent.id)));
                em.clear();
                factory.getCache().evictAll();
                var refreshed = em.find(SharedCompositeOwner.class, 19L);
                assertThat(refreshed.values.getFirst().target.id).isEqualTo(firstParent.id);
                refreshed.values.getFirst().target = em.find(SharedCompositeTarget.class, secondParent.id);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(SharedCompositeTarget.class, firstParent.id));
                transaction(em, () -> em.remove(refreshed));
                assertThat(em.find(SharedCompositeTarget.class, firstParent.id)).isNotNull();
                assertThat(em.find(SharedCompositeTarget.class, secondParent.id)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from shared_composite_values").getSingleResult()).isEqualTo(0L);
                var partial = new SharedCompositeOwner();
                partial.id = 20L;
                partial.values.add(new SharedCompositeValue("partial", em.find(SharedCompositeTarget.class, firstParent.id)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update shared_composite_values set element_serial = null where owner_id = 20").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(SharedCompositeOwner.class, 20L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    @Test void wholeCompositeMapsIdTargetsKeepSharedCacheIdentityAndRejectParentReassignment() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, SharedCompositeOwner.class, SharedCompositeValue.class,
                SharedCompositeTarget.class, CompositeTarget.class, CompositeKey.class).sharedCacheMode(SharedCacheMode.ALL);
            if (xml) configuration.mappingFile("orm/element-collection-shared-composite-mapsid.xml");
            try (var factory = configuration.createEntityManagerFactory()) {
                var key = new CompositeKey(3, 19);
                try (var em = factory.createEntityManager()) {
                    var parent = new CompositeTarget();
                    parent.id = key;
                    var target = new SharedCompositeTarget(parent);
                    var owner = new SharedCompositeOwner();
                    owner.id = 21L;
                    owner.values.add(new SharedCompositeValue("first", target));
                    owner.values.add(new SharedCompositeValue("duplicate", target));
                    transaction(em, () -> { em.persist(target); em.persist(owner); });
                    em.clear();
                    assertThat(em.find(SharedCompositeOwner.class, 21L).values).hasSize(2);
                }
                assertThat(factory.getCache().contains(SharedCompositeOwner.class, 21L)).isTrue();
                try (var em = factory.createEntityManager()) {
                    var found = em.find(SharedCompositeOwner.class, 21L);
                    var target = em.find(SharedCompositeTarget.class, key);
                    assertThat(found.values.getFirst().target).isSameAs(found.values.getLast().target).isSameAs(target);
                    assertThat(target.parent).isSameAs(em.find(CompositeTarget.class, key));
                    var different = new CompositeTarget();
                    different.id = new CompositeKey(19, 3);
                    em.getTransaction().begin();
                    em.persist(different);
                    target.parent = different;
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                    em.getTransaction().rollback();
                }
            }
        }
    }

    @Test void wholeCompositeMapsIdElementTargetsKeepPolymorphicParentsRefused() {
        assertThatThrownBy(() -> factory(false, SharedCompositeOwner.class, SharedCompositeValue.class,
            SharedCompositeTarget.class, CompositeTarget.class, CompositeKey.class, CompositeParentSubtype.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("requires an eligible primary-key target");
    }

    // §2.4.2.1 and §2.8: the dependent EmbeddedId has the same Java type as its parent's IdClass.
    @Test void wholeMapsIdFromIdClassParentKeepsElementTuplesAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, SharedIdClassOwner.class, SharedIdClassValue.class,
                SharedIdClassTarget.class, SharedClassParent.class, IdClassKey.class);
            if (xml) configuration.mappingFile("orm/element-collection-shared-idclass-parent.xml");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var firstParent = new SharedClassParent();
                firstParent.region = 3;
                firstParent.serial = 19;
                var secondParent = new SharedClassParent();
                secondParent.region = 19;
                secondParent.serial = 3;
                var first = new SharedIdClassTarget(firstParent);
                var second = new SharedIdClassTarget(secondParent);
                var firstKey = new IdClassKey(3, 19);
                var secondKey = new IdClassKey(19, 3);
                var owner = new SharedIdClassOwner();
                owner.id = 22L;
                owner.values.add(new SharedIdClassValue("first", first));
                owner.values.add(new SharedIdClassValue("duplicate", first));
                owner.values.add(new SharedIdClassValue("null", null));
                owner.values.add(new SharedIdClassValue("second", second));
                transaction(em, () -> { em.persist(first); em.persist(second); em.persist(owner); });
                assertThat(first.id).isEqualTo(firstKey);
                assertThat(second.id).isEqualTo(secondKey);
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(first)).isEqualTo(firstKey);
                List<?> rows = em.createNativeQuery(
                    "select element_region, element_serial from shared_idclass_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly(3L, 19L);
                assertThat((Object[]) rows.get(1)).containsExactly(3L, 19L);
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly(19L, 3L);
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into shared_idclass_values (owner_id, value_position, element_region, element_serial)"
                        + " values (22, 4, 3, 3)").executeUpdate())
                    .isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();
                var found = em.find(SharedIdClassOwner.class, 22L);
                var managedFirst = em.find(SharedIdClassTarget.class, firstKey);
                var managedSecond = em.find(SharedIdClassTarget.class, secondKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedFirst, managedFirst, null, managedSecond);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedFirst);
                assertThat(managedFirst.parent).isSameAs(em.find(SharedClassParent.class, firstKey));
                assertThat(managedSecond.parent).isSameAs(em.find(SharedClassParent.class, secondKey));
                assertThat(em.createQuery(
                    "select v.target from SharedIdClassOwner o join o.values v order by index(v)", SharedIdClassTarget.class)
                    .getResultList()).containsExactly(managedFirst, managedFirst, null, managedSecond);
                assertThat(em.createQuery(
                    "select v.target.id.serial from SharedIdClassOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(19L, 19L, 3L);
                assertThat(em.createQuery(
                    "select v.target.parent.region from SharedIdClassOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(3L, 3L, 19L);
                assertThat(em.createQuery(
                    "select v.label from SharedIdClassOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedSecond).getResultList()).containsExactly("second");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(SharedIdClassTarget.class);
                var values = query.from(SharedIdClassOwner.class).joinList("values");
                query.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(query).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond);
                var projected = em.createQuery(
                    "select v from SharedIdClassOwner o join o.values v order by index(v)", SharedIdClassValue.class)
                    .getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedFirst);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedSecond;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(SharedIdClassOwner.class, 22L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(secondKey, null, secondKey);
                em.clear();
                reloaded.values.getFirst().target = first;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(SharedIdClassTarget.class, firstKey)));
                em.clear();
                factory.getCache().evictAll();
                var refreshed = em.find(SharedIdClassOwner.class, 22L);
                assertThat(refreshed.values.getFirst().target.id).isEqualTo(firstKey);
                refreshed.values.getFirst().target = em.find(SharedIdClassTarget.class, secondKey);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(SharedIdClassTarget.class, firstKey));
                transaction(em, () -> em.remove(refreshed));
                assertThat(em.find(SharedIdClassTarget.class, firstKey)).isNotNull();
                assertThat(em.find(SharedIdClassTarget.class, secondKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from shared_idclass_values").getSingleResult()).isEqualTo(0L);
                var partial = new SharedIdClassOwner();
                partial.id = 23L;
                partial.values.add(new SharedIdClassValue("partial", em.find(SharedIdClassTarget.class, firstKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update shared_idclass_values set element_serial = null where owner_id = 23").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(SharedIdClassOwner.class, 23L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    @Test void wholeMapsIdCopiesParentKeyByAttributeRatherThanDeclarationOrder() {
        try (var factory = configuration(false, SharedIdClassTarget.class, SharedClassParent.class,
                IdClassKey.class).createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var parent = new SharedClassParent();
            parent.region = 3;
            parent.serial = 19;
            var target = new SharedIdClassTarget(parent);
            transaction(em, () -> em.persist(target));
            var key = new IdClassKey(3, 19);
            assertThat(target.id).isEqualTo(key);
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(SharedIdClassTarget.class, key);
            assertThat(found.id).isEqualTo(key);
            assertThat(found.parent).isSameAs(em.find(SharedClassParent.class, key));
        }
    }

    @Test void sharedIdClassParentTargetsKeepCacheIdentityAndRejectParentReassignment() {
        for (boolean xml : List.of(false, true)) {
            var configuration = configuration(false, SharedIdClassOwner.class, SharedIdClassValue.class,
                SharedIdClassTarget.class, SharedClassParent.class, IdClassKey.class).sharedCacheMode(SharedCacheMode.ALL);
            if (xml) configuration.mappingFile("orm/element-collection-shared-idclass-parent.xml");
            try (var factory = configuration.createEntityManagerFactory()) {
                var key = new IdClassKey(3, 19);
                try (var em = factory.createEntityManager()) {
                    var parent = new SharedClassParent();
                    parent.region = 3;
                    parent.serial = 19;
                    var target = new SharedIdClassTarget(parent);
                    var owner = new SharedIdClassOwner();
                    owner.id = 24L;
                    owner.values.add(new SharedIdClassValue("first", target));
                    owner.values.add(new SharedIdClassValue("duplicate", target));
                    transaction(em, () -> { em.persist(target); em.persist(owner); });
                    em.clear();
                    assertThat(em.find(SharedIdClassOwner.class, 24L).values).hasSize(2);
                }
                assertThat(factory.getCache().contains(SharedIdClassOwner.class, 24L)).isTrue();
                try (var em = factory.createEntityManager()) {
                    var found = em.find(SharedIdClassOwner.class, 24L);
                    var target = em.find(SharedIdClassTarget.class, key);
                    assertThat(found.values.getFirst().target).isSameAs(found.values.getLast().target).isSameAs(target);
                    assertThat(target.parent).isSameAs(em.find(SharedClassParent.class, key));
                    var different = new SharedClassParent();
                    different.region = 19;
                    different.serial = 3;
                    em.getTransaction().begin();
                    em.persist(different);
                    target.parent = different;
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                    em.getTransaction().rollback();
                }
            }
        }
    }

    @Test void sharedIdClassParentTargetsKeepPolymorphicAndMismatchedParentsRefused() {
        assertThatThrownBy(() -> factory(false, SharedIdClassOwner.class, SharedIdClassValue.class,
            SharedIdClassTarget.class, SharedClassParent.class, IdClassKey.class, SharedClassParentSubtype.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("requires an eligible primary-key target");
        assertThatThrownBy(() -> configuration(false, MismatchedSharedClassTarget.class,
            SharedClassParent.class, IdClassKey.class, CompositeKey.class).createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("identifier types must match");
    }

    // §2.4.2.1 and §2.8: a named embedded component carries the composite parent's identity.
    @Test void namedCompositeMapsIdKeepsElementTuplesAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            var configuration = namedCompositeConfiguration(xml);
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                var firstParent = new CompositeTarget();
                firstParent.id = new CompositeKey(3, 19);
                var secondParent = new CompositeTarget();
                secondParent.id = new CompositeKey(19, 3);
                var first = new NamedCompositeTarget("same", firstParent);
                var second = new NamedCompositeTarget("same", secondParent);
                var third = new NamedCompositeTarget("other", firstParent);
                var firstKey = new NamedCompositeKey("same", new CompositeKey(3, 19));
                var secondKey = new NamedCompositeKey("same", new CompositeKey(19, 3));
                var thirdKey = new NamedCompositeKey("other", new CompositeKey(3, 19));
                var owner = new NamedCompositeOwner();
                owner.id = 25L;
                owner.values.add(new NamedCompositeValue("first", first));
                owner.values.add(new NamedCompositeValue("duplicate", first));
                owner.values.add(new NamedCompositeValue("null", null));
                owner.values.add(new NamedCompositeValue("second", second));
                owner.values.add(new NamedCompositeValue("third", third));
                transaction(em, () -> { em.persist(first); em.persist(second); em.persist(third); em.persist(owner); });
                assertThat(first.id).isEqualTo(firstKey);
                assertThat(second.id).isEqualTo(secondKey);
                assertThat(third.id).isEqualTo(thirdKey);
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(first)).isEqualTo(firstKey);
                List<?> rows = em.createNativeQuery(
                    "select element_code, element_region, element_serial from named_composite_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly("same", 3L, 19L);
                assertThat((Object[]) rows.get(1)).containsExactly("same", 3L, 19L);
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly("same", 19L, 3L);
                assertThat((Object[]) rows.get(4)).containsExactly("other", 3L, 19L);
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into named_composite_values (owner_id, value_position, element_code, element_region, element_serial)"
                        + " values (25, 5, 'same', 3, 3)").executeUpdate()).isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();
                var found = em.find(NamedCompositeOwner.class, 25L);
                var managedFirst = em.find(NamedCompositeTarget.class, firstKey);
                var managedSecond = em.find(NamedCompositeTarget.class, secondKey);
                var managedThird = em.find(NamedCompositeTarget.class, thirdKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedFirst);
                assertThat(managedFirst.parent).isSameAs(managedThird.parent)
                    .isSameAs(em.find(CompositeTarget.class, firstParent.id));
                assertThat(em.createQuery(
                    "select v.target from NamedCompositeOwner o join o.values v order by index(v)", NamedCompositeTarget.class)
                    .getResultList()).containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                assertThat(em.createQuery(
                    "select v.target.parent.id.region from NamedCompositeOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(3L, 3L, 19L, 3L);
                assertThat(em.createQuery(
                    "select v.target.id.parentKey.serial from NamedCompositeOwner o join o.values v order by index(v)", Long.class)
                    .getResultList()).containsExactly(19L, 19L, 3L, 19L);
                assertThat(em.createQuery(
                    "select v.label from NamedCompositeOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedSecond).getResultList()).containsExactly("second");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(NamedCompositeTarget.class);
                var values = query.from(NamedCompositeOwner.class).joinList("values");
                query.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(query).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                var scalarQuery = cb.createQuery(Long.class);
                var scalarValues = scalarQuery.from(NamedCompositeOwner.class).joinList("values");
                scalarQuery.select(scalarValues.get("target").get("id").get("parentKey").get("region"))
                    .orderBy(cb.asc(scalarValues.index()));
                assertThat(em.createQuery(scalarQuery).getResultList()).containsExactly(3L, 3L, 19L, 3L);
                var projected = em.createQuery(
                    "select v from NamedCompositeOwner o join o.values v order by index(v)", NamedCompositeValue.class)
                    .getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedFirst);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedSecond;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(NamedCompositeOwner.class, 25L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(secondKey, null, secondKey, thirdKey);
                em.clear();
                reloaded.values.getFirst().target = first;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(NamedCompositeTarget.class, firstKey)));
                em.clear();
                factory.getCache().evictAll();
                var refreshed = em.find(NamedCompositeOwner.class, 25L);
                assertThat(refreshed.values.getFirst().target.id).isEqualTo(firstKey);
                refreshed.values.getFirst().target = em.find(NamedCompositeTarget.class, secondKey);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(NamedCompositeTarget.class, firstKey));
                transaction(em, () -> em.remove(refreshed));
                assertThat(em.find(NamedCompositeTarget.class, firstKey)).isNotNull();
                assertThat(em.find(NamedCompositeTarget.class, secondKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from named_composite_values").getSingleResult()).isEqualTo(0L);
                var partial = new NamedCompositeOwner();
                partial.id = 26L;
                partial.values.add(new NamedCompositeValue("partial", em.find(NamedCompositeTarget.class, firstKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update named_composite_values set element_serial = null where owner_id = 26").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(NamedCompositeOwner.class, 26L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    private PersistenceConfiguration namedCompositeConfiguration(boolean xml) {
        var configuration = configuration(false, NamedCompositeOwner.class, NamedCompositeValue.class,
            NamedCompositeTarget.class, NamedCompositeKey.class, CompositeTarget.class, CompositeKey.class);
        if (xml) configuration.mappingFile("orm/element-collection-named-composite-mapsid.xml");
        return configuration;
    }

    @Test void namedCompositeMapsIdKeepsCacheIdentityAndRefusesParentOrBasicKeyChanges() {
        for (boolean xml : List.of(false, true)) {
            try (var factory = namedCompositeConfiguration(xml).sharedCacheMode(SharedCacheMode.ALL)
                    .createEntityManagerFactory()) {
                var key = new NamedCompositeKey("cached", new CompositeKey(7, 31));
                try (var em = factory.createEntityManager()) {
                    var parent = new CompositeTarget();
                    parent.id = new CompositeKey(7, 31);
                    var target = new NamedCompositeTarget("cached", parent);
                    var owner = new NamedCompositeOwner();
                    owner.id = 27L;
                    owner.values.add(new NamedCompositeValue("first", target));
                    owner.values.add(new NamedCompositeValue("duplicate", target));
                    transaction(em, () -> { em.persist(target); em.persist(owner); });
                    em.clear();
                    assertThat(em.find(NamedCompositeOwner.class, 27L).values).hasSize(2);
                }
                assertThat(factory.getCache().contains(NamedCompositeOwner.class, 27L)).isTrue();
                try (var em = factory.createEntityManager()) {
                    var owner = em.find(NamedCompositeOwner.class, 27L);
                    var target = em.find(NamedCompositeTarget.class, key);
                    assertThat(owner.values.getFirst().target).isSameAs(owner.values.getLast().target).isSameAs(target);
                    assertThat(target.parent).isSameAs(em.find(CompositeTarget.class, key.parentKey));
                    var other = new CompositeTarget();
                    other.id = new CompositeKey(31, 7);
                    em.getTransaction().begin();
                    em.persist(other);
                    target.parent = other;
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                    em.getTransaction().rollback();
                }
                try (var em = factory.createEntityManager()) {
                    var target = em.find(NamedCompositeTarget.class, key);
                    em.getTransaction().begin();
                    target.id.code = "changed";
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                    em.getTransaction().rollback();
                }
            }
        }
    }

    @Test void namedCompositeMapsIdDefaultsAndRecordKeysRetainCompleteIdentity() {
        try (var factory = configuration(false, DefaultNamedCompositeTarget.class, RecordNamedCompositeKey.class,
                DefaultNamedCompositeOwner.class, DefaultNamedCompositeValue.class,
                CompositeTarget.class, CompositeKey.class).createEntityManagerFactory();
                var em = factory.createEntityManager()) {
            var parent = new CompositeTarget();
            parent.id = new CompositeKey(13, 29);
            var first = new DefaultNamedCompositeTarget();
            first.id = new RecordNamedCompositeKey(5, null, "first");
            first.parent = parent;
            var second = new DefaultNamedCompositeTarget();
            second.id = new RecordNamedCompositeKey(6, null, "second");
            second.parent = parent;
            var owner = new DefaultNamedCompositeOwner();
            owner.id = 28L;
            owner.values.add(new DefaultNamedCompositeValue(first));
            owner.values.add(new DefaultNamedCompositeValue(second));
            transaction(em, () -> { em.persist(first); em.persist(second); em.persist(owner); });
            var firstKey = new RecordNamedCompositeKey(5, new CompositeKey(13, 29), "first");
            var secondKey = new RecordNamedCompositeKey(6, new CompositeKey(13, 29), "second");
            assertThat(first.id).isEqualTo(firstKey);
            assertThat(second.id).isEqualTo(secondKey);
            List<?> rows = em.createNativeQuery(
                "select sequence, parent_region, parent_serial, code from DefaultNamedCompositeTarget order by sequence")
                .getResultList();
            assertThat((Object[]) rows.getFirst()).containsExactly(5, 13L, 29L, "first");
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(DefaultNamedCompositeTarget.class, firstKey);
            assertThat(found.id).isEqualTo(firstKey);
            assertThat(found.parent).isSameAs(em.find(DefaultNamedCompositeTarget.class, secondKey).parent);
            assertThat(em.find(DefaultNamedCompositeOwner.class, 28L).values).extracting(value -> value.target.id)
                .containsExactly(firstKey, secondKey);
            assertThat(em.find(DefaultNamedCompositeOwner.class, 28L).values.getFirst().target).isSameAs(found);
        }
    }

    @Test void namedCompositeMapsIdTargetsKeepBroaderIdentityShapesRefused() {
        assertThatThrownBy(() -> namedCompositeConfiguration(false).managedClass(CompositeParentSubtype.class)
            .createEntityManagerFactory()).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("requires an eligible primary-key target");
    }

    // §2.4.2.1 and §2.8: the named embedded component uses its parent's IdClass type.
    @Test void namedIdClassMapsIdKeepsPhysicalTuplesAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            try (var factory = namedClassCompositeConfiguration(xml).createEntityManagerFactory();
                    var em = factory.createEntityManager()) {
                var firstParent = new SharedClassParent();
                firstParent.region = 3;
                firstParent.serial = 19;
                var secondParent = new SharedClassParent();
                secondParent.region = 19;
                secondParent.serial = 3;
                var first = new NamedClassCompositeTarget("same", firstParent);
                var second = new NamedClassCompositeTarget("same", secondParent);
                var third = new NamedClassCompositeTarget("other", firstParent);
                var firstKey = new NamedClassCompositeKey("same", new IdClassKey(3, 19));
                var secondKey = new NamedClassCompositeKey("same", new IdClassKey(19, 3));
                var thirdKey = new NamedClassCompositeKey("other", new IdClassKey(3, 19));
                var owner = new NamedClassCompositeOwner();
                owner.id = 29L;
                owner.values.add(new NamedClassCompositeValue("first", first));
                owner.values.add(new NamedClassCompositeValue("duplicate", first));
                owner.values.add(new NamedClassCompositeValue("null", null));
                owner.values.add(new NamedClassCompositeValue("second", second));
                owner.values.add(new NamedClassCompositeValue("third", third));
                transaction(em, () -> { em.persist(first); em.persist(second); em.persist(third); em.persist(owner); });
                assertThat(first.id).isEqualTo(firstKey);
                assertThat(second.id).isEqualTo(secondKey);
                assertThat(third.id).isEqualTo(thirdKey);
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(first)).isEqualTo(firstKey);
                List<?> rows = em.createNativeQuery(
                    "select element_code, element_region, element_serial from named_class_values order by value_position")
                    .getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly("same", 3L, 19L);
                assertThat((Object[]) rows.get(1)).containsExactly("same", 3L, 19L);
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly("same", 19L, 3L);
                assertThat((Object[]) rows.get(4)).containsExactly("other", 3L, 19L);
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into named_class_values (owner_id, value_position, element_code, element_region, element_serial)"
                        + " values (29, 5, 'same', 3, 3)").executeUpdate()).isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();
                var found = em.find(NamedClassCompositeOwner.class, 29L);
                var managedFirst = em.find(NamedClassCompositeTarget.class, firstKey);
                var managedSecond = em.find(NamedClassCompositeTarget.class, secondKey);
                var managedThird = em.find(NamedClassCompositeTarget.class, thirdKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedFirst);
                assertThat(managedFirst.parent).isSameAs(managedThird.parent)
                    .isSameAs(em.find(SharedClassParent.class, firstKey.parentKey));
                assertThat(em.createQuery(
                    "select v.target from NamedClassCompositeOwner o join o.values v order by index(v)",
                    NamedClassCompositeTarget.class).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                assertThat(em.createQuery(
                    "select v.target.id.parentKey.serial from NamedClassCompositeOwner o join o.values v order by index(v)",
                    Long.class).getResultList()).containsExactly(19L, 19L, 3L, 19L);
                assertThat(em.createQuery(
                    "select v.target.parent.region from NamedClassCompositeOwner o join o.values v order by index(v)",
                    Long.class).getResultList()).containsExactly(3L, 3L, 19L, 3L);
                assertThat(em.createQuery(
                    "select v.label from NamedClassCompositeOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedSecond).getResultList()).containsExactly("second");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(NamedClassCompositeTarget.class);
                var values = query.from(NamedClassCompositeOwner.class).joinList("values");
                query.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(query).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                var scalarQuery = cb.createQuery(Long.class);
                var scalarValues = scalarQuery.from(NamedClassCompositeOwner.class).joinList("values");
                scalarQuery.select(scalarValues.get("target").get("id").get("parentKey").get("region"))
                    .orderBy(cb.asc(scalarValues.index()));
                assertThat(em.createQuery(scalarQuery).getResultList()).containsExactly(3L, 3L, 19L, 3L);
                var projected = em.createQuery(
                    "select v from NamedClassCompositeOwner o join o.values v order by index(v)",
                    NamedClassCompositeValue.class).getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedFirst);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedSecond;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(NamedClassCompositeOwner.class, 29L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(secondKey, null, secondKey, thirdKey);
                em.clear();
                reloaded.values.getFirst().target = first;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(NamedClassCompositeTarget.class, firstKey)));
                em.clear();
                factory.getCache().evictAll();
                var refreshed = em.find(NamedClassCompositeOwner.class, 29L);
                assertThat(refreshed.values.getFirst().target.id).isEqualTo(firstKey);
                refreshed.values.getFirst().target = em.find(NamedClassCompositeTarget.class, secondKey);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(NamedClassCompositeTarget.class, firstKey));
                transaction(em, () -> em.remove(refreshed));
                assertThat(em.find(NamedClassCompositeTarget.class, firstKey)).isNotNull();
                assertThat(em.find(NamedClassCompositeTarget.class, secondKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from named_class_values").getSingleResult()).isEqualTo(0L);
                var partial = new NamedClassCompositeOwner();
                partial.id = 30L;
                partial.values.add(new NamedClassCompositeValue("partial", em.find(NamedClassCompositeTarget.class, firstKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update named_class_values set element_serial = null where owner_id = 30").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(NamedClassCompositeOwner.class, 30L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    private PersistenceConfiguration namedClassCompositeConfiguration(boolean xml) {
        var configuration = configuration(false, NamedClassCompositeOwner.class, NamedClassCompositeValue.class,
            NamedClassCompositeTarget.class, NamedClassCompositeKey.class, SharedClassParent.class, IdClassKey.class);
        if (xml) configuration.mappingFile("orm/element-collection-named-idclass-mapsid.xml");
        return configuration;
    }

    @Test void namedIdClassMapsIdKeepsCacheIdentityAndRejectsKeyChanges() {
        for (boolean xml : List.of(false, true)) {
            try (var factory = namedClassCompositeConfiguration(xml).sharedCacheMode(SharedCacheMode.ALL)
                    .createEntityManagerFactory()) {
                var key = new NamedClassCompositeKey("cached", new IdClassKey(7, 31));
                try (var em = factory.createEntityManager()) {
                    var parent = new SharedClassParent();
                    parent.region = 7;
                    parent.serial = 31;
                    var target = new NamedClassCompositeTarget("cached", parent);
                    var owner = new NamedClassCompositeOwner();
                    owner.id = 31L;
                    owner.values.add(new NamedClassCompositeValue("first", target));
                    owner.values.add(new NamedClassCompositeValue("duplicate", target));
                    transaction(em, () -> { em.persist(target); em.persist(owner); });
                    em.clear();
                    assertThat(em.find(NamedClassCompositeOwner.class, 31L).values).hasSize(2);
                }
                assertThat(factory.getCache().contains(NamedClassCompositeOwner.class, 31L)).isTrue();
                try (var em = factory.createEntityManager()) {
                    var owner = em.find(NamedClassCompositeOwner.class, 31L);
                    var target = em.find(NamedClassCompositeTarget.class, key);
                    assertThat(owner.values.getFirst().target).isSameAs(owner.values.getLast().target).isSameAs(target);
                    assertThat(target.parent).isSameAs(em.find(SharedClassParent.class, key.parentKey));
                    var other = new SharedClassParent();
                    other.region = 31;
                    other.serial = 7;
                    em.getTransaction().begin();
                    em.persist(other);
                    target.parent = other;
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                    em.getTransaction().rollback();
                }
                try (var em = factory.createEntityManager()) {
                    var target = em.find(NamedClassCompositeTarget.class, key);
                    em.getTransaction().begin();
                    target.id.code = "changed";
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                    em.getTransaction().rollback();
                }
            }
        }
    }

    @Test void namedIdClassMapsIdDefaultJoinsAndRecordKeysKeepParentPartOrder() {
        try (var factory = configuration(false, DefaultNamedClassTarget.class, RecordNamedClassKey.class,
                DefaultNamedClassOwner.class, DefaultNamedClassValue.class, SharedClassParent.class, IdClassKey.class)
                .createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var parent = new SharedClassParent();
            parent.region = 13;
            parent.serial = 29;
            var first = new DefaultNamedClassTarget();
            first.id = new RecordNamedClassKey(5, null, "first");
            first.parent = parent;
            var second = new DefaultNamedClassTarget();
            second.id = new RecordNamedClassKey(6, null, "second");
            second.parent = parent;
            var owner = new DefaultNamedClassOwner();
            owner.id = 32L;
            owner.values.add(new DefaultNamedClassValue(first));
            owner.values.add(new DefaultNamedClassValue(second));
            transaction(em, () -> { em.persist(first); em.persist(second); em.persist(owner); });
            var firstKey = new RecordNamedClassKey(5, new IdClassKey(13, 29), "first");
            var secondKey = new RecordNamedClassKey(6, new IdClassKey(13, 29), "second");
            assertThat(first.id).isEqualTo(firstKey);
            assertThat(second.id).isEqualTo(secondKey);
            List<?> rows = em.createNativeQuery(
                "select sequence, parent_region, parent_serial, code from DefaultNamedClassTarget order by sequence")
                .getResultList();
            assertThat((Object[]) rows.getFirst()).containsExactly(5, 13L, 29L, "first");
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(DefaultNamedClassTarget.class, firstKey);
            assertThat(found.id).isEqualTo(firstKey);
            assertThat(found.parent).isSameAs(em.find(DefaultNamedClassTarget.class, secondKey).parent);
            var values = em.find(DefaultNamedClassOwner.class, 32L).values;
            assertThat(values).extracting(value -> value.target.id).containsExactly(firstKey, secondKey);
            assertThat(values.getFirst().target).isSameAs(found);
        }
    }

    @Test void namedIdClassMapsIdKeepsPolymorphicAndMismatchedParentsRefused() {
        assertThatThrownBy(() -> namedClassCompositeConfiguration(false).managedClass(SharedClassParentSubtype.class)
            .createEntityManagerFactory()).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("requires an eligible primary-key target");
        assertThatThrownBy(() -> configuration(false, MismatchedNamedClassTarget.class, NamedCompositeKey.class,
            CompositeKey.class, SharedClassParent.class, IdClassKey.class).createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("identifier types must match");
    }

    // §2.4.2.1 and §2.8: each named component carries its own parent's complete key.
    @Test void multipleCompositeMapsIdKeepsIndependentParentTuplesAndManagedIdentity() {
        for (boolean xml : List.of(false, true)) {
            try (var factory = multipleCompositeConfiguration(xml).createEntityManagerFactory();
                    var em = factory.createEntityManager()) {
                var firstParent = new CompositeTarget();
                firstParent.id = new CompositeKey(3, 19);
                var secondParent = new CompositeTarget();
                secondParent.id = new CompositeKey(19, 3);
                var firstClassParent = new SharedClassParent();
                firstClassParent.region = 5;
                firstClassParent.serial = 23;
                var secondClassParent = new SharedClassParent();
                secondClassParent.region = 23;
                secondClassParent.serial = 5;
                var first = new MultipleCompositeTarget("same", firstParent, firstClassParent);
                var second = new MultipleCompositeTarget("same", secondParent, secondClassParent);
                var third = new MultipleCompositeTarget("other", firstParent, secondClassParent);
                var firstKey = new MultipleCompositeKey("same", new CompositeKey(3, 19), new IdClassKey(5, 23));
                var secondKey = new MultipleCompositeKey("same", new CompositeKey(19, 3), new IdClassKey(23, 5));
                var thirdKey = new MultipleCompositeKey("other", new CompositeKey(3, 19), new IdClassKey(23, 5));
                var owner = new MultipleCompositeOwner();
                owner.id = 33L;
                owner.values.add(new MultipleCompositeValue("first", first));
                owner.values.add(new MultipleCompositeValue("duplicate", first));
                owner.values.add(new MultipleCompositeValue("null", null));
                owner.values.add(new MultipleCompositeValue("second", second));
                owner.values.add(new MultipleCompositeValue("third", third));
                transaction(em, () -> { em.persist(first); em.persist(second); em.persist(third); em.persist(owner); });
                assertThat(first.id).isEqualTo(firstKey);
                assertThat(second.id).isEqualTo(secondKey);
                assertThat(third.id).isEqualTo(thirdKey);
                assertThat(factory.getPersistenceUnitUtil().getIdentifier(first)).isEqualTo(firstKey);
                List<?> rows = em.createNativeQuery(
                    "select element_code, element_first_region, element_first_serial, element_second_region, element_second_serial"
                        + " from multiple_composite_values order by value_position").getResultList();
                assertThat((Object[]) rows.getFirst()).containsExactly("same", 3L, 19L, 5L, 23L);
                assertThat((Object[]) rows.get(1)).containsExactly("same", 3L, 19L, 5L, 23L);
                assertThat((Object[]) rows.get(2)).containsOnlyNulls();
                assertThat((Object[]) rows.get(3)).containsExactly("same", 19L, 3L, 23L, 5L);
                assertThat((Object[]) rows.get(4)).containsExactly("other", 3L, 19L, 23L, 5L);
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createNativeQuery(
                    "insert into multiple_composite_values (owner_id, value_position, element_code,"
                        + " element_first_region, element_first_serial, element_second_region, element_second_serial)"
                        + " values (33, 5, 'same', 3, 19, 23, 5)").executeUpdate())
                    .isInstanceOf(PersistenceException.class);
                em.getTransaction().rollback();
                em.clear();
                factory.getCache().evictAll();
                var found = em.find(MultipleCompositeOwner.class, 33L);
                var managedFirst = em.find(MultipleCompositeTarget.class, firstKey);
                var managedSecond = em.find(MultipleCompositeTarget.class, secondKey);
                var managedThird = em.find(MultipleCompositeTarget.class, thirdKey);
                assertThat(found.values).extracting(value -> value.target)
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                assertThat(found.values.getFirst().target).isSameAs(found.values.get(1).target).isSameAs(managedFirst);
                assertThat(managedFirst.first).isSameAs(managedThird.first)
                    .isSameAs(em.find(CompositeTarget.class, firstKey.first));
                assertThat(managedSecond.second).isSameAs(managedThird.second)
                    .isSameAs(em.find(SharedClassParent.class, secondKey.second));
                assertThat(em.createQuery(
                    "select v.target from MultipleCompositeOwner o join o.values v order by index(v)",
                    MultipleCompositeTarget.class).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                assertThat(em.createQuery(
                    "select v.target.id.second.serial from MultipleCompositeOwner o join o.values v order by index(v)",
                    Long.class).getResultList()).containsExactly(23L, 23L, 5L, 5L);
                assertThat(em.createQuery(
                    "select v.target.first.id.region from MultipleCompositeOwner o join o.values v order by index(v)",
                    Long.class).getResultList()).containsExactly(3L, 3L, 19L, 3L);
                assertThat(em.createQuery(
                    "select v.label from MultipleCompositeOwner o join o.values v where v.target = :target", String.class)
                    .setParameter("target", managedSecond).getResultList()).containsExactly("second");
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(MultipleCompositeTarget.class);
                var values = query.from(MultipleCompositeOwner.class).joinList("values");
                query.select(values.get("target")).orderBy(cb.asc(values.index()));
                assertThat(em.createQuery(query).getResultList())
                    .containsExactly(managedFirst, managedFirst, null, managedSecond, managedThird);
                var scalarQuery = cb.createQuery(Long.class);
                var scalarValues = scalarQuery.from(MultipleCompositeOwner.class).joinList("values");
                scalarQuery.select(scalarValues.get("target").get("id").get("first").get("serial"))
                    .orderBy(cb.asc(scalarValues.index()));
                assertThat(em.createQuery(scalarQuery).getResultList()).containsExactly(19L, 19L, 3L, 19L);
                var projected = em.createQuery(
                    "select v from MultipleCompositeOwner o join o.values v order by index(v)",
                    MultipleCompositeValue.class).getResultList();
                assertThat(projected.getFirst().target).isSameAs(managedFirst);
                assertThat(projected.get(2).target).isNull();
                transaction(em, () -> {
                    found.values.remove(1);
                    found.values.getFirst().target = managedSecond;
                });
                em.clear();
                factory.getCache().evictAll();
                var reloaded = em.find(MultipleCompositeOwner.class, 33L);
                assertThat(reloaded.values).extracting(value -> value.target == null ? null : value.target.id)
                    .containsExactly(secondKey, null, secondKey, thirdKey);
                em.clear();
                reloaded.values.getFirst().target = first;
                transaction(em, () -> assertThat(em.merge(reloaded).values.getFirst().target)
                    .isSameAs(em.find(MultipleCompositeTarget.class, firstKey)));
                em.clear();
                factory.getCache().evictAll();
                var refreshed = em.find(MultipleCompositeOwner.class, 33L);
                assertThat(refreshed.values.getFirst().target.id).isEqualTo(firstKey);
                refreshed.values.getFirst().target = em.find(MultipleCompositeTarget.class, secondKey);
                em.refresh(refreshed);
                assertThat(refreshed.values.getFirst().target).isSameAs(em.find(MultipleCompositeTarget.class, firstKey));
                transaction(em, () -> em.remove(refreshed));
                assertThat(em.find(MultipleCompositeTarget.class, firstKey)).isNotNull();
                assertThat(em.find(MultipleCompositeTarget.class, secondKey)).isNotNull();
                assertThat(em.createNativeQuery("select count(*) from multiple_composite_values").getSingleResult()).isEqualTo(0L);
                var partial = new MultipleCompositeOwner();
                partial.id = 34L;
                partial.values.add(new MultipleCompositeValue("partial", em.find(MultipleCompositeTarget.class, firstKey)));
                transaction(em, () -> em.persist(partial));
                transaction(em, () -> em.createNativeQuery(
                    "update multiple_composite_values set element_second_serial = null where owner_id = 34").executeUpdate());
                em.clear();
                factory.getCache().evictAll();
                assertThatThrownBy(() -> em.find(MultipleCompositeOwner.class, 34L).values.size())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("partially null");
            }
        }
    }

    private PersistenceConfiguration multipleCompositeConfiguration(boolean xml) {
        var configuration = configuration(false, MultipleCompositeOwner.class, MultipleCompositeValue.class,
            MultipleCompositeTarget.class, MultipleCompositeKey.class, CompositeTarget.class, CompositeKey.class,
            SharedClassParent.class, IdClassKey.class);
        if (xml) configuration.mappingFile("orm/element-collection-multiple-composite-mapsid.xml");
        return configuration;
    }

    @Test void multipleCompositeMapsIdCacheKeepsIdentityAndRejectsEachParentChange() {
        for (boolean xml : List.of(false, true)) {
            try (var factory = multipleCompositeConfiguration(xml).sharedCacheMode(SharedCacheMode.ALL)
                    .createEntityManagerFactory()) {
                var key = new MultipleCompositeKey("cached", new CompositeKey(7, 31), new IdClassKey(11, 37));
                try (var em = factory.createEntityManager()) {
                    var first = new CompositeTarget();
                    first.id = new CompositeKey(7, 31);
                    var second = new SharedClassParent();
                    second.region = 11;
                    second.serial = 37;
                    var target = new MultipleCompositeTarget("cached", first, second);
                    var owner = new MultipleCompositeOwner();
                    owner.id = 35L;
                    owner.values.add(new MultipleCompositeValue("first", target));
                    owner.values.add(new MultipleCompositeValue("duplicate", target));
                    transaction(em, () -> { em.persist(target); em.persist(owner); });
                    em.clear();
                    assertThat(em.find(MultipleCompositeOwner.class, 35L).values).hasSize(2);
                }
                assertThat(factory.getCache().contains(MultipleCompositeOwner.class, 35L)).isTrue();
                for (int changed = 0; changed < 3; changed++) {
                    try (var em = factory.createEntityManager()) {
                        var owner = em.find(MultipleCompositeOwner.class, 35L);
                        var target = em.find(MultipleCompositeTarget.class, key);
                        assertThat(owner.values.getFirst().target).isSameAs(owner.values.getLast().target).isSameAs(target);
                        assertThat(target.first).isSameAs(em.find(CompositeTarget.class, key.first));
                        assertThat(target.second).isSameAs(em.find(SharedClassParent.class, key.second));
                        em.getTransaction().begin();
                        switch (changed) {
                            case 0 -> {
                                var other = new CompositeTarget();
                                other.id = new CompositeKey(31, 7);
                                em.persist(other);
                                target.first = other;
                            }
                            case 1 -> {
                                var other = new SharedClassParent();
                                other.region = 37;
                                other.serial = 11;
                                em.persist(other);
                                target.second = other;
                            }
                            case 2 -> target.id.code = "changed";
                            default -> throw new AssertionError(changed);
                        }
                        assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
                        em.getTransaction().rollback();
                    }
                }
            }
        }
    }

    @Test void multipleCompositeMapsIdRecordAndSimpleComponentsKeepDefaultJoinOrder() {
        try (var factory = configuration(false, MixedCompositeTarget.class, MixedCompositeKey.class,
                MixedCompositeOwner.class, MixedCompositeValue.class, CompositeTarget.class, CompositeKey.class,
                SharedClassParent.class, IdClassKey.class, MapsIdElementParent.class).createEntityManagerFactory();
                var em = factory.createEntityManager()) {
            var first = new CompositeTarget();
            first.id = new CompositeKey(13, 29);
            var second = new SharedClassParent();
            second.region = 17;
            second.serial = 41;
            var simple = new MapsIdElementParent("generated");
            var target = new MixedCompositeTarget();
            target.id = new MixedCompositeKey(5, null, 0, null, "first");
            target.first = first;
            target.second = second;
            target.simple = simple;
            var owner = new MixedCompositeOwner();
            owner.id = 36L;
            owner.values.add(new MixedCompositeValue(target));
            owner.values.add(new MixedCompositeValue(target));
            transaction(em, () -> { em.persist(target); em.persist(owner); });
            assertThat(simple.id).isPositive();
            var key = new MixedCompositeKey(5, new CompositeKey(13, 29), simple.id, new IdClassKey(17, 41), "first");
            assertThat(target.id).isEqualTo(key);
            List<?> rows = em.createNativeQuery(
                "select sequence, first_region, first_serial, simple_id, second_region, second_serial, code"
                    + " from MixedCompositeTarget").getResultList();
            assertThat((Object[]) rows.getFirst()).containsExactly(5, 13L, 29L, simple.id, 17L, 41L, "first");
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(MixedCompositeTarget.class, key);
            assertThat(found.id).isEqualTo(key);
            assertThat(found.first).isSameAs(em.find(CompositeTarget.class, key.first()));
            assertThat(found.second).isSameAs(em.find(SharedClassParent.class, key.second()));
            assertThat(found.simple).isSameAs(em.find(MapsIdElementParent.class, simple.id));
            var values = em.find(MixedCompositeOwner.class, 36L).values;
            assertThat(values).hasSize(2);
            assertThat(values.getFirst().target).isSameAs(values.getLast().target).isSameAs(found);
        }
    }

    @Test void recordMapsIdHydratesDerivedPrimitiveWithoutCollectionOwner() {
        try (var factory = configuration(false, MixedCompositeTarget.class, MixedCompositeKey.class,
                CompositeTarget.class, CompositeKey.class, SharedClassParent.class, IdClassKey.class,
                MapsIdElementParent.class).createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var target = new MixedCompositeTarget();
            target.id = new MixedCompositeKey(9, null, 0, null, "isolated");
            target.first = new CompositeTarget();
            target.first.id = new CompositeKey(13, 29);
            target.second = new SharedClassParent();
            target.second.region = 17;
            target.second.serial = 41;
            target.simple = new MapsIdElementParent("isolated");
            transaction(em, () -> em.persist(target));
            var key = target.id;
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(MixedCompositeTarget.class, key);
            assertThat(found.id).isEqualTo(key);
            assertThat(found.simple.id).isEqualTo(key.simpleKey());
            em.refresh(found);
            assertThat(found.id).isEqualTo(key);
            assertThat(em.createQuery("select t from MixedCompositeTarget t", MixedCompositeTarget.class)
                .getSingleResult()).isSameAs(found);
        }
    }

    @Test void recordMapsIdWithOnlyDerivedScalarComponentsLoadsAndRefreshes() {
        try (var factory = configuration(false, ScalarRecordTarget.class, ScalarRecordKey.class,
                MapsIdElementParent.class).createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var target = new ScalarRecordTarget();
            target.id = new ScalarRecordKey(0);
            target.parent = new MapsIdElementParent("scalar-only");
            transaction(em, () -> em.persist(target));
            var key = new ScalarRecordKey(target.parent.id);
            assertThat(target.id).isEqualTo(key);
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(ScalarRecordTarget.class, key);
            assertThat(found.id).isEqualTo(key);
            assertThat(found.parent).isSameAs(em.find(MapsIdElementParent.class, key.parentKey()));
            em.refresh(found);
            assertThat(found.id).isEqualTo(key);
        }
    }

    @Test void multipleCompositeMapsIdKeepsPolymorphicOverlappingAndUnmappedPartsRefused() {
        for (Class<?> subtype : List.of(CompositeParentSubtype.class, SharedClassParentSubtype.class)) {
            assertThatThrownBy(() -> multipleCompositeConfiguration(false).managedClass(subtype)
                .createEntityManagerFactory()).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("requires an eligible primary-key target");
        }
        assertThatThrownBy(() -> multipleCompositeConfiguration(false).managedClass(OverlappingCompositeTarget.class)
            .createEntityManagerFactory()).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("overlapping identifier mappings");
        assertThatThrownBy(() -> configuration(false, UnmappedCompositeTarget.class, MultipleCompositeKey.class,
            CompositeTarget.class, CompositeKey.class, IdClassKey.class).createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("unmapped nested embedded identifier parts");
    }

    @Test void compositeAndSimpleIdClassParentsCanCoexistWithDefaultJoinColumns() {
        try (var factory = factory(false, CompositeParentsOwner.class, CompositeParentsValue.class,
                CompositeParentsTarget.class, CompositeParentsKey.class, CompositeTarget.class, CompositeKey.class,
                MapsIdElementParent.class); var em = factory.createEntityManager()) {
            var composite = new CompositeTarget();
            composite.id = new CompositeKey(3, 19);
            var simple = new MapsIdElementParent("simple");
            var target = new CompositeParentsTarget();
            target.composite = composite;
            target.simple = simple;
            var value = new CompositeParentsValue();
            value.target = target;
            var owner = new CompositeParentsOwner();
            owner.id = 14L;
            owner.values = new ArrayList<>(List.of(value));
            transaction(em, () -> {
                em.persist(composite);
                em.persist(simple);
                em.persist(target);
                em.persist(owner);
            });
            var key = new CompositeParentsKey();
            key.composite = composite.id;
            key.simple = simple.id;
            assertThat(factory.getPersistenceUnitUtil().getIdentifier(target)).isEqualTo(key);
            em.clear();
            factory.getCache().evictAll();
            assertThat(em.find(CompositeParentsOwner.class, 14L).values.getFirst().target)
                .isSameAs(em.find(CompositeParentsTarget.class, key));
        }
    }

    @Test void compositeParentTargetRetainsIdentityAcrossSharedCacheReads() {
        try (var factory = configuration(false, CompositeLinkOwner.class, CompositeLinkValue.class,
                CompositeLinkTarget.class, CompositeLinkKey.class, CompositeTarget.class, CompositeKey.class,
                IdClassTarget.class, IdClassKey.class).sharedCacheMode(SharedCacheMode.ALL).createEntityManagerFactory()) {
            var key = new CompositeLinkKey(new CompositeKey(3, 19), new IdClassKey(7, 23), "cached");
            try (var em = factory.createEntityManager()) {
                var embedded = new CompositeTarget();
                embedded.id = key.embedded;
                var idClass = new IdClassTarget();
                idClass.region = 7;
                idClass.serial = 23;
                var target = new CompositeLinkTarget(embedded, idClass, "cached");
                var owner = new CompositeLinkOwner();
                owner.id = 15L;
                owner.values.add(new CompositeLinkValue("first", target));
                owner.values.add(new CompositeLinkValue("duplicate", target));
                transaction(em, () -> {
                    em.persist(embedded);
                    em.persist(idClass);
                    em.persist(target);
                    em.persist(owner);
                });
                em.clear();
                assertThat(em.find(CompositeLinkOwner.class, 15L).values).hasSize(2);
            }
            assertThat(factory.getCache().contains(CompositeLinkOwner.class, 15L)).isTrue();
            try (var em = factory.createEntityManager()) {
                var found = em.find(CompositeLinkOwner.class, 15L);
                assertThat(found.values.getFirst().target).isSameAs(found.values.getLast().target)
                    .isSameAs(em.find(CompositeLinkTarget.class, key));
                assertThat(found.values.getFirst().target.embedded).isSameAs(em.find(CompositeTarget.class, key.embedded));
                assertThat(found.values.getFirst().target.idClass).isSameAs(em.find(IdClassTarget.class, key.idClass));
            }
        }
    }

    @Test void idClassElementParentsKeepNestedKeysAndPolymorphicParentsRefused() {
        assertThatThrownBy(() -> factory(false, NestedParentOwner.class, NestedParentValue.class,
            NestedParentTarget.class, NestedParentTargetKey.class, NestedParent.class, NestedParentKey.class,
            CompositeKey.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("requires an eligible primary-key target");
        assertThatThrownBy(() -> factory(false, CompositeLinkOwner.class, CompositeLinkValue.class,
            CompositeLinkTarget.class, CompositeLinkKey.class, CompositeTarget.class, CompositeKey.class,
            IdClassTarget.class, IdClassKey.class, CompositeParentSubtype.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("requires an eligible primary-key target");
    }

    @Test void idClassParentsKeepChainedDerivedParentsExplicitlyRefused() {
        assertThatThrownBy(() -> factory(false, ChainedParentsOwner.class, ChainedParentsValue.class,
            ChainedParentsTarget.class, ChainedParentsKey.class, MapsIdElementTarget.class, MapsIdElementParent.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("requires an eligible primary-key target");
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

    @Test void unsupportedBusinessMapsIdOrphanAndCollectionMappingsAreExplicitlyRefused() {
        for (Class<?> owner : List.of(BusinessOwner.class, MapsOwner.class,
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
        @ElementCollection public List<IdClassValue> idClassElements;
    }
    @Embeddable public static class CompositeValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "target_serial", referencedColumnName = "serial"),
            @JoinColumn(name = "target_region", referencedColumnName = "region")
        })
        public CompositeTarget target;
        @OneToOne @JoinColumns({
            @JoinColumn(name = "one_target_serial", referencedColumnName = "serial"),
            @JoinColumn(name = "one_target_region", referencedColumnName = "region")
        })
        public CompositeTarget oneTarget;
    }
    @Entity public static class CompositeTarget { @EmbeddedId public CompositeKey id; }
    @Embeddable public static class CompositeKey implements java.io.Serializable {
        public long region;
        public long serial;
        public CompositeKey() {}
        CompositeKey(long region, long serial) { this.region = region; this.serial = serial; }
        @Override public boolean equals(Object other) {
            return other instanceof CompositeKey key && region == key.region && serial == key.serial;
        }
        @Override public int hashCode() { return java.util.Objects.hash(region, serial); }
    }
    @Entity @IdClass(IdClassKey.class)
    public static class IdClassTarget {
        @Id public long region;
        @Id public long serial;
        public IdClassTarget() {}
    }
    @Embeddable public static class IdClassKey implements java.io.Serializable {
        public long region;
        public long serial;
        public IdClassKey() {}
        IdClassKey(long region, long serial) { this.region = region; this.serial = serial; }
        @Override public boolean equals(Object other) {
            return other instanceof IdClassKey key && region == key.region && serial == key.serial;
        }
        @Override public int hashCode() { return java.util.Objects.hash(region, serial); }
    }
    @Embeddable public static class IdClassValue { @ManyToOne public IdClassTarget target; }
    @Entity(name = "MapsIdElementParent")
    public static class MapsIdElementParent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        public String name;
        public MapsIdElementParent() {}
        MapsIdElementParent(String name) { this.name = name; }
    }
    @Entity(name = "MapsIdElementTarget")
    public static class MapsIdElementTarget {
        @Id public long id;
        @MapsId @OneToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "parent_fk")
        public MapsIdElementParent parent;
    }
    @Entity(name = "MapsIdElementOwner")
    public static class MapsIdElementOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "derived_owner_values", joinColumns = @JoinColumn(name = "owner_id"))
        public List<MapsIdElementValue> values;
    }
    @Embeddable public static class MapsIdElementValue {
        @ManyToOne @JoinColumn(name = "target_fk") public MapsIdElementTarget target;
        public MapsIdElementValue() {}
        MapsIdElementValue(MapsIdElementTarget target) { this.target = target; }
    }
    @Entity(name = "DerivedCompositeOwner") public static class DerivedCompositeOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "derived_composite_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        public List<DerivedCompositeValue> values;
    }
    @Embeddable public static class DerivedCompositeValue {
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "derived_parent_fk", referencedColumnName = "parent_fk"),
            @JoinColumn(name = "derived_code", referencedColumnName = "code")
        })
        public DerivedCompositeTarget target;
    }
    @Entity(name = "DerivedCompositeTarget") public static class DerivedCompositeTarget {
        @EmbeddedId public DerivedCompositeKey id;
        @MapsId("parentId") @ManyToOne @JoinColumn(name = "parent_fk")
        public MapsIdElementParent parent;
    }
    @Embeddable public static class DerivedCompositeKey implements java.io.Serializable {
        public long parentId;
        public String code;
        public DerivedCompositeKey() {}
        DerivedCompositeKey(long parentId, String code) { this.parentId = parentId; this.code = code; }
        @Override public boolean equals(Object other) {
            return other instanceof DerivedCompositeKey key && parentId == key.parentId
                && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(parentId, code); }
    }
    @Entity(name = "IdClassDerivedOwner") public static class IdClassDerivedOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "idclass_derived_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        public List<IdClassDerivedValue> values;
    }
    @Embeddable public static class IdClassDerivedValue {
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "derived_parent_fk", referencedColumnName = "parent_fk"),
            @JoinColumn(name = "derived_code", referencedColumnName = "code")
        })
        public IdClassDerivedTarget target;
    }
    @Entity(name = "IdClassDerivedTarget") @IdClass(IdClassDerivedKey.class)
    public static class IdClassDerivedTarget {
        @Id @ManyToOne @JoinColumn(name = "parent_fk") public MapsIdElementParent parent;
        @Id @Column(name = "code") public String code;
    }
    public static class IdClassDerivedKey implements java.io.Serializable {
        public long parent;
        public String code;
        public IdClassDerivedKey() {}
        IdClassDerivedKey(long parent, String code) { this.parent = parent; this.code = code; }
        @Override public boolean equals(Object other) {
            return other instanceof IdClassDerivedKey key && parent == key.parent
                && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(parent, code); }
    }
    @Entity(name = "MultiParentOwner") public static class MultiParentOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "multi_parent_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<MultiParentValue> values = new ArrayList<>();
    }
    @Embeddable public static class MultiParentValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_destination", referencedColumnName = "destination_fk"),
            @JoinColumn(name = "element_code", referencedColumnName = "route_code"),
            @JoinColumn(name = "element_source", referencedColumnName = "source_fk")
        })
        public MultiParentTarget target;
        public MultiParentValue() {}
        MultiParentValue(String label, MultiParentTarget target) { this.label = label; this.target = target; }
    }
    @Entity(name = "MultiParentTarget") @IdClass(MultiParentKey.class)
    public static class MultiParentTarget {
        @Id @ManyToOne @JoinColumn(name = "source_fk") public MapsIdElementParent source;
        @Id @ManyToOne @JoinColumn(name = "destination_fk") public MapsIdElementParent destination;
        @Id @Column(name = "route_code") public String code;
        public MultiParentTarget() {}
        MultiParentTarget(MapsIdElementParent source, MapsIdElementParent destination, String code) {
            this.source = source; this.destination = destination; this.code = code;
        }
    }
    public static class MultiParentKey implements java.io.Serializable {
        public long source;
        public long destination;
        public String code;
        public MultiParentKey() {}
        MultiParentKey(long source, long destination, String code) {
            this.source = source; this.destination = destination; this.code = code;
        }
        @Override public boolean equals(Object other) {
            return other instanceof MultiParentKey key && source == key.source && destination == key.destination
                && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(source, destination, code); }
    }
    @Embeddable public static class NamedCompositeKey implements java.io.Serializable {
        public String code;
        @Embedded public CompositeKey parentKey;
        public NamedCompositeKey() {}
        NamedCompositeKey(String code, CompositeKey parentKey) { this.code = code; this.parentKey = parentKey; }
        @Override public boolean equals(Object other) {
            return other instanceof NamedCompositeKey key && java.util.Objects.equals(code, key.code)
                && java.util.Objects.equals(parentKey, key.parentKey);
        }
        @Override public int hashCode() { return java.util.Objects.hash(code, parentKey); }
    }
    @Entity(name = "NamedCompositeTarget") public static class NamedCompositeTarget {
        @EmbeddedId public NamedCompositeKey id;
        @MapsId("parentKey") @ManyToOne(cascade = CascadeType.PERSIST) @JoinColumns({
            @JoinColumn(name = "named_serial_fk", referencedColumnName = "serial"),
            @JoinColumn(name = "named_region_fk", referencedColumnName = "region")
        })
        public CompositeTarget parent;
        public NamedCompositeTarget() {}
        NamedCompositeTarget(String code, CompositeTarget parent) {
            id = new NamedCompositeKey(code, null);
            this.parent = parent;
        }
    }
    @Entity(name = "NamedCompositeOwner") public static class NamedCompositeOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "named_composite_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<NamedCompositeValue> values = new ArrayList<>();
    }
    @Embeddable public static class NamedCompositeValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_serial", referencedColumnName = "named_serial_fk"),
            @JoinColumn(name = "element_code", referencedColumnName = "code"),
            @JoinColumn(name = "element_region", referencedColumnName = "named_region_fk")
        })
        public NamedCompositeTarget target;
        public NamedCompositeValue() {}
        NamedCompositeValue(String label, NamedCompositeTarget target) { this.label = label; this.target = target; }
    }
    @Embeddable public record RecordNamedCompositeKey(
        int sequence, @Embedded CompositeKey parentKey, String code) implements java.io.Serializable {}
    @Entity public static class DefaultNamedCompositeTarget {
        @EmbeddedId public RecordNamedCompositeKey id;
        @MapsId("parentKey") @ManyToOne(cascade = CascadeType.PERSIST) public CompositeTarget parent;
    }
    @Entity public static class DefaultNamedCompositeOwner {
        @Id public Long id;
        @ElementCollection @OrderColumn public List<DefaultNamedCompositeValue> values = new ArrayList<>();
    }
    @Embeddable public static class DefaultNamedCompositeValue {
        @ManyToOne public DefaultNamedCompositeTarget target;
        public DefaultNamedCompositeValue() {}
        DefaultNamedCompositeValue(DefaultNamedCompositeTarget target) { this.target = target; }
    }
    @Embeddable public static class NamedClassCompositeKey implements java.io.Serializable {
        public String code;
        @Embedded public IdClassKey parentKey;
        public NamedClassCompositeKey() {}
        NamedClassCompositeKey(String code, IdClassKey parentKey) { this.code = code; this.parentKey = parentKey; }
        @Override public boolean equals(Object other) {
            return other instanceof NamedClassCompositeKey key && java.util.Objects.equals(code, key.code)
                && java.util.Objects.equals(parentKey, key.parentKey);
        }
        @Override public int hashCode() { return java.util.Objects.hash(code, parentKey); }
    }
    @Entity(name = "NamedClassCompositeTarget") public static class NamedClassCompositeTarget {
        @EmbeddedId public NamedClassCompositeKey id;
        @MapsId("parentKey") @ManyToOne(cascade = CascadeType.PERSIST) @JoinColumns({
            @JoinColumn(name = "named_class_region_fk", referencedColumnName = "region"),
            @JoinColumn(name = "named_class_serial_fk", referencedColumnName = "serial")
        })
        public SharedClassParent parent;
        public NamedClassCompositeTarget() {}
        NamedClassCompositeTarget(String code, SharedClassParent parent) {
            id = new NamedClassCompositeKey(code, null);
            this.parent = parent;
        }
    }
    @Embeddable public static class NamedClassCompositeValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_serial", referencedColumnName = "named_class_serial_fk"),
            @JoinColumn(name = "element_code", referencedColumnName = "code"),
            @JoinColumn(name = "element_region", referencedColumnName = "named_class_region_fk")
        })
        public NamedClassCompositeTarget target;
        public NamedClassCompositeValue() {}
        NamedClassCompositeValue(String label, NamedClassCompositeTarget target) { this.label = label; this.target = target; }
    }
    @Entity(name = "NamedClassCompositeOwner") public static class NamedClassCompositeOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "named_class_values", joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<NamedClassCompositeValue> values = new ArrayList<>();
    }
    @Embeddable public record RecordNamedClassKey(
        int sequence, @Embedded IdClassKey parentKey, String code) implements java.io.Serializable {}
    @Entity public static class DefaultNamedClassTarget {
        @EmbeddedId public RecordNamedClassKey id;
        @MapsId("parentKey") @ManyToOne(cascade = CascadeType.PERSIST) public SharedClassParent parent;
    }
    @Entity public static class DefaultNamedClassOwner {
        @Id public Long id;
        @ElementCollection @OrderColumn public List<DefaultNamedClassValue> values = new ArrayList<>();
    }
    @Embeddable public static class DefaultNamedClassValue {
        @ManyToOne public DefaultNamedClassTarget target;
        public DefaultNamedClassValue() {}
        DefaultNamedClassValue(DefaultNamedClassTarget target) { this.target = target; }
    }
    @Entity public static class MismatchedNamedClassTarget {
        @EmbeddedId public NamedCompositeKey id;
        @MapsId("parentKey") @ManyToOne public SharedClassParent parent;
    }
    @Embeddable public static class MultipleCompositeKey implements java.io.Serializable {
        public String code;
        @Embedded public CompositeKey first;
        @Embedded public IdClassKey second;
        public MultipleCompositeKey() {}
        MultipleCompositeKey(String code, CompositeKey first, IdClassKey second) {
            this.code = code;
            this.first = first;
            this.second = second;
        }
        @Override public boolean equals(Object other) {
            return other instanceof MultipleCompositeKey key && java.util.Objects.equals(code, key.code)
                && java.util.Objects.equals(first, key.first) && java.util.Objects.equals(second, key.second);
        }
        @Override public int hashCode() { return java.util.Objects.hash(code, first, second); }
    }
    @Entity(name = "MultipleCompositeTarget") public static class MultipleCompositeTarget {
        @EmbeddedId public MultipleCompositeKey id;
        @MapsId("second") @ManyToOne(cascade = CascadeType.PERSIST) @JoinColumns({
            @JoinColumn(name = "second_region_fk", referencedColumnName = "region"),
            @JoinColumn(name = "second_serial_fk", referencedColumnName = "serial")
        })
        public SharedClassParent second;
        @MapsId("first") @ManyToOne(cascade = CascadeType.PERSIST) @JoinColumns({
            @JoinColumn(name = "first_serial_fk", referencedColumnName = "serial"),
            @JoinColumn(name = "first_region_fk", referencedColumnName = "region")
        })
        public CompositeTarget first;
        public MultipleCompositeTarget() {}
        MultipleCompositeTarget(String code, CompositeTarget first, SharedClassParent second) {
            id = new MultipleCompositeKey(code, null, null);
            this.first = first;
            this.second = second;
        }
    }
    @Embeddable public static class MultipleCompositeValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_second_region", referencedColumnName = "second_region_fk"),
            @JoinColumn(name = "element_first_serial", referencedColumnName = "first_serial_fk"),
            @JoinColumn(name = "element_code", referencedColumnName = "code"),
            @JoinColumn(name = "element_second_serial", referencedColumnName = "second_serial_fk"),
            @JoinColumn(name = "element_first_region", referencedColumnName = "first_region_fk")
        })
        public MultipleCompositeTarget target;
        public MultipleCompositeValue() {}
        MultipleCompositeValue(String label, MultipleCompositeTarget target) { this.label = label; this.target = target; }
    }
    @Entity(name = "MultipleCompositeOwner") public static class MultipleCompositeOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "multiple_composite_values", joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<MultipleCompositeValue> values = new ArrayList<>();
    }
    @Embeddable public record MixedCompositeKey(
        int sequence, @Embedded CompositeKey first, long simpleKey, @Embedded IdClassKey second,
        String code) implements java.io.Serializable {}
    @Entity public static class MixedCompositeTarget {
        @EmbeddedId public MixedCompositeKey id;
        @MapsId("second") @ManyToOne(cascade = CascadeType.PERSIST) public SharedClassParent second;
        @MapsId("simpleKey") @ManyToOne(cascade = CascadeType.PERSIST) public MapsIdElementParent simple;
        @MapsId("first") @ManyToOne(cascade = CascadeType.PERSIST) public CompositeTarget first;
    }
    @Entity public static class MixedCompositeOwner {
        @Id public Long id;
        @ElementCollection @OrderColumn public List<MixedCompositeValue> values = new ArrayList<>();
    }
    @Embeddable public static class MixedCompositeValue {
        @ManyToOne public MixedCompositeTarget target;
        public MixedCompositeValue() {}
        MixedCompositeValue(MixedCompositeTarget target) { this.target = target; }
    }
    @Entity public static class OverlappingCompositeTarget extends MultipleCompositeTarget {
        @MapsId("first") @ManyToOne public CompositeTarget duplicate;
    }
    @Embeddable public record ScalarRecordKey(long parentKey) implements java.io.Serializable {}
    @Entity public static class ScalarRecordTarget {
        @EmbeddedId public ScalarRecordKey id;
        @MapsId("parentKey") @ManyToOne(cascade = CascadeType.PERSIST) public MapsIdElementParent parent;
    }
    @Entity public static class UnmappedCompositeTarget {
        @EmbeddedId public MultipleCompositeKey id;
        @MapsId("first") @ManyToOne public CompositeTarget first;
    }
    @Entity(name = "SharedIdClassOwner") public static class SharedIdClassOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "shared_idclass_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<SharedIdClassValue> values = new ArrayList<>();
    }
    @Embeddable public static class SharedIdClassValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_serial", referencedColumnName = "class_serial_fk"),
            @JoinColumn(name = "element_region", referencedColumnName = "class_region_fk")
        })
        public SharedIdClassTarget target;
        public SharedIdClassValue() {}
        SharedIdClassValue(String label, SharedIdClassTarget target) { this.label = label; this.target = target; }
    }
    @Entity @IdClass(IdClassKey.class) public static class SharedClassParent {
        @Id public long serial;
        @Id public long region;
    }
    @Entity public static class SharedClassParentSubtype extends SharedClassParent {}
    @Entity public static class MismatchedSharedClassTarget {
        @EmbeddedId public CompositeKey id;
        @MapsId @OneToOne public SharedClassParent parent;
    }
    @Entity(name = "SharedIdClassTarget") public static class SharedIdClassTarget {
        @EmbeddedId public IdClassKey id;
        @MapsId @OneToOne(cascade = CascadeType.PERSIST) @JoinColumns({
            @JoinColumn(name = "class_serial_fk", referencedColumnName = "serial"),
            @JoinColumn(name = "class_region_fk", referencedColumnName = "region")
        })
        public SharedClassParent parent;
        public SharedIdClassTarget() {}
        SharedIdClassTarget(SharedClassParent parent) { this.parent = parent; }
    }
    @Entity(name = "SharedCompositeOwner") public static class SharedCompositeOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "shared_composite_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<SharedCompositeValue> values = new ArrayList<>();
    }
    @Embeddable public static class SharedCompositeValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_serial", referencedColumnName = "shared_serial_fk"),
            @JoinColumn(name = "element_region", referencedColumnName = "shared_region_fk")
        })
        public SharedCompositeTarget target;
        public SharedCompositeValue() {}
        SharedCompositeValue(String label, SharedCompositeTarget target) { this.label = label; this.target = target; }
    }
    @Entity(name = "SharedCompositeTarget") public static class SharedCompositeTarget {
        @EmbeddedId public CompositeKey id;
        @MapsId @OneToOne(cascade = CascadeType.PERSIST) @JoinColumns({
            @JoinColumn(name = "shared_serial_fk", referencedColumnName = "serial"),
            @JoinColumn(name = "shared_region_fk", referencedColumnName = "region")
        })
        public CompositeTarget parent;
        public SharedCompositeTarget() {}
        SharedCompositeTarget(CompositeTarget parent) { this.parent = parent; }
    }
    @Entity public static class MapsParentSubtype extends MapsIdElementParent {}
    @Entity(name = "MultiMapsOwner") public static class MultiMapsOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "multi_maps_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<MultiMapsValue> values = new ArrayList<>();
    }
    @Embeddable public static class MultiMapsValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_destination", referencedColumnName = "destination_fk"),
            @JoinColumn(name = "element_code", referencedColumnName = "route_code"),
            @JoinColumn(name = "element_source", referencedColumnName = "source_fk")
        })
        public MultiMapsTarget target;
        public MultiMapsValue() {}
        MultiMapsValue(String label, MultiMapsTarget target) { this.label = label; this.target = target; }
    }
    @Entity(name = "MultiMapsTarget") public static class MultiMapsTarget {
        @EmbeddedId public MultiMapsKey id;
        @MapsId("sourceId") @ManyToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "source_fk") public MapsIdElementParent source;
        @MapsId("destinationId") @ManyToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "destination_fk") public MapsIdElementParent destination;
        public MultiMapsTarget() {}
        MultiMapsTarget(MapsIdElementParent source, MapsIdElementParent destination, String code) {
            this.source = source; this.destination = destination; this.id = new MultiMapsKey(0, 0, code);
        }
    }
    @Embeddable public static class MultiMapsKey implements java.io.Serializable {
        public long sourceId;
        public long destinationId;
        @Column(name = "route_code") public String code;
        public MultiMapsKey() {}
        MultiMapsKey(long sourceId, long destinationId, String code) {
            this.sourceId = sourceId; this.destinationId = destinationId; this.code = code;
        }
        @Override public boolean equals(Object other) {
            return other instanceof MultiMapsKey key && sourceId == key.sourceId && destinationId == key.destinationId
                && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(sourceId, destinationId, code); }
    }
    @Entity(name = "CompositeLinkOwner") public static class CompositeLinkOwner {
        @Id public Long id;
        @ElementCollection @CollectionTable(name = "composite_link_values",
            joinColumns = @JoinColumn(name = "owner_id"))
        @OrderColumn(name = "value_position")
        public List<CompositeLinkValue> values = new ArrayList<>();
    }
    @Embeddable public static class CompositeLinkValue {
        public String label;
        @ManyToOne @JoinColumns({
            @JoinColumn(name = "element_idclass_serial", referencedColumnName = "idclass_serial_fk"),
            @JoinColumn(name = "element_embedded_serial", referencedColumnName = "embedded_serial_fk"),
            @JoinColumn(name = "element_code", referencedColumnName = "link_code"),
            @JoinColumn(name = "element_embedded_region", referencedColumnName = "embedded_region_fk"),
            @JoinColumn(name = "element_idclass_region", referencedColumnName = "idclass_region_fk")
        })
        public CompositeLinkTarget target;
        public CompositeLinkValue() {}
        CompositeLinkValue(String label, CompositeLinkTarget target) { this.label = label; this.target = target; }
    }
    @Entity(name = "CompositeLinkTarget") @IdClass(CompositeLinkKey.class)
    public static class CompositeLinkTarget {
        @Id @ManyToOne @JoinColumns({
            @JoinColumn(name = "embedded_serial_fk", referencedColumnName = "serial"),
            @JoinColumn(name = "embedded_region_fk", referencedColumnName = "region")
        })
        public CompositeTarget embedded;
        @Id @ManyToOne @JoinColumns({
            @JoinColumn(name = "idclass_serial_fk", referencedColumnName = "serial"),
            @JoinColumn(name = "idclass_region_fk", referencedColumnName = "region")
        })
        public IdClassTarget idClass;
        @Id @Column(name = "link_code") public String code;
        public CompositeLinkTarget() {}
        CompositeLinkTarget(CompositeTarget embedded, IdClassTarget idClass, String code) {
            this.embedded = embedded; this.idClass = idClass; this.code = code;
        }
    }
    public static class CompositeLinkKey implements java.io.Serializable {
        public CompositeKey embedded;
        public IdClassKey idClass;
        public String code;
        public CompositeLinkKey() {}
        CompositeLinkKey(CompositeKey embedded, IdClassKey idClass, String code) {
            this.embedded = embedded; this.idClass = idClass; this.code = code;
        }
        @Override public boolean equals(Object other) {
            return other instanceof CompositeLinkKey key && java.util.Objects.equals(embedded, key.embedded)
                && java.util.Objects.equals(idClass, key.idClass) && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(embedded, idClass, code); }
    }
    @Entity public static class CompositeParentsOwner {
        @Id public Long id;
        @ElementCollection public List<CompositeParentsValue> values;
    }
    @Embeddable public static class CompositeParentsValue {
        @ManyToOne public CompositeParentsTarget target;
    }
    @Entity @IdClass(CompositeParentsKey.class) public static class CompositeParentsTarget {
        @Id @ManyToOne public CompositeTarget composite;
        @Id @ManyToOne public MapsIdElementParent simple;
    }
    public static class CompositeParentsKey implements java.io.Serializable {
        public CompositeKey composite;
        public long simple;
        public CompositeParentsKey() {}
        @Override public boolean equals(Object other) {
            return other instanceof CompositeParentsKey key && simple == key.simple
                && java.util.Objects.equals(composite, key.composite);
        }
        @Override public int hashCode() { return java.util.Objects.hash(composite, simple); }
    }
    @Entity public static class CompositeParentSubtype extends CompositeTarget {}
    @Entity public static class NestedParentOwner {
        @Id public Long id;
        @ElementCollection public List<NestedParentValue> values;
    }
    @Embeddable public static class NestedParentValue {
        @ManyToOne public NestedParentTarget target;
    }
    @Entity @IdClass(NestedParentTargetKey.class) public static class NestedParentTarget {
        @Id @ManyToOne public NestedParent parent;
        @Id public String code;
    }
    public static class NestedParentTargetKey implements java.io.Serializable {
        public NestedParentKey parent;
        public String code;
        public NestedParentTargetKey() {}
        @Override public boolean equals(Object other) {
            return other instanceof NestedParentTargetKey key && java.util.Objects.equals(parent, key.parent)
                && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(parent, code); }
    }
    @Entity public static class NestedParent { @EmbeddedId public NestedParentKey id; }
    @Embeddable public static class NestedParentKey implements java.io.Serializable {
        @Embedded public CompositeKey nested;
        public String code;
        public NestedParentKey() {}
        @Override public boolean equals(Object other) {
            return other instanceof NestedParentKey key && java.util.Objects.equals(nested, key.nested)
                && java.util.Objects.equals(code, key.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(nested, code); }
    }
    @Entity public static class ChainedParentsOwner {
        @Id public Long id;
        @ElementCollection public List<ChainedParentsValue> values;
    }
    @Embeddable public static class ChainedParentsValue {
        @ManyToOne public ChainedParentsTarget target;
    }
    @Entity @IdClass(ChainedParentsKey.class) public static class ChainedParentsTarget {
        @Id @ManyToOne public MapsIdElementTarget derived;
        @Id @ManyToOne public MapsIdElementParent simple;
    }
    public static class ChainedParentsKey implements java.io.Serializable {
        public long derived;
        public long simple;
        public ChainedParentsKey() {}
        @Override public boolean equals(Object other) {
            return other instanceof ChainedParentsKey key && derived == key.derived && simple == key.simple;
        }
        @Override public int hashCode() { return java.util.Objects.hash(derived, simple); }
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
