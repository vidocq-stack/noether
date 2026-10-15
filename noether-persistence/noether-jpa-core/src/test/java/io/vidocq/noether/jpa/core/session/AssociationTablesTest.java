/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** §2.10 and §11.1.25–27: alternate relational representations share the entity lifecycle. */
class AssociationTablesTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private EntityManagerFactory factory;
    private EntityManager em;

    private void open(Class<?>... types) {
        open(null, types);
    }

    private void open(String mapping, Class<?>... types) {
        var configuration = new PersistenceConfiguration("association-tables")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:association-tables-" + DATABASES.incrementAndGet())
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property("jakarta.persistence.schema-generation.database.action", "create");
        for (Class<?> type : types) configuration.managedClass(type);
        if (mapping != null) configuration.mappingFile(mapping);
        factory = configuration.createEntityManagerFactory();
        em = factory.createEntityManager();
    }

    @AfterEach
    void close() {
        if (factory != null) factory.close();
    }

    private void transaction(Runnable action) {
        em.getTransaction().begin();
        action.run();
        em.getTransaction().commit();
    }

    @Test
    void toOneJoinTablesLoadBothDirectionsAndWriteReassignmentAndUnlink() {
        open(Owner.class, Target.class);
        Target first = new Target(1), second = new Target(2);
        Owner owner = new Owner(10);
        owner.target = first;
        owner.shared = first;
        transaction(() -> { em.persist(owner); em.persist(first); em.persist(second); });
        em.clear();
        Owner loaded = em.find(Owner.class, 10L);
        assertThat(loaded.target.id).isEqualTo(1);
        assertThat(loaded.target.owner).isSameAs(loaded);
        assertThat(loaded.shared).isSameAs(loaded.target);
        transaction(() -> { loaded.target = em.find(Target.class, 2L); loaded.shared = null; });
        em.clear();
        assertThat(em.find(Owner.class, 10L).target.id).isEqualTo(2);
        assertThat(em.find(Target.class, 1L).owner).isNull();
        assertThat(em.find(Owner.class, 10L).shared).isNull();
        transaction(() -> em.remove(em.find(Owner.class, 10L)));
        em.clear();
        assertThat(em.find(Target.class, 2L).owner).isNull();
    }

    @Test
    void toOneJoinTablesSupportQueriesAndDetachedMerge() {
        open(Owner.class, Target.class);
        Owner owner = new Owner(10);
        owner.target = new Target(1);
        transaction(() -> { em.persist(owner); em.persist(owner.target); });
        em.clear();
        assertThat(em.createQuery("select o from TableOwner o join o.target t where t.id = 1", Owner.class)
            .getSingleResult().id).isEqualTo(10);
        assertThat(em.createQuery("select t from TableTarget t join t.owner o where o.id = 10", Target.class)
            .getSingleResult().id).isEqualTo(1);
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(Owner.class);
        var root = query.from(Owner.class);
        query.select(root).where(builder.equal(root.join("target").get("id"), 1L));
        assertThat(em.createQuery(query).getSingleResult().id).isEqualTo(10);
        assertThat(em.createQuery("select o from TableOwner o where exists (select t from o.target t where t.id = 1)", Owner.class)
            .getResultList()).hasSize(1);
        assertThat(em.createQuery("select t from TableTarget t where exists (select o from t.owner o where o.id = 10)", Target.class)
            .getResultList()).hasSize(1);
        Owner detached = em.find(Owner.class, 10L);
        em.clear();
        detached.target = null;
        transaction(() -> em.merge(detached));
        em.clear();
        assertThat(em.find(Owner.class, 10L).target).isNull();
        assertThat(em.createQuery("select o from TableOwner o where o.target is null", Owner.class)
            .getResultList()).hasSize(1);
        assertThat(em.createQuery("select o from TableOwner o left join o.shared t on o.target.id = t.id", Owner.class)
            .getResultList()).hasSize(1);
    }

    @Test
    void targetForeignKeyCollectionsLoadDiffMergeAndRemoveWithoutDeletingTargets() {
        open(Parent.class, Child.class);
        Parent parent = new Parent(1);
        parent.children.add(new Child(10));
        parent.children.add(new Child(11));
        transaction(() -> { em.persist(parent); parent.children.forEach(em::persist); });
        em.clear();
        Parent loaded = em.find(Parent.class, 1L);
        assertThat(loaded.children).extracting(c -> c.id).containsExactly(10L, 11L);
        transaction(() -> loaded.children.removeFirst());
        em.clear();
        assertThat(em.find(Parent.class, 1L).children).extracting(c -> c.id).containsExactly(11L);
        assertThat(em.find(Child.class, 10L)).isNotNull();
        Parent detached = em.find(Parent.class, 1L);
        em.clear();
        detached.children.add(new Child(12));
        transaction(() -> { em.persist(detached.children.getLast()); em.merge(detached); });
        em.clear();
        assertThat(em.find(Parent.class, 1L).children).hasSize(2);
        transaction(() -> em.remove(em.find(Parent.class, 1L)));
        em.clear();
        assertThat(em.createQuery("select c from TableChild c", Child.class).getResultList()).hasSize(3);
    }

    @Test
    void targetForeignKeyCollectionsSupportJoinsMembershipAndCriteria() {
        open(Parent.class, Child.class);
        Parent parent = new Parent(1);
        Child child = new Child(10);
        parent.children.add(child);
        transaction(() -> { em.persist(parent); em.persist(child); });
        em.clear();
        assertThat(em.createQuery("select p from TableParent p join p.children c where c.id = 10", Parent.class)
            .getSingleResult().id).isEqualTo(1);
        assertThat(em.createQuery("select p from TableParent p where size(p.children) = 1", Parent.class)
            .getResultList()).hasSize(1);
        assertThat(em.createQuery("select p from TableParent p where :child member of p.children", Parent.class)
            .setParameter("child", em.find(Child.class, 10L)).getResultList()).hasSize(1);
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(Parent.class);
        var root = query.from(Parent.class);
        query.select(root).where(builder.equal(root.join("children").get("id"), 10L));
        assertThat(em.createQuery(query).getResultList()).hasSize(1);
    }

    @Test
    void foreignKeysCanTransferBetweenOwnersInEitherManagedOrder() {
        open(Parent.class, Child.class);
        Parent first = new Parent(1), second = new Parent(2);
        Child child = new Child(10);
        second.children.add(child);
        transaction(() -> { em.persist(first); em.persist(second); em.persist(child); });
        transaction(() -> { first.children.add(child); second.children.clear(); });
        em.clear();
        assertThat(em.find(Parent.class, 1L).children).hasSize(1);
        assertThat(em.find(Parent.class, 2L).children).isEmpty();
    }

    @Test
    void nonNullableCollectionKeysArePresentAtTargetInsertAndOrphansAreDeleted() {
        open(RequiredParent.class, Child.class);
        RequiredParent parent = new RequiredParent();
        parent.children.add(new Child(10));
        transaction(() -> em.persist(parent));
        em.clear();
        RequiredParent loaded = em.find(RequiredParent.class, parent.id);
        assertThat(loaded.children).hasSize(1);
        transaction(() -> loaded.children.clear());
        em.clear();
        assertThat(em.find(Child.class, 10L)).isNull();
    }

    @Test
    void compositeKeysMatchReferencedColumnsRatherThanDeclaredJoinOrder() {
        open(CompositeOwner.class, CompositeTarget.class);
        CompositeOwner owner = new CompositeOwner();
        owner.id = new Pair("owner", 7);
        CompositeTarget target = new CompositeTarget();
        target.id = new Pair("target", 8);
        owner.target = target;
        owner.children.add(target);
        transaction(() -> { em.persist(owner); em.persist(target); });
        em.clear();
        CompositeOwner loaded = em.find(CompositeOwner.class, new Pair("owner", 7));
        assertThat(loaded.target.id.code).isEqualTo("target");
        assertThat(loaded.children).containsExactly(loaded.target);
        assertThat(em.createQuery("select o from CompositeOwner o join o.children c where c.id.number = 8", CompositeOwner.class)
            .getSingleResult()).isSameAs(loaded);
        transaction(() -> { loaded.target = null; loaded.children.clear(); });
        em.clear();
        assertThat(em.find(CompositeOwner.class, new Pair("owner", 7)).target).isNull();
        assertThat(em.find(CompositeOwner.class, new Pair("owner", 7)).children).isEmpty();
    }

    @Test
    void xmlMappingsUseTheSameSchemaLoadingAndWritePlans() {
        open("orm/association-tables.xml", XmlOwner.class, XmlTarget.class);
        XmlOwner owner = new XmlOwner();
        owner.id = 1;
        XmlTarget target = new XmlTarget();
        target.id = 10;
        owner.target = target;
        owner.children.add(target);
        transaction(() -> { em.persist(owner); em.persist(target); });
        em.clear();
        XmlOwner loaded = em.find(XmlOwner.class, 1L);
        assertThat(loaded.target.owner).isSameAs(loaded);
        assertThat(loaded.children).containsExactly(loaded.target);
        assertThat(em.createQuery("select o from XmlOwner o join o.target t join o.children c where t = c", XmlOwner.class)
            .getSingleResult()).isSameAs(loaded);
        transaction(() -> { loaded.target = null; loaded.children.clear(); });
        em.clear();
        assertThat(em.find(XmlOwner.class, 1L).target).isNull();
        assertThat(em.find(XmlOwner.class, 1L).children).isEmpty();
    }

    @Test
    void bulkToOneAssignmentCreatesReplacesAndRemovesJoinRows() {
        open(Owner.class, Target.class);
        Owner owner = new Owner(1);
        Target first = new Target(10), second = new Target(11);
        transaction(() -> { em.persist(owner); em.persist(first); em.persist(second); });
        transaction(() -> assertThat(em.createQuery("update TableOwner o set o.target = :target where o.id = 1")
            .setParameter("target", first).executeUpdate()).isEqualTo(1));
        em.clear();
        assertThat(em.find(Owner.class, 1L).target.id).isEqualTo(10);
        transaction(() -> em.createQuery("update TableOwner o set o.target = :target where o.id = 1")
            .setParameter("target", second).executeUpdate());
        em.clear();
        assertThat(em.find(Owner.class, 1L).target.id).isEqualTo(11);
        transaction(() -> em.createQuery("update TableOwner o set o.target = null where o.id = 1").executeUpdate());
        em.clear();
        assertThat(em.find(Owner.class, 1L).target).isNull();
    }

    @Test
    void bulkOwnerDeletesReleaseBothAlternateRelationshipRepresentations() {
        open(Owner.class, Target.class, Parent.class, Child.class);
        Owner owner = new Owner(1);
        owner.target = new Target(10);
        Parent parent = new Parent(2);
        parent.children.add(new Child(20));
        transaction(() -> { em.persist(owner); em.persist(owner.target); em.persist(parent); em.persist(parent.children.getFirst()); });
        transaction(() -> {
            assertThat(em.createQuery("delete from TableOwner o where o.id = 1").executeUpdate()).isEqualTo(1);
            assertThat(em.createQuery("delete from TableParent p where p.id = 2").executeUpdate()).isEqualTo(1);
        });
        em.clear();
        assertThat(em.find(Target.class, 10L).owner).isNull();
        assertThat(em.find(Child.class, 20L)).isNotNull();
    }

    @Test
    void joinColumnWriteFlagsApplyToTheColumnRatherThanTheWholeRelationship() {
        open(FlagOwner.class, CompositeTarget.class);
        FlagOwner owner = new FlagOwner();
        owner.id = 1;
        CompositeTarget first = new CompositeTarget(), second = new CompositeTarget();
        first.id = new Pair("same", 10);
        second.id = new Pair("same", 11);
        owner.target = first;
        transaction(() -> { em.persist(owner); em.persist(first); em.persist(second); });
        transaction(() -> owner.target = second);
        em.clear();
        assertThat(em.find(FlagOwner.class, 1L).target.id.number).isEqualTo(11);
    }

    @Test
    void collectionWriteFlagsDistinguishInitialLinksFromSubsequentChanges() {
        open(FlagParent.class, Child.class);
        FlagParent parent = new FlagParent();
        parent.id = 1;
        Child first = new Child(10), second = new Child(11);
        parent.fixed.add(first);
        parent.later.add(first);
        transaction(() -> { em.persist(parent); em.persist(first); em.persist(second); });
        em.clear();
        FlagParent loaded = em.find(FlagParent.class, 1L);
        assertThat(loaded.fixed).hasSize(1);
        assertThat(loaded.later).isEmpty();
        transaction(() -> { loaded.fixed.clear(); loaded.later.add(em.find(Child.class, 11L)); });
        em.clear();
        assertThat(em.find(FlagParent.class, 1L).fixed).hasSize(1);
        assertThat(em.find(FlagParent.class, 1L).later).extracting(c -> c.id).containsExactly(11L);
    }

    @Test
    void oneToOneTargetsCanBeSwappedWithoutViolatingTheirUniqueJoinColumns() {
        open(Owner.class, Target.class);
        Owner first = new Owner(1), second = new Owner(2);
        first.target = new Target(10);
        second.target = new Target(11);
        transaction(() -> { em.persist(first); em.persist(second); em.persist(first.target); em.persist(second.target); });
        transaction(() -> {
            Target old = first.target;
            first.target = second.target;
            second.target = old;
        });
        em.clear();
        assertThat(em.find(Owner.class, 1L).target.id).isEqualTo(11);
        assertThat(em.find(Owner.class, 2L).target.id).isEqualTo(10);
    }

    @Test
    void aNonInsertableToOneJoinColumnLoadsAsNullUntilAnUpdateWritesIt() {
        open(LaterOwner.class, Target.class, Owner.class);
        LaterOwner owner = new LaterOwner();
        owner.id = 1;
        Target first = new Target(10), second = new Target(11);
        owner.target = first;
        transaction(() -> { em.persist(owner); em.persist(first); em.persist(second); });
        em.clear();
        LaterOwner loaded = em.find(LaterOwner.class, 1L);
        assertThat(loaded.target).isNull();
        transaction(() -> loaded.target = em.find(Target.class, 11L));
        em.clear();
        assertThat(em.find(LaterOwner.class, 1L).target.id).isEqualTo(11);
    }

    @Test
    void foreignKeysInSecondaryTablesUseTheirActualPrimaryKeyJoinColumns() {
        open(SecondaryParent.class, SecondaryChild.class);
        SecondaryParent parent = new SecondaryParent();
        parent.id = 1;
        SecondaryChild child = new SecondaryChild();
        child.id = 10;
        parent.children.add(child);
        transaction(() -> { em.persist(parent); em.persist(child); });
        em.clear();
        SecondaryParent loaded = em.find(SecondaryParent.class, 1L);
        assertThat(loaded.children).extracting(c -> c.id).containsExactly(10L);
        assertThat(em.createQuery("select p from SecondaryParent p join p.children c where c.id = 10", SecondaryParent.class)
            .getSingleResult()).isSameAs(loaded);
        transaction(() -> loaded.children.clear());
        em.clear();
        assertThat(em.find(SecondaryParent.class, 1L).children).isEmpty();
    }

    @Test
    void joinedTargetsUseTheirRelationalKeysAndAreInsertedAfterTheirOwners() {
        open(JoinedParent.class, BaseChild.class, JoinedChild.class);
        JoinedParent parent = new JoinedParent();
        parent.id = 1;
        JoinedChild child = new JoinedChild();
        child.id = 10;
        parent.children.add(child);
        parent.target = child;
        transaction(() -> { em.persist(child); em.persist(parent); });
        em.clear();
        JoinedParent loaded = em.find(JoinedParent.class, 1L);
        assertThat(loaded.children).containsExactly(loaded.target);
        assertThat(loaded.target.id).isEqualTo(10);
        assertThat(em.createQuery("select p from JoinedParent p join p.children c where c.id = 10", JoinedParent.class)
            .getSingleResult()).isSameAs(loaded);
        transaction(() -> { em.remove(loaded); em.remove(loaded.target); });
    }

    @Entity(name = "LaterOwner")
    public static class LaterOwner {
        @Id public long id;
        @ManyToOne @JoinTable(inverseJoinColumns = @JoinColumn(name = "target_key", insertable = false))
        public Target target;
        public LaterOwner() {}
    }

    @Entity(name = "SecondaryParent")
    public static class SecondaryParent {
        @Id public long id;
        @OneToMany @JoinColumn(name = "parent_key", table = "secondary_child")
        public List<SecondaryChild> children = new ArrayList<>();
        public SecondaryParent() {}
    }

    @Entity(name = "SecondaryChild")
    @SecondaryTable(name = "secondary_child", pkJoinColumns = @PrimaryKeyJoinColumn(name = "child_key"))
    public static class SecondaryChild {
        @Id public long id;
        @Column(table = "secondary_child") public String label;
        public SecondaryChild() {}
    }

    @Entity(name = "JoinedParent")
    public static class JoinedParent {
        @Id public long id;
        @OneToMany @JoinColumn(name = "parent_key", nullable = false)
        public List<BaseChild> children = new ArrayList<>();
        @ManyToOne @JoinTable public JoinedChild target;
        public JoinedParent() {}
    }

    @Entity(name = "BaseChild") @Inheritance(strategy = InheritanceType.JOINED)
    public static class BaseChild {
        @Id public long id;
        public BaseChild() {}
    }

    @Entity(name = "JoinedChild") @PrimaryKeyJoinColumn(name = "child_key")
    public static class JoinedChild extends BaseChild {
        public JoinedChild() {}
    }

    @Entity(name = "FlagOwner")
    public static class FlagOwner {
        @Id public long id;
        @ManyToOne @JoinTable(joinColumns = @JoinColumn(name = "owner_key", updatable = false),
            inverseJoinColumns = { @JoinColumn(name = "target_number", referencedColumnName = "number"),
                @JoinColumn(name = "target_code", referencedColumnName = "code", updatable = false) })
        public CompositeTarget target;
        public FlagOwner() {}
    }

    @Entity(name = "FlagParent")
    public static class FlagParent {
        @Id public long id;
        @OneToMany @JoinColumn(name = "fixed_parent", updatable = false)
        public List<Child> fixed = new ArrayList<>();
        @OneToMany @JoinColumn(name = "later_parent", insertable = false)
        public List<Child> later = new ArrayList<>();
        public FlagParent() {}
    }

    public static class XmlOwner {
        public long id;
        public XmlTarget target;
        public List<XmlTarget> children = new ArrayList<>();
        public XmlOwner() {}
    }

    public static class XmlTarget {
        public long id;
        public XmlOwner owner;
        public XmlTarget() {}
    }

    @Embeddable
    public static class Pair implements java.io.Serializable {
        public String code;
        public int number;
        public Pair() {}
        Pair(String code, int number) { this.code = code; this.number = number; }
        @Override public boolean equals(Object other) {
            return other instanceof Pair pair && number == pair.number && java.util.Objects.equals(code, pair.code);
        }
        @Override public int hashCode() { return java.util.Objects.hash(code, number); }
    }

    @Entity(name = "CompositeOwner")
    public static class CompositeOwner {
        @EmbeddedId public Pair id;
        @ManyToOne @JoinTable(name = "composite_link",
            joinColumns = { @JoinColumn(name = "owner_number", referencedColumnName = "number"),
                @JoinColumn(name = "owner_code", referencedColumnName = "code") },
            inverseJoinColumns = { @JoinColumn(name = "target_number", referencedColumnName = "number"),
                @JoinColumn(name = "target_code", referencedColumnName = "code") })
        public CompositeTarget target;
        @OneToMany @JoinColumns({ @JoinColumn(name = "parent_number", referencedColumnName = "number"),
            @JoinColumn(name = "parent_code", referencedColumnName = "code") })
        public List<CompositeTarget> children = new ArrayList<>();
        public CompositeOwner() {}
    }

    @Entity(name = "CompositeTarget")
    public static class CompositeTarget {
        @EmbeddedId public Pair id;
        public CompositeTarget() {}
    }

    @Entity(name = "RequiredParent")
    public static class RequiredParent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true) @JoinColumn(name = "required_parent", nullable = false)
        public List<Child> children = new ArrayList<>();
        public RequiredParent() {}
    }

    @Entity(name = "TableOwner")
    public static class Owner {
        @Id public long id;
        @OneToOne @JoinTable(name = "owner_target")
        public Target target;
        @ManyToOne @JoinTable(name = "owner_shared")
        public Target shared;
        public Owner() {}
        Owner(long id) { this.id = id; }
    }

    @Entity(name = "TableTarget")
    public static class Target {
        @Id public long id;
        @OneToOne(mappedBy = "target") public Owner owner;
        public Target() {}
        Target(long id) { this.id = id; }
    }

    @Entity(name = "TableParent")
    public static class Parent {
        @Id public long id;
        @OneToMany @JoinColumn(name = "parent_id") @OrderColumn(name = "position")
        public List<Child> children = new ArrayList<>();
        public Parent() {}
        Parent(long id) { this.id = id; }
    }

    @Entity(name = "TableChild")
    public static class Child {
        @Id public long id;
        public Child() {}
        Child(long id) { this.id = id; }
    }
}
