/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.*;
import jakarta.persistence.criteria.JoinType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** §4.4.5.3 / §6.3.4: complete collections from SQL fetch rows, with managed identity and no secondary reads. */
class CollectionFetchJoinTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private final List<String> sql = new ArrayList<>();
    private EntityManagerFactory factory;
    private EntityManager em;

    @BeforeEach
    void open() {
        var source = new CountingDataSource("jdbc:h2:mem:collection-fetch-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1") {
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
        factory = new PersistenceConfiguration("collection-fetch")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Owner.class).managedClass(Child.class).managedClass(Inventory.class).managedClass(Value.class)
            .managedClass(Group.class).managedClass(Member.class).managedClass(Holder.class)
            .sharedCacheMode(SharedCacheMode.ALL)
            .property("jakarta.persistence.nonJtaDataSource", source)
            .property("jakarta.persistence.schema-generation.database.action", "create").createEntityManagerFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        Owner a = new Owner(1, "first"), b = new Owner(2, "empty"), c = new Owner(3, "third");
        Child x = new Child(10, "x", a), y = new Child(11, "y", a);
        a.children.addAll(List.of(y, x));
        a.sorted.addAll(List.of(y, x));
        a.shared.addAll(List.of(x, y));
        c.shared.add(x);
        a.labels.addAll(List.of("z", "a", "z"));
        a.settings.put("b", "two");
        a.settings.put("a", "one");
        a.named.put("second", y);
        a.named.put("first", x);
        a.byName.put("x", x);
        a.byName.put("y", y);
        a.bag.addAll(List.of("same", "same", "different"));
        a.values.addAll(List.of(new Value("a", 1), new Value("b", 2)));
        em.persist(a); em.persist(b); em.persist(c); em.persist(x); em.persist(y);
        em.getTransaction().commit();
        em.clear(); factory.getCache().evictAll(); sql.clear(); Owner.LOADS.set(0); Child.LOADS.set(0);
    }

    @AfterEach void close() { if (factory != null) factory.close(); }
    private long selects() { return sql.stream().filter(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")).count(); }
    private List<Owner> query(String join) {
        return em.createQuery("select o from CfOwner o " + join + " order by o.id", Owner.class).getResultList();
    }

    private void persistNullElements() {
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        owner.labels.add(null);
        owner.bag.add(null);
        owner.settings.put("null-value", null);
        Inventory eager = new Inventory();
        eager.id = 91;
        eager.tags.addAll(Arrays.asList("z", "a", "z", null));
        em.persist(eager);
        em.getTransaction().commit();
        em.clear();
        factory.getCache().evictAll();
        sql.clear();
    }

    @Test
    void ordinaryLazyLoadingPreservesPersistedNullRowsAndBagDuplicates() {
        // §2.7: a persisted null value is a row, not an absent outer-join row.
        persistNullElements();
        Owner owner = em.find(Owner.class, 1L);
        assertThat(owner.labels).containsExactly("z", "a", "z", null);
        assertThat(owner.bag).containsExactlyInAnyOrder("same", "same", "different", null);
        assertThat(owner.settings).containsEntry("null-value", null).hasSize(3);
        assertThat(em.find(Owner.class, 2L).labels).isEmpty();
    }

    @Test
    void graphEagerLoadingPreservesPersistedNullRows() {
        persistNullElements();
        var graph = em.createEntityGraph(Owner.class);
        graph.addAttributeNodes("labels", "settings", "bag");
        Owner owner = em.find(Owner.class, 1L, Map.of("jakarta.persistence.loadgraph", graph));
        assertThat(owner.labels).containsExactly("z", "a", "z", null);
        assertThat(owner.settings).containsEntry("null-value", null);
        assertThat(owner.bag).containsExactlyInAnyOrder("same", "same", "different", null);
        Inventory eager = em.find(Inventory.class, 91L);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(eager, "tags")).isTrue();
        assertThat(eager.tags).containsExactly("z", "z", "a", null);
        assertThat(Inventory.loadedSize).isEqualTo(4);
    }

    @Test
    void leftFetchDistinguishesNullElementFromEmptyOwner() {
        persistNullElements();
        var owners = query("left join fetch o.labels").stream().distinct().toList();
        assertThat(owners.getFirst().labels).containsExactly("z", "a", "z", null);
        assertThat(owners.get(1).labels).isEmpty();
        em.clear();
        owners = query("left join fetch o.bag").stream().distinct().toList();
        assertThat(owners.getFirst().bag).containsExactlyInAnyOrder("same", "same", "different", null);
        assertThat(owners.get(1).bag).isEmpty();
        em.clear();
        owners = query("left join fetch o.settings").stream().distinct().toList();
        assertThat(owners.getFirst().settings).containsEntry("null-value", null);
        assertThat(owners.get(1).settings).isEmpty();
    }

    @Test void innerFetchPreservesDuplicateOwnerRowsAndCompleteOrderedChildren() {
        List<Owner> rows = query("join fetch o.children");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).isSameAs(rows.get(1));
        assertThat(rows.getFirst().children).extracting(c -> c.id).containsExactly(11L, 10L);
        assertThat(rows.getFirst().children).allSatisfy(c -> assertThat(c.owner).isSameAs(rows.getFirst()));
        assertThat(em.find(Child.class, 10L)).isSameAs(rows.getFirst().children.get(1));
        assertThat(Owner.LOADS.get()).isEqualTo(1);
        assertThat(Child.LOADS.get()).isEqualTo(2);
        assertThat(sql.getFirst()).containsIgnoringCase("child_position");
        assertThat(selects()).isEqualTo(1);
    }

    @Test void leftDistinctFetchKeepsEmptyCollectionsAndCriteriaUsesSamePipeline() {
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(Owner.class);
        var root = query.from(Owner.class);
        root.fetch("children", JoinType.LEFT);
        query.select(root).distinct(true).where(cb.ge(root.get("id"), cb.parameter(Long.class, "min")))
            .orderBy(cb.asc(root.get("id")));
        List<Owner> rows = em.createQuery(query).setParameter("min", 1L).getResultList();
        assertThat(rows).extracting(o -> o.id).containsExactly(1L, 2L, 3L);
        assertThat(rows.getFirst().children).extracting(c -> c.name).containsExactly("y", "x");
        assertThat(rows.get(1).children).isEmpty();
        assertThat(rows.get(2).children).isEmpty();
        assertThat(rows).allSatisfy(o -> assertThat(factory.getPersistenceUnitUtil().isLoaded(o, "children")).isTrue());
        assertThat(selects()).isEqualTo(1);
    }

    @Test void criteriaInnerFetchExcludesEmptyOwnersAndRetainsFullContents() {
        var cb = em.getCriteriaBuilder();
        var criteria = cb.createQuery(Owner.class);
        var root = criteria.from(Owner.class);
        root.fetch("children", JoinType.INNER);
        criteria.select(root).distinct(true);
        List<Owner> rows = em.createQuery(criteria).getResultList();
        assertThat(rows).extracting(o -> o.id).containsExactly(1L);
        assertThat(rows.getFirst().children).extracting(c -> c.id).containsExactly(11L, 10L);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void existingUnloadedElementReferenceHydratesInPlaceAndCallbacksSeeTheCompleteGraph() {
        em.getTransaction().begin();
        Group group = new Group();
        group.setId(1);
        Member member = new Member();
        member.setId(1); member.setName("member"); member.setGroup(group);
        Holder holder = new Holder();
        holder.setId(1); holder.setMember(member);
        em.persist(group); em.persist(member); em.persist(holder);
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll();
        Member reference = em.find(Holder.class, 1L).getMember();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isFalse();
        Group.loadedSize = -1; Member.loadedSize = -1; Member.loads = 0; sql.clear();
        Group loaded = em.createQuery("select g from CfGroup g join fetch g.members where g.id = 1", Group.class)
            .getSingleResult();
        assertThat(loaded.getMembers()).containsExactly(reference);
        assertThat(reference.getName()).isEqualTo("member");
        assertThat(reference.getGroup()).isSameAs(loaded);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isTrue();
        assertThat(Group.loadedSize).isEqualTo(1);
        assertThat(Member.loadedSize).isEqualTo(1);
        assertThat(Member.loads).isEqualTo(1);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void unloadedWrapperIsInitializedInPlaceAndDirtyStateIsNotOverwritten() {
        Owner owner = em.find(Owner.class, 1L);
        List<Child> wrapper = owner.children;
        assertThat(factory.getPersistenceUnitUtil().isLoaded(owner, "children")).isFalse();
        owner.name = "dirty";
        sql.clear();
        Owner found = em.createQuery("select distinct o from CfOwner o join fetch o.children where o.id = :id", Owner.class)
            .setParameter("id", 1L).getSingleResult();
        assertThat(found).isSameAs(owner);
        assertThat(found.children).isSameAs(wrapper);
        assertThat(wrapper).extracting(c -> c.id).containsExactly(11L, 10L);
        assertThat(owner.name).isEqualTo("dirty");
        wrapper.getFirst().name = "dirty-child";
        wrapper.removeLast();
        sql.clear();
        query("left join fetch o.children");
        assertThat(wrapper).hasSize(1);
        assertThat(wrapper.getFirst().name).isEqualTo("dirty-child");
        assertThat(selects()).isEqualTo(1);
        owner.shared.removeIf(c -> c.id == 10);
        owner.named.remove("first");
        owner.byName.remove("x");
        em.find(Owner.class, 3L).shared.clear();
        em.getTransaction().begin();
        em.flush();
        em.getTransaction().commit();
        em.clear(); factory.getCache().evictAll();
        assertThat(em.find(Child.class, 10L)).isNull();
        assertThat(em.find(Owner.class, 1L).name).isEqualTo("dirty");
        assertThat(em.find(Child.class, 11L).name).isEqualTo("dirty-child");
    }

    @Test void sharedManyToManyTargetsUseOneManagedInstanceAndOneSelect() {
        List<Owner> rows = em.createQuery("select distinct o from CfOwner o left join fetch o.shared order by o.id",
            Owner.class).getResultList();
        Child x = rows.getFirst().shared.stream().filter(c -> c.id == 10).findFirst().orElseThrow();
        assertThat(rows.get(2).shared).containsExactly(x);
        assertThat(rows.get(2).shared.iterator().next()).isSameAs(x);
        assertThat(rows.get(1).shared).isEmpty();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void sortedCollectionComparatorSeesResolvedElementRelationships() {
        List<Owner> rows = em.createQuery("select distinct o from CfOwner o left join fetch o.sorted order by o.id",
            Owner.class).getResultList();
        assertThat(rows.getFirst().sorted).extracting(c -> c.id).containsExactly(10L, 11L);
        assertThat(rows.get(1).sorted).isEmpty();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void elementListRetainsPositionsDuplicatesAndFlushBaseline() {
        List<Owner> rows = em.createQuery("select distinct o from CfOwner o left join fetch o.labels order by o.id",
            Owner.class).getResultList();
        assertThat(rows.getFirst().labels).containsExactly("z", "a", "z");
        assertThat(rows.get(1).labels).isEmpty();
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().begin();
        rows.getFirst().labels.remove(1);
        em.flush();
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll();
        assertThat(em.find(Owner.class, 1L).labels).containsExactly("z", "z");
    }

    @Test void elementAndEntityMapsRetainStoredKeys() {
        List<Owner> rows = query("left join fetch o.settings");
        assertThat(rows.getFirst().settings).containsExactlyInAnyOrderEntriesOf(Map.of("a", "one", "b", "two"));
        assertThat(rows.stream().filter(o -> o.id == 2).findFirst().orElseThrow().settings).isEmpty();
        assertThat(selects()).isEqualTo(1);
        em.clear(); factory.getCache().evictAll(); sql.clear();
        rows = query("left join fetch o.named");
        assertThat(rows.getFirst().named).hasSize(2);
        assertThat(rows.getFirst().named.get("first").name).isEqualTo("x");
        assertThat(rows.getFirst().named.get("second").name).isEqualTo("y");
        assertThat(selects()).isEqualTo(1);
    }

    @Test void paginationLimitsDistinctOwnersInSqlThenLoadsTheirWholeCollections() {
        List<Owner> first = em.createQuery("select o from CfOwner o left join fetch o.children order by o.name", Owner.class)
            .setMaxResults(1).getResultList();
        assertThat(first).extracting(o -> o.id).containsExactly(2L);
        assertThat(first.getFirst().children).isEmpty();
        assertThat(sql.getFirst()).containsPattern("(?i)(fetch|limit)");
        em.clear(); factory.getCache().evictAll(); sql.clear();
        List<Owner> next = em.createQuery("select o from CfOwner o left join fetch o.children order by o.id", Owner.class)
            .setFirstResult(1).setMaxResults(1).getResultList();
        assertThat(next).extracting(o -> o.id).containsExactly(2L);
        assertThat(next.getFirst().children).isEmpty();
        em.clear(); factory.getCache().evictAll(); sql.clear();
        var cb = em.getCriteriaBuilder();
        var criteria = cb.createQuery(Owner.class);
        var root = criteria.from(Owner.class);
        root.fetch("children", JoinType.LEFT);
        criteria.select(root).distinct(true).orderBy(cb.asc(root.get("id")));
        List<Owner> rows = em.createQuery(criteria).setMaxResults(1).getResultList();
        assertThat(rows).extracting(o -> o.id).containsExactly(1L);
        assertThat(rows.getFirst().children).extracting(c -> c.id).containsExactly(11L, 10L);
        assertThat(sql.getFirst()).containsPattern("(?i)(fetch|limit)");
    }

    @Test void unsupportedPaginationIsExplicitlyRefusedBeforeReadingPartialCollections() {
        assertThatThrownBy(() -> em.createQuery("select o, o.name from CfOwner o left join fetch o.children")
            .setMaxResults(1).getResultList()).isInstanceOf(PersistenceException.class).hasMessageContaining("collection fetch");
        assertThatThrownBy(() -> em.createQuery("select o from CfOwner o left join fetch o.children c order by c.name", Owner.class)
            .setFirstResult(1).getResultList()).isInstanceOf(PersistenceException.class).hasMessageContaining("collection fetch");
        assertThatThrownBy(() -> em.createQuery("select c from CfOwner o join CfOwner c on c.id = o.id left join fetch c.children",
            Owner.class).setMaxResults(1).getResultList()).isInstanceOf(PersistenceException.class).hasMessageContaining("collection fetch");
        assertThat(selects()).isZero();
    }

    @Test void distinctOwnerDoesNotDiscardDuplicateUnindexedBagElements() {
        Owner owner = em.createQuery("select distinct o from CfOwner o join fetch o.bag where o.id = 1", Owner.class)
            .getSingleResult();
        assertThat(owner.bag).containsExactlyInAnyOrder("same", "same", "different");
        assertThat(selects()).isEqualTo(1);
    }

    @Test void embeddableValuesAndDerivedMapKeysUseExistingAssembly() {
        Owner owner = em.createQuery("select distinct o from CfOwner o join fetch o.values where o.id = 1", Owner.class)
            .getSingleResult();
        assertThat(owner.values).extracting(v -> v.text).containsExactly("a", "b");
        assertThat(owner.values).extracting(v -> v.number).containsExactly(1, 2);
        assertThat(selects()).isEqualTo(1);
        em.clear(); factory.getCache().evictAll(); sql.clear();
        owner = em.createQuery("select distinct o from CfOwner o join fetch o.byName where o.id = 1", Owner.class)
            .getSingleResult();
        assertThat(owner.byName).hasSize(2);
        assertThat(owner.byName.get("x").id).isEqualTo(10);
        assertThat(owner.byName.get("y").id).isEqualTo(11);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void eagerCollectionIsCompletedBeforePostLoadWithoutSecondarySelect() {
        em.getTransaction().begin();
        Inventory inventory = new Inventory();
        inventory.id = 1;
        inventory.tags.addAll(List.of("a", "c", "b"));
        em.persist(inventory);
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll(); sql.clear();
        Inventory.loadedSize = -1;
        Inventory found = em.createQuery("select i from CfInventory i join fetch i.tags where i.id = 1", Inventory.class)
            .getSingleResult();
        assertThat(found.tags).containsExactly("c", "b", "a");
        assertThat(Inventory.loadedSize).isEqualTo(3);
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().begin();
        em.flush();
        em.getTransaction().commit();
        assertThat(sql.stream().filter(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("delete"))).isEmpty();
    }

    @Test void distinctAndSingleResultUseManagedIdentityNotApplicationEquality() {
        em.getTransaction().begin();
        for (long id : List.of(1L, 2L)) {
            Inventory inventory = new Inventory();
            inventory.id = id;
            inventory.tags.addAll(List.of("same", "same"));
            em.persist(inventory);
        }
        em.getTransaction().commit(); em.clear(); factory.getCache().evictAll(); sql.clear();
        assertThat(em.createQuery("select distinct i from CfInventory i join fetch i.tags order by i.id", Inventory.class)
            .getResultList()).extracting(i -> i.id).containsExactly(1L, 2L);
        assertThat(selects()).isEqualTo(1);
        assertThatThrownBy(() -> em.createQuery("select i from CfInventory i join fetch i.tags", Inventory.class)
            .getSingleResult()).isInstanceOf(NonUniqueResultException.class);
    }

    @Test void singleResultNeverLimitsCollectionRowsAndCacheStoresCompletedGraph() {
        Owner owner = em.createQuery("select o from CfOwner o join fetch o.labels where o.id = 1", Owner.class).getSingleResult();
        assertThat(owner.labels).containsExactly("z", "a", "z");
        assertThat(selects()).isEqualTo(1);
        em.clear(); sql.clear();
        assertThat(em.find(Owner.class, 1L).labels).containsExactly("z", "a", "z");
        assertThat(selects()).isZero();
    }

    @Test void multipleFetchesAndRestrictedFetchAliasRetainCompleteFallbackCollections() {
        List<Owner> rows = query("left join fetch o.children left join fetch o.labels");
        assertThat(rows.getFirst().children).hasSize(2);
        assertThat(rows.getFirst().labels).containsExactly("z", "a", "z");
        em.clear(); factory.getCache().evictAll();
        Owner owner = em.createQuery("select o from CfOwner o join fetch o.children c where c.name = 'x'", Owner.class)
            .getSingleResult();
        assertThat(owner.children).extracting(c -> c.id).containsExactly(11L, 10L);
    }

    @Entity(name = "CfOwner") @Table(name = "CF_OWNER")
    public static class Owner {
        static final AtomicInteger LOADS = new AtomicInteger();
        @Id long id;
        String name;
        @OneToMany(mappedBy = "owner", orphanRemoval = true) @OrderColumn(name = "child_position")
        List<Child> children = new ArrayList<>();
        @OneToMany(mappedBy = "owner") SortedSet<Child> sorted = new TreeSet<>();
        @ManyToMany Set<Child> shared = new LinkedHashSet<>();
        @OneToMany @JoinTable(name = "CF_NAMED") @MapKeyColumn(name = "child_key")
        Map<String, Child> named = new LinkedHashMap<>();
        @ElementCollection @OrderColumn(name = "label_position")
        List<String> labels = new ArrayList<>();
        @ElementCollection @MapKeyColumn(name = "setting_key")
        Map<String, String> settings = new LinkedHashMap<>();
        @ElementCollection Collection<String> bag = new ArrayList<>();
        @ElementCollection @OrderColumn(name = "value_position")
        List<Value> values = new ArrayList<>();
        @ManyToMany @JoinTable(name = "CF_BY_NAME") @MapKey(name = "name")
        Map<String, Child> byName = new LinkedHashMap<>();
        public Owner() {}
        Owner(long id, String name) { this.id = id; this.name = name; }
        @PostLoad void loaded() { LOADS.incrementAndGet(); }
    }
    @Entity(name = "CfChild") @Table(name = "CF_CHILD")
    public static class Child implements Comparable<Child> {
        static final AtomicInteger LOADS = new AtomicInteger();
        @Id long id;
        String name;
        @ManyToOne Owner owner;
        public Child() {}
        Child(long id, String name, Owner owner) { this.id = id; this.name = name; this.owner = owner; }
        @PostLoad void loaded() { LOADS.incrementAndGet(); }
        @Override public int compareTo(Child other) {
            int byOwner = Long.compare(owner.id, other.owner.id);
            return byOwner == 0 ? Long.compare(id, other.id) : byOwner;
        }
    }
    @Embeddable
    public static class Value {
        String text;
        @Column(name = "value_number") int number;
        public Value() {}
        Value(String text, int number) { this.text = text; this.number = number; }
    }
    @Entity(name = "CfInventory") @Table(name = "CF_INVENTORY")
    public static class Inventory {
        static int loadedSize;
        @Id long id;
        @ElementCollection(fetch = FetchType.EAGER) @OrderBy("DESC")
        List<String> tags = new ArrayList<>();
        public Inventory() {}
        @PostLoad void loaded() { loadedSize = tags.size(); }
        @Override public boolean equals(Object other) { return other instanceof Inventory; }
        @Override public int hashCode() { return 1; }
    }
    @Entity(name = "CfGroup") @Table(name = "CF_GROUP")
    public static class Group {
        static int loadedSize;
        private long id;
        private List<Member> members = new ArrayList<>();
        public Group() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @OneToMany(mappedBy = "group") public List<Member> getMembers() { return members; }
        public void setMembers(List<Member> members) { this.members = members; }
        @PostLoad void loaded() { loadedSize = members.size(); }
    }
    @Entity(name = "CfMember") @Table(name = "CF_MEMBER")
    public static class Member {
        static int loadedSize, loads;
        private long id;
        private String name;
        private Group group;
        public Member() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @ManyToOne(fetch = FetchType.LAZY) public Group getGroup() { return group; }
        public void setGroup(Group group) { this.group = group; }
        @PostLoad void loaded() { loads++; loadedSize = group.getMembers().size(); }
    }
    @Entity(name = "CfHolder") @Table(name = "CF_HOLDER")
    public static class Holder {
        private long id;
        private Member member;
        public Holder() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @ManyToOne(fetch = FetchType.LAZY) public Member getMember() { return member; }
        public void setMember(Member member) { this.member = member; }
    }
}
