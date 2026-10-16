/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** §11.1.25: referenced columns need not be the target's JPA identity. */
class BusinessKeyToOneTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Entity(name = "BusinessCustomer")
    public static class Customer {
        @Id public long id;
        @Column(unique = true, nullable = false, updatable = false) public String code;
        public Customer() {}
        Customer(long id, String code) { this.id = id; this.code = code; }
    }

    @Entity(name = "BusinessOrder")
    public static class Order {
        @Id public long id;
        @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
        @JoinColumn(name = "customer_code", referencedColumnName = "code")
        public Customer customer;
        public Order() {}
        Order(long id, Customer customer) { this.id = id; this.customer = customer; }
    }

    private EntityManagerFactory factory(Class<?>... classes) {
        return factory(false, classes);
    }

    private EntityManagerFactory factory(boolean cache, Class<?>... classes) {
        var configuration = new PersistenceConfiguration("business-key")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:business-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create")
            .sharedCacheMode(cache ? SharedCacheMode.ALL : SharedCacheMode.NONE);
        for (var type : classes.length == 0 ? new Class<?>[]{Customer.class, Order.class} : classes) configuration.managedClass(type);
        return configuration.createEntityManagerFactory();
    }

    @Test
    void writesBusinessValueAndResolvesActualIdentity() throws Exception {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Order(10, new Customer(1, "C-ONE")));
            em.persist(new Order(11, new Customer(2, "C-TWO")));
            em.persist(new Order(12, null));
            em.getTransaction().commit();
            try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                 var query = db.createStatement(); var row = query.executeQuery("select customer_code from BusinessOrder where id=10")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("C-ONE");
            }
            em.clear();
            var customer = em.find(Customer.class, 1L);
            var order = em.find(Order.class, 10L);
            assertThat(order.customer).isSameAs(customer);
            assertThat(em.find(Order.class, 12L).customer).isNull();
            assertThat(em.createQuery("select o.customer from BusinessOrder o where o.customer = :c", Customer.class)
                .setParameter("c", customer).getSingleResult()).isSameAs(customer);
            assertThat(em.createQuery("select o from BusinessOrder o join o.customer c where c.code = 'C-ONE'", Order.class)
                .getSingleResult()).isSameAs(order);
            em.getTransaction().begin();
            order.customer = em.find(Customer.class, 2L);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Order.class, 10L).customer.id).isEqualTo(2);
            em.getTransaction().begin();
            em.find(Order.class, 10L).customer = null;
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Order.class, 10L).customer).isNull();
        }
    }

        @Entity(name = "BusinessPart")
        @Table(uniqueConstraints = @UniqueConstraint(columnNames = {"part_code", "revision"}))
        public static class Part {
            @Id public long id;
            @Column(name = "part_code", nullable = false, updatable = false) public String code;
            @Column(nullable = false, updatable = false) public int revision;
            @OneToOne(mappedBy = "part") public Item item;
            public Part() {}
            Part(long id, String code, int revision) { this.id = id; this.code = code; this.revision = revision; }
        }

        @Entity(name = "BusinessItem")
        public static class Item {
            @Id public long id;
            @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
            @JoinColumns({
                @JoinColumn(name = "part_revision", referencedColumnName = "revision"),
                @JoinColumn(name = "part_business_code", referencedColumnName = "part_code")
            })
            public Part part;
            public Item() {}
            Item(long id, Part part) { this.id = id; this.part = part; }
        }

        @Test
        void compositeReorderedColumnsOneToOneInverseOrphanAndCriteria() throws Exception {
            try (var factory = factory(Part.class, Item.class); var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Item(7, new Part(99, "A", 3)));
                em.getTransaction().commit();
                try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                     var query = db.createStatement(); var row = query.executeQuery("select part_revision, part_business_code from BusinessItem")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(3);
                    assertThat(row.getString(2)).isEqualTo("A");
                }
                em.clear();
                var part = em.find(Part.class, 99L);
                assertThat(part.item.part).isSameAs(part);
                var cb = em.getCriteriaBuilder();
                var query = cb.createQuery(Item.class);
                var root = query.from(Item.class);
                query.select(root).where(cb.equal(root.join("part").get("code"), "A"));
                assertThat(em.createQuery(query).getSingleResult()).isSameAs(part.item);
                assertThat(em.createQuery("select i.part.id from BusinessItem i where i.part = :p", Long.class)
                    .setParameter("p", part).getSingleResult()).isEqualTo(99L);
                em.getTransaction().begin();
                part.item.part = null;
                em.getTransaction().commit();
                assertThat(em.find(Part.class, 99L)).isNull();
                em.clear();
                assertThat(em.find(Item.class, 7L).part).isNull();
            }
        }

        @Test
        void bulkReassignmentNullComparisonAndDeleteUseIdentityNotBusinessValue() {
            try (var factory = factory(); var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Order(10, new Customer(1, "FIRST")));
                em.persist(new Order(11, new Customer(2, "SECOND")));
                em.persist(new Order(12, null));
                em.getTransaction().commit();
                var second = em.find(Customer.class, 2L);
                em.getTransaction().begin();
                assertThat(em.createQuery("update BusinessOrder o set o.customer = :c where o.id = 10")
                    .setParameter("c", second).executeUpdate()).isEqualTo(1);
                em.getTransaction().commit();
                em.clear();
                assertThat(em.find(Order.class, 10L).customer.id).isEqualTo(2);
                assertThat(em.createQuery("select o from BusinessOrder o where o.customer is null", Order.class).getResultList())
                    .extracting(o -> o.id).containsExactly(12L);
                em.getTransaction().begin();
                assertThat(em.createQuery("update BusinessOrder o set o.customer = null where o.customer = :c")
                    .setParameter("c", second).executeUpdate()).isEqualTo(2);
                assertThat(em.createQuery("delete from BusinessOrder o where o.customer is null").executeUpdate()).isEqualTo(3);
                em.getTransaction().commit();
            }
        }

        @Test
        void bulkAssignmentOfAnUnknownIdentityMustNotSilentlyClearTheForeignKey() {
            try (var factory = factory(); var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Order(1, new Customer(42, "EXISTING")));
                em.getTransaction().commit();
                em.getTransaction().begin();
                assertThatThrownBy(() -> em.createQuery("update BusinessOrder o set o.customer = :c where o.id = 1")
                    .setParameter("c", new Customer(999, "MISSING")).executeUpdate())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("identity");
                em.getTransaction().rollback();
                em.clear();
                em.getTransaction().begin();
                var pending = new Customer(999, "NOT-INSERTED");
                em.persist(pending);
                assertThatThrownBy(() -> em.createQuery("update BusinessOrder o set o.customer = :c where o.id = 1")
                    .setFlushMode(jakarta.persistence.FlushModeType.COMMIT)
                    .setParameter("c", pending).executeUpdate())
                    .isInstanceOf(PersistenceException.class).hasMessageContaining("identity");
                em.getTransaction().rollback();
                em.clear();
                assertThat(em.find(Order.class, 1L).customer.id).isEqualTo(42);
            }
        }

        @Test
        void criteriaAssociationProjectionComparisonAndMutationUseJpaIdentity() {
            try (var factory = factory(); var em = factory.createEntityManager()) {
                var first = new Customer(1, "ONE");
                var second = new Customer(2, "TWO");
                em.getTransaction().begin();
                em.persist(new Order(1, first));
                em.persist(second);
                em.getTransaction().commit();
                var cb = em.getCriteriaBuilder();
                var select = cb.createQuery(Customer.class);
                var order = select.from(Order.class);
                select.select(order.get("customer")).where(cb.equal(order.get("customer"), first));
                assertThat(em.createQuery(select).getSingleResult()).isSameAs(first);
                em.getTransaction().begin();
                var update = cb.createCriteriaUpdate(Order.class);
                var root = update.from(Order.class);
                update.set(root.<Customer>get("customer"), second).where(cb.equal(root.get("customer"), first));
                assertThat(em.createQuery(update).executeUpdate()).isEqualTo(1);
                em.getTransaction().commit();
                em.clear();
                assertThat(em.find(Order.class, 1L).customer.id).isEqualTo(2);
            }
        }

        @Entity(name = "BusinessNode")
        public static class Node {
            @Id public long id;
            @Column(unique = true, nullable = false, updatable = false) public String code;
            @ManyToOne(cascade = CascadeType.PERSIST)
            @JoinColumn(name = "parent_code", referencedColumnName = "code") public Node parent;
            public Node() {}
            Node(long id, String code) { this.id = id; this.code = code; }
        }

        @Entity(name = "StrictBusinessNode")
        public static class StrictNode {
            @Id public long id;
            @Column(unique = true, nullable = false, updatable = false) public String code;
            @ManyToOne(cascade = CascadeType.PERSIST, optional = false)
            @JoinColumn(name = "parent_code", referencedColumnName = "code", nullable = false) public StrictNode parent;
            public StrictNode() {}
            StrictNode(long id, String code) { this.id = id; this.code = code; }
        }

        @Test
        void nullableBusinessKeyCyclesUseDeferredForeignKeyWritesAndCloseOnIdentity() {
            try (var factory = factory(Node.class); var em = factory.createEntityManager()) {
                var first = new Node(1, "FIRST");
                var second = new Node(2, "SECOND");
                first.parent = second; second.parent = first;
                em.getTransaction().begin(); em.persist(first); em.getTransaction().commit();
                em.clear();
                var loaded = em.find(Node.class, 1L);
                assertThat(loaded.parent.id).isEqualTo(2);
                assertThat(loaded.parent.parent).isSameAs(loaded);
            }
        }

        @Test
        void nonNullBusinessDependenciesAreOrderedByInstanceIncludingSelfReferences() {
            try (var factory = factory(StrictNode.class); var em = factory.createEntityManager()) {
                var child = new StrictNode(1, "CHILD");
                var parent = new StrictNode(2, "PARENT");
                child.parent = parent; parent.parent = parent;
                em.getTransaction().begin(); em.persist(child); em.getTransaction().commit();
                em.clear();
                assertThat(em.find(StrictNode.class, 1L).parent).isSameAs(em.find(StrictNode.class, 2L));
                assertThat(em.find(StrictNode.class, 2L).parent).isSameAs(em.find(StrictNode.class, 2L));
                em.getTransaction().begin();
                em.remove(em.find(StrictNode.class, 1L));
                em.remove(em.find(StrictNode.class, 2L));
                em.getTransaction().commit();
                assertThat(em.find(StrictNode.class, 1L)).isNull();
                assertThat(em.find(StrictNode.class, 2L)).isNull();
            }
        }

        @Test
        void nonNullBusinessKeyCyclesAreRefusedBeforeAttemptingNullForeignKeys() {
            try (var factory = factory(StrictNode.class); var em = factory.createEntityManager()) {
                var first = new StrictNode(1, "FIRST");
                var second = new StrictNode(2, "SECOND");
                first.parent = second; second.parent = first;
                em.getTransaction().begin(); em.persist(first);
                assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("non-null business-key cycle");
                em.getTransaction().rollback();
            }
        }

        @Entity(name = "BusinessGraphCustomer")
        public static class GraphCustomer {
            @Id public long id;
            @Column(unique = true, nullable = false, updatable = false) public String code;
            @ElementCollection public java.util.List<String> tags = new java.util.ArrayList<>();
            public GraphCustomer() {}
        }
        @Entity(name = "BusinessGraphOrder")
        public static class GraphOrder {
            @Id public long id;
            @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
            @JoinColumn(referencedColumnName = "code") public GraphCustomer customer;
            public GraphOrder() {}
        }

        @Test
        void rootBusinessToOneCollectionGraphsRetainCompleteOrdinaryLoading() {
            try (var factory = factory(GraphCustomer.class, GraphOrder.class); var em = factory.createEntityManager()) {
                var customer = new GraphCustomer(); customer.id = 42; customer.code = "SHARED"; customer.tags.add("tag");
                var first = new GraphOrder(); first.id = 1; first.customer = customer;
                var second = new GraphOrder(); second.id = 2; second.customer = customer;
                em.getTransaction().begin(); em.persist(first); em.persist(second); em.getTransaction().commit();
                em.clear();
                var graph = em.createEntityGraph(GraphOrder.class);
                graph.addSubgraph("customer").addAttributeNodes("tags");
                var orders = em.createQuery("select o from BusinessGraphOrder o order by o.id", GraphOrder.class)
                    .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
                assertThat(orders.getFirst().customer).isSameAs(orders.getLast().customer).isSameAs(em.find(GraphCustomer.class, 42L));
                assertThat(orders.getFirst().customer.tags).containsExactly("tag");
                assertThat(factory.getPersistenceUnitUtil().isLoaded(orders.getFirst().customer, "tags")).isTrue();
                em.clear();
                var found = em.find(GraphOrder.class, 1L, java.util.Map.of("jakarta.persistence.fetchgraph", graph));
                assertThat(found.customer.tags).containsExactly("tag");
            }
        }

        @Entity(name = "PropertyBusinessCustomer")
        public static class PropertyCustomer {
            private long id;
            private String code;
            public PropertyCustomer() {}
            @Id public long getId() { return id; }
            public void setId(long id) { this.id = id; }
            @Column(unique = true, nullable = false, updatable = false) public String getCode() { return code; }
            public void setCode(String code) { this.code = code; }
        }
        @Entity(name = "PropertyBusinessOrder")
        public static class PropertyOrder {
            private long id;
            private PropertyCustomer customer;
            public PropertyOrder() {}
            @Id public long getId() { return id; }
            public void setId(long id) { this.id = id; }
            @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
            @JoinColumn(referencedColumnName = "code") public PropertyCustomer getCustomer() { return customer; }
            public void setCustomer(PropertyCustomer customer) { this.customer = customer; }
        }

        @Test
        void otherwiseInterceptableLazyPropertiesUseSafeEagerBusinessLookup() {
            try (var factory = factory(PropertyCustomer.class, PropertyOrder.class); var em = factory.createEntityManager()) {
                var customer = new PropertyCustomer(); customer.setId(42); customer.setCode("BUSINESS");
                var order = new PropertyOrder(); order.setId(1); order.setCustomer(customer);
                em.getTransaction().begin(); em.persist(order); em.getTransaction().commit();
                em.clear();
                var loaded = em.find(PropertyOrder.class, 1L);
                assertThat(factory.getPersistenceUnitUtil().isLoaded(loaded, "customer")).isTrue();
                assertThat(loaded.getCustomer().getId()).isEqualTo(42);
                assertThat(loaded.getCustomer()).isSameAs(em.find(PropertyCustomer.class, 42L));
            }
        }

        @Entity(name = "GeneratedBusinessCustomer")
        public static class GeneratedCustomer {
            @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
            @Column(unique = true, nullable = false, updatable = false) public String code;
            public GeneratedCustomer() {}
        }
        @Entity(name = "GeneratedBusinessOrder")
        public static class GeneratedOrder {
            @Id public long id;
            @ManyToOne(cascade = CascadeType.PERSIST)
            @JoinColumn(referencedColumnName = "code") public GeneratedCustomer customer;
            public GeneratedOrder() {}
        }

        @Test
        void generatedIdentityIsSeparateFromTheAssignedBusinessTuple() {
            try (var factory = factory(GeneratedCustomer.class, GeneratedOrder.class); var em = factory.createEntityManager()) {
                var customer = new GeneratedCustomer(); customer.code = "ASSIGNED";
                var order = new GeneratedOrder(); order.id = 99; order.customer = customer;
                em.getTransaction().begin(); em.persist(order); em.getTransaction().commit();
                Long id = customer.id;
                assertThat(id).isNotNull();
                em.clear();
                assertThat(em.find(GeneratedOrder.class, 99L).customer).isSameAs(em.find(GeneratedCustomer.class, id));
            }
        }

        @Test
        void mergeAndRefreshResolveTargetsByActualIdentity() throws Exception {
            try (var factory = factory(); var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Order(1, new Customer(42, "ONE")));
                em.persist(new Customer(43, "TWO"));
                em.getTransaction().commit();
                em.clear();
                em.getTransaction().begin();
                var merged = em.merge(new Order(1, new Customer(42, "IGNORED-DETACHED-STATE")));
                assertThat(merged.customer).isSameAs(em.find(Customer.class, 42L));
                assertThat(merged.customer.code).isEqualTo("ONE");
                em.getTransaction().commit();
                try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                     var query = db.createStatement()) {
                    query.executeUpdate("update BusinessOrder set customer_code = 'TWO' where id=1");
                }
                em.refresh(merged);
                assertThat(merged.customer).isSameAs(em.find(Customer.class, 43L));
            }
        }

        @Test
        void compositeBulkAssignmentBindsTheReorderedTupleAndDoesNotCascadeOrphans() {
            try (var factory = factory(Part.class, Item.class); var em = factory.createEntityManager()) {
                var first = new Part(42, "ONE", 1);
                var second = new Part(43, "TWO", 2);
                em.getTransaction().begin();
                em.persist(new Item(1, first)); em.persist(second);
                em.getTransaction().commit();
                em.getTransaction().begin();
                assertThat(em.createQuery("update BusinessItem i set i.part = :newPart where i.part = :oldPart")
                    .setParameter("newPart", second).setParameter("oldPart", first).executeUpdate()).isEqualTo(1);
                em.getTransaction().commit();
                em.clear();
                assertThat(em.find(Item.class, 1L).part).isSameAs(em.find(Part.class, 43L));
                assertThat(em.find(Part.class, 43L).item).isSameAs(em.find(Item.class, 1L));
                assertThat(em.find(Part.class, 42L)).isNotNull();
                assertThat(em.find(Part.class, 42L).item).isNull();
            }
        }

        @Test
        void graphFetchAndRepeatedIdentityRemainCorrectWithOrdinaryFallback() {
            try (var factory = factory(); var em = factory.createEntityManager()) {
                var customer = new Customer(42, "BUSINESS");
                em.getTransaction().begin();
                em.persist(new Order(1, customer));
                em.persist(new Order(2, customer));
                em.getTransaction().commit();
                em.clear();
                var graph = em.createEntityGraph(Order.class);
                graph.addAttributeNodes("customer");
                var orders = em.createQuery("select o from BusinessOrder o order by o.id", Order.class)
                    .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
                assertThat(orders).hasSize(2);
                assertThat(orders.getFirst().customer).isSameAs(orders.getLast().customer).isSameAs(em.find(Customer.class, 42L));
                em.clear();
                var fetched = em.createQuery("select o from BusinessOrder o left join fetch o.customer order by o.id", Order.class).getResultList();
                assertThat(fetched.getFirst().customer).isSameAs(fetched.getLast().customer);
                assertThat(factory.getPersistenceUnitUtil().isLoaded(fetched.getFirst(), "customer")).isTrue();
            }
        }

        @Test
        void referencedValuesAreImmutableEvenWhenTheColumnIsNotUpdatable() {
            try (var factory = factory(); var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Order(1, new Customer(42, "ORIGINAL")));
                em.getTransaction().commit();
                em.getTransaction().begin();
                em.find(Customer.class, 42L).code = "CHANGED";
                assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("immutable");
                em.getTransaction().rollback();
                assertThatThrownBy(() -> em.createQuery("update BusinessCustomer c set c.code = 'CHANGED'").executeUpdate())
                    .isInstanceOf(RuntimeException.class);
            }
        }

        public record Code(String value) {}
        @Converter
        public static class CodeConverter implements AttributeConverter<Code, String> {
            public String convertToDatabaseColumn(Code value) { return value == null ? null : "db:" + value.value(); }
            public Code convertToEntityAttribute(String value) { return value == null ? null : new Code(value.substring(3)); }
        }

        @Entity(name = "ConvertedCustomer")
        public static class ConvertedCustomer {
            @Id public long id;
            @Convert(converter = CodeConverter.class)
            @Column(unique = true, nullable = false, updatable = false) public Code code;
            public ConvertedCustomer() {}
        }
        @Entity(name = "ConvertedOrder")
        public static class ConvertedOrder {
            @Id public long id;
            @ManyToOne(cascade = CascadeType.PERSIST)
            @JoinColumn(name = "customer_code", referencedColumnName = "code") public ConvertedCustomer customer;
            public ConvertedOrder() {}
        }

        @Test
        void xmlOverlaysSingleAndCompositeBusinessKeysAndConstraints() throws Exception {
            var configuration = new PersistenceConfiguration("xml-business")
                .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
                .managedClass(Customer.class).managedClass(Order.class).managedClass(Part.class).managedClass(Item.class)
                .mappingFile("orm/business-keys.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:business-xml-" + DATABASES.incrementAndGet())
                .property(PersistenceConfiguration.JDBC_USER, "sa")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
            try (var factory = configuration.createEntityManagerFactory(); var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Order(1, new Customer(55, "XML")));
                em.persist(new Item(2, new Part(66, "PART", 9)));
                em.getTransaction().commit();
                em.clear();
                assertThat(em.find(Order.class, 1L).customer).isSameAs(em.find(Customer.class, 55L));
                assertThat(em.find(Item.class, 2L).part).isSameAs(em.find(Part.class, 66L));
                try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                     var keys = db.getMetaData().getImportedKeys(null, null, "XML_ITEM")) {
                    var referenced = new java.util.ArrayList<String>();
                    while (keys.next()) referenced.add(keys.getString("PKCOLUMN_NAME"));
                    assertThat(referenced).containsExactlyInAnyOrder("XML_CODE", "XML_REVISION");
                }
                assertThat(em.createQuery("select i from BusinessItem i join i.part p where p.code = 'PART'", Item.class)
                    .getSingleResult()).isSameAs(em.find(Item.class, 2L));
            }
        }

        @Entity
        public static class InvalidTarget {
            @Id public long id;
            @Column(nullable = false, updatable = false) public String code;
            @Column(unique = true, nullable = false) public String mutableCode;
            @Column(unique = true, updatable = false) public String nullableCode;
            public InvalidTarget() {}
        }
        @Entity
        public static class NonUniqueOwner {
            @Id public long id;
            @ManyToOne @JoinColumn(referencedColumnName = "code") public InvalidTarget target;
            public NonUniqueOwner() {}
        }
        @Entity
        public static class MissingOwner {
            @Id public long id;
            @ManyToOne @JoinColumn(referencedColumnName = "missing") public InvalidTarget target;
            public MissingOwner() {}
        }
        @Entity
        public static class MutableOwner {
            @Id public long id;
            @ManyToOne @JoinColumn(referencedColumnName = "mutableCode") public InvalidTarget target;
            public MutableOwner() {}
        }
        @Entity
        public static class NullableOwner {
            @Id public long id;
            @ManyToOne @JoinColumn(referencedColumnName = "nullableCode") public InvalidTarget target;
            public NullableOwner() {}
        }
        @Entity
        public static class MixedOwner {
            @Id public long id;
            @ManyToOne @JoinColumns({@JoinColumn(referencedColumnName = "id"), @JoinColumn(referencedColumnName = "code")})
            public Customer target;
            public MixedOwner() {}
        }
        @Entity
        public static class DuplicateOwner {
            @Id public long id;
            @ManyToOne @JoinColumns({@JoinColumn(referencedColumnName = "code"), @JoinColumn(referencedColumnName = "code")})
            public Customer target;
            public DuplicateOwner() {}
        }
        @Entity
        public static class UnnamedOwner {
            @Id public long id;
            @ManyToOne @JoinColumns({@JoinColumn(referencedColumnName = "code"), @JoinColumn})
            public Customer target;
            public UnnamedOwner() {}
        }

        @Entity
        public static class NonUniformOwner {
            @Id public long id;
            @ManyToOne @JoinColumns({@JoinColumn(referencedColumnName = "code", nullable = false),
                @JoinColumn(referencedColumnName = "code")}) public Customer target;
            public NonUniformOwner() {}
        }
        @Entity
        public static class JoinTableOwner {
            @Id public long id;
            @ManyToOne @JoinTable(inverseJoinColumns = @JoinColumn(referencedColumnName = "code")) public Customer target;
            public JoinTableOwner() {}
        }
        @Entity
        public static class ReadOnlyOwner {
            @Id public long id;
            @ManyToOne @JoinColumn(referencedColumnName = "code", updatable = false) public Customer target;
            public ReadOnlyOwner() {}
        }
        @Entity
        public static class InverseTarget {
            @Id public long id;
            @Column(unique = true, nullable = false, updatable = false) public String code;
            @OneToMany(mappedBy = "target") public java.util.List<InverseOwner> owners;
            public InverseTarget() {}
        }
        @Entity
        public static class InverseOwner {
            @Id public long id;
            @ManyToOne @JoinColumn(referencedColumnName = "code") public InverseTarget target;
            public InverseOwner() {}
        }

        @Test
        void invalidBusinessKeyMappingsAreRejectedAtBootstrap() {
            assertThatThrownBy(() -> factory(InvalidTarget.class, NonUniqueOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("unique constraint");
            assertThatThrownBy(() -> factory(InvalidTarget.class, MissingOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("existing BASIC");
            assertThatThrownBy(() -> factory(InvalidTarget.class, MutableOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("immutable");
            assertThatThrownBy(() -> factory(InvalidTarget.class, NullableOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("non-null");
            assertThatThrownBy(() -> factory(Customer.class, MixedOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("non-ID");
            assertThatThrownBy(() -> factory(Customer.class, DuplicateOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("distinct");
            assertThatThrownBy(() -> factory(Customer.class, UnnamedOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("explicitly");
            assertThatThrownBy(() -> factory(Customer.class, NonUniformOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("uniform");
            assertThatThrownBy(() -> factory(Customer.class, JoinTableOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("join tables");
            assertThatThrownBy(() -> factory(Customer.class, ReadOnlyOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("writable");
            assertThatThrownBy(() -> factory(InverseTarget.class, InverseOwner.class)).isInstanceOf(PersistenceException.class)
                .hasMessageContaining("inverse collections");
        }

        @Test
        void aPartiallyNullCompositeForeignKeyIsRejectedRatherThanTreatedAsIdentity() throws Exception {
            try (var factory = factory(Part.class, Item.class); var em = factory.createEntityManager();
                 var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                 var query = db.createStatement()) {
                query.executeUpdate("insert into BusinessItem(id, part_revision, part_business_code) values (1, null, 'UNKNOWN')");
                assertThatThrownBy(() -> em.find(Item.class, 1L)).isInstanceOf(PersistenceException.class)
                    .hasMessageContaining("Partially null");
            }
        }

            @Entity(name = "StrictBusinessOrder")
            public static class StrictOrder {
                @Id public long id;
                @ManyToOne(optional = false)
                @JoinColumn(name = "customer_code", referencedColumnName = "code", nullable = false) public Customer customer;
                public StrictOrder() {}
            }

            @Test
            void aNonNullForeignKeyOrdersInsertsAndDetachedTargetsCannotContradictIdentity() {
                try (var factory = factory(Customer.class, StrictOrder.class); var em = factory.createEntityManager()) {
                    var customer = new Customer(42, "RIGHT");
                    var order = new StrictOrder(); order.id = 1; order.customer = customer;
                    em.getTransaction().begin();
                    em.persist(order);
                    em.persist(customer);
                    em.getTransaction().commit();
                    em.clear();
                    em.getTransaction().begin();
                    var second = new StrictOrder(); second.id = 2; second.customer = new Customer(42, "WRONG");
                    em.persist(second);
                    assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("business-key");
                    em.getTransaction().rollback();
                }
            }

            @Test
            void cacheRestorationKeepsActualIdentityAndConvertedReferenceValues() {
                try (var factory = factory(true, Customer.class, Order.class)) {
                    try (var em = factory.createEntityManager()) {
                        var customer = new Customer(42, "NOT-AN-ID");
                        em.getTransaction().begin();
                        em.persist(new Order(1, customer));
                        em.persist(new Order(2, customer));
                        em.getTransaction().commit();
                    }
                    assertThat(factory.getCache().contains(Customer.class, 42L)).isTrue();
                    assertThatThrownBy(() -> factory.getCache().contains(Customer.class, "NOT-AN-ID"))
                        .isInstanceOf(IllegalArgumentException.class);
                    try (var em = factory.createEntityManager()) {
                        assertThat(em.find(Order.class, 1L).customer).isSameAs(em.find(Order.class, 2L).customer)
                            .isSameAs(em.find(Customer.class, 42L));
                        em.getTransaction().begin();
                        var third = new Order(3, em.getReference(Customer.class, 42L));
                        em.persist(third);
                        em.getTransaction().commit();
                        em.clear();
                        assertThat(em.find(Order.class, 3L).customer).isSameAs(em.find(Customer.class, 42L));
                    }
                }
            }
        @Test
        void referencedAttributeConverterIsUsedBothForWritingAndIdentityLookup() throws Exception {
            try (var factory = factory(ConvertedCustomer.class, ConvertedOrder.class); var em = factory.createEntityManager()) {
                var target = new ConvertedCustomer(); target.id = 88; target.code = new Code("CODE");
                var order = new ConvertedOrder(); order.id = 1; order.customer = target;
                em.getTransaction().begin(); em.persist(order); em.getTransaction().commit();
                em.clear();
                var loaded = em.find(ConvertedOrder.class, 1L);
                assertThat(loaded.customer).isSameAs(em.find(ConvertedCustomer.class, 88L));
                assertThat(loaded.customer.code).isEqualTo(new Code("CODE"));
                em.getTransaction().begin();
                em.flush();
                em.getTransaction().commit();
                assertThat(em.createQuery("select o.customer from ConvertedOrder o where o.customer.code = :code", ConvertedCustomer.class)
                    .setParameter("code", new Code("CODE")).getSingleResult()).isSameAs(loaded.customer);
                try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                     var query = db.createStatement(); var row = query.executeQuery("select customer_code from ConvertedOrder")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("db:CODE");
                }
        }
    }
}
