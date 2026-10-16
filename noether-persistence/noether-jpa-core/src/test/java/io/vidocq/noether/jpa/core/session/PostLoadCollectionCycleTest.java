/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * BUG-20261016-01, §3.6.3 / §2.10: a PostLoad callback runs once the state of its entity is loaded, and the LAZY
 * collections it reads from there are complete, even when an element's callback reads the collection whose loading
 * loaded that element. Groups use property access, members/clubs/nodes field access: both generated access shapes.
 */
class PostLoadCollectionCycleTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String SETTING = "io.vidocq.noether.jpa.fetch.batch-size";
    private EntityManagerFactory factory;
    private EntityManager em;

    private void open(int batchSize) {
        factory = new PersistenceConfiguration("postload-cycle")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Group.class).managedClass(Member.class).managedClass(Club.class).managedClass(Node.class)
            .sharedCacheMode(SharedCacheMode.NONE)
            .property(SETTING, Integer.toString(batchSize))
            .property("jakarta.persistence.nonJtaDataSource",
                new CountingDataSource("jdbc:h2:mem:postload-cycle-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1"))
            .property("jakarta.persistence.schema-generation.database.action", "create").createEntityManagerFactory();
        em = factory.createEntityManager();
        Group.LOADS.set(0); Member.LOADS.set(0); Node.LOADS.set(0);
        Group.mutate = false; Member.fail = false; Member.readClub = false;
    }

    @AfterEach void close() {
        Group.mutate = false; Member.fail = false; Member.readClub = false;
        if (factory != null) factory.close();
    }

    /** Groups 1 (members 11, 12, 13 in club 100) and 2 (member 21 in club 100); group 3 is empty. */
    private void seed() {
        em.getTransaction().begin();
        Club club = new Club(100);
        em.persist(club);
        for (long g = 1; g <= 3; g++) {
            Group group = new Group();
            group.setId(g);
            group.setName("group-" + g);
            em.persist(group);
        }
        long[][] members = {{11, 1}, {12, 1}, {13, 1}, {21, 2}};
        for (long[] m : members) em.persist(new Member(m[0], em.find(Group.class, m[1]), club));
        em.getTransaction().commit();
        cold();
    }

    private void cold() {
        em.clear();
        factory.getCache().evictAll();
        Group.LOADS.set(0); Member.LOADS.set(0); Node.LOADS.set(0);
    }

    private static List<Long> ids(Collection<Member> members) {
        return members.stream().map(m -> m.id).toList();
    }

    /** Each group's callback saw its whole collection, each member's callback saw the whole collection of its group. */
    private static void assertReady(Group group, Long... expected) {
        assertThat(group.loads).isEqualTo(1);
        assertThat(group.seen).containsExactlyInAnyOrder(expected).doesNotHaveDuplicates();
        assertThat(ids(group.getMembers())).containsExactlyInAnyOrder(expected).doesNotHaveDuplicates();
        for (Member member : group.getMembers()) {
            assertThat(member.getGroup()).isSameAs(group);
            assertThat(member.loads).isEqualTo(1);
            assertThat(member.seen).containsExactlyInAnyOrder(expected).doesNotHaveDuplicates();
            assertThat(member.sawSelf).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void ordinaryFindCompletesTheCollectionBeforeParentAndChildCallbacks(int batchSize) {
        open(batchSize);
        seed();
        Group group = em.find(Group.class, 1L);
        assertReady(group, 11L, 12L, 13L);
        assertThat(Group.LOADS).hasValue(1);
        assertThat(Member.LOADS).hasValue(3);
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void getReferenceOfTheSameShapeIsReadyToo(int batchSize) {
        open(batchSize);
        seed();
        Group group = em.getReference(Group.class, 1L);
        assertReady(group, 11L, 12L, 13L);
        assertThat(em.find(Group.class, 1L)).isSameAs(group);
        assertThat(Member.LOADS).hasValue(3);
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void emptyCollectionCallbackSeesAnEmptyLoadedCollection(int batchSize) {
        open(batchSize);
        seed();
        Group group = em.find(Group.class, 3L);
        assertReady(group);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(group, "members")).isTrue();
        assertThat(Member.LOADS).hasValue(0);
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void queriedGroupsShareIdentityAndEveryCallbackRunsOnceOnCompleteCollections(int batchSize) {
        open(batchSize);
        seed();
        List<Group> groups = em.createQuery("select g from PcGroup g order by g.id", Group.class).getResultList();
        assertThat(groups).extracting(Group::getId).containsExactly(1L, 2L, 3L);
        assertReady(groups.get(0), 11L, 12L, 13L);
        assertReady(groups.get(1), 21L);
        assertReady(groups.get(2));
        assertThat(Group.LOADS).hasValue(3);
        assertThat(Member.LOADS).hasValue(4);
        assertThat(em.find(Member.class, 21L)).isSameAs(groups.get(1).getMembers().getFirst());
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void directLazyAccessWithoutOwnerCallbackCompletesBeforeElementCallbacks(int batchSize) {
        open(batchSize);
        seed();
        Member.readClub = true;
        Club club = em.find(Club.class, 100L);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(club, "members")).isFalse();
        assertThat(ids(club.members)).containsExactlyInAnyOrder(11L, 12L, 13L, 21L).doesNotHaveDuplicates();
        for (Member member : club.members) {
            assertThat(member.club).isSameAs(club);
            assertThat(member.loads).isEqualTo(1);
            assertThat(member.clubSeen).containsExactlyInAnyOrder(11L, 12L, 13L, 21L);
        }
        assertReady(em.find(Group.class, 1L), 11L, 12L, 13L);
        assertReady(em.find(Group.class, 2L), 21L);
        assertThat(Member.LOADS).hasValue(4);
        assertThat(Group.LOADS).hasValue(2);
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void childFindReachesItsParentCollectionThroughItsCallback(int batchSize) {
        open(batchSize);
        seed();
        Member member = em.find(Member.class, 12L);
        assertThat(member.loads).isEqualTo(1);
        assertReady(member.getGroup(), 11L, 12L, 13L);
        assertThat(member.getGroup().getMembers()).contains(member);
        assertThat(Member.LOADS).hasValue(3);
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void nestedSelfReferencingTreeIsReadyAtEveryLevel(int batchSize) {
        open(batchSize);
        em.getTransaction().begin();
        Node root = new Node(1, null);
        em.persist(root);
        Node a = new Node(2, root), b = new Node(3, root);
        em.persist(a); em.persist(b);
        em.persist(new Node(4, a)); em.persist(new Node(5, a)); em.persist(new Node(6, b));
        em.getTransaction().commit();
        cold();
        Node found = em.find(Node.class, 1L);
        assertThat(Node.LOADS).hasValue(6);
        Map<Long, List<Long>> expected = Map.of(1L, List.of(2L, 3L), 2L, List.of(4L, 5L), 3L, List.of(6L),
            4L, List.of(), 5L, List.of(), 6L, List.of());
        Deque<Node> pending = new ArrayDeque<>(List.of(found));
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            assertThat(visited.add(node)).isTrue();
            assertThat(node.loads).isEqualTo(1);
            assertThat(node.childrenSeen).containsExactlyInAnyOrderElementsOf(expected.get(node.id));
            assertThat(node.children).extracting(n -> n.id).containsExactlyInAnyOrderElementsOf(expected.get(node.id));
            if (node.parent != null) {
                assertThat(node.parent.children).contains(node);
                assertThat(node.siblingsSeen).containsExactlyInAnyOrderElementsOf(expected.get(node.parent.id));
            }
            node.children.forEach(child -> assertThat(child.parent).isSameAs(node));
            pending.addAll(node.children);
        }
        assertThat(visited).hasSize(6);
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void callbackMutationsAreDirtyAndFlushed(int batchSize) {
        open(batchSize);
        seed();
        Group.mutate = true;
        em.getTransaction().begin();
        Group group = em.find(Group.class, 1L);
        assertReady(group, 11L, 12L, 13L);
        assertThat(group.getName()).isEqualTo("group-1:3");
        em.getTransaction().commit();
        Group.mutate = false;
        cold();
        assertThat(em.find(Group.class, 1L).getName()).isEqualTo("group-1:3");
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void elementCallbackFailurePropagatesAndMarksTheTransactionForRollback(int batchSize) {
        open(batchSize);
        seed();
        Member.fail = true;
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.find(Group.class, 1L)).hasStackTraceContaining("member callback failed");
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }

    @ParameterizedTest @ValueSource(ints = {1, 16})
    void refreshReloadsTheCollectionAndCallsNewElementCallbacksOnce(int batchSize) {
        open(batchSize);
        seed();
        Group group = em.find(Group.class, 2L);
        assertReady(group, 21L);
        EntityManager other = factory.createEntityManager();
        other.getTransaction().begin();
        other.persist(new Member(22, other.find(Group.class, 2L), other.find(Club.class, 100L)));
        other.getTransaction().commit();
        other.close();
        Member old = group.getMembers().getFirst();
        group.loads = 0;
        em.getTransaction().begin();
        em.refresh(group);
        em.getTransaction().commit();
        assertThat(group.loads).isEqualTo(1);
        assertThat(group.seen).containsExactlyInAnyOrder(21L, 22L);
        assertThat(ids(group.getMembers())).containsExactlyInAnyOrder(21L, 22L).doesNotHaveDuplicates();
        assertThat(group.getMembers()).contains(old);
        assertThat(old.loads).isEqualTo(1);
        Member fresh = group.getMembers().stream().filter(m -> m.id == 22).findFirst().orElseThrow();
        assertThat(fresh.loads).isEqualTo(1);
        assertThat(fresh.seen).containsExactlyInAnyOrder(21L, 22L);
    }

    @Entity(name = "PcGroup") @Table(name = "PC_GROUP")
    public static class Group {
        static final AtomicInteger LOADS = new AtomicInteger();
        static boolean mutate;
        private long id;
        private String name;
        private List<Member> members = new ArrayList<>();
        int loads;
        List<Long> seen;
        public Group() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @OneToMany(mappedBy = "group") public List<Member> getMembers() { return members; }
        public void setMembers(List<Member> members) { this.members = members; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            loads++;
            seen = ids(members);
            if (mutate) name = name + ":" + members.size();
        }
    }

    @Entity(name = "PcMember") @Table(name = "PC_MEMBER")
    public static class Member {
        static final AtomicInteger LOADS = new AtomicInteger();
        static boolean fail, readClub;
        @Id long id;
        @ManyToOne(fetch = FetchType.LAZY) Group group;
        @ManyToOne(fetch = FetchType.LAZY) Club club;
        @Transient int loads;
        @Transient List<Long> seen;
        @Transient List<Long> clubSeen;
        @Transient boolean sawSelf;
        public Member() {}
        Member(long id, Group group, Club club) { this.id = id; this.group = group; this.club = club; }
        public Group getGroup() { return group; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            loads++;
            if (fail) throw new IllegalStateException("member callback failed");
            seen = ids(group.getMembers());
            sawSelf = group.getMembers().contains(this);
            if (readClub) clubSeen = ids(club.members);
        }
    }

    @Entity(name = "PcClub") @Table(name = "PC_CLUB")
    public static class Club {
        @Id long id;
        @OneToMany(mappedBy = "club") Set<Member> members = new HashSet<>();
        public Club() {}
        Club(long id) { this.id = id; }
    }

    @Entity(name = "PcNode") @Table(name = "PC_NODE")
    public static class Node {
        static final AtomicInteger LOADS = new AtomicInteger();
        @Id long id;
        @ManyToOne(fetch = FetchType.LAZY) Node parent;
        @OneToMany(mappedBy = "parent") List<Node> children = new ArrayList<>();
        @Transient int loads;
        @Transient List<Long> childrenSeen;
        @Transient List<Long> siblingsSeen;
        public Node() {}
        Node(long id, Node parent) { this.id = id; this.parent = parent; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            loads++;
            childrenSeen = children.stream().map(n -> n.id).toList();
            if (parent != null) siblingsSeen = parent.children.stream().map(n -> n.id).toList();
        }
    }
}
