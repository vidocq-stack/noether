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
 * §3.7.4 and §4.4.5.3: one graph-requested root collection and the to-one subgraphs of its elements are read from
 * the same SQL rows, with complete collections, one managed instance per identity and staged callbacks (D17).
 */
class CollectionSubgraphTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String FETCH = "jakarta.persistence.fetchgraph";
    private static final String LOAD = "jakarta.persistence.loadgraph";
    private final List<String> sql = new ArrayList<>();
    private EntityManagerFactory factory;
    private EntityManager em;

    @BeforeEach
    void open() {
        var source = new CountingDataSource("jdbc:h2:mem:collection-subgraph-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1") {
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
        factory = new PersistenceConfiguration("collection-subgraph")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Order.class).managedClass(Line.class).managedClass(Product.class).managedClass(Supplier.class)
            .managedClass(Category.class).managedClass(Customer.class).managedClass(Item.class).managedClass(SpecialItem.class)
            .sharedCacheMode(SharedCacheMode.ALL)
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
        Item plain = new Item(600, "plain");
        SpecialItem special = new SpecialItem(601, "special", "gold");
        Order first = new Order(1, "first", alice), empty = new Order(2, "empty", null), third = new Order(3, "third", null);
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
        for (Object entity : List.of(tools, acme, globex, hammer, saw, drill, alice, plain, special, first, empty, third,
                l10, l11, l12, l13, l20, l21)) em.persist(entity);
        em.getTransaction().commit();
        cold();
    }

    @AfterEach void close() {
        Order.inspect = Line.inspect = Product.fail = Product.mutate = false;
        if (factory != null) factory.close();
    }

    private long selects() { return sql.stream().filter(s -> s.stripLeading().toLowerCase(Locale.ROOT).startsWith("select")).count(); }
    private boolean loaded(Object entity, String attribute) { return factory.getPersistenceUnitUtil().isLoaded(entity, attribute); }
    private boolean loaded(Object entity) { return factory.getPersistenceUnitUtil().isLoaded(entity); }

    private void cold() {
        em.clear();
        factory.getCache().evictAll();
        sql.clear();
        for (AtomicInteger counter : List.of(Order.LOADS, Line.LOADS, Product.LOADS, Supplier.LOADS, Category.LOADS)) counter.set(0);
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
        graphs.put("nested collection", graph("lines", "supplier", "tags"));
        var polymorphic = em.createEntityGraph(Order.class);
        polymorphic.addSubgraph("lines").addAttributeNodes("item");
        graphs.put("polymorphic target", polymorphic);
        var multiple = graph("lines", "supplier");
        multiple.addAttributeNodes("picks");
        graphs.put("multiple collections", multiple);
        var mixed = graph("lines", "supplier");
        mixed.addAttributeNodes("customer");
        graphs.put("root to-one with collection", mixed);
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
                    case "multiple collections" -> assertThat(first.getPicks()).hasSize(3);
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
        static boolean inspect;
        private long id;
        private String label;
        private Customer customer;
        private List<Line> lines = new ArrayList<>();
        private Collection<Line> picks = new ArrayList<>();
        private Map<String, Line> keyed = new LinkedHashMap<>();
        List<String> seen;
        public Order() {}
        Order(long id, String label, Customer customer) { this.id = id; this.label = label; this.customer = customer; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id")
        public Customer getCustomer() { return customer; }
        public void setCustomer(Customer customer) { this.customer = customer; }
        @OneToMany(mappedBy = "order") @OrderColumn(name = "line_position")
        public List<Line> getLines() { return lines; }
        public void setLines(List<Line> lines) { this.lines = lines; }
        @ManyToMany @JoinTable(name = "CS_PICKS")
        public Collection<Line> getPicks() { return picks; }
        public void setPicks(Collection<Line> picks) { this.picks = picks; }
        @OneToMany @JoinTable(name = "CS_KEYED") @MapKeyColumn(name = "line_key")
        public Map<String, Line> getKeyed() { return keyed; }
        public void setKeyed(Map<String, Line> keyed) { this.keyed = keyed; }
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
            if (inspect) seen = describe(lines);
        }
    }

    @Entity(name = "CsLine") @Table(name = "CS_LINE")
    public static class Line {
        static final AtomicInteger LOADS = new AtomicInteger();
        static boolean inspect;
        private long id;
        private int quantity;
        private Order order;
        private Product product;
        private Item item;
        String productSeen;
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
        @PostLoad void loaded() {
            LOADS.incrementAndGet();
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
        private long id;
        private String name;
        public Customer() {}
        Customer(long id, String name) { this.id = id; this.name = name; }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
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
