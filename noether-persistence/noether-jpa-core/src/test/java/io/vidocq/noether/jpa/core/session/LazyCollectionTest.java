/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Jakarta Persistence 3.2 §2.10, §3.2.9 and §11.1: a LAZY to-many or element collection is loaded by its first access,
 * not with its owner; EAGER, fetch joins and entity graphs still load what they promise.
 */
class LazyCollectionTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private final List<String> statements = new CopyOnWriteArrayList<>();
    private final List<Boolean> virtual = new CopyOnWriteArrayList<>();
    private EntityManagerFactory factory;
    private EntityManager em;

    private void open(Class<?>... types) {
        open(null, SharedCacheMode.NONE, types);
    }

    private void open(String mapping, SharedCacheMode cache, Class<?>... types) {
        String url = "jdbc:h2:mem:lazy-collections-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        var source = new CountingDataSource(url) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection delegate = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, arguments) -> {
                        if (method.getName().startsWith("prepare") && arguments != null && arguments[0] instanceof String sql) {
                            record(sql);
                        }
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                                return recording(statement);
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
        };
        var configuration = new PersistenceConfiguration("lazy-collections")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property("jakarta.persistence.nonJtaDataSource", source)
            .property("jakarta.persistence.schema-generation.database.action", "create")
            .sharedCacheMode(cache);
        for (Class<?> type : types) configuration.managedClass(type);
        if (mapping != null) configuration.mappingFile(mapping);
        factory = configuration.createEntityManagerFactory();
        em = factory.createEntityManager();
    }

    private void record(String sql) {
        statements.add(sql.toLowerCase(Locale.ROOT));
        virtual.add(Thread.currentThread().isVirtual());
    }

    private Statement recording(Statement statement) {
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] { Statement.class },
            (proxy, method, arguments) -> {
                if (method.getName().startsWith("execute") && arguments != null && arguments[0] instanceof String sql) {
                    record(sql);
                }
                try {
                    return method.invoke(statement, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }

    /** Forgets the statements run so far: the assertions count from the next operation. */
    private void reset() {
        statements.clear();
        virtual.clear();
    }

    @AfterEach
    void close() {
        if (factory != null && factory.isOpen()) factory.close();
    }

    private void transaction(Runnable action) {
        em.getTransaction().begin();
        action.run();
        em.getTransaction().commit();
    }

    private Owner seed() {
        Owner owner = new Owner(1, "owner");
        Tag red = new Tag(1, "red"), blue = new Tag(2, "blue");
        Child first = new Child(10, owner), second = new Child(11, owner);
        owner.children.add(first);
        owner.children.add(second);
        owner.tags.add(red);
        owner.sorted.add(blue);
        owner.sorted.add(red);
        owner.nicknames.add("one");
        owner.nicknames.add("two");
        owner.labels.put("k", "v");
        owner.scores.put("b", 2);
        owner.scores.put("a", 1);
        transaction(() -> { em.persist(red); em.persist(blue); em.persist(owner); });
        em.clear();
        reset();
        return owner;
    }

    private static PersistenceUnitUtil util(EntityManagerFactory factory) {
        return factory.getPersistenceUnitUtil();
    }

    @Test
    void findSelectsOnlyTheOwnerAndFirstAccessLoadsEachCollectionOnceOnAVirtualThread() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        Owner owner = em.find(Owner.class, 1L);
        assertThat(statements).hasSize(1);
        assertThat(statements.getFirst()).contains("lazyowner").doesNotContain("lazychild").doesNotContain("nicknames");
        reset();
        PersistenceUnitUtil util = util(factory);
        for (String attribute : List.of("children", "tags", "sorted", "nicknames", "labels", "scores")) {
            assertThat(util.isLoaded(owner, attribute)).as(attribute).isFalse();
            assertThat(Persistence.getPersistenceUtil().isLoaded(owner, attribute)).as(attribute).isFalse();
        }
        assertThat(util.isLoaded(owner)).isTrue();
        assertThat(util.isLoaded(owner, "name")).isTrue();
        assertThat(statements).isEmpty();
        assertThat(owner.children).extracting(c -> c.id).containsExactly(10L, 11L);
        // one select of the collection, then its elements by their ids (secondary selects, no batching yet)
        assertThat(statements).filteredOn(s -> s.contains("where owner_id")).hasSize(1);
        assertThat(statements).allMatch(s -> s.contains("lazychild"));
        int firstAccess = statements.size();
        assertThat(virtual).containsOnly(true);
        assertThat(owner.children.get(0).owner).isSameAs(owner);
        assertThat(owner.children.get(0)).isSameAs(em.find(Child.class, 10L));
        assertThat(owner.children.size()).isEqualTo(2);
        assertThat(statements).hasSize(firstAccess);
        assertThat(util.isLoaded(owner, "children")).isTrue();
        assertThat(util.isLoaded(owner, "tags")).isFalse();
        reset();
        assertThat(owner.tags).extracting(t -> t.name).containsExactly("red");
        assertThat(owner.sorted).extracting(t -> t.name).containsExactly("blue", "red");
        assertThat(owner.sorted).isInstanceOf(SortedSet.class);
        assertThat(owner.nicknames).containsExactly("one", "two");
        assertThat(owner.labels).containsExactly(Map.entry("k", "v"));
        assertThat(owner.scores).isInstanceOf(SortedMap.class);
        assertThat(((SortedMap<String, Integer>) owner.scores).firstKey()).isEqualTo("a");
        for (String table : List.of("lazy_owner_tags", "lazy_owner_sorted", "lazy_nicknames", "lazy_labels", "lazy_scores")) {
            assertThat(statements).as(table).filteredOn(s -> s.contains(table)).hasSize(1);
        }
        int loaded = statements.size();
        owner.tags.size();
        owner.labels.size();
        owner.sorted.first();
        assertThat(statements).hasSize(loaded);
    }

    @Test
    void untouchedLazyCollectionsSurviveFlushCommitAndOwnerUpdatesWithoutLoading() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        owner.name = "renamed";
        em.flush();
        em.getTransaction().commit();
        assertThat(statements).filteredOn(s -> s.startsWith("select")).hasSize(1);
        assertThat(statements).filteredOn(s -> s.startsWith("update")).hasSize(1);
        assertThat(statements).noneMatch(s -> s.startsWith("delete") || s.startsWith("insert"));
        assertThat(util(factory).isLoaded(owner, "children")).isFalse();
        em.clear();
        Owner reloaded = em.find(Owner.class, 1L);
        assertThat(reloaded.name).isEqualTo("renamed");
        assertThat(reloaded.children).hasSize(2);
        assertThat(reloaded.tags).hasSize(1);
        assertThat(reloaded.nicknames).containsExactly("one", "two");
    }

    @Test
    void inTransactionAccessUsesTheTransactionConnection() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        Child other = new Child(12, owner);
        em.persist(other);
        reset();
        // the pending insert is not flushed by the collection load, which reads the committed and flushed rows only
        assertThat(owner.children).extracting(c -> c.id).containsExactly(10L, 11L);
        assertThat(statements).filteredOn(s -> s.contains("where owner_id")).hasSize(1);
        assertThat(statements).noneMatch(s -> s.startsWith("insert"));
        em.getTransaction().rollback();
    }

    @Test
    void mutationsInitializeAndAreWrittenIncludingOrphanRemoval() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        Tag blue = em.find(Tag.class, 2L);
        reset();
        owner.children.removeFirst();
        assertThat(util(factory).isLoaded(owner, "children")).isTrue();
        assertThat(statements).filteredOn(s -> s.contains("where owner_id")).hasSize(1);
        assertThat(statements).allMatch(s -> s.startsWith("select") && s.contains("lazychild"));
        owner.tags.add(blue);
        owner.nicknames.add("three");
        owner.labels.put("x", "y");
        owner.sorted.remove(blue);
        Child added = new Child(13, owner);
        owner.children.add(added);
        em.getTransaction().commit();
        em.getTransaction().begin();
        owner.nicknames.remove("one");
        em.getTransaction().commit();
        em.clear();
        reset();
        Owner reloaded = em.find(Owner.class, 1L);
        assertThat(reloaded.children).extracting(c -> c.id).containsExactly(11L, 13L);
        assertThat(em.find(Child.class, 10L)).isNull();
        assertThat(reloaded.tags).extracting(t -> t.id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(reloaded.sorted).extracting(t -> t.id).containsExactly(1L);
        assertThat(reloaded.nicknames).containsExactly("two", "three");
        assertThat(reloaded.labels).containsOnly(Map.entry("k", "v"), Map.entry("x", "y"));
    }

    @Test
    void replacingAnUnloadedCollectionWritesTheNewOneAndOrphansTheOldChildren() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        owner.children = new ArrayList<>(List.of(new Child(20, owner)));
        owner.nicknames = new ArrayList<>(List.of("only"));
        owner.tags = new HashSet<>();
        em.getTransaction().commit();
        em.clear();
        Owner reloaded = em.find(Owner.class, 1L);
        assertThat(reloaded.children).extracting(c -> c.id).containsExactly(20L);
        assertThat(em.find(Child.class, 10L)).isNull();
        assertThat(reloaded.nicknames).containsExactly("only");
        assertThat(reloaded.tags).isEmpty();
    }

    @Test
    void removingTheOwnerDeletesItsCollectionsAndCascadesToUnloadedChildren() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        transaction(() -> em.remove(em.find(Owner.class, 1L)));
        em.clear();
        assertThat(em.find(Owner.class, 1L)).isNull();
        assertThat(em.find(Child.class, 10L)).isNull();
        assertThat(em.createNativeQuery("select count(*) from LAZY_NICKNAMES").getSingleResult()).isEqualTo(0L);
        assertThat(em.createNativeQuery("select count(*) from LAZY_OWNER_TAGS").getSingleResult()).isEqualTo(0L);
        assertThat(em.find(Tag.class, 1L)).isNotNull();
    }

    @Test
    void unloadedCollectionsFailExplicitlyAfterDetachClearCloseAndRollbackWhileLoadedOnesStayUsable() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        Owner detached = em.find(Owner.class, 1L);
        detached.nicknames.size();
        em.detach(detached);
        reset();
        assertThatThrownBy(() -> detached.children.size()).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("children");
        assertThatThrownBy(detached.tags::iterator).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> detached.labels.get("k")).isInstanceOf(PersistenceException.class);
        assertThat(detached.nicknames).containsExactly("one", "two");
        assertThat(statements).isEmpty();
        assertThat(util(factory).isLoaded(detached, "children")).isFalse();

        Owner cleared = em.find(Owner.class, 1L);
        em.clear();
        assertThatThrownBy(() -> cleared.children.isEmpty()).isInstanceOf(PersistenceException.class);

        Owner closed = em.find(Owner.class, 1L);
        closed.tags.size();
        em.close();
        assertThatThrownBy(() -> closed.children.size()).isInstanceOf(PersistenceException.class);
        assertThat(closed.tags).hasSize(1);

        em = factory.createEntityManager();
        em.getTransaction().begin();
        Owner rolledBack = em.find(Owner.class, 1L);
        em.getTransaction().rollback();
        assertThatThrownBy(() -> rolledBack.sorted.first()).isInstanceOf(PersistenceException.class);
        assertThat(statements).filteredOn(s -> s.contains("lazy_owner_sorted")).isEmpty();
    }

    @Test
    void anEntityManagerClosedInsideItsTransactionStillLoadsUntilTheTransactionEnds() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        em.getTransaction().begin();
        EntityTransaction transaction = em.getTransaction();
        Owner owner = em.find(Owner.class, 1L);
        em.close();
        assertThat(owner.children).hasSize(2);
        transaction.commit();
        assertThatThrownBy(() -> owner.tags.size()).isInstanceOf(PersistenceException.class);
    }

    @Test
    void serializedUnloadedCollectionsFailInsteadOfLookingEmptyAndLoadedOnesKeepTheirElements() throws Exception {
        open(Owner.class, Child.class, Tag.class);
        seed();
        Owner owner = em.find(Owner.class, 1L);
        owner.nicknames.size();
        Owner copy = roundTrip(owner);
        assertThat(copy.nicknames).containsExactly("one", "two");
        assertThatThrownBy(() -> copy.children.size()).isInstanceOf(PersistenceException.class);
        assertThat(util(factory).isLoaded(copy, "children")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (var in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    @Test
    void mergeOfADetachedGraphKeepsUnloadedCollectionsAndWritesLoadedChanges() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        Owner detached = em.find(Owner.class, 1L);
        detached.nicknames.add("three");
        detached.children.size();
        em.clear();
        detached.children.add(new Child(14, detached));
        detached.name = "merged";
        reset();
        transaction(() -> {
            Owner merged = em.merge(detached);
            assertThat(util(factory).isLoaded(merged, "tags")).isFalse();
            assertThat(merged.children).extracting(c -> c.id).containsExactly(10L, 11L, 14L);
        });
        // the collections that were not loaded are neither read nor written; the loaded element collection is
        // written again whole, as every changed element collection
        assertThat(statements).noneMatch(s -> s.contains("lazy_owner_tags") || s.contains("lazy_owner_sorted")
            || s.contains("lazy_labels") || s.contains("lazy_scores") || s.startsWith("delete from lazychild"));
        em.clear();
        Owner reloaded = em.find(Owner.class, 1L);
        assertThat(reloaded.name).isEqualTo("merged");
        assertThat(reloaded.nicknames).containsExactly("one", "two", "three");
        assertThat(reloaded.children).extracting(c -> c.id).containsExactly(10L, 11L, 14L);
        assertThat(reloaded.tags).hasSize(1);
        assertThat(reloaded.labels).containsOnly(Map.entry("k", "v"));
    }

    @Test
    void persistCascadesThroughNewCollectionsAndFreshInstancesAreLoaded() {
        open(Owner.class, Child.class, Tag.class);
        Owner owner = new Owner(5, "new");
        owner.children.add(new Child(50, owner));
        transaction(() -> em.persist(owner));
        assertThat(util(factory).isLoaded(owner, "children")).isTrue();
        assertThat(owner.children).hasSize(1);
        em.clear();
        assertThat(em.find(Child.class, 50L)).isNotNull();
    }

    @Test
    void refreshDiscardsAndReloadsCollectionsOnTheNextAccess() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        em.getTransaction().begin();
        Owner owner = em.find(Owner.class, 1L);
        owner.nicknames.add("dirty");
        reset();
        em.refresh(owner);
        assertThat(statements).noneMatch(s -> s.contains("lazy_nicknames"));
        assertThat(owner.nicknames).containsExactly("one", "two");
        assertThat(owner.children).hasSize(2);
        em.getTransaction().commit();
    }

    @Test
    void joinFetchAndCriteriaFetchInitializeIncludingEmptyCollections() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        Owner empty = new Owner(2, "empty");
        transaction(() -> em.persist(empty));
        em.clear();
        reset();
        List<Owner> owners = em.createQuery(
            "select distinct o from LazyOwner o left join fetch o.children left join fetch o.nicknames order by o.id", Owner.class)
            .getResultList();
        assertThat(owners).hasSize(2);
        long afterQuery = statements.size();
        for (Owner owner : owners) {
            assertThat(util(factory).isLoaded(owner, "children")).isTrue();
            assertThat(util(factory).isLoaded(owner, "nicknames")).isTrue();
            assertThat(util(factory).isLoaded(owner, "tags")).isFalse();
        }
        em.clear();
        assertThat(owners.get(0).children).extracting(c -> c.id).containsExactly(10L, 11L);
        assertThat(owners.get(0).nicknames).containsExactly("one", "two");
        assertThat(owners.get(1).children).isEmpty();
        assertThat(owners.get(1).nicknames).isEmpty();
        assertThat(statements).hasSize((int) afterQuery);

        var builder = em.getCriteriaBuilder();
        var criteria = builder.createQuery(Owner.class);
        var root = criteria.from(Owner.class);
        root.fetch("tags", jakarta.persistence.criteria.JoinType.LEFT);
        criteria.select(root).distinct(true).orderBy(builder.asc(root.get("id")));
        List<Owner> fetched = em.createQuery(criteria).getResultList();
        em.clear();
        assertThat(fetched.get(0).tags).extracting(t -> t.name).containsExactly("red");
        assertThat(fetched.get(1).tags).isEmpty();
        assertThatThrownBy(() -> fetched.get(0).children.size()).isInstanceOf(PersistenceException.class);
    }

    @Test
    void nestedJoinFetchInitializesTheFetchedPathOfEveryElement() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        transaction(() -> em.find(Child.class, 10L).toys.add("ball"));
        em.clear();
        Owner owner = em.createQuery("select o from LazyOwner o left join fetch o.children c left join fetch c.toys where o.id = 1",
            Owner.class).getResultList().getFirst();
        em.clear();
        assertThat(owner.children.getFirst().toys).containsExactly("ball");
        assertThat(owner.children.get(1).toys).isEmpty();
    }

    @Test
    void fetchAndLoadGraphsInitializeTheirNodesForFindAndQueries() {
        open(Owner.class, Child.class, Tag.class);
        seed();
        EntityGraph<Owner> graph = em.createEntityGraph(Owner.class);
        graph.addAttributeNodes("tags");
        graph.addSubgraph("children").addAttributeNodes("toys");
        Owner found = em.find(Owner.class, 1L, Map.of("jakarta.persistence.fetchgraph", graph));
        Owner same = em.find(graph, 1L);
        assertThat(same).isSameAs(found);
        em.clear();
        assertThat(found.tags).hasSize(1);
        assertThat(found.children).hasSize(2);
        assertThat(found.children.getFirst().toys).isEmpty();
        assertThatThrownBy(() -> found.nicknames.size()).isInstanceOf(PersistenceException.class);

        // a graph applied to an instance already in the persistence context still loads its nodes
        Owner managed = em.find(Owner.class, 1L);
        em.find(Owner.class, 1L, Map.of("jakarta.persistence.loadgraph", graph));
        assertThat(util(factory).isLoaded(managed, "tags")).isTrue();
        em.clear();

        List<Owner> queried = em.createQuery("select o from LazyOwner o", Owner.class)
            .setHint("jakarta.persistence.loadgraph", graph).getResultList();
        em.clear();
        assertThat(queried.getFirst().tags).hasSize(1);
        assertThat(queried.getFirst().children).hasSize(2);
    }

    @Test
    void eagerCollectionsAndConcreteCollectionTypesLoadWithTheirOwner() {
        open(Eager.class, Tag.class);
        Eager eager = new Eager(1);
        Tag tag = new Tag(1, "t");
        eager.tags.add(tag);
        eager.words.add("w");
        transaction(() -> { em.persist(tag); em.persist(eager); });
        em.clear();
        Eager loaded = em.find(Eager.class, 1L);
        assertThat(util(factory).isLoaded(loaded, "tags")).isTrue();
        assertThat(util(factory).isLoaded(loaded, "words")).isTrue();
        em.clear();
        assertThat(loaded.tags).extracting(t -> t.name).containsExactly("t");
        assertThat(loaded.words).containsExactly("w");
    }

    @Test
    void xmlFetchOverridesShareTheAnnotationModel() {
        open("orm/lazy-collections.xml", SharedCacheMode.NONE, XmlFetch.class);
        XmlFetch entity = new XmlFetch();
        entity.id = 1;
        entity.eager.add("e");
        entity.lazy.add("l");
        transaction(() -> em.persist(entity));
        em.clear();
        XmlFetch loaded = em.find(XmlFetch.class, 1L);
        assertThat(util(factory).isLoaded(loaded, "eager")).isTrue();
        assertThat(util(factory).isLoaded(loaded, "lazy")).isFalse();
        assertThat(loaded.lazy).containsExactly("l");
    }

    @Test
    void sharedCacheRestoresUnloadedCollectionsWithoutLoadingThem() {
        open(null, SharedCacheMode.ALL, Owner.class, Child.class, Tag.class);
        seed();
        factory.getCache().evictAll(); // the persisted instance held its collections: cache one read from its row
        try (EntityManager filler = factory.createEntityManager()) {
            filler.find(Owner.class, 1L);
        }
        assertThat(factory.getCache().contains(Owner.class, 1L)).isTrue();
        reset();
        Owner cached = em.find(Owner.class, 1L);
        assertThat(statements).isEmpty();
        assertThat(util(factory).isLoaded(cached, "children")).isFalse();
        assertThat(cached.nicknames).containsExactly("one", "two");
        assertThat(cached.children).extracting(c -> c.id).containsExactly(10L, 11L);
        assertThat(statements).filteredOn(s -> s.contains("lazy_nicknames")).hasSize(1);
        em.clear();
        try (EntityManager loader = factory.createEntityManager()) {
            Owner loaded = loader.find(Owner.class, 1L);
            loaded.nicknames.size();
            transaction(() -> em.merge(loaded));
        }
        reset();
        try (EntityManager reader = factory.createEntityManager()) {
            Owner again = reader.find(Owner.class, 1L);
            assertThat(again.nicknames).containsExactly("one", "two");
            assertThat(again.children).hasSize(2);
        }
    }

    @Test
    void tablePerClassSubclassesLoadInheritedCollectionsLazily() {
        open(Asset.class, Book.class);
        Book book = new Book();
        book.id = 1;
        book.tags.add("paper");
        transaction(() -> em.persist(book));
        em.clear();
        Asset loaded = em.find(Asset.class, 1L);
        assertThat(util(factory).isLoaded(loaded, "tags")).isFalse();
        assertThat(loaded.tags).containsExactly("paper");
    }

    @Entity(name = "LazyOwner")
    @Cacheable
    public static class Owner implements Serializable {
        @Id public long id;
        public String name;
        @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("id")
        public List<Child> children = new ArrayList<>();
        @ManyToMany @JoinTable(name = "LAZY_OWNER_TAGS")
        public Set<Tag> tags = new HashSet<>();
        @ManyToMany @JoinTable(name = "LAZY_OWNER_SORTED")
        public SortedSet<Tag> sorted = new TreeSet<>();
        @ElementCollection @CollectionTable(name = "LAZY_NICKNAMES") @OrderColumn(name = "position")
        public List<String> nicknames = new ArrayList<>();
        @ElementCollection @CollectionTable(name = "LAZY_LABELS") @MapKeyColumn(name = "label_key")
        public Map<String, String> labels = new HashMap<>();
        @ElementCollection @CollectionTable(name = "LAZY_SCORES") @MapKeyColumn(name = "score_key")
        public SortedMap<String, Integer> scores = new TreeMap<>();
        public Owner() {}
        Owner(long id, String name) { this.id = id; this.name = name; }
    }

    @Entity(name = "LazyChild")
    public static class Child implements Serializable {
        @Id public long id;
        @ManyToOne public Owner owner;
        @ElementCollection @CollectionTable(name = "LAZY_TOYS")
        public List<String> toys = new ArrayList<>();
        public Child() {}
        Child(long id, Owner owner) { this.id = id; this.owner = owner; }
    }

    @Entity(name = "LazyTag")
    public static class Tag implements Serializable, Comparable<Tag> {
        @Id public long id;
        public String name;
        public Tag() {}
        Tag(long id, String name) { this.id = id; this.name = name; }
        @Override public int compareTo(Tag other) { return name.compareTo(other.name); }
        @Override public boolean equals(Object other) { return other instanceof Tag t && t.id == id; }
        @Override public int hashCode() { return Long.hashCode(id); }
    }

    @Entity(name = "EagerHolder")
    public static class Eager {
        @Id public long id;
        @ManyToMany(fetch = FetchType.EAGER) @JoinTable(name = "EAGER_TAGS")
        public List<Tag> tags = new ArrayList<>();
        @ElementCollection @CollectionTable(name = "EAGER_WORDS")
        public ArrayList<String> words = new ArrayList<>();
        public Eager() {}
        Eager(long id) { this.id = id; }
    }

    public static class XmlFetch {
        public long id;
        public List<String> eager = new ArrayList<>();
        public List<String> lazy = new ArrayList<>();
        public XmlFetch() {}
    }

    @Entity(name = "LazyAsset") @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public abstract static class Asset {
        @Id public long id;
        @ElementCollection @CollectionTable(name = "LAZY_ASSET_TAGS")
        public List<String> tags = new ArrayList<>();
        protected Asset() {}
    }

    @Entity(name = "LazyBook")
    public static class Book extends Asset {
        public Book() {}
    }
}
