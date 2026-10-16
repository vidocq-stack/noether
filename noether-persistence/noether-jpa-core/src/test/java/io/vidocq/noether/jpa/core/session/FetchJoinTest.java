/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.*;
import jakarta.persistence.criteria.JoinType;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §4.4.5.3 and §6.3.3: a to-one fetch join returns its owner and the fetched entity from the same rows, without a select
 * per owner or per target, while preserving one managed instance per identity (§3.2.8).
 */
class FetchJoinTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private final List<String> sql = new ArrayList<>();
    private EntityManagerFactory factory;
    private EntityManager em;

    @BeforeEach
    void reset() {
        Customer.POST_LOADS.set(0);
    }

    private void open() {
        open(null);
    }

    private void open(String mapping) {
        open(mapping, SharedCacheMode.NONE);
    }

    private void open(String mapping, SharedCacheMode cache) {
        var source = new CountingDataSource("jdbc:h2:mem:fetch-join-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1") {
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
        var configuration = new PersistenceConfiguration("fetch-join")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Order.class).managedClass(Customer.class).managedClass(Address.class).managedClass(Part.class)
            .managedClass(PartKey.class).managedClass(Vehicle.class).managedClass(Car.class).managedClass(Line.class).managedClass(Shipment.class)
            .managedClass(CityConverter.class).managedClass(CompositeOrder.class).sharedCacheMode(cache)
            .property("jakarta.persistence.nonJtaDataSource", source)
            .property("jakarta.persistence.schema-generation.database.action", "create");
        if (mapping != null) configuration.mappingFile(mapping);
        factory = configuration.createEntityManagerFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        Address address = new Address(100, "paris", "75001");
        em.persist(address);
        Customer alice = new Customer(10, "alice", address);
        em.persist(alice);
        CompositeOrder composite = new CompositeOrder();
        composite.key = new PartKey("C", 2);
        composite.customer = alice;
        em.persist(composite);
        Customer bob = new Customer(11, "bob", null);
        em.persist(bob);
        Part part = new Part(new PartKey("A", 1), "bolt");
        em.persist(part);
        Car car = new Car(200, "fiat");
        em.persist(car);
        Order first = new Order(1, "o1", alice);
        em.persist(first);
        Shipment shipment = new Shipment();
        shipment.setId(1);
        shipment.setPart(part);
        shipment.setCar(car);
        shipment.setVehicle(car);
        em.persist(shipment);
        em.persist(new Order(2, "o2", alice));
        em.persist(new Order(3, "o3", null));
        em.persist(new Order(4, "o4", bob));
        Line line = new Line(1000, first);
        em.persist(line);
        em.getTransaction().commit();
        em.clear();
        sql.clear();
        Customer.POST_LOADS.set(0);
    }

    @AfterEach
    void close() {
        if (factory != null && factory.isOpen()) factory.close();
    }

    private long selects() {
        return sql.stream().filter(s -> s.trim().toLowerCase(Locale.ROOT).startsWith("select")).count();
    }

    private long updates() {
        return sql.stream().filter(s -> s.trim().toLowerCase(Locale.ROOT).startsWith("update")).count();
    }

    private boolean loaded(Object entity, String attribute) {
        return factory.getPersistenceUnitUtil().isLoaded(entity, attribute);
    }

    @Test
    void findFetchGraphLoadsDirectToOneInOneSelect() {
        // §3.8.1: find applies the graph without acquiring query auto-flush semantics.
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        Order order = em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph));
        assertThat(order.getCustomer().getName()).isEqualTo("alice");
        assertThat(loaded(order.getCustomer(), "address")).isFalse();
        assertThat(selects()).isEqualTo(1);
        assertThat(Customer.POST_LOADS.get()).isEqualTo(1);
        assertThat(em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph))).isSameAs(order);
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void findLoadGraphLoadsNestedConvertedSecondaryStateInOneSelect() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        Order order = em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.loadgraph", graph));
        assertThat(order.getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(order.getCustomer().getAddress().getZip()).isEqualTo("75001");
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().begin();
        em.getTransaction().commit();
        assertThat(updates()).isZero();
    }

    @Test
    void findGraphPreservesNullTargetsAndMissingRoots() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        assertThat(em.find(graph, 3L).getCustomer()).isNull();
        assertThat(selects()).isEqualTo(1);
        sql.clear();
        assertThat(em.find(graph, 4L).getCustomer().getAddress()).isNull();
        assertThat(selects()).isEqualTo(1);
        sql.clear();
        assertThat(em.find(graph, 999L)).isNull();
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void findLoadGraphIncludesOmittedEagerToOne() {
        open();
        var graph = em.createEntityGraph(Line.class);
        assertThat(em.find(Line.class, 1000L, java.util.Map.of("jakarta.persistence.loadgraph", graph))
            .order.getLabel()).isEqualTo("o1");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        assertThat(em.find(Line.class, 1000L, java.util.Map.of("jakarta.persistence.fetchgraph", graph))
            .order.getLabel()).isEqualTo("o1");
        assertThat(selects()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void findGraphHydratesExistingTargetReferenceInPlace() {
        open();
        Customer reference = em.find(Order.class, 2L).getCustomer();
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        sql.clear();
        Order order = em.find(graph, 1L);
        assertThat(order.getCustomer()).isSameAs(reference);
        assertThat(reference.getName()).isEqualTo("alice");
        assertThat(reference.getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
        assertThat(Customer.POST_LOADS.get()).isEqualTo(1);
    }

    @Test
    void findGraphInitializesAnUnloadedRootReferenceInPlace() {
        open();
        Customer reference = em.find(Order.class, 2L).getCustomer();
        var graph = em.createEntityGraph(Customer.class);
        graph.addAttributeNodes("address");
        sql.clear();
        assertThat(em.find(graph, 10L)).isSameAs(reference);
        assertThat(reference.getAddress().getCity()).isEqualTo("paris");
        assertThat(reference.getName()).isEqualTo("alice");
        assertThat(selects()).isEqualTo(1);
        assertThat(Customer.POST_LOADS.get()).isEqualTo(1);
    }

    @Test
    void findGraphBindsEveryCompositeRootKeyPartThroughMappedBinders() {
        open();
        var graph = em.createEntityGraph(CompositeOrder.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        CompositeOrder order = em.find(graph, new PartKey("C", 2));
        assertThat(order.key).isEqualTo(new PartKey("C", 2));
        assertThat(order.customer.getAddress().getZip()).isEqualTo("75001");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        assertThat(em.find(graph, new PartKey("C", 3))).isNull();
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void findGraphOnCachedRootStillInitializesRequestedUncachedReference() {
        open(null, SharedCacheMode.ALL);
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        em.find(Order.class, 2L); // keeps an unloaded customer reference in this context
        sql.clear();
        Order order = em.find(graph, 1L);
        assertThat(order.getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(em.find(Customer.class, 10L)).isSameAs(order.getCustomer());
        assertThat(loaded(order, "customer")).isTrue();
        em.clear();
        sql.clear();
        assertThat(em.find(graph, 1L).getCustomer().getAddress().getZip()).isEqualTo("75001");
        assertThat(selects()).isZero();
    }

    @Test
    void findGraphKeepsManagedDirtyRootAndTargetWithoutAutoFlush() {
        open();
        Customer customer = em.find(Customer.class, 10L);
        Order managed = em.find(Order.class, 1L);
        em.getTransaction().begin();
        customer.setName("dirty");
        managed.setLabel("dirty-root");
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        sql.clear();
        assertThat(em.find(graph, 2L).getCustomer()).isSameAs(customer);
        assertThat(selects()).isEqualTo(1);
        assertThat(customer.getName()).isEqualTo("dirty");
        assertThat(em.find(graph, 1L)).isSameAs(managed);
        assertThat(managed.getLabel()).isEqualTo("dirty-root");
        assertThat(selects()).isEqualTo(1);
        assertThat(updates()).isZero();
        em.getTransaction().commit();
        assertThat(updates()).isEqualTo(2);
        em.clear();
        assertThat(em.find(Order.class, 1L).getLabel()).isEqualTo("dirty-root");
        assertThat(em.find(Customer.class, 10L).getName()).isEqualTo("dirty");
    }

    @Test
    void findGraphMutationAndBothValidatedHintsAreAppliedPerInvocation() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        assertThat(em.find(graph, 1L).getCustomer().getName()).isEqualTo("alice");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        graph.addSubgraph("customer").addAttributeNodes("address");
        assertThat(em.find(graph, 1L).getCustomer().getAddress().getZip()).isEqualTo("75001");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        assertThat(em.find(Order.class, 1L, java.util.Map.of(
            "jakarta.persistence.fetchgraph", em.createEntityGraph(Order.class),
            "jakarta.persistence.loadgraph", graph)).getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
        assertThatIllegalArgumentException().isThrownBy(() ->
            em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", "bad")));
        assertThatIllegalArgumentException().isThrownBy(() ->
            em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.loadgraph", em.createEntityGraph(Customer.class))));
    }

    @Test
    void findGraphCacheMissPublishesCompleteGraphAndCacheHitNeedsNoSelect() {
        open(null, SharedCacheMode.ALL);
        factory.getCache().evictAll();
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        assertThat(em.find(graph, 1L).getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        assertThat(em.find(graph, 1L).getCustomer().getAddress().getZip()).isEqualTo("75001");
        assertThat(selects()).isZero();
        em.clear();
        sql.clear();
        assertThat(em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph,
            "jakarta.persistence.cache.retrieveMode", CacheRetrieveMode.BYPASS,
            "jakarta.persistence.cache.storeMode", CacheStoreMode.BYPASS))
            .getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void findGraphRefreshHintBypassesSharedCacheButNotManagedDirtyState() {
        open(null, SharedCacheMode.ALL);
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        em.find(graph, 1L);
        em.clear();
        sql.clear();
        Order order = em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph,
            "jakarta.persistence.cache.storeMode", CacheStoreMode.REFRESH));
        assertThat(order.getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
        order.setLabel("local");
        sql.clear();
        assertThat(em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph,
            "jakarta.persistence.cache.storeMode", CacheStoreMode.REFRESH))).isSameAs(order);
        assertThat(order.getLabel()).isEqualTo("local");
        assertThat(selects()).isZero();
    }

    @Test
    void findGraphLockAndPolymorphicFallbacksStillLoadCompleteState() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        assertThatThrownBy(() -> em.find(graph, 1L, LockModeType.PESSIMISTIC_WRITE))
            .isInstanceOf(TransactionRequiredException.class);
        em.getTransaction().begin();
        Order order = em.find(graph, 1L, LockModeType.PESSIMISTIC_WRITE, Timeout.seconds(2));
        assertThat(order.getCustomer().getName()).isEqualTo("alice");
        assertThat(em.getLockMode(order)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(selects()).isGreaterThan(1);
        em.getTransaction().commit();
        em.clear();
        sql.clear();
        var shipmentGraph = em.createEntityGraph(Shipment.class);
        shipmentGraph.addAttributeNodes("vehicle", "part");
        Shipment shipment = em.find(shipmentGraph, 1L);
        assertThat(((Car) shipment.getVehicle()).brand).isEqualTo("fiat");
        assertThat(shipment.getPart().name).isEqualTo("bolt");
        assertThat(selects()).isGreaterThan(1);
    }

    @Test
    void findMixedGraphKeepsCompleteCollectionFollowUpAndPlainFindUnchanged() {
        open();
        Order plain = em.find(Order.class, 1L);
        assertThat(plain.getLabel()).isEqualTo("o1");
        assertThat(loaded(plain, "customer")).isFalse();
        assertThat(loaded(plain, "lines")).isFalse();
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer", "lines");
        Order order = em.find(graph, 1L);
        assertThat(order.getCustomer().getName()).isEqualTo("alice");
        assertThat(order.getLines()).extracting(line -> line.id).containsExactly(1000L);
        assertThat(order.getLines().getFirst().order).isSameAs(order);
        assertThat(selects()).isEqualTo(3);
    }

    @Test
    void jpqlGraphLoadsNullableToOnesFromTheResultSql() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        var orders = em.createQuery("select o from FjOrder o order by o.id", Order.class)
            .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
        assertThat(orders).extracting(Order::getLabel).containsExactly("o1", "o2", "o3", "o4");
        assertThat(selects()).isEqualTo(1);
        assertThat(orders.get(2).getCustomer()).isNull();
        assertThat(orders.get(0).getCustomer().getName()).isEqualTo("alice");
        assertThat(orders.get(0).getCustomer()).isSameAs(orders.get(1).getCustomer());
        assertThat(loaded(orders.get(0), "customer")).isTrue();
        assertThat(loaded(orders.get(0).getCustomer(), "address")).isFalse();
        assertThat(Customer.POST_LOADS.get()).isEqualTo(2);
    }

    @Test
    void criteriaGraphLoadsNestedToOnesWithConvertersAndSecondaryTables() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        var cb = em.getCriteriaBuilder();
        var criteria = cb.createQuery(Order.class);
        var root = criteria.from(Order.class);
        criteria.select(root).orderBy(cb.asc(root.get("id")));
        var orders = em.createQuery(criteria).setHint("jakarta.persistence.loadgraph", graph).getResultList();
        assertThat(orders).hasSize(4);
        assertThat(orders.get(2).getCustomer()).isNull();
        assertThat(orders.get(3).getCustomer().getAddress()).isNull();
        assertThat(orders.get(0).getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(orders.get(0).getCustomer().getAddress().getZip()).isEqualTo("75001");
        assertThat(loaded(orders.get(0).getCustomer(), "address")).isTrue();
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void loadGraphAddsOmittedEagerToOnesWhileFetchGraphMayOverfetchThemThroughTheLoader() {
        // §3.8.1: FETCH permits extra eager state; LOAD must retain the mapped eager contract.
        open();
        var graph = em.createEntityGraph(Line.class);
        Line fetched = em.createQuery("select l from FjLine l", Line.class)
            .setHint("jakarta.persistence.fetchgraph", graph).getSingleResult();
        assertThat(fetched.order.getLabel()).isEqualTo("o1");
        assertThat(loaded(fetched, "order")).isTrue();
        assertThat(selects()).isGreaterThan(1);
        em.clear();
        sql.clear();
        Line loaded = em.createQuery("select l from FjLine l", Line.class)
            .setHint("jakarta.persistence.loadgraph", graph).getSingleResult();
        assertThat(loaded.order.getLabel()).isEqualTo("o1");
        assertThat(loaded(loaded, "order")).isTrue();
        assertThat(loaded(loaded.order, "customer")).isFalse();
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void graphHydratesExistingLazyReferencesInPlaceAndPreservesDirtyState() {
        open();
        Customer reference = em.find(Order.class, 2L).getCustomer();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        sql.clear();
        var query = em.createQuery("select o from FjOrder o where o.id = 1", Order.class)
            .setHint("jakarta.persistence.fetchgraph", graph);
        var order = query.getSingleResult();
        assertThat(order.getCustomer()).isSameAs(reference);
        assertThat(reference.getName()).isEqualTo("alice");
        assertThat(loaded(order, "customer")).isTrue();
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().begin();
        reference.setName("dirty");
        sql.clear();
        assertThat(query.setFlushMode(FlushModeType.COMMIT).getSingleResult()).isSameAs(order);
        assertThat(reference.getName()).isEqualTo("dirty");
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().commit();
        assertThat(updates()).isEqualTo(1);
        em.clear();
        assertThat(em.find(Customer.class, 10L).getName()).isEqualTo("dirty");
    }

    @Test
    void graphMutationAndHintReplacementAreSeenOnEveryExecution() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        var query = em.createQuery("select o from FjOrder o where o.id = 1", Order.class)
            .setHint("jakarta.persistence.fetchgraph", graph);
        var order = query.getSingleResult();
        assertThat(loaded(order.getCustomer(), "address")).isFalse();
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        graph.addSubgraph("customer").addAttributeNodes("address");
        assertThat(query.getSingleResult().getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        query.setHint("jakarta.persistence.fetchgraph", em.createEntityGraph(Order.class));
        order = query.getSingleResult();
        assertThat(loaded(order, "customer")).isFalse();
        assertThat(selects()).isEqualTo(2); // unchanged identifier query + ordinary owner load
    }

    @Test
    void graphAndExplicitFetchJoinCoexistWithSqlPaginationAndFiltering() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        var orders = em.createQuery("select o from FjOrder o left join fetch o.customer c"
            + " where o.label like :p order by o.id desc", Order.class)
            .setParameter("p", "o%").setFirstResult(1).setMaxResults(2)
            .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
        assertThat(orders).extracting(Order::getLabel).containsExactly("o3", "o2");
        assertThat(orders.get(0).getCustomer()).isNull();
        assertThat(orders.get(1).getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void graphFallbacksRetainDistinctCollectionAndPolymorphicResultsAndFind() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer", "lines");
        var orders = em.createQuery("select distinct o from FjOrder o order by o.id", Order.class)
            .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
        assertThat(orders).hasSize(4);
        assertThat(orders.getFirst().getLines()).hasSize(1);
        assertThat(loaded(orders.getFirst(), "lines")).isTrue();
        assertThat(orders.getFirst().getCustomer().getName()).isEqualTo("alice");
        assertThat(selects()).isGreaterThan(1);
        em.clear();
        sql.clear();
        var shipments = em.createEntityGraph(Shipment.class);
        shipments.addAttributeNodes("vehicle");
        var shipment = em.createQuery("select s from FjShipment s", Shipment.class)
            .setHint("jakarta.persistence.loadgraph", shipments).getSingleResult();
        assertThat(shipment.getVehicle()).isInstanceOf(Car.class);
        assertThat(((Car) shipment.getVehicle()).brand).isEqualTo("fiat");
        assertThat(loaded(shipment, "vehicle")).isTrue();
        em.clear();
        sql.clear();
        assertThat(em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph))
            .getCustomer().getName()).isEqualTo("alice");
        assertThat(selects()).isGreaterThan(1);
    }

    @Test
    void graphHintValidationStillRejectsWrongTypesAndRoots() {
        open();
        var query = em.createQuery("select o from FjOrder o", Order.class);
        assertThatIllegalArgumentException().isThrownBy(() -> query.setHint("jakarta.persistence.fetchgraph", "bad"));
        assertThatIllegalArgumentException().isThrownBy(() ->
            query.setHint("jakarta.persistence.loadgraph", em.createEntityGraph(Customer.class)));
    }

    @Test
    void mixedGraphKeepsCollectionFollowUpCompleteWithoutMultiplyingRootRows() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer", "lines");
        var orders = em.createQuery("select o from FjOrder o order by o.id", Order.class)
            .setHint("jakarta.persistence.fetchgraph", graph).setMaxResults(2).getResultList();
        assertThat(orders).extracting(Order::getId).containsExactly(1L, 2L);
        assertThat(orders.getFirst().getLines()).hasSize(1);
        assertThat(orders.get(1).getLines()).isEmpty();
        assertThat(orders).allSatisfy(o -> {
            assertThat(loaded(o, "customer")).isTrue();
            assertThat(loaded(o, "lines")).isTrue();
        });
        assertThat(selects()).isEqualTo(4); // graph row query + collection keys/element + empty collection
    }

    @Test
    void graphHydrationKeepsXmlConvertersSnapshotsCallbacksAndCacheSafe() {
        open("orm/fetch-join.xml", SharedCacheMode.ALL);
        var graph = em.createEntityGraph(Order.class);
        graph.addSubgraph("customer").addAttributeNodes("address");
        em.getTransaction().begin();
        var orders = em.createQuery("select o from FjOrder o order by o.id", Order.class)
            .setHint("jakarta.persistence.loadgraph", graph).getResultList();
        assertThat(orders.getFirst().getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(orders.getFirst().getCustomer().getAddress().getZip()).isEqualTo("75001");
        assertThat(selects()).isEqualTo(1);
        assertThat(Customer.POST_LOADS.get()).isEqualTo(2);
        em.getTransaction().commit();
        assertThat(updates()).isZero();
        em.clear();
        sql.clear();
        assertThat(em.find(Order.class, 1L).getCustomer().getAddress().getCity()).isEqualTo("paris");
    }

    @Test
    void graphHintPrecedenceAndReplacementDoNotLeaveASqlPlanBehind() {
        open();
        var graph = em.createEntityGraph(Line.class);
        var query = em.createQuery("select l from FjLine l", Line.class)
            .setHint("jakarta.persistence.loadgraph", graph);
        assertThat(query.getSingleResult().order.getLabel()).isEqualTo("o1");
        assertThat(selects()).isEqualTo(1);
        em.clear();
        sql.clear();
        query.setHint("jakarta.persistence.fetchgraph", graph);
        assertThat(query.getSingleResult().order.getLabel()).isEqualTo("o1");
        assertThat(selects()).isEqualTo(3);
        em.clear();
        sql.clear();
        graph.addAttributeNodes("order");
        assertThat(query.getSingleResult().order.getLabel()).isEqualTo("o1");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void groupingAndMultiSelectionRemainOnTheCompleteFollowUpPath() {
        open();
        var graph = em.createEntityGraph(Order.class);
        graph.addAttributeNodes("customer");
        var rows = em.createQuery("select o, o.label from FjOrder o order by o.id", Object[].class)
            .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
        assertThat(rows).hasSize(4);
        assertThat(((Order) rows.getFirst()[0]).getCustomer().getName()).isEqualTo("alice");
        assertThat(loaded(rows.getFirst()[0], "customer")).isTrue();
        assertThat(selects()).isGreaterThan(1);
        em.clear();
        sql.clear();
        var orders = em.createQuery("select o from FjOrder o group by o order by o.id", Order.class)
            .setHint("jakarta.persistence.loadgraph", graph).getResultList();
        assertThat(orders).hasSize(4);
        assertThat(orders.getFirst().getCustomer().getName()).isEqualTo("alice");
        assertThat(selects()).isGreaterThan(1);
    }

    @Test
    void jpqlInnerFetchJoinLoadsOwnersAndSharedTargetsFromOneSelectAndExcludesMissingTargets() {
        open();
        List<Order> orders = em.createQuery("select o from FjOrder o join fetch o.customer order by o.id", Order.class)
            .getResultList();
        assertThat(orders).extracting(Order::getLabel).containsExactly("o1", "o2", "o4");
        assertThat(selects()).isEqualTo(1);
        assertThat(orders).allSatisfy(o -> assertThat(loaded(o, "customer")).isTrue());
        assertThat(orders.get(0).getCustomer()).isSameAs(orders.get(1).getCustomer()).isSameAs(em.find(Customer.class, 10L));
        assertThat(orders.get(0).getCustomer().getName()).isEqualTo("alice");
        assertThat(orders.get(2).getCustomer().getName()).isEqualTo("bob");
        assertThat(factory.getPersistenceUnitUtil().isLoaded(orders.get(0).getCustomer())).isTrue();
        assertThat(Customer.POST_LOADS.get()).isEqualTo(2);
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void jpqlLeftFetchJoinKeepsOwnersWithoutTargetAsNull() {
        open();
        List<Order> orders = em.createQuery("select o from FjOrder o left join fetch o.customer order by o.id", Order.class)
            .getResultList();
        assertThat(orders).extracting(Order::getLabel).containsExactly("o1", "o2", "o3", "o4");
        assertThat(orders.get(2).getCustomer()).isNull();
        assertThat(orders.get(3).getCustomer().getName()).isEqualTo("bob");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void nestedFetchJoinsHydrateTheWholeChainWithSecondaryTablesAndConverters() {
        open();
        List<Order> orders = em.createQuery("select o from FjOrder o left join fetch o.customer c left join fetch c.address"
            + " order by o.id", Order.class).getResultList();
        assertThat(orders).hasSize(4);
        Address address = orders.get(0).getCustomer().getAddress();
        assertThat(address.getCity()).isEqualTo("paris");
        assertThat(address.getZip()).isEqualTo("75001");
        assertThat(orders.get(3).getCustomer().getAddress()).isNull();
        assertThat(loaded(orders.get(0).getCustomer(), "address")).isTrue();
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void criteriaFetchesUseTheSameSingleSelect() {
        open();
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(Order.class);
        var root = query.from(Order.class);
        root.fetch("customer", JoinType.LEFT).fetch("address", JoinType.LEFT);
        query.select(root).orderBy(builder.asc(root.get("id")));
        List<Order> orders = em.createQuery(query).getResultList();
        assertThat(orders).extracting(Order::getLabel).containsExactly("o1", "o2", "o3", "o4");
        assertThat(orders.get(0).getCustomer().getAddress().getCity()).isEqualTo("paris");
        assertThat(orders.get(2).getCustomer()).isNull();
        assertThat(selects()).isEqualTo(1);

        sql.clear();
        em.clear();
        var inner = builder.createQuery(Order.class);
        var innerRoot = inner.from(Order.class);
        innerRoot.fetch("customer");
        inner.select(innerRoot).orderBy(builder.asc(innerRoot.get("id")));
        assertThat(em.createQuery(inner).getResultList()).extracting(Order::getLabel).containsExactly("o1", "o2", "o4");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void anExistingUnloadedReferenceIsHydratedInPlace() {
        open();
        Customer reference = em.find(Order.class, 2L).getCustomer();
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isFalse();
        sql.clear();
        Order order = em.createQuery("select o from FjOrder o join fetch o.customer where o.id = :id", Order.class)
            .setParameter("id", 1L).getSingleResult();
        assertThat(order.getCustomer()).isSameAs(reference);
        assertThat(factory.getPersistenceUnitUtil().isLoaded(reference)).isTrue();
        assertThat(reference.getName()).isEqualTo("alice");
        assertThat(Customer.POST_LOADS.get()).isEqualTo(1);
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void aManagedModifiedTargetIsNotOverwrittenAndOnlyItsChangeIsFlushed() {
        open();
        em.getTransaction().begin();
        Customer alice = em.find(Customer.class, 10L);
        alice.setName("changed");
        sql.clear();
        Order order = em.createQuery("select o from FjOrder o join fetch o.customer where o.id = 1", Order.class)
            .getSingleResult();
        assertThat(order.getCustomer()).isSameAs(alice);
        assertThat(alice.getName()).isEqualTo("changed");
        assertThat(selects()).isEqualTo(1);
        em.getTransaction().commit();
        assertThat(updates()).isEqualTo(1);
        em.clear();
        assertThat(em.find(Customer.class, 10L).getName()).isEqualTo("changed");
        assertThat(em.find(Customer.class, 10L).getVersion()).isEqualTo(1);
    }

    @Test
    void hydratedEntitiesHaveCleanSnapshotsAndVersions() {
        open();
        em.getTransaction().begin();
        List<Order> orders = em.createQuery("select o from FjOrder o left join fetch o.customer c left join fetch c.address"
            + " order by o.id", Order.class).getResultList();
        assertThat(orders).hasSize(4);
        assertThat(orders.get(0).getCustomer().getVersion()).isZero();
        sql.clear();
        em.getTransaction().commit();
        assertThat(updates()).isZero();
        em.getTransaction().begin();
        orders.get(0).getCustomer().setName("alice2");
        em.getTransaction().commit();
        em.clear();
        assertThat(em.find(Customer.class, 10L).getVersion()).isEqualTo(1);
    }

    @Test
    void paginationParametersAndOrderingAreUnchanged() {
        open();
        List<Order> orders = em.createQuery("select o from FjOrder o left join fetch o.customer where o.label like :p"
            + " order by o.id desc", Order.class).setParameter("p", "o%").setFirstResult(1).setMaxResults(2).getResultList();
        assertThat(orders).extracting(Order::getLabel).containsExactly("o3", "o2");
        assertThat(orders.get(1).getCustomer().getName()).isEqualTo("alice");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void compositeIdentifiersAndSingleTableLeavesAreHydrated() {
        open();
        Shipment order = em.createQuery("select s from FjShipment s join fetch s.part join fetch s.car where s.id = 1",
            Shipment.class).getSingleResult();
        assertThat(order.getPart().name).isEqualTo("bolt");
        assertThat(order.getCar().brand).isEqualTo("fiat");
        assertThat(order.getCar()).isSameAs(em.find(Vehicle.class, 200L));
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void xmlMappedEagerToOneIsHydratedByItsFetchJoin() {
        open("orm/fetch-join.xml");
        Order order = em.createQuery("select o from FjOrder o join fetch o.customer where o.id = 4", Order.class)
            .getSingleResult();
        assertThat(order.getCustomer().getName()).isEqualTo("bob");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void ineligibleShapesKeepTheirCorrectFollowUpLoading() {
        open();
        Shipment polymorphic = em.createQuery("select s from FjShipment s join fetch s.vehicle where s.id = 1",
            Shipment.class).getSingleResult();
        assertThat(polymorphic.getVehicle()).isInstanceOf(Car.class);
        assertThat(((Car) polymorphic.getVehicle()).brand).isEqualTo("fiat");
        assertThat(selects()).isGreaterThan(1); // a target with subclasses is found by identifier, as before
        em.clear();
        List<Order> distinct = em.createQuery("select distinct o from FjOrder o left join fetch o.customer order by o.id",
            Order.class).getResultList();
        assertThat(distinct).hasSize(4);
        assertThat(distinct.get(0).getCustomer().getName()).isEqualTo("alice");
        em.clear();
        List<Order> withLines = em.createQuery("select distinct o from FjOrder o left join fetch o.lines where o.id = 1",
            Order.class).getResultList();
        assertThat(withLines).hasSize(1);
        assertThat(loaded(withLines.getFirst(), "lines")).isTrue();
        assertThat(withLines.getFirst().getLines()).extracting(l -> l.id).containsExactly(1000L);
    }

    @Test
    void hydratedEntitiesAreStoredInTheSharedCacheLikeFoundOnes() {
        open(null, SharedCacheMode.ALL);
        em.createQuery("select o from FjOrder o join fetch o.customer where o.id = 4", Order.class).getSingleResult();
        assertThat(selects()).isEqualTo(1);
        assertThat(factory.getCache().contains(Customer.class, 11L)).isTrue();
        assertThat(factory.getCache().contains(Order.class, 4L)).isTrue();
        em.clear();
        sql.clear();
        assertThat(em.find(Customer.class, 11L).getName()).isEqualTo("bob");
        assertThat(selects()).isZero();
    }

    @Test
    void aPlainJoinDoesNotFetch() {
        open();
        List<Order> orders = em.createQuery("select o from FjOrder o join o.customer c where c.name = 'alice' order by o.id",
            Order.class).getResultList();
        assertThat(orders).hasSize(2);
        assertThat(loaded(orders.getFirst(), "customer")).isFalse();
        assertThat(selects()).isEqualTo(3); // unchanged: the query, then each owner by its key

    }

    @Test
    void scalarAndMultiItemSelectsStillResolve() {
        open();
        List<Object[]> rows = em.createQuery("select o, o.label from FjOrder o join fetch o.customer where o.id = 2",
            Object[].class).getResultList();
        assertThat(rows).hasSize(1);
        assertThat(((Order) rows.getFirst()[0]).getCustomer().getName()).isEqualTo("alice");
        assertThat(rows.getFirst()[1]).isEqualTo("o2");
        assertThat(selects()).isEqualTo(1);
    }

    @Entity(name = "FjOrder")
    @Table(name = "FJ_ORDER")
    public static class Order {
        private long id;
        private String label;
        private Customer customer;
        private List<Line> lines = new ArrayList<>();
        public Order() {}
        Order(long id, String label, Customer customer) {
            this.id = id;
            this.label = label;
            this.customer = customer;
        }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "customer_id")
        public Customer getCustomer() { return customer; }
        public void setCustomer(Customer customer) { this.customer = customer; }
        @OneToMany(mappedBy = "order")
        public List<Line> getLines() { return lines; }
        public void setLines(List<Line> lines) { this.lines = lines; }
    }

    @Entity(name = "FjShipment")
    @Table(name = "FJ_SHIPMENT")
    public static class Shipment {
        private long id;
        private Part part;
        private Car car;
        private Vehicle vehicle;
        public Shipment() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "part_code", referencedColumnName = "code")
        @JoinColumn(name = "part_rev", referencedColumnName = "rev")
        public Part getPart() { return part; }
        public void setPart(Part part) { this.part = part; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "car_id")
        public Car getCar() { return car; }
        public void setCar(Car car) { this.car = car; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "vehicle_id")
        public Vehicle getVehicle() { return vehicle; }
        public void setVehicle(Vehicle vehicle) { this.vehicle = vehicle; }
    }

    @Entity(name = "FjCompositeOrder")
    @Table(name = "FJ_COMPOSITE_ORDER")
    public static class CompositeOrder {
        @EmbeddedId PartKey key;
        @ManyToOne(fetch = FetchType.LAZY) Customer customer;
        public CompositeOrder() {}
    }

    @Entity(name = "FjCustomer")
    @Table(name = "FJ_CUSTOMER")
    public static class Customer {
        static final AtomicInteger POST_LOADS = new AtomicInteger();
        private long id;
        private String name;
        private long version;
        private Address address;
        public Customer() {}
        Customer(long id, String name, Address address) {
            this.id = id;
            this.name = name;
            this.address = address;
        }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        @Version public long getVersion() { return version; }
        public void setVersion(long version) { this.version = version; }
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "address_id")
        public Address getAddress() { return address; }
        public void setAddress(Address address) { this.address = address; }
        @PostLoad void loaded() { POST_LOADS.incrementAndGet(); }
    }

    @Entity(name = "FjAddress")
    @Table(name = "FJ_ADDRESS")
    @SecondaryTable(name = "FJ_ADDRESS_EXT", pkJoinColumns = @PrimaryKeyJoinColumn(name = "address_key"))
    public static class Address {
        private long id;
        private String city;
        private String zip;
        public Address() {}
        Address(long id, String city, String zip) {
            this.id = id;
            this.city = city;
            this.zip = zip;
        }
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @Convert(converter = CityConverter.class) public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        @Column(table = "FJ_ADDRESS_EXT") public String getZip() { return zip; }
        public void setZip(String zip) { this.zip = zip; }
    }

    @Converter
    public static class CityConverter implements AttributeConverter<String, String> {
        public CityConverter() {}
        @Override public String convertToDatabaseColumn(String city) { return city == null ? null : "city:" + city; }
        @Override public String convertToEntityAttribute(String column) { return column == null ? null : column.substring(5); }
    }

    @Embeddable
    public static class PartKey implements Serializable {
        String code;
        int rev;
        public PartKey() {}
        PartKey(String code, int rev) {
            this.code = code;
            this.rev = rev;
        }
        @Override public boolean equals(Object other) {
            return other instanceof PartKey key && Objects.equals(code, key.code) && rev == key.rev;
        }
        @Override public int hashCode() { return Objects.hash(code, rev); }
    }

    @Entity(name = "FjPart")
    @Table(name = "FJ_PART")
    public static class Part {
        @EmbeddedId PartKey key;
        String name;
        public Part() {}
        Part(PartKey key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    @Entity(name = "FjVehicle")
    @Table(name = "FJ_VEHICLE")
    @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
    @DiscriminatorValue("V")
    public static class Vehicle {
        @Id long id;
        public Vehicle() {}
    }

    @Entity(name = "FjCar")
    @DiscriminatorValue("C")
    public static class Car extends Vehicle {
        String brand;
        public Car() {}
        Car(long id, String brand) {
            this.id = id;
            this.brand = brand;
        }
    }

    @Entity(name = "FjLine")
    @Table(name = "FJ_LINE")
    public static class Line {
        @Id long id;
        @ManyToOne @JoinColumn(name = "order_id") Order order;
        public Line() {}
        Line(long id, Order order) {
            this.id = id;
            this.order = order;
        }
    }
}
