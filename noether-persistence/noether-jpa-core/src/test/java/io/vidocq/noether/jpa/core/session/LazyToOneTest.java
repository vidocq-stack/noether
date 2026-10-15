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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 §2.10, §3.3.7 and §7.11: lazy references retain persistence identity. */
class LazyToOneTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private final List<String> sql = new ArrayList<>();
    private EntityManagerFactory factory;
    private EntityManager em;

    private void open() {
        open(SharedCacheMode.NONE);
    }

    private void open(SharedCacheMode cache) {
        open(cache, null);
    }

    private void open(SharedCacheMode cache, String mapping) {
        var source = new CountingDataSource("jdbc:h2:mem:lazy-one-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1") {
            @Override
            public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                        if (method.getName().startsWith("prepare") && args[0] instanceof String statement) sql.add(statement);
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            }
        };
        var configuration = new PersistenceConfiguration("lazy-one")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Owner.class).managedClass(Target.class).managedClass(FieldOwner.class)
            .managedClass(OneOwner.class).managedClass(FallbackOwner.class).managedClass(FinalTarget.class)
            .managedClass(FinalMethodTarget.class).sharedCacheMode(cache)
            .managedClass(PairOwner.class).managedClass(PairInverse.class)
            .property("jakarta.persistence.nonJtaDataSource", source)
            .property("jakarta.persistence.schema-generation.database.action", "create");
        if (mapping != null) configuration.mappingFile(mapping);
        factory = configuration.createEntityManagerFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        Target target = new Target();
        target.setId(2L);
        target.setName("target");
        em.persist(target);
        Owner owner = new Owner();
        owner.setId(1L);
        owner.setName("owner");
        owner.setTarget(target);
        em.persist(owner);
        FieldOwner field = new FieldOwner();
        field.id = 3;
        field.target = target;
        em.persist(field);
        em.getTransaction().commit();
        em.clear();
        sql.clear();
    }

    @AfterEach
    void close() {
        if (factory != null && factory.isOpen()) factory.close();
    }

    @Test
    void identifierDoesNotSelectAndFirstGetterInitializesTheSameManagedInstanceOnce() {
        open();
        Owner owner = em.find(Owner.class, 1L);
        assertThat(sql).hasSize(1);
        Target target = owner.getTarget();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isFalse();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(target)).isFalse();
        assertThat(target.getId()).isEqualTo(2);
        assertThat(factory.getPersistenceUnitUtil().getIdentifier(target)).isEqualTo(2L);
        assertThat(sql).hasSize(1);
        assertThat(em.contains(target)).isTrue();
        assertThat(target.getName()).isEqualTo("target");
        assertThat(sql).hasSize(2);
        assertThat(target).isSameAs(em.find(Target.class, 2L));
        assertThat(owner.getTarget()).isSameAs(target);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isTrue();
        target.getName();
        assertThat(sql).hasSize(2);
    }

    @Test
    void findInitializesAnExistingReferenceWithoutSplittingIdentity() {
        open();
        Target reference = em.find(Owner.class, 1L).getTarget();
        assertThat(sql).hasSize(1);
        assertThat(em.find(Target.class, 2L)).isSameAs(reference);
        assertThat(reference.getName()).isEqualTo("target");
        assertThat(sql).hasSize(2);
    }

    @Test
    void anOverloadNamedLikeTheIdentifierGetterStillInitializesState() {
        open();
        Target reference = em.find(Owner.class, 1L).getTarget();
        assertThat(sql).hasSize(1);
        assertThat(reference.getId(0)).isEqualTo("target");
        assertThat(sql).hasSize(2);
    }

    @Test
    void flushLeavesUntouchedReferencesUnloadedAndSetterChangesAreWritten() {
        open();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        owner.setName("changed");
        em.flush();
        assertThat(sql.stream().filter(s -> s.startsWith("SELECT") || s.startsWith("select"))).hasSize(1);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isFalse();
        owner.getTarget().setName("updated");
        em.getTransaction().commit();
        em.clear();
        assertThat(em.find(Target.class, 2L).getName()).isEqualTo("updated");
    }

    @Test
    void detachedAndClosedReferencesFailInsteadOfExposingEmptyState() {
        open();
        Target reference = em.find(Owner.class, 1L).getTarget();
        assertThat(sql).hasSize(1);
        em.clear();
        assertThatThrownBy(reference::getName).isInstanceOf(PersistenceException.class).hasMessageContaining("detached");
        Owner owner = em.find(Owner.class, 1L);
        em.close();
        assertThatThrownBy(owner.getTarget()::getName).isInstanceOf(PersistenceException.class);
    }

    @Test
    void joinFetchAndGraphInitializeAndFieldOwnersFallBackToEager() {
        open();
        Owner owner = em.createQuery("select o from ToOneOwner o join fetch o.target", Owner.class).getSingleResult();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isTrue();
        em.clear();
        var graph = em.createEntityGraph(Owner.class);
        graph.addAttributeNodes("target");
        owner = em.find(Owner.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph));
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isTrue();
        em.clear();
        sql.clear();
        assertThat(em.find(FieldOwner.class, 3L).target.getName()).isEqualTo("target");
        assertThat(sql).hasSize(2);
    }

    @Test
    void criteriaFetchAndExplicitLoadInitializeReferences() {
        open();
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(Owner.class);
        var root = query.from(Owner.class);
        root.fetch("target");
        Owner owner = em.createQuery(query.select(root)).getSingleResult();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isTrue();
        em.clear();
        owner = em.find(Owner.class, 1L);
        Target reference = owner.getTarget();
        assertThat(Persistence.getPersistenceUtil().isLoaded(owner, "target")).isFalse();
        factory.getPersistenceUnitUtil().load(owner, "target");
        assertThat(reference.getName()).isEqualTo("target");
        assertThat(factory.getPersistenceUnitUtil().getClass(reference)).isEqualTo(Target.class);
        assertThat(Persistence.getPersistenceUtil().isLoaded(reference)).isTrue();
    }

    @Test
    void xmlFetchOverridesUseTheEffectiveMappingInsteadOfTheGeneratedSubclassHint() {
        open(SharedCacheMode.NONE, "orm/lazy-to-one.xml");
        Owner owner = em.find(Owner.class, 1L);
        assertThat(sql).hasSize(2);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "target")).isTrue();
        assertThat(owner.getTarget().getName()).isEqualTo("target");
    }

    @Test
    void owningOneToOneIsLazyAndNullForeignKeysRemainNull() {
        open();
        em.getTransaction().begin();
        OneOwner first = new OneOwner();
        first.setId(7);
        first.setTarget(em.find(Target.class, 2L));
        em.persist(first);
        OneOwner empty = new OneOwner();
        empty.setId(8);
        em.persist(empty);
        em.getTransaction().commit();
        em.clear();
        sql.clear();
        OneOwner loaded = em.find(OneOwner.class, 7L);
        assertThat(sql).hasSize(1);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(loaded, "target")).isFalse();
        assertThat(loaded.getTarget().getName()).isEqualTo("target");
        assertThat(sql).hasSize(2);
        assertThat(em.find(OneOwner.class, 8L).getTarget()).isNull();
    }

    @Test
    void inverseOneToOneStaysEagerAndClosesTheCycleOnTheManagedInstance() {
        open();
        em.getTransaction().begin();
        PairInverse inverse = new PairInverse();
        inverse.setId(21);
        PairOwner owner = new PairOwner();
        owner.setId(22);
        owner.setInverse(inverse);
        inverse.setOwner(owner);
        em.persist(inverse);
        em.persist(owner);
        em.getTransaction().commit();
        em.clear();
        PairInverse loaded = em.find(PairInverse.class, 21L);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(loaded, "owner")).isTrue();
        assertThat(loaded.getOwner()).isSameAs(em.find(PairOwner.class, 22L));
        assertThat(loaded.getOwner().getInverse()).isSameAs(loaded);
    }

    @Test
    void refreshAndRollbackDoNotLeakUninitializedState() {
        open();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        Target reference = owner.getTarget();
        em.refresh(owner);
        assertThat(owner.getTarget()).isSameAs(reference);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isFalse();
        em.getTransaction().rollback();
        assertThatThrownBy(reference::getName).isInstanceOf(PersistenceException.class);
        assertThat(reference.getId()).isEqualTo(2);
    }

    @Test
    void refreshingAPendingReferenceHydratesItInPlaceWithOneSelect() {
        open();
        Target reference = em.find(Owner.class, 1L).getTarget();
        em.getTransaction().begin();
        sql.clear();
        em.refresh(reference);
        assertThat(sql).hasSize(1);
        assertThat(reference.getName()).isEqualTo("target");
        assertThat(em.find(Target.class, 2L)).isSameAs(reference);
        em.getTransaction().commit();
    }

    @Test
    void mergeIgnoresUnloadedDetachedStateAndRelationshipReplacementSurvivesFlush() {
        open();
        Owner detached = em.find(Owner.class, 1L);
        Target reference = detached.getTarget();
        em.clear();
        em.getTransaction().begin();
        detached.setName("merged");
        Owner merged = em.merge(detached);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(merged, "target")).isFalse();
        assertThat(merged.getTarget()).isNotSameAs(reference);
        merged.setTarget(null);
        em.getTransaction().commit();
        em.clear();
        assertThat(em.find(Owner.class, 1L).getTarget()).isNull();
        assertThat(em.find(Target.class, 2L).getName()).isEqualTo("target");
    }

    @Test
    void missingReferenceThrowsAndDoesNotBecomeAnOrdinaryEmptyEntity() {
        open();
        Target reference = em.find(Owner.class, 1L).getTarget();
        em.getTransaction().begin();
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        em.createNativeQuery("DELETE FROM ToOneTarget WHERE id = 2").executeUpdate();
        em.getTransaction().commit();
        assertThatThrownBy(reference::getName).isInstanceOf(EntityNotFoundException.class);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isFalse();
        assertThatThrownBy(reference::getName).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void cacheDoesNotForceLoadingOrSplitAnExistingReference() {
        open(SharedCacheMode.ALL);
        factory.getCache().evictAll();
        Owner owner = em.find(Owner.class, 1L);
        Target reference = owner.getTarget();
        assertThat(sql).hasSize(1);
        assertThat(reference.getName()).isEqualTo("target");
        em.clear();
        em.find(Target.class, 2L);
        em.clear();
        owner = em.find(Owner.class, 1L);
        reference = owner.getTarget();
        assertThat(em.find(Target.class, 2L)).isSameAs(reference);
        assertThat(reference.getName()).isEqualTo("target");
    }

    @Test
    void cachedEagerGraphInitializesAnAlreadyManagedLazyReference() {
        open(SharedCacheMode.ALL);
        factory.getCache().evict(Owner.class);
        Owner owner = em.find(Owner.class, 1L);
        Target reference = owner.getTarget();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isFalse();
        FieldOwner eager = em.find(FieldOwner.class, 3L);
        assertThat(eager.target).isSameAs(reference);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(eager, "target")).isTrue();
    }

    @Test
    void finalTargetsAndFinalTargetMethodsRemainEager() {
        open();
        em.getTransaction().begin();
        FinalTarget first = new FinalTarget();
        first.setId(11);
        first.setName("final class");
        FinalMethodTarget second = new FinalMethodTarget();
        second.setId(12);
        second.setName("final method");
        em.persist(first);
        em.persist(second);
        FallbackOwner owner = new FallbackOwner();
        owner.setId(13);
        owner.setFirst(first);
        owner.setSecond(second);
        owner.setThird(em.find(Target.class, 2L));
        em.persist(owner);
        em.getTransaction().commit();
        em.clear();
        sql.clear();
        FallbackOwner loaded = em.find(FallbackOwner.class, 13L);
        assertThat(sql).hasSize(4);
        assertThat(loaded.getFirst().getName()).isEqualTo("final class");
        assertThat(loaded.getSecond().getName()).isEqualTo("final method");
        assertThat(factory.getPersistenceUnitUtil().isLoaded(loaded, "third")).isTrue();
    }

    @Test
    void detachedReferenceMergeRemoveAndRefreshRetainOneIdentity() {
        open();
        Target reference = em.find(Owner.class, 1L).getTarget();
        em.detach(reference);
        assertThatThrownBy(reference::getName).isInstanceOf(PersistenceException.class);
        em.getTransaction().begin();
        Target merged = em.merge(reference);
        assertThat(merged.getName()).isEqualTo("target");
        em.refresh(merged);
        assertThat(merged).isSameAs(em.find(Target.class, 2L));
        em.getTransaction().commit();
        em.clear();
        reference = em.find(Owner.class, 1L).getTarget();
        em.getTransaction().begin();
        em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        em.remove(reference);
        em.getTransaction().commit();
        em.clear();
        assertThat(em.find(Target.class, 2L)).isNull();
    }

    @Entity(name = "ToOneOwner")
    @Table(name = "ToOneOwner")
    public static class Owner {
        private long id;
        private String name;
        private Target target;
        public Owner() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "target_id")
        public Target getTarget() { return target; }
        public void setTarget(Target target) { this.target = target; }
    }

    @Entity(name = "ToOneTarget")
    @Table(name = "ToOneTarget")
    public static class Target {
        private long id;
        private String name;
        public Target() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getId(int ignored) { return name; }
    }

    @Entity(name = "ToOneFieldOwner")
    @Table(name = "ToOneFieldOwner")
    public static class FieldOwner {
        @Id long id;
        @ManyToOne(fetch = FetchType.LAZY) Target target;
    }

    @Entity(name = "ToOneSingleOwner")
    public static class OneOwner {
        private long id;
        private Target target;
        public OneOwner() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @OneToOne(fetch = FetchType.LAZY) public Target getTarget() { return target; }
        public void setTarget(Target target) { this.target = target; }
    }

    @Entity(name = "ToOneFinalTarget")
    public static final class FinalTarget {
        private long id;
        private String name;
        public FinalTarget() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity(name = "ToOneFinalMethodTarget")
    public static class FinalMethodTarget {
        private long id;
        private String name;
        public FinalMethodTarget() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public final String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity(name = "ToOneFallbackOwner")
    public static class FallbackOwner {
        private long id;
        private FinalTarget first;
        private FinalMethodTarget second;
        private Target third;
        public FallbackOwner() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @ManyToOne(fetch = FetchType.LAZY) public FinalTarget getFirst() { return first; }
        public void setFirst(FinalTarget first) { this.first = first; }
        @ManyToOne(fetch = FetchType.LAZY) public FinalMethodTarget getSecond() { return second; }
        public void setSecond(FinalMethodTarget second) { this.second = second; }
        @ManyToOne(fetch = FetchType.LAZY) public final Target getThird() { return third; }
        public void setThird(Target third) { this.third = third; }
    }

    @Entity(name = "ToOnePairOwner")
    public static class PairOwner {
        private long id;
        private PairInverse inverse;
        public PairOwner() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @OneToOne(fetch = FetchType.LAZY) public PairInverse getInverse() { return inverse; }
        public void setInverse(PairInverse inverse) { this.inverse = inverse; }
    }

    @Entity(name = "ToOnePairInverse")
    public static class PairInverse {
        private long id;
        private PairOwner owner;
        public PairInverse() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @OneToOne(mappedBy = "inverse", fetch = FetchType.LAZY) public PairOwner getOwner() { return owner; }
        public void setOwner(PairOwner owner) { this.owner = owner; }
    }
}
