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
import org.junit.jupiter.api.*;

/**
 * §3.7.4 and §4.4.5.3: one graph-requested root collection and root/element to-one branches are read from
 * the same SQL rows, with complete collections, one managed instance per identity and staged callbacks (D17/D18).
 */
class CollectionSubgraphTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String FETCH = "jakarta.persistence.fetchgraph";
    private static final String LOAD = "jakarta.persistence.loadgraph";
    private final List<String> sql = new ArrayList<>();
    private int connections;
    private EntityManagerFactory factory;
    private EntityManager em;

    @BeforeEach
    void open() {
        open(16);
    }

    private void open(int batchSize) {
        var source = new CountingDataSource("jdbc:h2:mem:collection-subgraph-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1") {
            @Override public Connection getConnection() throws SQLException {
                connections++;
                Connection connection = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("prepare") && args[0] instanceof String statement) sql.add(statement);
                        try { return method.invoke(connection, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            }
        };
        factory = new PersistenceConfiguration("collection-subgraph")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Order.class).managedClass(Line.class).managedClass(Product.class).managedClass(Supplier.class)
            .managedClass(Category.class).managedClass(Customer.class).managedClass(Item.class).managedClass(SpecialItem.class)
            .managedClass(Payment.class)
            .sharedCacheMode(SharedCacheMode.ALL)
            .property("io.vidocq.noether.jpa.fetch.batch-size", batchSize)
            .property("jakarta.persistence.nonJtaDataSource", source)
            .property("jakarta.persistence.schema-generation.database.action", "create").createEntityManagerFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        Category tools = new Category(400, "tools");
        Supplier acme = new Supplier(300, "acme"), globex = new Supplier(301, "globex");
        Product hammer = new Product(200, "hammer", acme, tools), saw = new Product(201, "saw", globex, tools);
        Product drill = new Product(202, "drill", null, tools);
        hammer.getTags().addAll(List.of("steel", "wood"));
        Customer alice = new Customer(500, "alice");
        alice.setFavorite(hammer);
        alice.getRecommendations().addAll(List.of(hammer, hammer, saw));
        hammer.getFacts().put("material", "steel");
        Item plain = new Item(600, "plain");
        SpecialItem special = new SpecialItem(601, "special", "gold");
        Order first = new Order(1, "first", alice), empty = new Order(2, "empty", null), third = new Order(3, "third", null);
        first.setFeatured(hammer);
        third.setFeatured(drill);
        // items stay unset here: a lazy to-one of a polymorphic hierarchy is resolved eagerly (D10), see the fallback test
        Line l10 = new Line(10, 1, first, hammer, null), l11 = new Line(11, 2, first, saw, null);
        Line l12 = new Line(12, 3, first, null, null), l13 = new Line(13, 4, first, hammer, null);
        Line l20 = new Line(20, 5, third, hammer, null), l21 = new Line(21, 6, third, drill, null);
        first.getLines().addAll(List.of(l11, l10, l13, l12));
        third.getLines().addAll(List.of(l20, l21));
        first.getPicks().addAll(List.of(l10, l10, l11));
        third.getPicks().add(l20);
        first.getKeyed().put("b", l12);
        first.getKeyed().put("a", l10);
        l10.getDiscounts().addAll(List.of(hammer, hammer, saw));
        l20.getDiscounts().add(hammer);
        l10.getNotes().addAll(Arrays.asList("first", null, "first"));
        l10.getCodes().put("a", "one");
        l10.getCodes().put("b", "two");
        var payment = new Payment(700, first, hammer);
        var noTarget = new Payment(701, first, null);
        first.getPayments().addAll(List.of(noTarget, payment));
        em.persist(payment);
        em.persist(noTarget);
        for (Object entity : List.of(tools, acme, globex, hammer, saw, drill, alice, plain, special, first, empty, third,
                l10, l11, l12, l13, l20, l21)) em.persist(entity);
        em.getTransaction().commit();
        cold();
    }

    @AfterEach void close() {
        Order.inspectRootBranch = Order.inspectRecommendations = false;
        Order.inspectNested = Line.inspectNested = false;
        Order.requireComplete = Payment.inspect = Order.inspectAll = Order.inspect = Order.inspectMixed = Line.inspectAll = Line.inspect = Product.fail = Product.mutate = false;
        if (factory != null) factory.close();
    }

    private long selects() { return sql.stream().filter(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")).count(); }
    private boolean loaded(Object entity, String attribute) { return factory.getPersistenceUnitUtil().isLoaded(entity, attribute); }
    private boolean loaded(Object entity) { return factory.getPersistenceUnitUtil().isLoaded(entity); }

    private void cold() {
        em.clear();
        factory.getCache().evictAll();
        sql.clear();
        for (AtomicInteger counter : List.of(Order.LOADS, Line.LOADS, Product.LOADS, Supplier.LOADS, Category.LOADS, Customer.LOADS)) counter.set(0);
    }

    /** {@code collection{product{nested...}}}. */
    private EntityGraph<Order> graph(String collection, String... productNodes) {
        var graph = em.createEntityGraph(Order.class);
        Subgraph<Line> lines = graph.addSubgraph(collection);
        if (productNodes.length > 0) lines.addSubgraph("product").addAttributeNodes(productNodes);
        else lines.addAttributeNodes("product");
        return graph;
    }

    private List<Order> orders(String hint, EntityGraph<?> graph) {
        return em.createQuery("select o from CsOrder o order by o.id", Order.class).setHint(hint, graph).getResultList();
    }

    private EntityGraph<Order> mixedGraph(String collection) {
        var graph = graph(collection, "supplier", "category");
        graph.addSubgraph("customer").addSubgraph("favorite").addAttributeNodes("supplier", "category");
        graph.addSubgraph("featured").addAttributeNodes("supplier", "category");
        return graph;
    }

    private EntityGraph<Order> multipleGraph() {
        var graph = mixedGraph("lines");
        graph.addSubgraph("picks").addSubgraph("product").addAttributeNodes("supplier", "category");
        graph.addSubgraph("keyed").addSubgraph("product").addAttributeNodes("supplier", "category");
        return graph;
    }

    private EntityGraph<Order> nestedGraph(String collection) {
        var graph = mixedGraph(collection);
        Subgraph<Line> lines = graph.addSubgraph(collection);
        lines.addSubgraph("discounts").addAttributeNodes("supplier", "category");
        lines.addAttributeNodes("notes", "codes");
        return graph;
    }

    private EntityGraph<Order> rootBranchGraph() {
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addSubgraph("favorite").addAttributeNodes("tags", "facts", "supplier");
        graph.addSubgraph("featured").addAttributeNodes("tags", "facts", "supplier");
        return graph;
    }

    /** §3.7.4: root to-one paths share distinct collection owners, before any lifecycle callback. */
    @Test void rootToOneCollectionsUseGroupedReadsForJpqlCriteriaAndFind() {
        for (String hint : List.of(FETCH, LOAD)) {
            for (String operation : List.of("jpql", "criteria", "find")) {
                cold();
                Order.inspectRootBranch = true;
                List<Order> found;
                if (operation.equals("find")) found = List.of(em.find(Order.class, 1L, Map.of(hint, rootBranchGraph())));
                else if (operation.equals("criteria")) {
                    var cb = em.getCriteriaBuilder();
                    var query = cb.createQuery(Order.class);
                    var root = query.from(Order.class);
                    query.select(root).distinct(true).orderBy(cb.asc(root.get("id")));
                    found = em.createQuery(query).setHint(hint, rootBranchGraph()).getResultList();
                } else found = orders(hint, rootBranchGraph());
                assertThat(selects()).as(operation + "/" + hint + sql).isEqualTo(operation.equals("find") ? 3 : 4);
                Order first = found.getFirst();
                assertThat(first.getCustomer().getFavorite()).isSameAs(first.getFeatured());
                assertThat(first.getFeatured().getTags()).containsExactlyInAnyOrder("steel", "wood");
                assertThat(first.getFeatured().getFacts()).containsOnlyKeys("material").containsEntry("material", "steel");
                assertThat(first.rootBranchSeen).isEqualTo("2:1");
                assertThat(Order.LOADS.get()).isEqualTo(found.size());
                assertThat(Product.LOADS.get()).isEqualTo(operation.equals("find") ? 1 : 2);
                assertThat(Customer.LOADS.get()).isEqualTo(1);
                if (!operation.equals("find")) {
                    assertThat(found.get(1).getCustomer()).isNull();
                    assertThat(found.get(1).getFeatured()).isNull();
                    assertThat(found.getLast().getFeatured().getTags()).isEmpty();
                }
                assertThat(selects()).isEqualTo(operation.equals("find") ? 3 : 4);
            }
        }
    }

    @Test void directRootToOneEntityCollectionsKeepEmptyOwnersBagsAndElementToOneSubgraphs() {
        em.getTransaction().begin();
        Customer empty = new Customer(501, "empty-customer");
        em.persist(empty);
        em.find(Order.class, 3L).setCustomer(empty);
        em.getTransaction().commit();
        for (String hint : List.of(FETCH, LOAD)) {
            for (boolean find : List.of(false, true)) {
                cold();
                var graph = em.createEntityGraph(Order.class);
                graph.addSubgraph("customer").addSubgraph("recommendations").addAttributeNodes("supplier", "category");
                var found = find ? List.of(em.find(Order.class, 1L, Map.of(hint, graph))) : orders(hint, graph);
                Collection<Product> recommendations = found.getFirst().getCustomer().getRecommendations();
                assertThat(recommendations).extracting(Product::getId).containsExactlyInAnyOrder(200L, 200L, 201L);
                assertThat(recommendations).filteredOn(p -> p.getId() == 200)
                    .allSatisfy(p -> assertThat(p).isSameAs(found.getFirst().getCustomer().getFavorite()));
                assertThat(recommendations).extracting(p -> p.getSupplier().getName())
                    .containsExactlyInAnyOrder("acme", "acme", "globex");
                if (!find) {
                    assertThat(found.get(1).getCustomer()).isNull();
                    assertThat(found.getLast().getCustomer().getRecommendations()).isEmpty();
                    assertThat(found.getLast().getCustomer().getFavorite()).isNull();
                }
                assertThat(selects()).as(sql.toString()).isEqualTo(find ? 2 : 3);
            }
        }
    }

    @Test void rootToOneSharedOwnersScaleByDistinctTargetBatchesAndSqlRootPages() {
        em.getTransaction().begin();
        Customer shared = em.find(Customer.class, 500L);
        for (int i = 4; i <= 40; i++) em.persist(new Order(i, "shared", shared));
        em.getTransaction().commit();
        cold();
        var found = orders(FETCH, rootBranchGraph());
        assertThat(found).hasSize(40);
        assertThat(found.subList(3, 40)).allSatisfy(o -> {
            assertThat(o.getCustomer()).isSameAs(found.getFirst().getCustomer());
            assertThat(o.getCustomer().getFavorite().getTags()).containsExactlyInAnyOrder("steel", "wood");
        });
        assertThat(selects()).as(sql.toString()).isEqualTo(6); // root IDs + three seed batches + two product collections
        assertThat(sql.stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("join cs_product_tag"))).hasSize(1);
        cold();
        var page = em.createQuery("select o from CsOrder o order by o.id", Order.class)
            .setHint(LOAD, rootBranchGraph()).setFirstResult(1).setMaxResults(3).getResultList();
        assertThat(page).extracting(Order::getId).containsExactly(2L, 3L, 4L);
        assertThat(page.getFirst().getCustomer()).isNull();
        assertThat(page.get(1).getFeatured().getTags()).isEmpty();
        assertThat(page.getLast().getCustomer().getFavorite().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(selects()).isEqualTo(4);
        assertThat(sql.getFirst()).containsIgnoringCase("offset").containsIgnoringCase("fetch first")
            .doesNotContainIgnoringCase("join");
        cold();
        assertThat(em.find(Order.class, 999L, Map.of(FETCH, rootBranchGraph()))).isNull();
        assertThat(selects()).isEqualTo(1);
        cold();
        assertThat(em.find(Order.class, 2L, Map.of(FETCH, rootBranchGraph())).getCustomer()).isNull();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void rootToOneTargetBatchCrossingAndDisabledBatchingKeepCompleteCollections() {
        for (int batch : List.of(16, 1)) {
            if (batch == 1) { factory.close(); open(1); }
            em.getTransaction().begin();
            for (int i = 4; i <= 37; i++) {
                Product product = new Product(1000 + i, "p" + i, null, em.find(Category.class, 400L));
                product.getTags().add("tag" + i);
                Order order = new Order(i, "o" + i, null);
                order.setFeatured(product);
                em.persist(product);
                em.persist(order);
            }
            em.getTransaction().commit();
            cold();
            var graph = em.createEntityGraph(Order.class);
            graph.addSubgraph("featured").addAttributeNodes("tags");
            var found = orders(FETCH, graph);
            assertThat(found).hasSize(37);
            assertThat(found.getLast().getFeatured().getTags()).containsExactly("tag37");
            assertThat(selects()).as("batch=" + batch + sql).isEqualTo(batch == 16 ? 7 : 74);
        }
    }

    @Test void rootToOneCollectionsPreserveDirtyStateReferencesWrappersAndFindFastHits() {
        Order first = em.find(Order.class, 1L);
        Product reference = first.getFeatured();
        assertThat(loaded(reference)).isFalse();
        Product product = em.find(Product.class, 200L);
        List<String> tags = product.getTags();
        assertThat(loaded(product, "tags")).isFalse();
        em.getTransaction().begin();
        first.setLabel("dirty-order");
        product.setName("dirty-product");
        sql.clear();
        assertThat(em.find(Order.class, 1L, Map.of(FETCH, rootBranchGraph()))).isSameAs(first);
        assertThat(first.getFeatured()).isSameAs(reference).isSameAs(product);
        assertThat(product.getName()).isEqualTo("dirty-product");
        assertThat(product.getTags()).isSameAs(tags).containsExactlyInAnyOrder("steel", "wood");
        assertThat(selects()).isEqualTo(3);
        assertThat(sql).allSatisfy(s -> assertThat(s).startsWithIgnoringCase("select"));
        tags.add("dirty-tag");
        product.getFacts().put("dirty", "yes");
        sql.clear();
        int acquired = connections;
        assertThat(em.find(Order.class, 1L, Map.of(LOAD, rootBranchGraph()))).isSameAs(first);
        assertThat(selects()).isZero();
        assertThat(connections).isEqualTo(acquired);
        em.getTransaction().commit();
        cold();
        first = em.find(Order.class, 1L, Map.of(LOAD, rootBranchGraph()));
        assertThat(first.getLabel()).isEqualTo("dirty-order");
        assertThat(first.getFeatured().getName()).isEqualTo("dirty-product");
        assertThat(first.getFeatured().getTags()).containsExactlyInAnyOrder("steel", "wood", "dirty-tag");
        assertThat(first.getFeatured().getFacts()).containsEntry("dirty", "yes");
    }

    @Test void rootToOneCollectionsCompletePartialCacheBeforeCallbacksAndReuseCompleteCache() {
        em.find(Order.class, 1L);
        em.clear();
        sql.clear();
        Order.inspectRootBranch = true;
        Order.LOADS.set(0);
        Order first = em.find(Order.class, 1L, Map.of(LOAD, rootBranchGraph()));
        assertThat(first.rootBranchSeen).isEqualTo("2:1");
        assertThat(Order.LOADS.get()).isEqualTo(1);
        assertThat(selects()).isEqualTo(3);
        em.clear();
        sql.clear();
        int acquired = connections;
        first = em.find(Order.class, 1L, Map.of(FETCH, rootBranchGraph()));
        assertThat(first.rootBranchSeen).isEqualTo("2:1");
        assertThat(selects()).isZero();
        assertThat(connections).isEqualTo(acquired);
    }

    @Test void rootToOneCompletionKeepsAlreadyDirtyCollectionsAndReassignedManagedBranches() {
        Order first = em.find(Order.class, 1L);
        Product favorite = first.getCustomer().getFavorite();
        List<String> tags = favorite.getTags();
        tags.add("local");
        assertThat(loaded(favorite, "facts")).isFalse();
        Product changed = em.find(Product.class, 201L);
        em.getTransaction().begin();
        first.setFeatured(changed);
        sql.clear();
        Order found = em.find(Order.class, 1L, Map.of(FETCH, rootBranchGraph()));
        assertThat(found).isSameAs(first);
        assertThat(found.getFeatured()).isSameAs(changed);
        assertThat(found.getCustomer().getFavorite()).isSameAs(favorite);
        assertThat(favorite.getTags()).isSameAs(tags).containsExactlyInAnyOrder("steel", "wood", "local");
        assertThat(favorite.getFacts()).containsEntry("material", "steel");
        assertThat(changed.getTags()).isEmpty();
        assertThat(changed.getFacts()).isEmpty();
        assertThat(sql).allSatisfy(s -> assertThat(s).startsWithIgnoringCase("select"));
        em.getTransaction().commit();
        cold();
        found = em.find(Order.class, 1L, Map.of(LOAD, rootBranchGraph()));
        assertThat(found.getFeatured().getId()).isEqualTo(201);
        assertThat(found.getCustomer().getFavorite().getTags()).containsExactlyInAnyOrder("steel", "wood", "local");
    }

    @Test void rootToOneCollectionsKeepTwoEdgesAndIndependentRootBranches() {
        var graph = rootBranchGraph();
        graph.addSubgraph("customer").addSubgraph("recommendations").addAttributeNodes("tags", "facts", "supplier");
        graph.addSubgraph("lines").addSubgraph("discounts").addAttributeNodes("supplier", "category");
        Order.inspectRootBranch = Order.inspectRecommendations = true;
        var found = orders(LOAD, graph);
        assertThat(found.getFirst().getCustomer().getRecommendations()).extracting(Product::getId)
            .containsExactlyInAnyOrder(200L, 200L, 201L);
        assertThat(found.getFirst().getCustomer().getRecommendations()).filteredOn(p -> p.getId() == 200)
            .allSatisfy(p -> assertThat(p).isSameAs(found.getFirst().getFeatured()));
        assertThat(found.getFirst().getLines().get(1).getDiscounts()).hasSize(3);
        assertThat(found.getFirst().rootBranchSeen).isEqualTo("2:1");
        assertThat(selects()).as(sql.toString()).isEqualTo(7);
    }

    @Test void rootToOnePlansRebuildMutableGraphsAndKeepCallbackChangesAndFailures() {
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("featured").addAttributeNodes("supplier", "category");
        var query = em.createQuery("select o from CsOrder o order by o.id", Order.class).setHint(FETCH, graph);
        assertThat(query.getResultList().getFirst().getFeatured().getName()).isEqualTo("hammer");
        assertThat(selects()).isEqualTo(1);
        cold();
        graph.addSubgraph("featured").addAttributeNodes("tags", "facts");
        em.getTransaction().begin();
        Product.mutate = true;
        var first = query.getResultList().getFirst();
        assertThat(first.getFeatured().getName()).isEqualTo("hammer!");
        assertThat(first.getFeatured().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(Product.LOADS.get()).isEqualTo(2);
        assertThat(selects()).isEqualTo(4);
        Product.mutate = false;
        em.getTransaction().commit();
        cold();
        assertThat(em.find(Product.class, 200L).getName()).isEqualTo("hammer!");
        cold();
        var replacement = rootBranchGraph();
        replacement.addSubgraph("customer").addSubgraph("recommendations").addAttributeNodes("tags");
        em.getTransaction().begin();
        Product.fail = true;
        assertThatThrownBy(() -> query.setHint(FETCH, replacement).getResultList())
            .hasStackTraceContaining("product callback failed");
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }

    @Test void rootToOneThirdCollectionEdgesPolymorphicAndComplexShapesKeepCompleteFallbacks() {
        var graph = rootBranchGraph();
        graph.addSubgraph("lines").addSubgraph("discounts").addAttributeNodes("tags");
        var first = em.find(Order.class, 1L, Map.of(LOAD, graph));
        assertThat(first.getCustomer().getFavorite().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(first.getLines().get(1).getDiscounts()).filteredOn(p -> p.getId() == 200)
            .allSatisfy(p -> assertThat(p.getTags()).containsExactlyInAnyOrder("steel", "wood"));
        assertThat(sql.getFirst()).doesNotContainIgnoringCase("CS_LINE");
        cold();
        var cyclic = rootBranchGraph();
        cyclic.addSubgraph("lines").addSubgraph("order").addSubgraph("lines")
            .addSubgraph("order").addAttributeNodes("lines");
        first = em.find(Order.class, 1L, Map.of(FETCH, cyclic));
        final Order identity = first;
        assertThat(first.getLines()).hasSize(4).allSatisfy(l -> assertThat(l.getOrder()).isSameAs(identity));
        cold();
        var polymorphic = rootBranchGraph();
        polymorphic.addSubgraph("lines").addSubgraph("item").addAttributeNodes("name");
        first = em.find(Order.class, 1L, Map.of(FETCH, polymorphic));
        assertThat(first.getCustomer().getFavorite().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(first.getLines()).hasSize(4);
        cold();
        first = em.createQuery("select o from CsOrder o left join o.lines l where o.id = 1", Order.class)
            .setHint(FETCH, rootBranchGraph()).getResultList().getFirst();
        assertThat(first.getFeatured().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(first.getFeatured().getFacts()).containsEntry("material", "steel");
    }

    @Test void rootToOneCyclicCollectionOwnerPathsKeepCompleteFallbackRatherThanRescheduleAncestors() {
        var graph = rootBranchGraph();
        graph.addSubgraph("lines").addSubgraph("order").addSubgraph("customer")
            .addSubgraph("favorite").addAttributeNodes("tags", "facts");
        Order first = em.find(Order.class, 1L, Map.of(LOAD, graph));
        assertThat(first.getLines()).hasSize(4).allSatisfy(line -> {
            assertThat(line.getOrder()).isSameAs(first);
            assertThat(line.getOrder().getCustomer().getFavorite().getTags()).containsExactlyInAnyOrder("steel", "wood");
        });
        assertThat(sql).noneMatch(s -> s.toLowerCase(Locale.ROOT).contains("join cs_line"));
    }

    /** §3.7.4: each collection edge is a separate complete, bounded read, never a multiplying join. */
    @Test void nestedCollectionsUseGroupedReadsForQueriesCriteriaAndFindBeforeCallbacks() {
        for (String hint : List.of(FETCH, LOAD)) {
            for (String operation : List.of("jpql", "criteria", "find")) {
                cold();
                Order.inspectNested = Line.inspectNested = true;
                List<Order> found;
                if (operation.equals("find")) found = List.of(em.find(Order.class, 1L, Map.of(hint, nestedGraph("lines"))));
                else if (operation.equals("criteria")) {
                    var cb = em.getCriteriaBuilder();
                    var criteria = cb.createQuery(Order.class);
                    var root = criteria.from(Order.class);
                    criteria.select(root).distinct(true).orderBy(cb.asc(root.get("id")));
                    found = em.createQuery(criteria).setHint(hint, nestedGraph("lines")).getResultList();
                } else found = orders(hint, nestedGraph("lines"));
                assertThat(selects()).as(operation + "/" + hint + sql).isEqualTo(operation.equals("find") ? 4 : 5);
                assertMixed(found.getFirst());
                Line line = found.getFirst().getLines().get(1);
                assertThat(line.getDiscounts()).extracting(Product::getId).containsExactlyInAnyOrder(200L, 200L, 201L);
                assertThat(line.getDiscounts()).filteredOn(p -> p.getId() == 200).allSatisfy(p -> assertThat(p).isSameAs(line.getProduct()));
                assertThat(line.getNotes()).containsExactly("first", null, "first");
                assertThat(line.getCodes()).containsEntry("a", "one").containsEntry("b", "two").hasSize(2);
                assertThat(found.getFirst().nestedSeen).isEqualTo(3);
                assertThat(line.nestedSeen).isEqualTo("3:3:2");
                assertThat(found.getFirst().getLines().getFirst().getDiscounts()).isEmpty();
                assertThat(Order.LOADS.get()).isEqualTo(found.size());
                assertThat(Line.LOADS.get()).isEqualTo(found.size() == 1 ? 4 : 6);
                assertThat(selects()).isEqualTo(operation.equals("find") ? 4 : 5);
            }
        }
    }

    @Test void nestedCollectionsPreserveBagsMapsSharedOwnersAndRootOnlyPagination() {
        for (String collection : List.of("picks", "keyed")) {
            cold();
            var found = orders(LOAD, nestedGraph(collection));
            Collection<Line> lines = collection.equals("picks") ? found.getFirst().getPicks() : found.getFirst().getKeyed().values();
            Line line = lines.stream().filter(l -> l.getId() == 10).findFirst().orElseThrow();
            assertThat(line.getDiscounts()).hasSize(3);
            assertThat(line.getNotes()).containsExactly("first", null, "first");
            if (collection.equals("picks")) assertThat(lines.stream().filter(l -> l == line)).hasSize(2);
            else assertThat(found.getFirst().getKeyed()).containsOnlyKeys("a", "b");
            assertThat(selects()).isEqualTo(5);
        }
        cold();
        var graph = nestedGraph("lines");
        graph.addSubgraph("picks").addSubgraph("discounts").addAttributeNodes("supplier", "category");
        var found = orders(LOAD, graph);
        assertThat(found.getFirst().getPicks()).hasSize(3);
        assertThat(found.getFirst().getPicks()).filteredOn(l -> l.getId() == 10)
            .allSatisfy(l -> assertThat(l).isSameAs(found.getFirst().getLines().get(1)));
        assertThat(found.getFirst().getLines().get(1).getDiscounts()).hasSize(3);
        assertThat(selects()).isEqualTo(6);
        cold();
        var page = em.createQuery("select o from CsOrder o order by o.id", Order.class)
            .setHint(LOAD, nestedGraph("lines")).setFirstResult(1).setMaxResults(2).getResultList();
        assertThat(page).extracting(Order::getId).containsExactly(2L, 3L);
        assertThat(page.getFirst().getLines()).isEmpty();
        assertThat(page.getLast().getLines()).hasSize(2);
        assertThat(page.getLast().getLines().getFirst().getDiscounts()).extracting(Product::getId).containsExactly(200L);
        assertThat(selects()).isEqualTo(5);
        assertThat(sql.getFirst()).containsIgnoringCase("offset").containsIgnoringCase("fetch first").doesNotContainIgnoringCase("join");
        cold();
        assertThat(em.find(Order.class, 999L, Map.of(FETCH, nestedGraph("lines")))).isNull();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void nestedOwnerCountsAreIndependentlyBoundedAndSettingOneDisablesGrouping() {
        for (int batch : List.of(16, 1)) {
            if (batch == 1) { factory.close(); open(1); }
            em.getTransaction().begin();
            Order first = em.find(Order.class, 1L);
            for (int i = 0; i < 17; i++) {
                var line = new Line(100 + i, i, first, null, null);
                line.getNotes().add("extra");
                first.getLines().add(line);
                em.persist(line);
            }
            em.getTransaction().commit();
            cold();
            var graph = em.createEntityGraph(Order.class);
            graph.addSubgraph("lines").addAttributeNodes("notes");
            var found = orders(FETCH, graph);
            assertThat(found.getFirst().getLines()).hasSize(21);
            assertThat(found.getFirst().getLines().getLast().getNotes()).containsExactly("extra");
            assertThat(selects()).as("batch " + batch + sql).isEqualTo(batch == 16 ? 4 : 27);
        }
    }

    @Test void nestedFindCompletesExistingWrappersAndReferencesWithoutOverwritingDirtyStateOrFlushing() {
        Order first = em.find(Order.class, 1L);
        List<Line> wrapper = first.getLines();
        assertThat(loaded(first, "lines")).isFalse();
        Line known = em.find(Line.class, 10L);
        Collection<Product> discounts = known.getDiscounts();
        List<String> notes = known.getNotes();
        known.setQuantity(99);
        Product reference = known.getProduct();
        assertThat(loaded(reference)).isFalse();
        em.getTransaction().begin();
        first.setLabel("dirty");
        sql.clear();
        Order found = em.find(Order.class, 1L, Map.of(FETCH, nestedGraph("lines")));
        assertThat(found).isSameAs(first);
        assertThat(found.getLines()).isSameAs(wrapper);
        assertThat(found.getLines().get(1)).isSameAs(known);
        assertThat(known.getDiscounts()).isSameAs(discounts).hasSize(3);
        assertThat(known.getNotes()).isSameAs(notes).containsExactly("first", null, "first");
        assertThat(known.getProduct()).isSameAs(reference);
        assertThat(reference.getName()).isEqualTo("hammer");
        assertThat(known.getQuantity()).isEqualTo(99);
        assertThat(first.getLabel()).isEqualTo("dirty");
        assertThat(selects()).isEqualTo(4);
        assertThat(sql).allSatisfy(s -> assertThat(s).startsWithIgnoringCase("select"));
        notes.add("dirty-note");
        discounts.remove(discounts.iterator().next());
        reference.setName("dirty-product");
        sql.clear();
        int acquired = connections;
        assertThat(em.find(Order.class, 1L, Map.of(LOAD, nestedGraph("lines")))).isSameAs(first);
        assertThat(connections).isEqualTo(acquired);
        assertThat(selects()).isZero();
        assertThat(notes).containsExactly("first", null, "first", "dirty-note");
        assertThat(discounts).hasSize(2);
        assertThat(reference.getName()).isEqualTo("dirty-product");
        em.getTransaction().commit();
        cold();
        first = em.find(Order.class, 1L, Map.of(LOAD, nestedGraph("lines")));
        assertThat(first.getLabel()).isEqualTo("dirty");
        assertThat(first.getLines().get(1).getQuantity()).isEqualTo(99);
        assertThat(first.getLines().get(1).getNotes()).containsExactly("first", null, "first", "dirty-note");
        assertThat(first.getLines().get(1).getDiscounts()).hasSize(2);
    }

    @Test void nestedFindCompletesPartiallyCachedGraphsBeforeCallbacksAndPreservesFastHits() {
        em.find(Order.class, 1L, Map.of(LOAD, mixedGraph("lines")));
        em.clear();
        sql.clear();
        Order.inspectNested = Line.inspectNested = true;
        Order.LOADS.set(0);
        var found = em.find(Order.class, 1L, Map.of(LOAD, nestedGraph("lines")));
        assertThat(found.nestedSeen).isEqualTo(3);
        assertThat(found.getLines().get(1).getDiscounts()).hasSize(3);
        assertThat(Order.LOADS.get()).isEqualTo(1);
        assertThat(selects()).isEqualTo(4);
        sql.clear();
        int acquired = connections;
        assertThat(em.find(Order.class, 1L, Map.of(FETCH, nestedGraph("lines")))).isSameAs(found);
        assertThat(connections).isEqualTo(acquired);
        assertThat(selects()).isZero();
        cold();
        em.find(Order.class, 1L, Map.of(LOAD, nestedGraph("lines")));
        em.clear();
        sql.clear();
        acquired = connections;
        found = em.find(Order.class, 1L, Map.of(LOAD, nestedGraph("lines")));
        assertThat(found.nestedSeen).isEqualTo(3);
        assertThat(selects()).isZero();
        assertThat(connections).isEqualTo(acquired);
    }

    @Test void nestedPlansAreRebuiltAfterGraphMutationAndHintReplacementAndKeepCallbackMutationsAndFailures() {
        var graph = mixedGraph("lines");
        var query = em.createQuery("select o from CsOrder o order by o.id", Order.class).setHint(FETCH, graph);
        assertThat(query.getResultList().getFirst().getLines()).hasSize(4);
        assertThat(selects()).isEqualTo(1);
        cold();
        graph.addSubgraph("lines").addSubgraph("discounts").addAttributeNodes("supplier", "category");
        em.getTransaction().begin();
        Product.mutate = true;
        var found = query.getResultList().getFirst();
        assertThat(found.getLines().get(1).getDiscounts()).extracting(Product::getName)
            .containsExactlyInAnyOrder("hammer!", "hammer!", "saw!");
        assertThat(Product.LOADS.get()).isEqualTo(3);
        assertThat(selects()).isEqualTo(3);
        Product.mutate = false;
        em.getTransaction().commit();
        cold();
        assertThat(em.find(Product.class, 200L).getName()).isEqualTo("hammer!");
        cold();
        assertThat(query.setHint(FETCH, mixedGraph("lines")).getResultList().getFirst().getLines()).hasSize(4);
        assertThat(selects()).isEqualTo(1);
        cold();
        em.getTransaction().begin();
        Product.fail = true;
        assertThatThrownBy(() -> query.setHint(FETCH, nestedGraph("lines")).getResultList())
            .hasStackTraceContaining("product callback failed");
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
    }

    @Test void nestedCollectionsThroughToOneOwnersAreGroupedAndThirdEdgesAndCyclesKeepCompleteFallbacks() {
        var graph = graph("lines", "supplier", "category", "tags", "facts");
        var found = orders(LOAD, graph);
        assertThat(found.getFirst().getLines().get(1).getProduct().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(found.getLast().getLines().getFirst().getProduct())
            .isSameAs(found.getFirst().getLines().get(1).getProduct());
        assertThat(selects()).isEqualTo(4);
        cold();
        var deep = nestedGraph("lines");
        deep.addSubgraph("lines").addSubgraph("discounts").addAttributeNodes("tags");
        var first = em.find(Order.class, 1L, Map.of(LOAD, deep));
        assertThat(first.getLines().get(1).getDiscounts()).filteredOn(p -> p.getId() == 200)
            .allSatisfy(p -> assertThat(p.getTags()).containsExactlyInAnyOrder("steel", "wood"));
        assertThat(sql.getFirst()).doesNotContainIgnoringCase("CS_LINE");
        cold();
        var cyclic = em.createEntityGraph(Order.class);
        cyclic.addSubgraph("lines").addSubgraph("order").addSubgraph("lines")
            .addSubgraph("order").addAttributeNodes("lines");
        var cycleRoot = em.find(Order.class, 1L, Map.of(FETCH, cyclic));
        assertThat(cycleRoot.getLines()).hasSize(4).allSatisfy(l -> assertThat(l.getOrder()).isSameAs(cycleRoot));
        assertThat(sql.getFirst()).doesNotContainIgnoringCase("join");
    }

    @Test void multipleRootCollectionsUseSeparateGroupedReadsBeforeCallbacks() {
        for (String hint : List.of(FETCH, LOAD)) {
            for (String operation : List.of("jpql", "criteria", "find")) {
                cold();
                Order.inspectAll = Order.inspect = Order.inspectMixed = Line.inspect = true;
                List<Order> found;
                if (operation.equals("find")) {
                    found = List.of(em.find(Order.class, 1L, Map.of(hint, multipleGraph())));
                } else if (operation.equals("criteria")) {
                    var cb = em.getCriteriaBuilder();
                    var criteria = cb.createQuery(Order.class);
                    var root = criteria.from(Order.class);
                    criteria.select(root).distinct(true).orderBy(cb.asc(root.get("id")));
                    found = em.createQuery(criteria).setHint(hint, multipleGraph()).getResultList();
                } else found = orders(hint, multipleGraph());
                assertThat(selects()).as(operation + "/" + hint + sql).isEqualTo(operation.equals("find") ? 3 : 4);
                assertMixed(found.getFirst());
                assertThat(found.getFirst().getPicks()).extracting(Line::getId).containsExactlyInAnyOrder(10L, 10L, 11L);
                assertThat(found.getFirst().getKeyed()).containsOnlyKeys("a", "b");
                assertThat(found.getFirst().getKeyed().get("a")).isSameAs(found.getFirst().getLines().get(1));
                assertThat(found.getFirst().allSeen).isEqualTo("4:3:2");
                assertThat(Order.LOADS.get()).isEqualTo(found.size());
                assertThat(Line.LOADS.get()).isEqualTo(found.size() == 1 ? 4 : 6);
                if (found.size() == 3) {
                    assertThat(found.get(1).allSeen).isEqualTo("0:0:0");
                    assertThat(found.get(2).allSeen).isEqualTo("2:1:0");
                }
                assertThat(selects()).isEqualTo(operation.equals("find") ? 3 : 4);
            }
        }

    }

    @Test void orderLinesProductsAndIndependentPaymentsAreGroupedWithoutElementFinds() {
        for (String hint : List.of(FETCH, LOAD)) {
            for (boolean find : List.of(false, true)) {
                cold();
                Payment.inspect = true;
                var graph = mixedGraph("lines");
                graph.addSubgraph("payments").addSubgraph("product").addAttributeNodes("supplier", "category");
                var found = find ? List.of(em.find(Order.class, 1L, Map.of(hint, graph))) : orders(hint, graph);
                var first = found.getFirst();
                assertMixed(first);
                assertThat(first.getPayments()).extracting(Payment::getId).containsExactly(700L, 701L);
                assertThat(first.getPayments().getFirst().getProduct()).isSameAs(first.getFeatured());
                assertThat(first.getPayments().getLast().getProduct()).isNull();
                assertThat(first.getPayments()).allSatisfy(p -> {
                    assertThat(p.getOrder()).isSameAs(first);
                    assertThat(p.seen).isEqualTo("4:2");
                });
                if (!find) assertThat(found.get(1).getPayments()).isEmpty();
                assertThat(selects()).isEqualTo(find ? 2 : 3);
            }
        }
    }

    @Test void multipleRootCollectionsScaleByOwnerBatchesAndPageCompleteOwners() {
        em.getTransaction().begin();
        for (long id = 4; id <= 19; id++) em.persist(new Order(id, "extra", null));
        var last = em.find(Order.class, 19L);
        var shared = em.find(Product.class, 200L);
        var lateLine = new Line(30, 7, last, shared, null);
        last.getLines().add(lateLine);
        last.getPicks().addAll(List.of(lateLine, lateLine));
        last.getKeyed().put("late", lateLine);
        em.persist(lateLine);
        em.getTransaction().commit();
        cold();
        Order.inspectAll = true;
        var query = em.createQuery("select o from CsOrder o order by o.id", Order.class).setHint(LOAD, multipleGraph());
        var found = query.getResultList();
        assertThat(found).hasSize(19);
        assertThat(found.getFirst().allSeen).isEqualTo("4:3:2");
        assertThat(found.getLast().allSeen).isEqualTo("1:2:1");
        assertThat(found.getLast().getLines().getFirst().getProduct()).isSameAs(found.getFirst().getFeatured());
        var late = found.getLast().getLines().getFirst();
        assertThat(found.getLast().getPicks()).containsExactly(late, late);
        assertThat(found.getLast().getKeyed().get("late")).isSameAs(late);
        assertThat(selects()).isEqualTo(7); // owner identities + three collections in two batches
        cold();
        found = query.setFirstResult(1).setMaxResults(2).getResultList();
        assertThat(found).extracting(Order::getId).containsExactly(2L, 3L);
        assertThat(found.getFirst().getLines()).isEmpty();
        assertThat(describe(found.get(1).getLines())).containsExactly("20:hammer/acme", "21:drill/-");
        assertThat(selects()).isEqualTo(4);
        assertThat(sql.getFirst()).containsIgnoringCase("offset").containsIgnoringCase("fetch first")
            .doesNotContainIgnoringCase("join");
    }


    @Test void multipleRootCollectionsRespectDisabledBatchingAndDoNotReadMissingOwners() {
        factory.close();
        open(1);
        var found = orders(LOAD, multipleGraph());
        assertMixed(found.getFirst());
        assertThat(found).hasSize(3);
        assertThat(selects()).isEqualTo(10); // one identity query + three reads for each of three owners
        assertThat(sql.stream().filter(s -> s.contains(" IN (")).toList()).allSatisfy(s -> assertThat(s).contains("IN (?)"));
        cold();
        assertThat(em.find(Order.class, 999L, Map.of(FETCH, multipleGraph()))).isNull();
        assertThat(selects()).isEqualTo(3);
        cold();
        assertThat(em.createQuery("select o from CsOrder o where o.id = 999", Order.class)
            .setHint(FETCH, multipleGraph()).getResultList()).isEmpty();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void multipleRootCollectionsPreserveDirtyManagedStateWrappersAndLazyTargetsWithoutFindFlush() {
        var line = em.find(Line.class, 10L);
        var product = line.getProduct();
        var first = em.find(Order.class, 1L);
        var lines = first.getLines();
        var picks = first.getPicks();
        var keyed = first.getKeyed();
        line.setQuantity(99);
        first.setLabel("dirty");
        Line.inspectAll = true;
        em.getTransaction().begin();
        sql.clear();
        assertThat(em.find(Order.class, 1L, Map.of(LOAD, multipleGraph()))).isSameAs(first);
        assertThat(selects()).isEqualTo(3);
        assertThat(first.getLines()).isSameAs(lines);
        assertThat(first.getPicks()).isSameAs(picks);
        assertThat(first.getKeyed()).isSameAs(keyed);
        assertThat(lines.get(1)).isSameAs(line);
        assertThat(line.getQuantity()).isEqualTo(99);
        assertThat(first.getFeatured()).isSameAs(product);
        assertThat(first.getLabel()).isEqualTo("dirty");
        assertThat(lines.getFirst().allSeen).isEqualTo("4:3:2");
        picks.removeIf(l -> l.getId() == 11);
        var removed = keyed.remove("b");
        lines.removeFirst();
        sql.clear();
        assertThat(em.find(Order.class, 1L, Map.of(FETCH, multipleGraph()))).isSameAs(first);
        assertThat(selects()).isZero();
        assertThat(picks).hasSize(2);
        assertThat(keyed).containsOnlyKeys("a");
        assertThat(lines).hasSize(3);
        keyed.put("b", removed);
        assertThat(sql).noneMatch(s -> s.toLowerCase(Locale.ROOT).startsWith("update"));
        em.getTransaction().commit();
        Line.inspectAll = false;
        cold();
        assertThat(em.find(Line.class, 10L).getQuantity()).isEqualTo(99);
        var reloaded = em.find(Order.class, 1L, Map.of(LOAD, multipleGraph()));
        assertThat(reloaded.getLabel()).isEqualTo("dirty");
        assertThat(reloaded.getPicks()).hasSize(2);
    }

    @Test void multipleRootCallbacksMutateAfterAllBranchesAndFailuresRollbackWithoutHidingErrors() {
        Order.inspectAll = Line.inspectAll = true;
        Product.mutate = true;
        em.getTransaction().begin();
        var first = em.find(Order.class, 1L, Map.of(LOAD, multipleGraph()));
        assertThat(first.allSeen).isEqualTo("4:3:2");
        assertThat(first.getLines()).allSatisfy(l -> assertThat(l.allSeen).isEqualTo("4:3:2"));
        assertThat(first.getFeatured().getName()).isEqualTo("hammer!");
        assertThat(Product.LOADS.get()).isEqualTo(2);
        assertThat(selects()).isEqualTo(3);
        em.getTransaction().commit();
        Product.mutate = Order.inspectAll = Line.inspectAll = false;
        em.clear();
        sql.clear();
        assertThat(em.find(Order.class, 1L, Map.of(FETCH, multipleGraph())).getFeatured().getName()).isEqualTo("hammer!");
        assertThat(selects()).isZero();
        cold();
        Product.fail = true;
        em.getTransaction().begin();
        assertThatThrownBy(() -> orders(LOAD, multipleGraph())).hasStackTraceContaining("product callback failed");
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
        Product.fail = false;
        cold();
        assertThat(orders(LOAD, multipleGraph()).getFirst().getPicks()).hasSize(3);
        assertThat(selects()).isEqualTo(4);
    }

    @Test void partiallyCachedRootDefersCallbacksUntilEveryRequestedCollectionIsComplete() {
        var rootOnly = em.createEntityGraph(Order.class);
        rootOnly.addSubgraph("customer").addSubgraph("favorite").addAttributeNodes("supplier", "category");
        rootOnly.addSubgraph("featured").addAttributeNodes("supplier", "category");
        var first = em.find(Order.class, 1L, Map.of(FETCH, rootOnly));
        assertThat(loaded(first, "lines")).isFalse();
        assertThat(factory.getCache().contains(Order.class, 1L)).isTrue();
        for (boolean find : List.of(true, false)) {
            em.clear();
            sql.clear();
            Order.LOADS.set(0);
            Order.requireComplete = true;
            Order.inspectAll = Line.inspectAll = true;
            first = find ? em.find(Order.class, 1L, Map.of(LOAD, multipleGraph()))
                : em.createQuery("select o from CsOrder o where o.id = 1", Order.class)
                    .setHint(LOAD, multipleGraph()).getSingleResult();
            assertThat(first.allSeen).isEqualTo("4:3:2");
            assertThat(first.getLines()).allSatisfy(l -> assertThat(l.allSeen).isEqualTo("4:3:2"));
            assertThat(Order.LOADS.get()).isEqualTo(1);
            assertThat(selects()).isEqualTo(find ? 3 : 4);
        }
    }

    @Test void multipleRootBasicCollectionsPreserveNullElementsBagsAndMapKeys() {
        em.getTransaction().begin();
        var product = em.find(Product.class, 200L);
        product.getTags().addAll(Arrays.asList(null, "steel"));
        product.getFacts().put("material", "steel");
        product.getFacts().put("handle", "wood");
        em.getTransaction().commit();
        for (String hint : List.of(FETCH, LOAD)) {
            for (boolean find : List.of(false, true)) {
                cold();
                var graph = em.createEntityGraph(Product.class);
                graph.addAttributeNodes("tags", "facts", "supplier", "category");
                var products = find ? List.of(em.find(Product.class, 200L, Map.of(hint, graph)))
                    : em.createQuery("select p from CsProduct p order by p.id", Product.class).setHint(hint, graph).getResultList();
                assertThat(products.getFirst().getTags()).containsExactlyInAnyOrder("steel", "wood", null, "steel");
                assertThat(products.getFirst().getFacts()).containsEntry("material", "steel").containsEntry("handle", "wood").hasSize(2);
                assertThat(products.getFirst().getSupplier().getName()).isEqualTo("acme");
                if (!find) {
                    assertThat(products.get(1).getTags()).isEmpty();
                    assertThat(products.get(2).getFacts()).isEmpty();
                    assertThat(products.get(2).getSupplier()).isNull();
                    assertThat(products.get(2).getCategory()).isSameAs(products.getFirst().getCategory());
                }
                assertThat(selects()).isEqualTo(find ? 2 : 3);
            }
        }
    }

    @Test void mutableGraphsReplaceSingleWithSplitPlansAndNestedCollectionsUseCompleteSplitReads() {
        var graph = mixedGraph("lines");
        var query = em.createQuery("select o from CsOrder o order by o.id", Order.class).setHint(FETCH, graph);
        assertMixed(query.getResultList().getFirst());
        assertThat(selects()).isEqualTo(1);
        cold();
        graph.addSubgraph("picks").addSubgraph("product").addAttributeNodes("supplier", "category");
        assertMixed(query.getResultList().getFirst());
        assertThat(selects()).isEqualTo(3);
        cold();
        assertMixed(query.setHint(FETCH, multipleGraph()).getResultList().getFirst());
        assertThat(selects()).isEqualTo(4);
        cold();
        var nested = multipleGraph();
        nested.addSubgraph("lines").addSubgraph("product").addAttributeNodes("tags");
        var first = query.setHint(FETCH, nested).getResultList().getFirst();
        assertMixed(first);
        assertThat(first.getPicks()).hasSize(3);
        assertThat(first.getKeyed()).hasSize(2);
        assertThat(first.getLines().get(1).getProduct().getTags()).containsExactlyInAnyOrder("steel", "wood");
        assertThat(selects()).isGreaterThan(4);
        cold();
        assertMixed(query.setHint(FETCH, mixedGraph("lines")).getResultList().getFirst());
        assertThat(selects()).isEqualTo(1);
    }

    private void assertMixed(Order order) {
        assertFirstOrderGraph(order);
        assertThat(order.getCustomer().getName()).isEqualTo("alice");
        assertThat(order.getCustomer().getFavorite()).isSameAs(order.getFeatured())
            .isSameAs(order.getLines().get(1).getProduct());
        assertThat(order.getFeatured().getSupplier().getName()).isEqualTo("acme");
    }

    @Test void mixedJpqlCriteriaAndFindReadRootAndElementBranchesInOneSelect() {
        for (String hint : List.of(FETCH, LOAD)) {
            for (String operation : List.of("jpql", "criteria", "find")) {
                cold();
                Order.inspect = Order.inspectMixed = Line.inspect = true;
                var graph = mixedGraph("lines");
                List<Order> found;
                if (operation.equals("find")) {
                    found = List.of(em.find(Order.class, 1L, Map.of(hint, graph)));
                } else if (operation.equals("criteria")) {
                    var cb = em.getCriteriaBuilder();
                    var criteria = cb.createQuery(Order.class);
                    var root = criteria.from(Order.class);
                    criteria.select(root).distinct(true).orderBy(cb.asc(root.get("id")));
                    found = em.createQuery(criteria).setHint(hint, graph).getResultList();
                } else {
                    // The user alias deliberately overlaps the generated alias prefix.
                    found = em.createQuery("select _noether_graph_0 from CsOrder _noether_graph_0 order by _noether_graph_0.id",
                        Order.class).setHint(hint, graph).getResultList();
                }
                assertThat(selects()).as(operation + "/" + hint + sql).isEqualTo(1);
                assertMixed(found.getFirst());
                assertThat(found.getFirst().rootSeen).isEqualTo("alice:hammer:acme");
                assertThat(Order.LOADS.get()).isEqualTo(found.size());
                assertThat(Product.LOADS.get()).isEqualTo(found.size() == 1 ? 2 : 3);
                assertThat(Category.LOADS.get()).isEqualTo(1);
                assertThat(Supplier.LOADS.get()).isEqualTo(2);
                if (found.size() == 3) {
                    assertThat(found.get(1).getCustomer()).isNull();
                    assertThat(found.get(1).getFeatured()).isNull();
                    assertThat(found.get(1).getLines()).isEmpty();
                    assertThat(found.get(2).getCustomer()).isNull();
                    assertThat(found.get(2).getFeatured()).isSameAs(found.get(2).getLines().get(1).getProduct());
                    assertThat(found.get(2).getFeatured().getSupplier()).isNull();
                }
                assertThat(selects()).isEqualTo(1);
            }
        }
        cold();
        assertMixed(em.find(mixedGraph("lines"), 1L));
        assertThat(selects()).isEqualTo(1);
        cold();
        assertThat(em.find(Order.class, 999L, Map.of(FETCH, mixedGraph("lines")))).isNull();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void mixedBagsMapsAndDirectRootNodesKeepAllEntries() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            var bag = mixedGraph("picks");
            var found = orders(hint, bag);
            var picks = new ArrayList<>(found.getFirst().getPicks());
            assertThat(picks).extracting(Line::getId).containsExactlyInAnyOrder(10L, 10L, 11L);
            assertThat(picks.stream().filter(l -> l.getId() == 10).findFirst().orElseThrow().getProduct())
                .isSameAs(found.getFirst().getCustomer().getFavorite()).isSameAs(found.getFirst().getFeatured());
            assertThat(found.get(1).getPicks()).isEmpty();
            assertThat(selects()).isEqualTo(1);
            cold();
            var keyed = orders(hint, mixedGraph("keyed"));
            assertThat(keyed.getFirst().getKeyed()).containsOnlyKeys("a", "b");
            assertThat(keyed.getFirst().getKeyed().get("a").getProduct()).isSameAs(keyed.getFirst().getFeatured());
            assertThat(keyed.getFirst().getKeyed().get("b").getProduct()).isNull();
            assertThat(keyed.get(1).getKeyed()).isEmpty();
            assertThat(selects()).isEqualTo(1);
            cold();
            var direct = graph("lines", "supplier", "category");
            direct.addAttributeNodes("customer");
            assertThat(orders(hint, direct).getFirst().getCustomer().getName()).isEqualTo("alice");
            assertThat(selects()).isEqualTo(1);
        }
    }

    @Test void mixedLoadExpandsRootEagerBranchesAndFetchAllowsOverfetch() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            var graph = graph("lines", "supplier", "category");
            graph.addAttributeNodes("customer");
            var first = orders(hint, graph).getFirst();
            assertThat(first.getCustomer().getFavorite()).isSameAs(first.getLines().get(1).getProduct());
            assertThat(first.getCustomer().getFavorite().getCategory().getName()).isEqualTo("tools");
            assertThat(selects()).isEqualTo(1);
        }
    }

    @Test void mixedManagedDirtyStateLazyReferencesAndWrappersSurviveWithoutFindFlush() {
        var line = em.find(Line.class, 10L);
        var product = line.getProduct();
        assertThat(loaded(product)).isFalse();
        line.setQuantity(99);
        var customer = em.find(Customer.class, 500L);
        customer.setName("dirty-alice");
        var first = em.find(Order.class, 1L);
        var wrapper = first.getLines();
        assertThat(loaded(first, "lines")).isFalse();
        em.getTransaction().begin();
        sql.clear();
        var found = em.createQuery("select o from CsOrder o where o.id = 1", Order.class)
            .setFlushMode(FlushModeType.COMMIT).setHint(FETCH, mixedGraph("lines")).getSingleResult();
        assertThat(found).isSameAs(first);
        assertThat(found.getLines()).isSameAs(wrapper);
        assertThat(found.getLines().get(1)).isSameAs(line);
        assertThat(line.getQuantity()).isEqualTo(99);
        assertThat(found.getFeatured()).isSameAs(product);
        assertThat(found.getCustomer()).isSameAs(customer);
        assertThat(customer.getName()).isEqualTo("dirty-alice");
        assertThat(selects()).isEqualTo(1);
        assertThat(sql).noneMatch(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("update"));
        var removed = wrapper.removeFirst();
        sql.clear();
        assertThat(em.find(Order.class, 1L, Map.of(LOAD, mixedGraph("lines"))).getLines())
            .isSameAs(wrapper).hasSize(3);
        assertThat(sql).noneMatch(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("update"));
        wrapper.addFirst(removed);
        em.getTransaction().commit();
        cold();
        assertThat(em.find(Line.class, 10L).getQuantity()).isEqualTo(99);
        assertThat(em.find(Customer.class, 500L).getName()).isEqualTo("dirty-alice");
    }

    @Test void mixedCallbacksMutationsAndCacheAreComplete() {
        Order.inspect = Order.inspectMixed = Line.inspect = true;
        Product.mutate = true;
        em.getTransaction().begin();
        var first = em.find(Order.class, 1L, Map.of(LOAD, mixedGraph("lines")));
        assertThat(first.rootSeen).isEqualTo("alice:hammer:acme");
        assertThat(first.getFeatured().getName()).isEqualTo("hammer!");
        assertThat(Product.LOADS.get()).isEqualTo(2);
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().commit();
        Product.mutate = false;
        Order.inspect = Order.inspectMixed = Line.inspect = false;
        em.clear();
        sql.clear();
        var product = em.find(Product.class, 200L);
        assertThat(product.getName()).isEqualTo("hammer!");
        assertThat(product.getSupplier().getName()).isEqualTo("acme");
        assertThat(selects()).isZero();
        cold();
        assertThat(em.find(Product.class, 200L).getName()).isEqualTo("hammer!");
        cold();
        Product.fail = true;
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.find(Order.class, 1L, Map.of(FETCH, mixedGraph("lines"))))
            .hasStackTraceContaining("product callback failed");
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
        Product.fail = false;
        cold();
        assertThat(em.find(Order.class, 1L, Map.of(LOAD, mixedGraph("lines"))).getFeatured().getName())
            .isEqualTo("hammer!");
        assertThat(selects()).isEqualTo(1);
    }

    @Test void mixedFindDoesNotFlushDirtyJoinedTargetsAndFiniteCyclesKeepIdentity() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            var supplier = em.find(Supplier.class, 300L);
            supplier.setName("dirty-acme");
            em.getTransaction().begin();
            var graph = mixedGraph("lines");
            graph.addSubgraph("lines").addSubgraph("order").addSubgraph("featured")
                .addAttributeNodes("supplier", "category");
            sql.clear();
            var first = em.find(Order.class, 1L, Map.of(hint, graph));
            assertThat(first.getFeatured().getSupplier()).isSameAs(supplier);
            assertThat(first.getCustomer().getFavorite()).isSameAs(first.getFeatured());
            assertThat(first.getLines().get(1).getOrder()).isSameAs(first);
            assertThat(first.getLines().get(1).getProduct()).isSameAs(first.getFeatured());
            assertThat(supplier.getName()).isEqualTo("dirty-acme");
            assertThat(Product.LOADS.get()).isEqualTo(2);
            assertThat(Order.LOADS.get()).isEqualTo(1);
            assertThat(selects()).isEqualTo(1);
            assertThat(sql).noneMatch(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("update"));
            em.getTransaction().rollback();
        }
    }

    @Test void mixedElementCollectionKeepsNullRowsDuplicatesAndAbsentRootTargets() {
        em.getTransaction().begin();
        em.find(Product.class, 200L).getTags().addAll(Arrays.asList(null, "steel"));
        em.getTransaction().commit();
        for (String hint : List.of(FETCH, LOAD)) {
            for (String operation : List.of("jpql", "criteria", "find")) {
                cold();
                var graph = em.createEntityGraph(Product.class);
                graph.addAttributeNodes("tags", "supplier", "category");
                List<Product> products;
                if (operation.equals("find")) {
                    products = List.of(em.find(Product.class, 200L, Map.of(hint, graph)));
                } else if (operation.equals("criteria")) {
                    var cb = em.getCriteriaBuilder();
                    var criteria = cb.createQuery(Product.class);
                    var root = criteria.from(Product.class);
                    criteria.select(root).orderBy(cb.asc(root.get("id")));
                    products = em.createQuery(criteria).setHint(hint, graph).getResultList();
                } else {
                    products = em.createQuery("select p from CsProduct p order by p.id", Product.class)
                        .setHint(hint, graph).getResultList();
                }
                assertThat(products.getFirst().getTags()).containsExactlyInAnyOrder("steel", "wood", null, "steel");
                assertThat(products.getFirst().getSupplier().getName()).isEqualTo("acme");
                assertThat(products.getFirst().getCategory().getName()).isEqualTo("tools");
                if (products.size() == 3) {
                    assertThat(products.get(1).getTags()).isEmpty();
                    assertThat(products.get(2).getTags()).isEmpty();
                    assertThat(products.get(2).getSupplier()).isNull();
                    assertThat(products.get(2).getCategory()).isSameAs(products.getFirst().getCategory());
                }
                assertThat(selects()).as(operation + "/" + hint).isEqualTo(1);
            }
        }
    }

    @Test void mixedMutableGraphHintsAndOwnerPaginationReplanWithoutPartialCollections() {
        var graph = graph("lines", "supplier", "category");
        var query = em.createQuery("select o from CsOrder o order by o.id", Order.class).setHint(FETCH, graph);
        assertThat(orders(FETCH, graph).getFirst().getLines()).hasSize(4);
        cold();
        graph.addSubgraph("customer").addSubgraph("favorite").addAttributeNodes("supplier", "category");
        graph.addSubgraph("featured").addAttributeNodes("supplier", "category");
        assertMixed(query.getResultList().getFirst());
        assertThat(selects()).isEqualTo(1);
        cold();
        var multiple = mixedGraph("lines");
        multiple.addAttributeNodes("picks");
        var fallback = query.setHint(FETCH, multiple).getResultList();
        assertMixed(fallback.getFirst());
        assertThat(fallback.getFirst().getPicks()).hasSize(3);
        assertThat(selects()).isGreaterThan(1);
        cold();
        assertMixed(query.setHint(FETCH, graph).getResultList().getFirst());
        assertThat(selects()).isEqualTo(1);
        cold();
        var page = query.setHint(LOAD, mixedGraph("lines")).setMaxResults(1).getResultList();
        assertThat(page).extracting(Order::getId).containsExactly(1L);
        assertMixed(page.getFirst());
        assertThat(sql.getFirst()).containsIgnoringCase("distinct").containsIgnoringCase("fetch first")
            .doesNotContainIgnoringCase("join");
        assertThat(selects()).isEqualTo(8);
        cold();
        page = query.setFirstResult(1).setMaxResults(2).getResultList();
        assertThat(page).extracting(Order::getId).containsExactly(2L, 3L);
        assertThat(page.getFirst().getLines()).isEmpty();
        assertThat(describe(page.get(1).getLines())).containsExactly("20:hammer/acme", "21:drill/-");
        assertThat(page.get(1).getFeatured()).isSameAs(page.get(1).getLines().get(1).getProduct());
        assertThat(selects()).isEqualTo(9);
    }

    private static List<String> describe(Collection<Line> lines) {
        return lines.stream().map(l -> l == null ? "null" : l.getId() + ":" + (l.getProduct() == null ? "-"
            : l.getProduct().getName() + "/" + (l.getProduct().getSupplier() == null ? "-" : l.getProduct().getSupplier().getName())))
            .toList();
    }

    private void assertFirstOrderGraph(Order first) {
        assertThat(describe(first.getLines())).containsExactly("11:saw/globex", "10:hammer/acme", "13:hammer/acme", "12:-");
        Line l10 = first.getLines().get(1), l13 = first.getLines().get(2);
        assertThat(l13.getProduct()).isSameAs(l10.getProduct());
        assertThat(l10.getOrder()).isSameAs(first);
        assertThat(first.getLines().getLast().getProduct()).isNull();
        for (Line line : first.getLines()) {
            assertThat(loaded(line)).isTrue();
            assertThat(loaded(line, "product")).isTrue();
            if (line.getProduct() != null) {
                assertThat(loaded(line.getProduct())).isTrue();
                assertThat(loaded(line.getProduct(), "supplier")).isTrue();
                assertThat(line.getProduct().getCategory().getName()).isEqualTo("tools");
            }
        }
    }

    @Test void jpqlFetchAndLoadGraphsReadLinesProductsSuppliersAndCategoriesInOneSelect() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            Order.inspect = Line.inspect = true;
            List<Order> orders = orders(hint, graph("lines", "supplier", "category"));
            assertThat(selects()).as(hint).isEqualTo(1);
            assertThat(sql.getFirst()).containsIgnoringCase("left join").containsIgnoringCase("CS_PRODUCT")
                .containsIgnoringCase("CS_SUPPLIER");
            assertThat(orders).extracting(Order::getId).containsExactly(1L, 2L, 3L);
            assertFirstOrderGraph(orders.getFirst());
            assertThat(orders.get(1).getLines()).isEmpty();
            assertThat(loaded(orders.get(1), "lines")).isTrue();
            assertThat(describe(orders.get(2).getLines())).containsExactly("20:hammer/acme", "21:drill/-");
            assertThat(orders.get(2).getLines().getFirst().getProduct()).isSameAs(orders.getFirst().getLines().get(1).getProduct());
            assertThat(orders.get(2).getLines().getFirst().getProduct().getSupplier())
                .isSameAs(orders.getFirst().getLines().get(1).getProduct().getSupplier());
            // every callback ran once, after the complete graph was assembled, without reading anything
            assertThat(Order.LOADS.get()).isEqualTo(3);
            assertThat(Line.LOADS.get()).isEqualTo(6);
            assertThat(Product.LOADS.get()).isEqualTo(3);
            assertThat(Supplier.LOADS.get()).isEqualTo(2);
            assertThat(Category.LOADS.get()).isEqualTo(1);
            assertThat(orders.getFirst().seen).containsExactly("11:saw/globex", "10:hammer/acme", "13:hammer/acme", "12:-");
            assertThat(orders.get(1).seen).isEmpty();
            assertThat(orders.getFirst().getLines()).extracting(l -> l.productSeen).containsExactly("saw", "hammer", "hammer", null);
            assertThat(selects()).as(hint).isEqualTo(1);
        }
    }

    @Test void criteriaDistinctFilteredGraphUsesOneSelectAndDistinctRoots() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            var cb = em.getCriteriaBuilder();
            var criteria = cb.createQuery(Order.class);
            var root = criteria.from(Order.class);
            criteria.select(root).distinct(true).where(root.get("id").in(1L, 3L)).orderBy(cb.desc(root.get("id")));
            var orders = em.createQuery(criteria).setHint(hint, graph("lines", "supplier", "category")).getResultList();
            assertThat(orders).extracting(Order::getId).containsExactly(3L, 1L);
            assertFirstOrderGraph(orders.get(1));
            assertThat(describe(orders.getFirst().getLines())).containsExactly("20:hammer/acme", "21:drill/-");
            assertThat(selects()).as(hint).isEqualTo(1);
            cold();
            // without DISTINCT, the synthetic join still never multiplies the roots
            criteria.distinct(false);
            orders = em.createQuery(criteria).setHint(hint, graph("lines", "supplier", "category")).getResultList();
            assertThat(orders).extracting(Order::getId).containsExactly(3L, 1L);
            assertThat(selects()).as(hint).isEqualTo(1);
        }
    }

    @Test void graphFindReadsTheCollectionSubgraphInOneSelectAndHandlesEmptyAndMissingRoots() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            Order first = em.find(Order.class, 1L, Map.of(hint, graph("lines", "supplier", "category")));
            assertFirstOrderGraph(first);
            assertThat(Product.LOADS.get()).isEqualTo(2);
            assertThat(selects()).as(hint).isEqualTo(1);
            cold();
            Order empty = em.find(Order.class, 2L, Map.of(hint, graph("lines", "supplier", "category")));
            assertThat(empty.getLines()).isEmpty();
            assertThat(loaded(empty, "lines")).isTrue();
            assertThat(selects()).isEqualTo(1);
            cold();
            assertThat(em.find(Order.class, 999L, Map.of(hint, graph("lines", "supplier", "category")))).isNull();
            assertThat(selects()).isEqualTo(1);
        }
        cold();
        Order typed = em.find(graph("lines", "supplier", "category"), 1L);
        assertFirstOrderGraph(typed);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void loadGraphJoinsElementEagerToOnesWhileFetchGraphMayOverfetchThem() {
        cold();
        Order fetched = orders(FETCH, graph("lines")).getFirst();
        // FETCH requests only product; the eager category is permitted follow-up state, the lazy supplier stays unloaded
        assertThat(selects()).isEqualTo(2);
        assertThat(fetched.getLines().get(1).getProduct().getName()).isEqualTo("hammer");
        assertThat(loaded(fetched.getLines().get(1).getProduct(), "supplier")).isFalse();
        assertThat(fetched.getLines().get(1).getProduct().getCategory().getName()).isEqualTo("tools");
        assertThat(selects()).isEqualTo(2);
        cold();
        Order load = orders(LOAD, graph("lines")).getFirst();
        assertThat(selects()).isEqualTo(1);
        assertThat(load.getLines().get(1).getProduct().getCategory().getName()).isEqualTo("tools");
        assertThat(load.getLines().get(1).getOrder()).isSameAs(load);
        assertThat(loaded(load.getLines().get(1).getProduct(), "supplier")).isFalse();
        assertThat(selects()).isEqualTo(1);
    }

    @Test void bagDuplicatesAndMapKeysKeepTheirSubgraphs() {
        for (String hint : List.of(FETCH, LOAD)) {
            cold();
            List<Order> orders = orders(hint, graph("picks", "supplier", "category"));
            assertThat(selects()).as(hint).isEqualTo(1);
            Order first = orders.getFirst();
            assertThat(describe(first.getPicks())).containsExactlyInAnyOrder("10:hammer/acme", "10:hammer/acme", "11:saw/globex");
            List<Line> picks = new ArrayList<>(first.getPicks());
            Line repeated = picks.stream().filter(l -> l.getId() == 10).findFirst().orElseThrow();
            assertThat(picks.stream().filter(l -> l == repeated)).hasSize(2);
            assertThat(orders.get(1).getPicks()).isEmpty();
            assertThat(describe(orders.get(2).getPicks())).containsExactly("20:hammer/acme");
            assertThat(orders.get(2).getPicks().iterator().next().getProduct()).isSameAs(repeated.getProduct());
            assertThat(Line.LOADS.get()).isEqualTo(3);
            cold();
            orders = orders(hint, graph("keyed", "supplier", "category"));
            assertThat(selects()).as(hint).isEqualTo(1);
            Map<String, Line> keyed = orders.getFirst().getKeyed();
            assertThat(keyed).containsOnlyKeys("a", "b");
            assertThat(describe(List.of(keyed.get("a"), keyed.get("b")))).containsExactly("10:hammer/acme", "12:-");
            assertThat(orders.get(1).getKeyed()).isEmpty();
        }
    }

    @Test void existingUnloadedAndDirtyInstancesKeepIdentityAndState() {
        // getReference loads (no proxies); a lazy to-one of a found line is an unloaded instance (D10)
        Product reference = em.find(Line.class, 11L).getProduct();
        assertThat(loaded(reference)).isFalse();
        Supplier acme = em.find(Supplier.class, 300L);
        acme.setName("dirty-acme");
        Line l10 = em.find(Line.class, 10L);
        l10.setQuantity(99);
        Product hammer = l10.getProduct();
        sql.clear();
        Product.LOADS.set(0);
        Supplier.LOADS.set(0);
        Order first = em.createQuery("select o from CsOrder o where o.id = 1", Order.class)
            .setHint(FETCH, graph("lines", "supplier", "category")).getSingleResult();
        assertThat(selects()).isEqualTo(1);
        assertThat(first.getLines().get(0).getProduct()).isSameAs(reference);
        assertThat(loaded(reference)).isTrue();
        assertThat(reference.getName()).isEqualTo("saw");
        assertThat(reference.getSupplier().getName()).isEqualTo("globex");
        assertThat(first.getLines().get(1)).isSameAs(l10);
        assertThat(l10.getQuantity()).isEqualTo(99);
        assertThat(l10.getProduct()).isSameAs(hammer);
        assertThat(hammer.getSupplier()).isSameAs(acme);
        assertThat(acme.getName()).isEqualTo("dirty-acme");
        assertThat(Product.LOADS.get()).isEqualTo(2); // both unloaded references completed in place
        assertThat(Supplier.LOADS.get()).isEqualTo(1);
        assertThat(selects()).isEqualTo(1);
        // a loaded, dirty collection keeps its in-memory content; a graph find never auto-flushes
        Order third = em.find(Order.class, 3L);
        List<Line> lines = third.getLines();
        Line removed = lines.removeFirst();
        em.getTransaction().begin();
        sql.clear();
        assertThat(em.find(Order.class, 3L, Map.of(LOAD, graph("lines", "supplier", "category")))).isSameAs(third);
        assertThat(third.getLines()).isSameAs(lines).extracting(Line::getId).containsExactly(21L);
        assertThat(sql).noneMatch(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("update"));
        third.getLines().addFirst(removed);
        em.getTransaction().commit();
        cold();
        assertThat(em.find(Supplier.class, 300L).getName()).isEqualTo("dirty-acme");
        assertThat(em.find(Line.class, 10L).getQuantity()).isEqualTo(99);
    }

    @Test void callbackMutationIsFlushedAndCallbackFailurePropagates() {
        Product.mutate = true;
        em.getTransaction().begin();
        Order first = em.createQuery("select o from CsOrder o where o.id = 1", Order.class)
            .setHint(LOAD, graph("lines", "supplier", "category")).getSingleResult();
        assertThat(first.getLines().get(1).getProduct().getName()).isEqualTo("hammer!");
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().commit();
        Product.mutate = false;
        cold();
        assertThat(em.find(Product.class, 200L).getName()).isEqualTo("hammer!");
        cold();
        Product.fail = true;
        em.getTransaction().begin();
        assertThatThrownBy(() -> em.createQuery("select o from CsOrder o where o.id = 1", Order.class)
            .setHint(FETCH, graph("lines", "supplier", "category")).getSingleResult())
            .hasStackTraceContaining("product callback failed");
        assertThat(em.getTransaction().getRollbackOnly()).isTrue();
        em.getTransaction().rollback();
        Product.fail = false;
        cold();
        // the failed load left nothing behind: a new context reads the flushed callback mutation in one select
        assertThat(describe(em.find(Order.class, 1L, Map.of(FETCH, graph("lines", "supplier", "category"))).getLines()))
            .containsExactly("11:saw!/globex", "10:hammer!/acme", "13:hammer!/acme", "12:-");
        assertThat(Product.LOADS.get()).isEqualTo(2);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void mutatedGraphAndReplacedHintsArePlannedPerExecution() {
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("lines");
        var query = em.createQuery("select o from CsOrder o where o.id = 1", Order.class).setHint(FETCH, graph);
        Order first = query.getSingleResult();
        assertThat(loaded(first, "lines")).isTrue();
        assertThat(loaded(first.getLines().get(1).getProduct())).isFalse();
        assertThat(sql.getFirst()).doesNotContainIgnoringCase("CS_PRODUCT");
        cold();
        graph.addSubgraph("lines").addSubgraph("product").addAttributeNodes("supplier", "category");
        first = query.getSingleResult();
        assertThat(selects()).isEqualTo(1);
        assertFirstOrderGraph(first);
        cold();
        var both = graph("lines", "supplier", "category");
        both.addAttributeNodes("picks");
        first = query.setHint(FETCH, both).getSingleResult();
        assertFirstOrderGraph(first);
        assertThat(first.getPicks()).hasSize(3);
        assertThat(sql.getFirst()).doesNotContainIgnoringCase("CS_PRODUCT");
        assertThat(selects()).isGreaterThan(1);
        cold();
        first = query.setHint(FETCH, graph("lines", "supplier", "category")).getSingleResult();
        assertFirstOrderGraph(first);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void paginationPagesOwnersInSqlThenCompletesCollectionsAndSubgraphs() {
        var query = em.createQuery("select o from CsOrder o order by o.id", Order.class)
            .setHint(LOAD, graph("lines", "supplier", "category")).setMaxResults(1);
        List<Order> page = query.getResultList();
        assertThat(sql.getFirst()).containsIgnoringCase("fetch first").containsIgnoringCase("distinct")
            .doesNotContainIgnoringCase("join");
        // owner page, root state, line keys, batched lines, batched products, category, batched suppliers
        assertThat(selects()).isEqualTo(7);
        assertThat(page).extracting(Order::getId).containsExactly(1L);
        assertFirstOrderGraph(page.getFirst());
        assertThat(Line.LOADS.get()).isEqualTo(4);
        assertThat(selects()).isEqualTo(7);
        cold();
        page = query.setFirstResult(1).setMaxResults(2).getResultList();
        assertThat(sql.getFirst()).containsIgnoringCase("offset").doesNotContainIgnoringCase("join");
        assertThat(selects()).isEqualTo(9); // the two owners' state and line keys are read per owner
        assertThat(page).extracting(Order::getId).containsExactly(2L, 3L);
        assertThat(page.getFirst().getLines()).isEmpty();
        assertThat(describe(page.get(1).getLines())).containsExactly("20:hammer/acme", "21:drill/-");
        assertThat(selects()).isEqualTo(9);
        cold();
        var cb = em.getCriteriaBuilder();
        var criteria = cb.createQuery(Order.class);
        var root = criteria.from(Order.class);
        criteria.select(root).orderBy(cb.desc(root.get("id")));
        page = em.createQuery(criteria).setHint(FETCH, graph("lines", "supplier", "category")).setMaxResults(2).getResultList();
        assertThat(sql.getFirst()).containsIgnoringCase("fetch first");
        assertThat(selects()).isEqualTo(9);
        assertThat(page).extracting(Order::getId).containsExactly(3L, 2L);
        assertThat(describe(page.getFirst().getLines())).containsExactly("20:hammer/acme", "21:drill/-");
        assertThat(selects()).isEqualTo(9);
        cold();
        // a non-scalar ordering cannot page whole owners in SQL: refused, never paginated in memory
        assertThatThrownBy(() -> em.createQuery("select o from CsOrder o order by o.customer.name", Order.class)
            .setHint(FETCH, graph("lines", "supplier", "category")).setMaxResults(1).getResultList())
            .isInstanceOf(PersistenceException.class);
    }

    @Test void secondLevelCacheStoresTheCompletedGraph() {
        orders(FETCH, graph("lines", "supplier", "category"));
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        // the subgraph targets are stored complete; orders and lines reach an unloaded lazy customer, which the
        // shared cache never captures (an existing rule, unchanged here)
        Product hammer = em.find(Product.class, 200L);
        Supplier acme = em.find(Supplier.class, 300L);
        Category tools = em.find(Category.class, 400L);
        assertThat(hammer.getName()).isEqualTo("hammer");
        assertThat(hammer.getSupplier()).isSameAs(acme);
        assertThat(hammer.getCategory()).isSameAs(tools);
        assertThat(acme.getName()).isEqualTo("acme");
        assertThat(selects()).as(sql.toString()).isZero();
        sql.clear();
        List<Order> warm = orders(LOAD, graph("lines", "supplier", "category"));
        assertFirstOrderGraph(warm.getFirst());
        assertThat(warm.getFirst().getLines().get(1).getProduct()).isSameAs(hammer);
        assertThat(selects()).isEqualTo(1);
    }

    @Test void unsupportedShapesKeepCompleteFallbacks() {
        em.getTransaction().begin();
        em.find(Line.class, 10L).setItem(em.find(Item.class, 600L));
        em.find(Line.class, 11L).setItem(em.find(Item.class, 601L));
        em.getTransaction().commit();
        Map<String, EntityGraph<Order>> graphs = new LinkedHashMap<>();
        var deeper = graph("lines", "supplier", "category");
        deeper.addSubgraph("lines").addSubgraph("discounts").addAttributeNodes("tags");
        graphs.put("nested collection", deeper);
        var polymorphic = em.createEntityGraph(Order.class);
        polymorphic.addSubgraph("lines").addAttributeNodes("item");
        graphs.put("polymorphic target", polymorphic);
        var rootNested = mixedGraph("lines");
        rootNested.addSubgraph("customer").addSubgraph("favorite").addAttributeNodes("tags");
        graphs.put("root nested collection", rootNested);
        for (var entry : graphs.entrySet()) {
            for (String hint : List.of(FETCH, LOAD)) {
                cold();
                Order first = em.createQuery("select o from CsOrder o where o.id = 1", Order.class)
                    .setHint(hint, entry.getValue()).getSingleResult();
                assertThat(sql.getFirst()).as(entry.getKey()).doesNotContainIgnoringCase("CS_LINE");
                assertThat(selects()).as(entry.getKey()).isGreaterThan(1);
                assertThat(first.getLines()).extracting(Line::getId).containsExactly(11L, 10L, 13L, 12L);
                switch (entry.getKey()) {
                    case "nested collection" -> assertThat(first.getLines().get(1).getProduct().getTags())
                        .containsExactlyInAnyOrder("steel", "wood");
                    case "polymorphic target" -> {
                        assertThat(first.getLines().get(0).getItem()).isInstanceOf(SpecialItem.class);
                        assertThat(((SpecialItem) first.getLines().get(0).getItem()).getMetal()).isEqualTo("gold");
                        assertThat(first.getLines().get(1).getItem().getName()).isEqualTo("plain");
                    }
                    case "root nested collection" -> assertThat(first.getCustomer().getFavorite().getTags())
                        .containsExactlyInAnyOrder("steel", "wood");
                    default -> assertThat(first.getCustomer().getName()).isEqualTo("alice");
                }
                cold();
                Order found = em.find(Order.class, 1L, Map.of(hint, entry.getValue()));
                assertThat(sql.getFirst()).as(entry.getKey()).doesNotContainIgnoringCase("CS_LINE");
                assertThat(describe(found.getLines())).containsExactly("11:saw/globex", "10:hammer/acme", "13:hammer/acme", "12:-");
            }
        }
    }

    @Test void explicitFetchJoinsAndUngraphedQueriesAreUnchanged() {
        List<Order> rows = em.createQuery(
            "select o from CsOrder o left join fetch o.lines l left join fetch l.product where o.id = 1", Order.class)
            .getResultList();
        assertThat(rows).hasSize(4).allSatisfy(o -> assertThat(o).isSameAs(rows.getFirst()));
        assertThat(describe(rows.getFirst().getLines())).containsExactly("11:saw/globex", "10:hammer/acme", "13:hammer/acme", "12:-");
        long explicit = selects();
        cold();
        var withGraph = em.createQuery(
            "select o from CsOrder o left join fetch o.lines l left join fetch l.product where o.id = 1", Order.class)
            .setHint(FETCH, graph("lines", "supplier", "category")).getResultList();
        assertThat(withGraph).hasSize(4);
        assertThat(selects()).isEqualTo(explicit);
        cold();
        Order plain = em.createQuery("select o from CsOrder o where o.id = 1", Order.class).getSingleResult();
        assertThat(sql.getFirst()).doesNotContainIgnoringCase("join");
        assertThat(loaded(plain, "lines")).isFalse();
        assertThat(describe(plain.getLines())).containsExactly("11:saw/globex", "10:hammer/acme", "13:hammer/acme", "12:-");
    }

    @Entity(name = "CsOrder") @Table(name = "CS_ORDER")
    public static class Order {
        static final AtomicInteger LOADS = new AtomicInteger();
        static boolean inspect, inspectMixed, inspectAll, requireComplete, inspectNested, inspectRootBranch, inspectRecommendations;
        private long id;
        private String label;
        private Customer customer;
        private Product featured;
        private List<Line> lines = new ArrayList<>();
        private Collection<Line> picks = new ArrayList<>();
        private Map<String, Line> keyed = new LinkedHashMap<>();
        private List<Payment> payments = new ArrayList<>();
        List<String> seen;
        String rootSeen;
        String allSeen;
        String rootBranchSeen;
        int nestedSeen;
        public Order() {}
        Order(long id, String label, Customer customer) { this.id = id; this.label = label; this.customer = customer; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id")
        public Customer getCustomer() { return customer; }
        public void setCustomer(Customer customer) { this.customer = customer; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "featured_id")
        public Product getFeatured() { return featured; }
        public void setFeatured(Product featured) { this.featured = featured; }
        @OneToMany(mappedBy = "order") @OrderColumn(name = "line_position")
        public List<Line> getLines() { return lines; }
        public void setLines(List<Line> lines) { this.lines = lines; }
        @ManyToMany @JoinTable(name = "CS_PICKS")
        public Collection<Line> getPicks() { return picks; }
        public void setPicks(Collection<Line> picks) { this.picks = picks; }
        @OneToMany @JoinTable(name = "CS_KEYED") @MapKeyColumn(name = "line_key")
        public Map<String, Line> getKeyed() { return keyed; }
        public void setKeyed(Map<String, Line> keyed) { this.keyed = keyed; }
        @OneToMany(mappedBy = "order") @OrderBy("id")
        public List<Payment> getPayments() { return payments; }
        public void setPayments(List<Payment> payments) { this.payments = payments; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            if (inspectRootBranch && customer != null) {
                Product favorite = customer.getFavorite();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(favorite.getTags())).isFalse();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(favorite.getFacts())).isFalse();
                rootBranchSeen = favorite.getTags().size() + ":" + favorite.getFacts().size();
                if (inspectRecommendations) {
                    assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(customer.getRecommendations())).isFalse();
                    customer.getRecommendations().forEach(p -> {
                        assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(p.getTags())).isFalse();
                        assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(p.getFacts())).isFalse();
                    });
                }
            }
            if (inspectNested) {
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(lines)).isFalse();
                nestedSeen = lines.stream().mapToInt(l -> {
                    assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(l.getDiscounts())).isFalse();
                    assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(l.getNotes())).isFalse();
                    return l.getDiscounts().size();
                }).sum();
            }
            if (requireComplete) {
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(lines)).isFalse();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(picks)).isFalse();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(keyed)).isFalse();
            }
            if (inspectAll) allSeen = lines.size() + ":" + picks.size() + ":" + keyed.size();
            if (inspect) seen = describe(lines);
            if (inspectMixed && customer != null) {
                rootSeen = customer.getName() + ":" + customer.getFavorite().getName() + ":"
                    + featured.getSupplier().getName();
                assertThat(customer.getFavorite()).isSameAs(featured).isSameAs(lines.get(1).getProduct());
            }
        }
    }

    @Entity(name = "CsPayment") @Table(name = "CS_PAYMENT")
    public static class Payment {
        static boolean inspect;
        private long id;
        private Order order;
        private Product product;
        String seen;
        public Payment() {}
        Payment(long id, Order order, Product product) { this.id = id; this.order = order; this.product = product; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @ManyToOne public Order getOrder() { return order; }
        public void setOrder(Order order) { this.order = order; }
        @ManyToOne(fetch = FetchType.LAZY) public Product getProduct() { return product; }
        public void setProduct(Product product) { this.product = product; }
        @PostLoad void loaded() { if (inspect) seen = order.getLines().size() + ":" + order.getPayments().size(); }
    }

    @Entity(name = "CsLine") @Table(name = "CS_LINE")
    public static class Line {
        static final AtomicInteger LOADS = new AtomicInteger();
        static boolean inspect, inspectAll, inspectNested;
        private long id;
        private int quantity;
        private Order order;
        private Product product;
        private Item item;
        private Collection<Product> discounts = new ArrayList<>();
        private List<String> notes = new ArrayList<>();
        private Map<String, String> codes = new LinkedHashMap<>();
        String nestedSeen;
        String productSeen;
        String allSeen;
        public Line() {}
        Line(long id, int quantity, Order order, Product product, Item item) {
            this.id = id; this.quantity = quantity; this.order = order; this.product = product; this.item = item;
        }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
        @ManyToOne @JoinColumn(name = "order_id")
        public Order getOrder() { return order; }
        public void setOrder(Order order) { this.order = order; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "product_id")
        public Product getProduct() { return product; }
        public void setProduct(Product product) { this.product = product; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "item_id")
        public Item getItem() { return item; }
        public void setItem(Item item) { this.item = item; }
        @ManyToMany @JoinTable(name = "CS_DISCOUNTS")
        public Collection<Product> getDiscounts() { return discounts; }
        public void setDiscounts(Collection<Product> discounts) { this.discounts = discounts; }
        @ElementCollection @CollectionTable(name = "CS_NOTES") @OrderColumn(name = "note_position")
        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
        @ElementCollection @CollectionTable(name = "CS_CODES") @MapKeyColumn(name = "code_key")
        public Map<String, String> getCodes() { return codes; }
        public void setCodes(Map<String, String> codes) { this.codes = codes; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            if (inspectNested) {
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(discounts)).isFalse();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(notes)).isFalse();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(codes)).isFalse();
                nestedSeen = discounts.size() + ":" + notes.size() + ":" + codes.size();
            }
            if (inspectAll) allSeen = order.getLines().size() + ":" + order.getPicks().size() + ":" + order.getKeyed().size();
            if (inspect) productSeen = product == null ? null : product.getName();
        }
    }

    @Entity(name = "CsProduct") @Table(name = "CS_PRODUCT")
    public static class Product {
        static final AtomicInteger LOADS = new AtomicInteger();
        static boolean fail, mutate;
        private long id;
        private String name;
        private Supplier supplier;
        private Category category;
        private List<String> tags = new ArrayList<>();
        private Map<String, String> facts = new LinkedHashMap<>();
        public Product() {}
        Product(long id, String name, Supplier supplier, Category category) {
            this.id = id; this.name = name; this.supplier = supplier; this.category = category;
        }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "supplier_id")
        public Supplier getSupplier() { return supplier; }
        public void setSupplier(Supplier supplier) { this.supplier = supplier; }
        @ManyToOne @JoinColumn(name = "category_id")
        public Category getCategory() { return category; }
        public void setCategory(Category category) { this.category = category; }
        @ElementCollection @CollectionTable(name = "CS_PRODUCT_TAG")
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        @ElementCollection @CollectionTable(name = "CS_PRODUCT_FACT") @MapKeyColumn(name = "fact_key")
        public Map<String, String> getFacts() { return facts; }
        public void setFacts(Map<String, String> facts) { this.facts = facts; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            if (fail && name.startsWith("saw")) throw new IllegalStateException("product callback failed");
            if (mutate) name = name + "!";
        }
    }

    @Entity(name = "CsSupplier") @Table(name = "CS_SUPPLIER")
    public static class Supplier {
        static final AtomicInteger LOADS = new AtomicInteger();
        private long id;
        private String name;
        public Supplier() {}
        Supplier(long id, String name) { this.id = id; this.name = name; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @PostLoad void loaded() { LOADS.incrementAndGet(); }
    }

    @Entity(name = "CsCategory") @Table(name = "CS_CATEGORY")
    public static class Category {
        static final AtomicInteger LOADS = new AtomicInteger();
        private long id;
        private String name;
        public Category() {}
        Category(long id, String name) { this.id = id; this.name = name; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @PostLoad void loaded() { LOADS.incrementAndGet(); }
    }

    @Entity(name = "CsCustomer") @Table(name = "CS_CUSTOMER")
    public static class Customer {
        static final AtomicInteger LOADS = new AtomicInteger();
        private long id;
        private String name;
        private Product favorite;
        private Collection<Product> recommendations = new ArrayList<>();
        public Customer() {}
        Customer(long id, String name) { this.id = id; this.name = name; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @ManyToOne @JoinColumn(name = "favorite_id")
        public Product getFavorite() { return favorite; }
        public void setFavorite(Product favorite) { this.favorite = favorite; }
        @ManyToMany @JoinTable(name = "CS_RECOMMENDATIONS")
        public Collection<Product> getRecommendations() { return recommendations; }
        public void setRecommendations(Collection<Product> recommendations) { this.recommendations = recommendations; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            if (Order.inspectRootBranch && favorite != null) {
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(favorite.getTags())).isFalse();
                assertThat(io.vidocq.noether.jpa.core.mapping.LazyCollection.unloaded(favorite.getFacts())).isFalse();
            }
        }
    }

    @Entity(name = "CsItem") @Table(name = "CS_ITEM")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    public static class Item {
        private long id;
        private String name;
        public Item() {}
        Item(long id, String name) { this.id = id; this.name = name; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Entity(name = "CsSpecialItem")
    public static class SpecialItem extends Item {
        private String metal;
        public SpecialItem() {}
        SpecialItem(long id, String name, String metal) { super(id, name); this.metal = metal; }
        public String getMetal() { return metal; }
        public void setMetal(String metal) { this.metal = metal; }
    }
}
