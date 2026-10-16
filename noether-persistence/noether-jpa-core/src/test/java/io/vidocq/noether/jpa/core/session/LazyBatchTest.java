/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** §2.7, §2.10, §3.3.7: batching changes SQL counts, never managed identity or collection contents. */
class LazyBatchTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String SETTING = "io.vidocq.noether.jpa.fetch.batch-size";
    private final List<String> sql = new ArrayList<>();
    private EntityManagerFactory factory;
    private EntityManager em;

    private void open(Object size) {
        var source = new CountingDataSource("jdbc:h2:mem:lazy-batch-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1") {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("prepare") && args[0] instanceof String statement) sql.add(statement);
                        try { return method.invoke(connection, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            }
        };
        var configuration = new PersistenceConfiguration("lazy-batch")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Owner.class).managedClass(Target.class).managedClass(Child.class).managedClass(Part.class)
            .managedClass(CompositeOwner.class).managedClass(CompositeKey.class)
            .managedClass(CompositeRoot.class)
            .managedClass(BaseOwner.class).managedClass(SpecialOwner.class)
            .managedClass(EagerOwner.class)
            .sharedCacheMode(SharedCacheMode.ALL)
            .property("jakarta.persistence.nonJtaDataSource", source)
            .property("jakarta.persistence.schema-generation.database.action", "create");
        if (size != null) configuration.property(SETTING, size);
        factory = configuration.createEntityManagerFactory();
        em = factory.createEntityManager();
    }

    private List<Owner> seed() {
        em.getTransaction().begin();
        for (long id = 1; id <= 5; id++) {
            Target target = new Target();
            target.setId(id); target.setName("target-" + id); em.persist(target);
            Owner owner = new Owner();
            owner.setId(id); owner.setName("owner-" + id); owner.setTarget(target);
            if (id != 2) {
                owner.getValues().addAll(Arrays.asList("z", "a", "z", null));
                owner.getLabels().put("nil", null);
                owner.getLabels().put("id", Long.toString(id));
                owner.getTargets().add(target);
                owner.getParts().add(new Part("present", 9));
            }
            em.persist(owner);
            if (id != 2) {
                Child child = new Child();
                child.id = id; child.owner = owner; em.persist(child);
            }
        }
        Owner duplicate = new Owner();
        duplicate.setId(6); duplicate.setTarget(em.find(Target.class, 1L)); em.persist(duplicate);
        em.getTransaction().commit();
        em.clear(); factory.getCache().evictAll(); sql.clear();
        List<Owner> owners = em.createQuery("select o from BatchOwner o order by o.id", Owner.class).getResultList();
        sql.clear();
        return owners;
    }

    private long selects() {
        return sql.stream().filter(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")).count();
    }

    @AfterEach void close() { if (factory != null && factory.isOpen()) factory.close(); }

    /** §3.7.4: intermediate composite IDs, not result count, determine each collection's parameter bound. */
    @Test void rootToOneGraphsGroupCompositeOwnersWithinTheParameterCeiling() {
        open(256);
        em.getTransaction().begin();
        for (int i = 1; i <= 181; i++) {
            var owner = new CompositeOwner();
            owner.id = new CompositeKey(9, i);
            if (i != 2) owner.items.addAll(Arrays.asList("first", null, "first"));
            owner.labels.put("nil", null);
            owner.labels.put("id", Integer.toString(i));
            em.persist(owner);
            for (int copy = 0; copy < 2; copy++) {
                var root = new CompositeRoot();
                root.id = 2L * i + copy;
                root.owner = owner;
                em.persist(root);
            }
        }
        var absent = new CompositeRoot();
        absent.id = 1;
        em.persist(absent);
        em.getTransaction().commit();
        em.clear();
        factory.getCache().evictAll();
        for (String hint : List.of("jakarta.persistence.fetchgraph", "jakarta.persistence.loadgraph")) {
            em.clear();
            factory.getCache().evictAll();
            sql.clear();
            var graph = em.createEntityGraph(CompositeRoot.class);
            graph.addSubgraph("owner").addAttributeNodes("items", "labels");
            var found = em.createQuery("select r from BatchCompositeRoot r order by r.id", CompositeRoot.class)
                .setHint(hint, graph).getResultList();
            assertThat(found).hasSize(363);
            assertThat(found.getFirst().owner).isNull();
            for (int i = 1; i <= 181; i++) {
                var owner = found.get(2 * i - 1).owner;
                assertThat(owner).isSameAs(found.get(2 * i).owner);
                if (i == 2) assertThat(owner.items).isEmpty();
                else assertThat(owner.items).containsExactly("first", null, "first");
                assertThat(owner.labels).containsEntry("nil", null).containsEntry("id", Integer.toString(i));
            }
            assertThat(selects()).as(sql.toString()).isEqualTo(7); // root IDs, two seed batches, two batches per collection
            assertThat(sql).allMatch(s -> s.chars().filter(c -> c == '?').count() <= 512);
        }
        sql.clear();
        em.getTransaction().begin();
        var graph = em.createEntityGraph(CompositeRoot.class);
        graph.addSubgraph("owner").addAttributeNodes("items", "labels");
        var first = em.find(CompositeRoot.class, 2L, Map.of("jakarta.persistence.fetchgraph", graph));
        first.owner.items.remove(0);
        first.owner.labels.put("new", "value");
        em.getTransaction().commit();
        em.clear();
        factory.getCache().evictAll();
        sql.clear();
        first = em.find(CompositeRoot.class, 2L, Map.of("jakarta.persistence.loadgraph", graph));
        assertThat(first.owner.items).containsExactly(null, "first");
        assertThat(first.owner.labels).containsEntry("new", "value").containsEntry("nil", null);
        assertThat(selects()).isEqualTo(3);
    }

    @Test void defaultBatchCompletesAllCompatibleOwnersIncludingEmptyAndNullRows() {
        open(null); var owners = seed();
        assertThat(owners.getFirst().getValues()).containsExactly("z", "a", "z", null);
        assertThat(selects()).isEqualTo(1);
        for (Owner owner : owners) {
            assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "values")).isTrue();
            if (owner.getId() == 2 || owner.getId() == 6) assertThat(owner.getValues()).isEmpty();
            else assertThat(owner.getValues()).containsExactly("z", "a", "z", null);
            assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "labels")).isFalse();
        }
        assertThat(selects()).isEqualTo(1);
        assertThat(owners.getFirst().getLabels()).containsEntry("nil", null).containsEntry("id", "1");
        for (Owner owner : owners) owner.getLabels().size();
        assertThat(selects()).isEqualTo(2);
    }

    @Test void configuredCollectionBoundAndRemainderNeverInitializeMoreOwners() {
        open(2); var owners = seed();
        owners.getFirst().getValues().size();
        assertThat(selects()).isEqualTo(1);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owners.get(1), "values")).isTrue();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owners.get(2), "values")).isFalse();
        for (Owner owner : owners) owner.getValues().size();
        assertThat(selects()).isEqualTo(3);
    }

    @Test void toOneBatchHydratesReferencesInPlaceWithoutIdentifierOrGetterSql() {
        open(2); var owners = seed();
        var references = owners.stream().map(Owner::getTarget).toList();
        for (Target target : references) target.getId();
        assertThat(selects()).isZero();
        assertThat(references.get(5)).isSameAs(references.getFirst());
        assertThat(references.getFirst().getName()).isEqualTo("target-1");
        assertThat(selects()).isEqualTo(1);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(references.get(1))).isTrue();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(references.get(2))).isFalse();
        for (Target target : references) {
            assertThat(target.getName()).isEqualTo("target-" + target.getId());
            assertThat(em.find(Target.class, target.getId())).isSameAs(target);
        }
        assertThat(selects()).isEqualTo(3);
    }

    @Test void entityCollectionsBatchTheirRowsAndHydrateTargetsWithoutFindPerElement() {
        open(2); var owners = seed();
        assertThat(owners.getFirst().getTargets()).extracting(Target::getId).containsExactly(1L);
        assertThat(selects()).isEqualTo(2); // collection rows, then complete target state
        assertThat(owners.getFirst().getTargets().getFirst()).isSameAs(owners.getFirst().getTarget());
        assertThat(owners.getFirst().getTarget().getName()).isEqualTo("target-1");
        assertThat(owners.get(1).getTargets()).isEmpty();
        for (Owner owner : owners) owner.getTargets().size();
        assertThat(selects()).isEqualTo(6);
    }

    @Test void disabledBatchUsesIndividualLoads() {
        open(1); var owners = seed();
        for (Owner owner : owners) owner.getValues().size();
        assertThat(selects()).isEqualTo(6);
        sql.clear();
        for (Owner owner : owners) owner.getTarget().getName();
        assertThat(selects()).isEqualTo(5);
    }

    @Test void inverseForeignKeyCollectionsBatchRowsAndKeepBackReferenceIdentity() {
        open(2); var owners = seed();
        assertThat(owners.getFirst().getChildren()).extracting(c -> c.id).containsExactly(1L);
        assertThat(selects()).isEqualTo(2);
        for (Owner owner : owners) {
            for (Child child : owner.getChildren()) assertThat(child.owner).isSameAs(owner);
        }
        assertThat(selects()).isEqualTo(6);
    }

    @Test void oneOwnerWithManyElementsUsesBoundedEntityStateQueriesNotOneFindPerElement() {
        open(2); seed();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        for (long id = 2; id <= 5; id++) owner.getTargets().add(em.find(Target.class, id));
        em.getTransaction().commit();
        em.clear(); factory.getCache().evictAll();
        owner = em.find(Owner.class, 1L); sql.clear();
        assertThat(owner.getTargets()).extracting(Target::getName)
            .containsExactly("target-1", "target-2", "target-3", "target-4", "target-5");
        assertThat(selects()).isEqualTo(4); // one collection read plus ceil(5 / 2) full-state reads
    }

    @Test void compositeOwnerKeysUseWholeTuplesAndDistributeNullAndEmptyCollections() {
        open(2);
        em.getTransaction().begin();
        for (long id = 1; id <= 3; id++) {
            CompositeOwner owner = new CompositeOwner();
            owner.id = new CompositeKey(1, id);
            if (id != 2) owner.items.addAll(Arrays.asList("v-" + id, null));
            em.persist(owner);
        }
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll();
        var owners = em.createQuery("select o from BatchCompositeOwner o order by o.id.part", CompositeOwner.class).getResultList();
        sql.clear();
        assertThat(owners.getFirst().items).containsExactly("v-1", null);
        assertThat(owners.get(1).items).isEmpty();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owners.get(2), "items")).isFalse();
        assertThat(selects()).isEqualTo(1);
        assertThat(owners.get(2).items).containsExactly("v-3", null);
        assertThat(selects()).isEqualTo(2);
    }

    @Test void polymorphicOwnersRetainCompleteOrdinaryCollectionLoading() {
        open(2);
        em.getTransaction().begin();
        for (long id = 1; id <= 2; id++) {
            SpecialOwner owner = new SpecialOwner(); owner.id = id;
            owner.items.addAll(Arrays.asList("v-" + id, null)); em.persist(owner);
        }
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll();
        var owners = em.createQuery("select o from BatchBaseOwner o order by o.id", BaseOwner.class).getResultList();
        sql.clear();
        assertThat(owners.getFirst().items).containsExactly("v-1", null);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owners.get(1), "items")).isFalse();
        assertThat(owners.get(1).items).containsExactly("v-2", null);
        assertThat(selects()).isEqualTo(2);
    }

    @Test void missingRequestedReferenceFailsWithoutMarkingItLoadedAndOtherGroupsStillLoad() {
        open(2); var owners = seed();
        em.getTransaction().begin();
        em.createNativeQuery("delete from BatchOwner where id = 2").executeUpdate();
        em.createNativeQuery("delete from BatchTarget where id = 2").executeUpdate();
        em.getTransaction().commit();
        Target missing = owners.get(1).getTarget();
        assertThatThrownBy(missing::getName).isInstanceOf(EntityNotFoundException.class);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(missing)).isFalse();
        assertThat(owners.getFirst().getTarget().getName()).isEqualTo("target-1");
    }

    @Test void collectingCollectionCandidatesNeverInitializesUnloadedOwnerReferences() {
        open(2); seed();
        em.getTransaction().begin();
        em.find(Owner.class, 1L).setParent(em.find(Owner.class, 5L));
        em.getTransaction().commit();
        em.clear(); factory.getCache().evictAll();
        Owner owner = em.find(Owner.class, 1L);
        Owner parent = owner.getParent();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(parent)).isFalse();
        sql.clear();
        assertThat(owner.getValues()).containsExactly("z", "a", "z", null);
        assertThat(selects()).isEqualTo(1);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(parent)).isFalse();
    }

    @Test void compositeGroupsRespectTheParameterBudgetAtMaximumConfiguredSize() {
        open(256);
        em.getTransaction().begin();
        for (long id = 1; id <= 173; id++) {
            CompositeOwner owner = new CompositeOwner(); owner.id = new CompositeKey(1, id);
            owner.items.add("item-" + id); em.persist(owner);
        }
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll();
        var owners = em.createQuery("select o from BatchCompositeOwner o order by o.id.part", CompositeOwner.class).getResultList();
        sql.clear();
        owners.getFirst().items.size();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owners.get(169), "items")).isTrue();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owners.get(170), "items")).isFalse();
        assertThat(selects()).isEqualTo(1);
        for (CompositeOwner owner : owners) assertThat(owner.items).containsExactly("item-" + owner.id.part);
        assertThat(selects()).isEqualTo(2);
        assertThat(sql).allMatch(s -> s.chars().filter(c -> c == '?').count() <= 512);
    }

    /** §3.7.4: the second collection edge uses its own owner-key width, not the root's batch bound. */
    @Test void nestedCompositeOwnersRespectParameterCeilingAndDeduplicateSharedOwners() {
        open(256);
        em.getTransaction().begin();
        Owner root = new Owner(); root.setId(1);
        for (long id = 1; id <= 173; id++) {
            CompositeOwner owner = new CompositeOwner();
            owner.id = new CompositeKey(id % 2, id);
            owner.items.addAll(Arrays.asList("item-" + id, null, "item-" + id));
            owner.labels.put("id", Long.toString(id));
            root.getComposites().add(owner);
            em.persist(owner);
        }
        root.getComposites().add(root.getComposites().getFirst());
        em.persist(root);
        em.getTransaction().commit();
        for (boolean find : List.of(false, true)) {
            em.clear(); factory.getCache().evictAll(); sql.clear();
            var graph = em.createEntityGraph(Owner.class);
            graph.addSubgraph("composites").addAttributeNodes("items", "labels");
            Owner found = find ? em.find(Owner.class, 1L, Map.of("jakarta.persistence.loadgraph", graph))
                : em.createQuery("select o from BatchOwner o", Owner.class)
                    .setHint("jakarta.persistence.fetchgraph", graph).getSingleResult();
            assertThat(found.getComposites()).hasSize(174);
            assertThat(found.getComposites().getLast()).isSameAs(found.getComposites().getFirst());
            for (CompositeOwner owner : found.getComposites()) {
                assertThat(owner.items).containsExactly("item-" + owner.id.part, null, "item-" + owner.id.part);
                assertThat(owner.labels).containsEntry("id", Long.toString(owner.id.part)).hasSize(1);
            }
            assertThat(selects()).isEqualTo(find ? 5 : 6);
            assertThat(sql).allMatch(s -> s.chars().filter(c -> c == '?').count() <= 512);
        }
    }

    @Test void nestedCollectorsIncludeMappedEagerCollectionsAndToOnesForFetchAndLoad() {
        open(16);
        em.getTransaction().begin();
        Owner root = new Owner(); root.setId(1);
        Target target = new Target(); target.setId(2); target.setName("shared"); em.persist(target);
        for (long id = 1; id <= 3; id++) {
            EagerOwner owner = new EagerOwner(); owner.id = id; owner.target = target;
            if (id != 2) {
                owner.first.add("first"); owner.second.add("second"); owner.eager.add("eager");
            }
            root.getEagerGroups().add(owner); em.persist(owner);
        }
        em.persist(root); em.getTransaction().commit();
        for (String hint : List.of("jakarta.persistence.fetchgraph", "jakarta.persistence.loadgraph")) {
            em.clear(); factory.getCache().evictAll(); sql.clear();
            var graph = em.createEntityGraph(Owner.class);
            graph.addSubgraph("eagerGroups").addAttributeNodes("first", "second");
            var found = em.createQuery("select o from BatchOwner o", Owner.class).setHint(hint, graph).getSingleResult();
            assertThat(found.getEagerGroups()).hasSize(3);
            assertThat(found.getEagerGroups().get(1).eager).isEmpty();
            assertThat(found.getEagerGroups().getFirst().eager).containsExactly("eager");
            assertThat(found.getEagerGroups().getLast().first).containsExactly("first");
            assertThat(found.getEagerGroups().getLast().second).containsExactly("second");
            assertThat(found.getEagerGroups().getFirst().target.getName()).isEqualTo("shared");
            assertThat(found.getEagerGroups().getLast().target).isSameAs(found.getEagerGroups().getFirst().target);
            assertThat(selects()).isEqualTo(5);
        }
    }

    @Test void splitGraphsUseCompositeOwnerTuplesWithinTheSameParameterCeiling() {
        open(256);
        em.getTransaction().begin();
        for (long id = 1; id <= 173; id++) {
            CompositeOwner owner = new CompositeOwner();
            owner.id = new CompositeKey(id % 2, id);
            owner.items.addAll(Arrays.asList("item-" + id, null, "item-" + id));
            owner.labels.put("id", Long.toString(id));
            em.persist(owner);
        }
        em.getTransaction().commit();
        em.clear(); factory.getCache().evictAll(); sql.clear();
        var graph = em.createEntityGraph(CompositeOwner.class);
        graph.addAttributeNodes("items", "labels");
        var owners = em.createQuery("select o from BatchCompositeOwner o order by o.id.part", CompositeOwner.class)
            .setHint("jakarta.persistence.loadgraph", graph).getResultList();
        assertThat(owners).hasSize(173);
        for (CompositeOwner owner : owners) {
            assertThat(owner.items).containsExactly("item-" + owner.id.part, null, "item-" + owner.id.part);
            assertThat(owner.labels).containsEntry("id", Long.toString(owner.id.part)).hasSize(1);
        }
        assertThat(selects()).isEqualTo(5); // owner IDs + two collectors, each in 170 + 3 owner groups
        assertThat(sql).allMatch(s -> s.chars().filter(c -> c == '?').count() <= 512);
        em.clear(); factory.getCache().evictAll(); sql.clear();
        var found = em.find(CompositeOwner.class, new CompositeKey(1, 173), Map.of("jakarta.persistence.fetchgraph", graph));
        assertThat(found.items).containsExactly("item-173", null, "item-173");
        assertThat(found.labels).containsEntry("id", "173");
        assertThat(selects()).isEqualTo(2);
    }

    @Test void splitGraphsExpandMappedEagerCollectionsAndToOnesWithoutPerOwnerReads() {
        open(2);
        em.getTransaction().begin();
        for (long id = 1; id <= 5; id++) {
            var target = new Target();
            target.setId(id); target.setName("target-" + id); em.persist(target);
            var owner = new EagerOwner();
            owner.id = id;
            owner.target = target;
            owner.first.add("first-" + id);
            owner.second.addAll(Arrays.asList(null, "second-" + id));
            owner.eager.add("eager-" + id);
            em.persist(owner);
        }
        em.getTransaction().commit();
        for (String hint : List.of("jakarta.persistence.fetchgraph", "jakarta.persistence.loadgraph")) {
            em.clear(); factory.getCache().evictAll(); sql.clear();
            var graph = em.createEntityGraph(EagerOwner.class);
            graph.addAttributeNodes("first", "second");
            var owners = em.createQuery("select o from BatchEagerOwner o order by o.id", EagerOwner.class)
                .setHint(hint, graph).getResultList();
            for (var owner : owners) {
                assertThat(owner.first).containsExactly("first-" + owner.id);
                assertThat(owner.second).containsExactly(null, "second-" + owner.id);
                assertThat(owner.eager).containsExactly("eager-" + owner.id);
                assertThat(owner.target.getName()).isEqualTo("target-" + owner.id);
            }
            assertThat(selects()).isEqualTo(10); // owner IDs + three collections in three groups
        }
    }

    @Test void sqlNullPrimitiveEntityColumnsRetainTheOrdinaryBindersDefault() {
        open(2); var owners = seed();
        em.getTransaction().begin();
        em.createNativeQuery("alter table BatchTarget alter column counter_value drop not null").executeUpdate();
        em.createNativeQuery("update BatchTarget set counter_value = null where id in (1, 2)").executeUpdate();
        em.getTransaction().commit(); sql.clear();
        assertThat(owners.getFirst().getTarget().getCounter()).isZero();
        assertThat(owners.get(1).getTarget().getCounter()).isZero();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void sqlNullPrimitiveEmbeddedElementColumnsRetainTheOrdinaryBindersDefault() {
        open(2); var owners = seed();
        em.getTransaction().begin();
        em.createNativeQuery("alter table batch_parts alter column part_counter drop not null").executeUpdate();
        em.createNativeQuery("update batch_parts set part_counter = null").executeUpdate();
        em.getTransaction().commit(); sql.clear();
        assertThat(owners.getFirst().getParts()).singleElement().satisfies(part -> {
            assertThat(part.text).isEqualTo("present");
            assertThat(part.counter).isZero();
        });
        assertThat(owners.get(1).getParts()).isEmpty();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void dirtyStateAndSnapshotsSurviveBatchLoadingFlushAndCacheRoundTrip() {
        open(3); var owners = seed();
        em.getTransaction().begin();
        owners.getFirst().setName("dirty");
        owners.getFirst().getValues().add("added");
        owners.getFirst().getTarget().setName("dirty-target");
        owners.get(3).getValues().size();
        owners.get(3).getTarget().getName();
        assertThat(owners.getFirst().getValues()).containsExactly("z", "a", "z", null, "added");
        assertThat(owners.getFirst().getTarget().getName()).isEqualTo("dirty-target");
        em.getTransaction().commit();
        em.clear();
        Owner restored = em.find(Owner.class, 1L);
        assertThat(restored.getName()).isEqualTo("dirty");
        assertThat(restored.getValues()).containsExactly("z", "a", "z", null, "added");
        assertThat(restored.getTarget().getName()).isEqualTo("dirty-target");
    }

    @Test void detachClearAndCloseDisconnectPendingGroups() {
        open(2); var owners = seed();
        Target detached = owners.getFirst().getTarget();
        em.detach(detached); em.detach(owners.getFirst());
        owners.get(1).getValues().size();
        owners.get(1).getTarget().getName();
        assertThatThrownBy(() -> owners.getFirst().getValues().size()).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(detached::getName).isInstanceOf(PersistenceException.class);
        var pending = owners.get(4).getValues();
        var reference = owners.get(4).getTarget();
        em.clear();
        assertThatThrownBy(pending::size).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(reference::getName).isInstanceOf(PersistenceException.class);
        Owner fresh = em.find(Owner.class, 5L);
        em.close();
        assertThatThrownBy(() -> fresh.getValues().size()).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> fresh.getTarget().getName()).isInstanceOf(PersistenceException.class);
    }

    @Test void invalidBatchSizesFailAtBootstrap() {
        for (Object size : List.of(0, -1, 257, "garbage", "1.5", "")) {
            assertThatThrownBy(() -> open(size)).as("size %s", size).isInstanceOf(PersistenceException.class)
                .hasMessageContaining(SETTING);
        }
    }

    @Entity(name = "BatchOwner")
    public static class Owner {
        private long id;
        private String name;
        private Target target;
        private Owner parent;
        private List<String> values = new ArrayList<>();
        private Map<String, String> labels = new LinkedHashMap<>();
        private List<Target> targets = new ArrayList<>();
        private List<Child> children = new ArrayList<>();
        private List<Part> parts = new ArrayList<>();
        private List<CompositeOwner> composites = new ArrayList<>();
        private List<EagerOwner> eagerGroups = new ArrayList<>();
        public Owner() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @ManyToOne(fetch = FetchType.LAZY) public Target getTarget() { return target; }
        public void setTarget(Target target) { this.target = target; }
        @ManyToOne(fetch = FetchType.LAZY) public Owner getParent() { return parent; }
        public void setParent(Owner parent) { this.parent = parent; }
        @ElementCollection @CollectionTable(name = "batch_values") @OrderColumn(name = "position") @Column(name = "element_value")
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
        @ElementCollection @CollectionTable(name = "batch_labels") @MapKeyColumn(name = "label_key")
        public Map<String, String> getLabels() { return labels; }
        public void setLabels(Map<String, String> labels) { this.labels = labels; }
        @ManyToMany @JoinTable(name = "batch_targets") @OrderColumn(name = "position")
        public List<Target> getTargets() { return targets; }
        public void setTargets(List<Target> targets) { this.targets = targets; }
        @OneToMany(mappedBy = "owner") public List<Child> getChildren() { return children; }
        public void setChildren(List<Child> children) { this.children = children; }
        @ElementCollection @CollectionTable(name = "batch_parts") @OrderColumn
        public List<Part> getParts() { return parts; }
        public void setParts(List<Part> parts) { this.parts = parts; }
        @ManyToMany @JoinTable(name = "batch_composites") @OrderColumn(name = "position")
        public List<CompositeOwner> getComposites() { return composites; }
        public void setComposites(List<CompositeOwner> composites) { this.composites = composites; }
        @OneToMany @JoinTable(name = "batch_eager_groups") @OrderColumn(name = "position")
        public List<EagerOwner> getEagerGroups() { return eagerGroups; }
        public void setEagerGroups(List<EagerOwner> eagerGroups) { this.eagerGroups = eagerGroups; }
    }

    @Entity(name = "BatchTarget")
    public static class Target {
        private long id;
        private String name;
        private int counter;
        public Target() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @Column(name = "counter_value") public int getCounter() { return counter; }
        public void setCounter(int counter) { this.counter = counter; }
    }

    @Entity(name = "BatchChild")
    public static class Child {
        @Id long id;
        @ManyToOne Owner owner;
        public Child() {}
    }

    @Embeddable @Access(AccessType.FIELD)
    public static class Part {
        @Column(name = "part_text") String text;
        @Column(name = "part_counter") int counter;
        public Part() {}
        Part(String text, int counter) { this.text = text; this.counter = counter; }
    }

    @Embeddable
    public static class CompositeKey {
        long scope;
        long part;
        long region;
        public CompositeKey() {}
        CompositeKey(long scope, long part) { this.scope = scope; this.part = part; }
        @Override public boolean equals(Object other) {
            return other instanceof CompositeKey key && scope == key.scope && part == key.part && region == key.region;
        }
        @Override public int hashCode() { return Objects.hash(scope, part, region); }
    }

    @Entity(name = "BatchCompositeOwner")
    public static class CompositeOwner {
        @EmbeddedId CompositeKey id;
        @ElementCollection @OrderColumn List<String> items = new ArrayList<>();
        @ElementCollection @MapKeyColumn(name = "label_key") Map<String, String> labels = new LinkedHashMap<>();
        public CompositeOwner() {}
    }

    @Entity(name = "BatchCompositeRoot")
    public static class CompositeRoot {
        @Id long id;
        @ManyToOne(fetch = FetchType.LAZY) CompositeOwner owner;
        public CompositeRoot() {}
    }

    @Entity(name = "BatchEagerOwner")
    public static class EagerOwner {
        @Id long id;
        @ManyToOne Target target;
        @ElementCollection @OrderColumn @Column(name = "first_value") List<String> first = new ArrayList<>();
        @ElementCollection @OrderColumn @Column(name = "second_value") List<String> second = new ArrayList<>();
        @ElementCollection(fetch = FetchType.EAGER) @OrderColumn List<String> eager = new ArrayList<>();
        public EagerOwner() {}
    }

    @Entity(name = "BatchBaseOwner")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class BaseOwner {
        @Id long id;
        @ElementCollection @OrderColumn List<String> items = new ArrayList<>();
        public BaseOwner() {}
    }

    @Entity(name = "BatchSpecialOwner")
    public static class SpecialOwner extends BaseOwner {
        public SpecialOwner() {}
    }
}
