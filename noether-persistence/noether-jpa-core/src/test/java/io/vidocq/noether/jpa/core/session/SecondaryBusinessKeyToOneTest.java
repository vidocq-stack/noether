/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SecondaryTable;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import java.io.StringWriter;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** §11.1.26/Table 24 and §11.1.47: optional non-PK references retain the target's primary identity. */
class SecondaryBusinessKeyToOneTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Entity(name = "SecondaryCustomer")
    @Table(name = "sb_customer")
    @SecondaryTable(name = "sb_details",
        pkJoinColumns = @PrimaryKeyJoinColumn(name = "customer_pk", referencedColumnName = "id"),
        uniqueConstraints = @UniqueConstraint(columnNames = "business_code"))
    public static class Customer {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        @Column(name = "business_code", table = "sb_details", nullable = false, updatable = false)
        public String code;
        public String label;
        public Customer() {}
        Customer(String code) { this.code = code; this.label = code; }
    }

    @Entity(name = "SecondaryOrder")
    @Table(name = "sb_order")
    public static class Order {
        @Id public long id;
        @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
        @JoinColumn(name = "customer_code", referencedColumnName = "business_code")
        public Customer customer;
        public Order() {}
        Order(long id, Customer customer) { this.id = id; this.customer = customer; }
    }

    @Entity(name = "SecondaryBadge")
    @Table(name = "sb_badge")
    public static class Badge {
        @Id public long id;
        @OneToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "customer_code", referencedColumnName = "business_code")
        public Customer customer;
        public Badge() {}
    }

    private PersistenceConfiguration configuration(boolean xml, Class<?>... types) {
        var configuration = new PersistenceConfiguration("secondary-business")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL,
                System.getProperty("secondary.business.jdbc", "jdbc:h2:mem:secondary-business-" + DATABASES.incrementAndGet()))
            .property(PersistenceConfiguration.JDBC_USER, System.getProperty("secondary.business.user", "sa"))
            .property(PersistenceConfiguration.JDBC_PASSWORD, System.getProperty("secondary.business.password", ""))
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
            .sharedCacheMode(SharedCacheMode.ALL);
        for (var type : types.length == 0 ? new Class<?>[]{Customer.class, Order.class, Badge.class} : types) {
            configuration.managedClass(type);
        }
        if (xml) configuration.mappingFile("orm/secondary-business-keys.xml");
        return configuration;
    }

    @Test void annotationLifecycleQueriesAndSchema() throws Exception { lifecycle(false); }
    @Test void xmlLifecycleQueriesAndSchema() throws Exception { lifecycle(true); }

    private void lifecycle(boolean xml) throws Exception {
        String details = xml ? "xml_sb_details" : "sb_details";
        String pk = xml ? "xml_customer_pk" : "customer_pk";
        try (var factory = configuration(xml).createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var first = new Customer("FIRST");
            var second = new Customer("SECOND");
            em.getTransaction().begin();
            em.persist(new Order(1, first));
            em.persist(new Order(2, second));
            em.persist(new Order(3, null));
            var badge = new Badge();
            badge.id = 4;
            badge.customer = first;
            em.persist(badge);
            em.getTransaction().commit();
            assertThat(first.id).isPositive();
            assertThat(second.id).isNotEqualTo(first.id);
            try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL),
                    System.getProperty("secondary.business.user", "sa"), System.getProperty("secondary.business.password", ""));
                 var keys = db.getMetaData().getImportedKeys(null, null,
                     System.getProperty("secondary.business.jdbc") == null ? "SB_ORDER" : "sb_order")) {
                assertThat(keys.next()).isTrue();
                assertThat(keys.getString("PKTABLE_NAME")).isEqualToIgnoringCase(details);
                assertThat(keys.getString("PKCOLUMN_NAME")).isEqualToIgnoringCase("business_code");
                assertThat(keys.next()).isFalse();
                try (var query = db.createStatement();
                     var row = query.executeQuery("select customer_code from sb_order where id=1")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("FIRST");
                }
                try (var secondaryKeys = db.getMetaData().getImportedKeys(null, null,
                        System.getProperty("secondary.business.jdbc") == null ? details.toUpperCase(java.util.Locale.ROOT) : details)) {
                    assertThat(secondaryKeys.next()).isTrue();
                    assertThat(secondaryKeys.getString("FKCOLUMN_NAME")).isEqualToIgnoringCase(pk);
                    assertThat(secondaryKeys.getString("PKTABLE_NAME")).isEqualToIgnoringCase("sb_customer");
                }
            }
            factory.getCache().evictAll();
            em.clear();
            var loaded = em.find(Order.class, 1L);
            assertThat(loaded.customer.id).isEqualTo(first.id);
            assertThat(loaded.customer).isSameAs(em.find(Customer.class, first.id));
            assertThat(factory.getPersistenceUnitUtil().isLoaded(loaded, "customer")).isTrue();
            assertThat(em.find(Badge.class, 4L).customer).isSameAs(loaded.customer);
            assertThat(em.find(Order.class, 3L).customer).isNull();
            assertThat(em.createQuery("select o.customer from SecondaryOrder o where o.customer = :c", Customer.class)
                .setParameter("c", loaded.customer).getSingleResult()).isSameAs(loaded.customer);
            assertThat(em.createQuery("select o from SecondaryOrder o join o.customer c where c.code = 'FIRST'", Order.class)
                .getSingleResult()).isSameAs(loaded);
            var cb = em.getCriteriaBuilder();
            var criteria = cb.createQuery(Order.class);
            var root = criteria.from(Order.class);
            criteria.select(root).where(cb.equal(root.join("customer").get("code"), "SECOND"));
            assertThat(em.createQuery(criteria).getSingleResult().customer.id).isEqualTo(second.id);
            var graph = em.createEntityGraph(Order.class);
            graph.addAttributeNodes("customer");
            em.clear();
            var fetched = em.createQuery("select o from SecondaryOrder o order by o.id", Order.class)
                .setHint("jakarta.persistence.fetchgraph", graph).getResultList();
            assertThat(fetched.getFirst().customer.id).isEqualTo(first.id);
            assertThat(fetched.getLast().customer).isNull();
            assertThat(em.createQuery("select o from SecondaryOrder o left join fetch o.customer order by o.id", Order.class)
                .getResultList()).hasSize(3);
            em.clear();
            assertThat(em.find(Order.class, 1L, java.util.Map.of("jakarta.persistence.loadgraph", graph)).customer)
                .isSameAs(em.find(Customer.class, first.id));
            em.getTransaction().begin();
            em.find(Order.class, 1L).customer = em.find(Customer.class, second.id);
            em.flush();
            em.find(Badge.class, 4L).customer = null;
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Order.class, 1L).customer.id).isEqualTo(second.id);
            assertThat(em.find(Badge.class, 4L).customer).isNull();
            em.getTransaction().begin();
            assertThat(em.createQuery("update SecondaryOrder o set o.customer = :c where o.id = 1")
                .setParameter("c", em.find(Customer.class, first.id)).executeUpdate()).isEqualTo(1);
            em.getTransaction().commit();
            em.clear();
            factory.getCache().evictAll();
            assertThat(em.find(Order.class, 1L).customer.id).isEqualTo(first.id);
            em.getTransaction().begin();
            em.find(Order.class, 1L).customer = null;
            em.find(Order.class, 2L).customer = null;
            em.flush();
            em.remove(em.find(Customer.class, first.id));
            em.remove(em.find(Customer.class, second.id));
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Order.class, 1L).customer).isNull();
            assertThat(em.find(Customer.class, first.id)).isNull();
            factory.getSchemaManager().drop(true);
        }
    }

    @Test
    void cacheIdentityAndImmutableKeyProtection() {
        try (var factory = configuration(false).createEntityManagerFactory()) {
            var customer = new Customer("CACHED");
            factory.runInTransaction(em -> em.persist(new Order(1, customer)));
            try (var em = factory.createEntityManager()) {
                var order = em.find(Order.class, 1L);
                assertThat(order.customer).isSameAs(em.find(Customer.class, customer.id));
                em.getTransaction().begin();
                order.customer.code = "MUTATED";
                assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("immutable");
                em.getTransaction().rollback();
            }
        }
    }

    @Entity @SecondaryTable(name = "sb_converted")
    public static class Converted {
        @Id public long id;
        @Convert(converter = BusinessKeyToOneTest.CodeConverter.class)
        @Column(table = "sb_converted", unique = true, nullable = false, updatable = false)
        public BusinessKeyToOneTest.Code code;
        public Converted() {}
    }
    @Entity public static class ConvertedOwner {
        @Id public long id;
        @ManyToOne(cascade = CascadeType.PERSIST) @JoinColumn(referencedColumnName = "code")
        public Converted target;
        public ConvertedOwner() {}
    }

    @Test
    void convertedSecondaryKeyUsesExistingBinder() {
        try (var factory = configuration(false, Converted.class, ConvertedOwner.class).createEntityManagerFactory();
             var em = factory.createEntityManager()) {
            var target = new Converted();
            target.id = 91;
            target.code = new BusinessKeyToOneTest.Code("CONVERTED");
            var owner = new ConvertedOwner();
            owner.id = 1;
            owner.target = target;
            em.getTransaction().begin();
            em.persist(owner);
            em.getTransaction().commit();
            em.clear();
            factory.getCache().evictAll();
            assertThat(em.find(ConvertedOwner.class, 1L).target).isSameAs(em.find(Converted.class, 91L));
            assertThat(em.find(Converted.class, 91L).code).isEqualTo(target.code);
        }
    }

    @Entity @SecondaryTable(name = "sb_invalid")
    public static class Invalid {
        @Id public long id;
        @Column(table = "sb_invalid", nullable = false, updatable = false) public String code;
        public Invalid() {}
    }
    @Entity public static class InvalidOwner {
        @Id public long id;
        @ManyToOne @JoinColumn(referencedColumnName = "code") public Invalid target;
        public InvalidOwner() {}
    }

    @Test
    void undeclaredUniqueConstraintIsRejectedBeforeAnyDdl() {
        rejected(configuration(false, Invalid.class, InvalidOwner.class), "unique constraint");
    }

    @Test
    void xmlUndeclaredUniqueConstraintIsRejectedBeforeAnyDdl() {
        rejected(configuration(false, Invalid.class, InvalidOwner.class)
            .mappingFile("orm/secondary-business-invalid.xml"), "unique constraint");
    }

    private void rejected(PersistenceConfiguration configuration, String message) {
        var ddl = new StringWriter();
        assertThatThrownBy(() -> configuration
            .property(PersistenceConfiguration.SCHEMAGEN_SCRIPTS_ACTION, "create")
            .property("jakarta.persistence.schema-generation.scripts.create-target", ddl).createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining(message);
        assertThat(ddl.toString()).isEmpty();
    }

    @Entity @SecondaryTable(name = "sb_mutable")
    public static class Mutable {
        @Id public long id;
        @Column(table = "sb_mutable", unique = true, nullable = false) public String code;
        public Mutable() {}
    }
    @Entity public static class MutableOwner {
        @Id public long id;
        @ManyToOne @JoinColumn(referencedColumnName = "code") public Mutable target;
        public MutableOwner() {}
    }
    @Entity @SecondaryTable(name = "sb_ambiguous")
    public static class Ambiguous {
        @Id public long id;
        @Column(name = "code", table = "sb_ambiguous", unique = true, nullable = false, updatable = false) public String code;
        @Column(name = "code", unique = true, nullable = false, updatable = false) public String primaryCode;
        public Ambiguous() {}
    }
    @Entity public static class AmbiguousOwner {
        @Id public long id;
        @ManyToOne @JoinColumn(referencedColumnName = "code") public Ambiguous target;
        public AmbiguousOwner() {}
    }
    @Entity @SecondaryTable(name = "sb_composite")
    @IdClass(CompositeId.class)
    public static class Composite {
        @Id public long id;
        @Id public long part;
        @Column(table = "sb_composite", unique = true, nullable = false, updatable = false) public String code;
        public Composite() {}
    }
    public static class CompositeId implements java.io.Serializable {
        public long id;
        public long part;
        public CompositeId() {}
        @Override public boolean equals(Object other) { return other instanceof CompositeId k && id == k.id && part == k.part; }
        @Override public int hashCode() { return java.util.Objects.hash(id, part); }
    }
    @Entity public static class CompositeOwner {
        @Id public long id;
        @ManyToOne @JoinColumn(referencedColumnName = "code") public Composite target;
        public CompositeOwner() {}
    }
    @Entity public static class MultipleOwner {
        @Id public long id;
        @ManyToOne @JoinColumns({@JoinColumn(name = "a", referencedColumnName = "business_code"),
            @JoinColumn(name = "b", referencedColumnName = "label")}) public Customer target;
        public MultipleOwner() {}
    }
    @Entity public static class PrimaryReference {
        @Id public long id;
        @ManyToOne(cascade = CascadeType.PERSIST) @JoinColumn(referencedColumnName = "id") public Customer target;
        public PrimaryReference() {}
    }
    @Entity public static class OwnerTableSelector {
        @Id public long id;
        @ManyToOne @JoinColumn(table = "sb_details", referencedColumnName = "business_code") public Customer target;
        public OwnerTableSelector() {}
    }

    @Test
    void joinColumnTableCannotBeUsedAsAReferencedTableSelector() {
        rejected(configuration(false, Customer.class, OwnerTableSelector.class), "owner secondary-table");
    }

    @Test
    void mutableAmbiguousCompositeAndMultiColumnSecondaryKeysStayRejected() {
        rejected(configuration(false, Mutable.class, MutableOwner.class), "immutable");
        rejected(configuration(false, Ambiguous.class, AmbiguousOwner.class), "unambiguous");
        rejected(configuration(false, Composite.class, CompositeOwner.class), "single-column target primary key");
        rejected(configuration(false, Customer.class, MultipleOwner.class), "one BASIC column");
    }

    @Test
    void ordinaryPrimaryKeyReferenceToSecondaryTableEntityIsUnchanged() {
        try (var factory = configuration(false, Customer.class, PrimaryReference.class).createEntityManagerFactory();
             var em = factory.createEntityManager()) {
            var owner = new PrimaryReference();
            owner.id = 1;
            owner.target = new Customer("PRIMARY");
            em.getTransaction().begin();
            em.persist(owner);
            em.getTransaction().commit();
            em.clear();
            factory.getCache().evictAll();
            assertThat(em.find(PrimaryReference.class, 1L).target.id).isEqualTo(owner.target.id);
        }
    }
}
