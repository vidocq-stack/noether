/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** §2.4.2: derived simple identity and a simple component of an embedded identity. */
class MapsIdToOneTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Entity(name = "MapsParent")
    public static class Parent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        public String name;
        public Parent() {}
        Parent(String name) { this.name = name; }
    }

    @Entity(name = "MapsShared")
    public static class Shared {
        @Id public long id;
        @MapsId @OneToOne(cascade = {CascadeType.PERSIST, CascadeType.MERGE}, fetch = FetchType.LAZY)
        @JoinColumn(name = "parent_id")
        public Parent parent;
        public String note;
        @Transient public long persistedId;
        @Transient public long loadedId;
        @Transient public Parent changeOnUpdate;
        public Shared() {}
        Shared(Parent parent) { this.parent = parent; this.note = "initial"; }
        @PostPersist void persisted() { persistedId = id; }
        @PostLoad void loaded() { loadedId = id; }
        @PreUpdate void updating() { if (changeOnUpdate != null) parent = changeOnUpdate; }
    }

    @Embeddable
    public static class Key implements Serializable {
        public String label;
        public long parentId;
        public Key() {}
        Key(String label, long parentId) { this.label = label; this.parentId = parentId; }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && java.util.Objects.equals(label, key.label) && parentId == key.parentId;
        }
        @Override public int hashCode() { return java.util.Objects.hash(label, parentId); }
    }

    @Entity(name = "MapsNamed")
    public static class Named {
        @EmbeddedId public Key id;
        @MapsId("parentId") @ManyToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
        @JoinColumn(name = "parent_fk")
        public Parent parent;
        public String note;
        public Named() {}
        Named(String label, Parent parent) { this.id = new Key(label, 0); this.parent = parent; this.note = "initial"; }
    }

    private EntityManagerFactory factory(Class<?>... classes) {
        var configuration = configuration(classes);
        return configuration.createEntityManagerFactory();
    }

    private PersistenceConfiguration configuration(Class<?>... classes) {
        var configuration = new PersistenceConfiguration("mapsid")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:mapsid-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
            .sharedCacheMode(SharedCacheMode.ALL);
        for (var type : classes.length == 0 ? new Class<?>[]{Parent.class, Shared.class, Named.class} : classes) {
            configuration.managedClass(type);
        }
        return configuration;
    }

    private long scalar(EntityManagerFactory factory, String sql) throws Exception {
        try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
             var statement = db.createStatement(); var row = statement.executeQuery(sql)) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    @Test
    void generatedSharedIdentityIsAssignedBeforePostPersistAndNeverCachedAsZero() throws Exception {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var parent = new Parent("generated");
            var dependent = new Shared(parent);
            em.getTransaction().begin();
            em.persist(dependent);
            em.flush();
            assertThat(parent.id).isPositive();
            assertThat(dependent.id).isEqualTo(parent.id);
            assertThat(dependent.persistedId).isEqualTo(parent.id);
            assertThat(em.find(Shared.class, parent.id)).isSameAs(dependent);
            em.getTransaction().commit();
            assertThat(scalar(factory, "select parent_id from MapsShared")).isEqualTo(parent.id);
            em.clear();
            var found = em.find(Shared.class, parent.id);
            assertThat(found.id).isEqualTo(parent.id);
            assertThat(found.loadedId).isEqualTo(parent.id);
            assertThat(found.parent).isSameAs(em.find(Parent.class, parent.id));
            assertThat(em.find(Shared.class, 0L)).isNull();
        }
    }

    @Test
    void generatedNamedIdentityAndPhysicalTupleAgree() throws Exception {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var parent = new Parent("named");
            var dependent = new Named("one", parent);
            em.getTransaction().begin();
            em.persist(dependent);
            em.getTransaction().commit();
            assertThat(dependent.id.parentId).isEqualTo(parent.id).isPositive();
            assertThat(scalar(factory, "select parent_fk from MapsNamed where label='one'")).isEqualTo(parent.id);
            assertThat(em.find(Named.class, new Key("one", parent.id))).isSameAs(dependent);
            em.clear();
            var found = em.find(Named.class, new Key("one", parent.id));
            assertThat(found.id).isEqualTo(new Key("one", parent.id));
            assertThat(found.parent).isSameAs(em.find(Parent.class, parent.id));
            assertThat(em.find(Named.class, new Key("one", 0))).isNull();
        }
    }

    @Test
    void insertedRelationshipCannotChangeItsIdentity() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var dependent = new Shared(new Parent("first"));
            var other = new Parent("other");
            em.getTransaction().begin();
            em.persist(dependent);
            em.persist(other);
            em.getTransaction().commit();
            em.getTransaction().begin();
            dependent.parent = other;
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Test
    void insertedRelationshipCannotBecomeNull() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var dependent = new Shared(new Parent("first"));
            em.getTransaction().begin();
            em.persist(dependent);
            em.getTransaction().commit();
            em.getTransaction().begin();
            dependent.parent = null;
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Test
    void namedRelationshipCannotChangeItsIdentity() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var dependent = new Named("one", new Parent("first"));
            var other = new Parent("other");
            em.getTransaction().begin();
            em.persist(dependent);
            em.persist(other);
            em.getTransaction().commit();
            em.getTransaction().begin();
            dependent.parent = other;
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Entity public static class WrongName {
        @EmbeddedId public Key id;
        @MapsId("missing") @ManyToOne public Parent parent;
    }
    @Entity public static class WrongType {
        @Id public String id;
        @MapsId @OneToOne public Parent parent;
    }
    @Entity public static class NamedSimple {
        @Id public long id;
        @MapsId("id") @ManyToOne public Parent parent;
    }
    @Entity public static class Inverse {
        @Id public long id;
        @MapsId @OneToOne(mappedBy = "parent") public Shared parent;
    }

    @Test void rejectsMissingNamedAttributeAtBootstrap() {
        assertThatThrownBy(() -> factory(Parent.class, WrongName.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsMismatchingIdentifierTypesAtBootstrap() {
        assertThatThrownBy(() -> factory(Parent.class, WrongType.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsNamedMapsIdOnSimpleIdentifier() {
        assertThatThrownBy(() -> factory(Parent.class, NamedSimple.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsInverseMapsId() {
        assertThatThrownBy(() -> factory(Parent.class, Shared.class, Inverse.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }

    @Test
    void updatesOrdinaryStateWithoutUpdatingTheSharedPrimaryKeyAndDeletesDependentFirst() throws Exception {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var dependent = new Shared(new Parent("parent"));
            em.getTransaction().begin();
            em.persist(dependent);
            em.getTransaction().commit();
            long id = dependent.id;
            em.clear();
            em.getTransaction().begin();
            var found = em.find(Shared.class, id);
            found.note = "updated";
            em.getTransaction().commit();
            assertThat(scalar(factory, "select parent_id from MapsShared where note='updated'")).isEqualTo(id);
            em.getTransaction().begin();
            em.remove(found.parent);
            em.remove(found);
            em.getTransaction().commit();
            assertThat(scalar(factory, "select count(*) from MapsShared")).isZero();
            assertThat(scalar(factory, "select count(*) from MapsParent")).isZero();
        }
    }

    @Test
    void jpqlAndCriteriaUseTheSameDerivedTuple() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var parent = new Parent("query");
            var shared = new Shared(parent);
            var named = new Named("one", parent);
            em.getTransaction().begin();
            em.persist(shared);
            em.persist(named);
            em.getTransaction().commit();
            em.clear();
            factory.getCache().evictAll();
            var found = em.createQuery("select s from MapsShared s where s.id = :id and s.parent = :parent", Shared.class)
                .setParameter("id", parent.id).setParameter("parent", parent).getSingleResult();
            assertThat(found.id).isEqualTo(parent.id);
            assertThat(em.createQuery("select n.id.parentId from MapsNamed n where n.parent.id = :id", Long.class)
                .setParameter("id", parent.id).getSingleResult()).isEqualTo(parent.id);
            var cb = em.getCriteriaBuilder();
            var query = cb.createQuery(Named.class);
            var root = query.from(Named.class);
            query.select(root).where(cb.and(cb.equal(root.get("id").get("parentId"), parent.id),
                cb.equal(root.get("parent"), found.parent)));
            var result = em.createQuery(query).getSingleResult();
            assertThat(result.id).isEqualTo(new Key("one", parent.id));
            assertThat(result.parent).isSameAs(found.parent);
            assertThat(em.createQuery("select s from MapsShared s join fetch s.parent", Shared.class).getSingleResult())
                .isSameAs(found);
        }
    }

    @Test
    void fetchGraphAndCacheHitsKeepRealIdentifiersAndPostLoadState() {
        try (var factory = factory()) {
            long id;
            try (var em = factory.createEntityManager()) {
                var shared = new Shared(new Parent("cached"));
                em.getTransaction().begin();
                em.persist(shared);
                em.getTransaction().commit();
                id = shared.id;
            }
            for (int attempt = 0; attempt < 2; attempt++) {
                try (var em = factory.createEntityManager()) {
                    var graph = em.createEntityGraph(Shared.class);
                    graph.addAttributeNodes("parent");
                    var found = em.find(Shared.class, id, java.util.Map.of("jakarta.persistence.fetchgraph", graph));
                    assertThat(found.id).isEqualTo(id).isPositive();
                    assertThat(found.loadedId).isEqualTo(id);
                    assertThat(found.parent.name).isEqualTo("cached");
                    assertThat(factory.getPersistenceUnitUtil().isLoaded(found, "parent")).isTrue();
                    assertThat(em.find(Shared.class, id)).isSameAs(found);
                    assertThat(factory.getCache().contains(Shared.class, 0L)).isFalse();
                }
            }
        }
    }

    @Test
    void embeddedDerivedAttributesAreReadOnlyOnPersistAndSubsequentFlush() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var parent = new Parent("assigned");
            em.getTransaction().begin();
            em.persist(parent);
            em.flush();
            var named = new Named("one", parent);
            named.id.parentId = 999;
            em.persist(named);
            assertThat(named.id.parentId).isEqualTo(parent.id);
            em.getTransaction().commit();
            em.getTransaction().begin();
            named.id.parentId = 999;
            em.flush();
            assertThat(named.id.parentId).isEqualTo(parent.id);
            em.getTransaction().commit();
        }
    }

    @Test
    void simpleIdentifierMutationIsRejectedEvenIfAssociationIsUnchanged() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var shared = new Shared(new Parent("immutable"));
            em.getTransaction().begin();
            em.persist(shared);
            em.getTransaction().commit();
            em.getTransaction().begin();
            shared.id += 1;
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Test
    void generatedForeignKeyIsARealDatabaseConstraint() throws Exception {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var shared = new Shared(new Parent("constraint"));
            var named = new Named("one", shared.parent);
            em.getTransaction().begin();
            em.persist(shared);
            em.persist(named);
            em.getTransaction().commit();
            try (var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
                 var query = db.createStatement()) {
                assertThatThrownBy(() -> query.executeUpdate("insert into MapsShared(parent_id,note) values(999,'invalid')"))
                    .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> query.executeUpdate("insert into MapsNamed(label,parent_fk,note) values('bad',999,'invalid')"))
                    .isInstanceOf(java.sql.SQLException.class);
            }
            assertThat(scalar(factory, "select count(*) from MapsShared")).isEqualTo(1);
            assertThat(scalar(factory, "select count(*) from MapsNamed")).isEqualTo(1);
        }
    }

    @Test
    void xmlMapsIdHasTheSameDerivationAndLoadSemantics() throws Exception {
        try (var factory = configuration().mappingFile("orm/mapsid-to-one.xml").createEntityManagerFactory();
             var em = factory.createEntityManager()) {
            var parent = new Parent("xml");
            var shared = new Shared(parent);
            var named = new Named("xml", parent);
            em.getTransaction().begin();
            em.persist(shared);
            em.persist(named);
            em.getTransaction().commit();
            assertThat(shared.id).isEqualTo(parent.id).isPositive();
            assertThat(named.id.parentId).isEqualTo(parent.id);
            assertThat(scalar(factory, "select xml_parent from MapsShared")).isEqualTo(parent.id);
            assertThat(scalar(factory, "select xml_fk from MapsNamed")).isEqualTo(parent.id);
            em.clear();
            assertThat(em.find(Shared.class, parent.id).parent.id).isEqualTo(parent.id);
            assertThat(em.find(Named.class, new Key("xml", parent.id)).parent.id).isEqualTo(parent.id);
        }
    }

    @Embeddable
    public record RecordKey(String label, long parentId) implements Serializable {}
    @Entity(name = "MapsRecord")
    public static class RecordDependent {
        @EmbeddedId public RecordKey id;
        @MapsId("parentId") @ManyToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "parent_fk") public Parent parent;
    }

    @Test
    void derivesRecordEmbeddedIdentifierWithoutLosingUnmappedParts() {
        try (var factory = factory(Parent.class, RecordDependent.class); var em = factory.createEntityManager()) {
            var dependent = new RecordDependent();
            dependent.id = new RecordKey("record", 0);
            dependent.parent = new Parent("record-parent");
            em.getTransaction().begin();
            em.persist(dependent);
            em.getTransaction().commit();
            assertThat(dependent.id).isEqualTo(new RecordKey("record", dependent.parent.id));
            em.clear();
            assertThat(em.find(RecordDependent.class, new RecordKey("record", dependent.parent.id)).id)
                .isEqualTo(dependent.id);
        }
    }

    @Test
    void mergeOfNewCascadeGraphDerivesGeneratedIdentity() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            var merged = em.merge(new Shared(new Parent("merged")));
            em.getTransaction().commit();
            assertThat(merged.id).isEqualTo(merged.parent.id).isPositive();
            em.clear();
            assertThat(em.find(Shared.class, merged.id).parent.id).isEqualTo(merged.id);
        }
    }

    @Test
    void missingParentIsRefusedWithoutPhantomZeroIdentity() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            assertThatThrownBy(() -> {
                em.persist(new Shared(null));
                em.flush();
            }).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
            assertThat(factory.getCache().contains(Shared.class, 0L)).isFalse();
        }
    }

    @Test
    void preUpdateCallbackCannotReassignDerivedIdentity() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var shared = new Shared(new Parent("first"));
            var other = new Parent("second");
            em.getTransaction().begin();
            em.persist(shared);
            em.persist(other);
            em.getTransaction().commit();
            em.getTransaction().begin();
            shared.note = "changed";
            shared.changeOnUpdate = other;
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Entity(name = "MapsSequenceParent")
    public static class SequenceParent {
        @Id @GeneratedValue(strategy = GenerationType.SEQUENCE) public long id;
    }
    @Entity(name = "MapsSequenceDependent")
    public static class SequenceDependent {
        @Id public long id;
        @MapsId @OneToOne(cascade = CascadeType.PERSIST) public SequenceParent parent;
    }

    @Test
    void cascadeSequenceIdentityIsAvailableWhenPersistReturns() {
        try (var factory = factory(SequenceParent.class, SequenceDependent.class); var em = factory.createEntityManager()) {
            var dependent = new SequenceDependent();
            dependent.parent = new SequenceParent();
            em.getTransaction().begin();
            em.persist(dependent);
            assertThat(dependent.id).isEqualTo(dependent.parent.id).isPositive();
            assertThat(em.find(SequenceDependent.class, dependent.id)).isSameAs(dependent);
            em.getTransaction().commit();
        }
    }

    @Test
    void bulkUpdateCannotReassignMapsIdAssociation() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var shared = new Shared(new Parent("first"));
            var other = new Parent("second");
            em.getTransaction().begin();
            em.persist(shared);
            em.persist(other);
            em.getTransaction().commit();
            em.getTransaction().begin();
            assertThatThrownBy(() -> em.createQuery("update MapsShared s set s.parent = :parent")
                .setParameter("parent", other).executeUpdate())
                .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Entity public static class NestedName {
        @EmbeddedId public Key id;
        @MapsId("parentId.part") @ManyToOne public Parent parent;
    }
    @Entity public static class WholeEmbeddedSimple {
        @EmbeddedId public Key id;
        @MapsId @OneToOne public Parent parent;
    }
    @Entity public static class GeneratedDependent {
        @Id @GeneratedValue public long id;
        @MapsId @OneToOne public Parent parent;
    }
    @Entity public static class Overlapping {
        @Id public long id;
        @MapsId @OneToOne public Parent first;
        @MapsId @OneToOne public Parent second;
    }
    @Entity public static class Chained {
        @Id public long id;
        @MapsId @OneToOne public Shared parent;
    }

    @Test void rejectsNestedMapsIdNamesRatherThanGuessingPartialTuples() {
        assertThatThrownBy(() -> factory(Parent.class, NestedName.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsWholeEmbeddedIdForSimpleTarget() {
        assertThatThrownBy(() -> factory(Parent.class, WholeEmbeddedSimple.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsIndependentGenerationOfDerivedIdentifier() {
        assertThatThrownBy(() -> factory(Parent.class, GeneratedDependent.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsOverlappingDerivedAttributes() {
        assertThatThrownBy(() -> factory(Parent.class, Overlapping.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsChainedDerivedIdentifiers() {
        assertThatThrownBy(() -> factory(Parent.class, Shared.class, Chained.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }

    @Entity(name = "MapsHolder")
    public static class Holder {
        @Id public long id;
        @ManyToOne(cascade = CascadeType.PERSIST) public Shared shared;
        @ManyToOne(cascade = CascadeType.PERSIST) public Named named;
    }

    @Test
    void incomingForeignKeysReferencePhysicalDerivedColumns() throws Exception {
        try (var factory = factory(Parent.class, Shared.class, Named.class, Holder.class); var em = factory.createEntityManager()) {
            var holder = new Holder();
            holder.id = 10;
            var parent = new Parent("incoming");
            holder.shared = new Shared(parent);
            holder.named = new Named("one", parent);
            em.getTransaction().begin();
            em.persist(holder);
            em.getTransaction().commit();
            em.clear();
            var found = em.find(Holder.class, 10L);
            assertThat(found.shared.id).isEqualTo(parent.id).isPositive();
            assertThat(found.named.id).isEqualTo(new Key("one", parent.id));
            assertThat(found.named.parent).isSameAs(found.shared.parent);
            assertThat(scalar(factory, "select shared_parent_id from MapsHolder")).isEqualTo(parent.id);
            assertThat(scalar(factory, "select named_parent_fk from MapsHolder")).isEqualTo(parent.id);
        }
    }

    @Entity(name = "MapsCycleParent")
    public static class CycleParent {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        @ManyToOne public CycleDependent backlink;
    }
    @Entity(name = "MapsCycleDependent")
    public static class CycleDependent {
        @Id public long id;
        @MapsId @OneToOne(cascade = CascadeType.PERSIST)
        @JoinColumn(name = "parent_fk") public CycleParent parent;
    }

    @Test
    void insertOrderingNeverDefersThePrimaryKeyInANullableBacklinkCycle() throws Exception {
        try (var factory = factory(CycleDependent.class, CycleParent.class); var em = factory.createEntityManager()) {
            var dependent = new CycleDependent();
            dependent.parent = new CycleParent();
            dependent.parent.backlink = dependent;
            em.getTransaction().begin();
            em.persist(dependent);
            em.getTransaction().commit();
            assertThat(dependent.id).isEqualTo(dependent.parent.id).isPositive();
            em.clear();
            var found = em.find(CycleDependent.class, dependent.id);
            assertThat(found.parent.backlink).isSameAs(found);
            assertThat(scalar(factory, "select parent_fk from MapsCycleDependent")).isEqualTo(found.id);
            em.getTransaction().begin();
            em.remove(found.parent);
            em.remove(found);
            em.getTransaction().commit();
        }
    }

    @Embeddable public static class NestedKey implements Serializable {
        @Embedded public Key nested;
    }
    @Entity public static class NestedTarget {
        @EmbeddedId public NestedKey id;
    }
    @Entity public static class NestedDependent {
        @EmbeddedId public NestedKey id;
        @MapsId @OneToOne public NestedTarget parent;
    }
    @Embeddable public static class PartialKey implements Serializable {
        @Embedded public Key nested;
        public long parentId;
    }
    @Entity public static class PartialDependent {
        @EmbeddedId public PartialKey id;
        @MapsId("parentId") @ManyToOne public Parent parent;
    }

    @Test void rejectsUnsupportedNestedCompositeTargetsAtBootstrap() {
        assertThatThrownBy(() -> factory(NestedTarget.class, NestedDependent.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }
    @Test void rejectsUnmappedNestedRemainderOfEmbeddedIdentifier() {
        assertThatThrownBy(() -> factory(Parent.class, PartialDependent.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
    }

    @Test
    void incompleteRemainingEmbeddedIdentifierIsRefusedBeforeSql() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var named = new Named(null, new Parent("incomplete"));
            em.getTransaction().begin();
            assertThatThrownBy(() -> {
                em.persist(named);
                em.flush();
            }).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Entity(name = "MapsAssignedParent")
    public static class AssignedParent {
        @Id public long id;
    }
    @Entity(name = "MapsAssignedDependent")
    public static class AssignedDependent {
        @Id public long id;
        @MapsId @OneToOne(cascade = CascadeType.PERSIST) public AssignedParent parent;
    }

    @Test
    void sharedOneToOneUsesAssignedSimpleIdentityIncludingLegitimateZero() throws Exception {
        try (var factory = factory(AssignedParent.class, AssignedDependent.class); var em = factory.createEntityManager()) {
            for (long id : new long[]{0, 57}) {
                var dependent = new AssignedDependent();
                dependent.parent = new AssignedParent();
                dependent.parent.id = id;
                em.getTransaction().begin();
                em.persist(dependent);
                assertThat(dependent.id).isEqualTo(id);
                em.getTransaction().commit();
                em.clear();
                assertThat(em.find(AssignedDependent.class, id).parent.id).isEqualTo(id);
            }
            assertThat(scalar(factory, "select count(*) from MapsAssignedDependent")).isEqualTo(2);
        }
    }

    @Test
    void physicalIncomingKeyTypesMatchTheirGeneratedLongParent() throws Exception {
        try (var factory = factory(Parent.class, Shared.class, Named.class, Holder.class);
             var db = DriverManager.getConnection((String) factory.getProperties().get(PersistenceConfiguration.JDBC_URL), "sa", "");
             var column = db.getMetaData().getColumns(null, null, "MAPSHOLDER", "SHARED_PARENT_ID")) {
            assertThat(column.next()).isTrue();
            assertThat(column.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.BIGINT);
        }
    }

    @Test
    void persistRegistersOneIdentityAndRefusesReassignmentBeforeFirstFlush() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var first = new Parent("first");
            var second = new Parent("second");
            em.getTransaction().begin();
            em.persist(first);
            em.persist(second);
            em.flush();
            var dependent = new Shared(first);
            em.persist(dependent);
            dependent.parent = second;
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Entity(name = "MapsPropertyParent")
    public static class PropertyParent {
        private long id;
        private String name;
        public PropertyParent() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
    @Entity(name = "MapsPropertyDependent")
    public static class PropertyDependent {
        private long id;
        private PropertyParent parent;
        public PropertyDependent() {}
        @Id public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        @MapsId @OneToOne(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
        @JoinColumn(name = "parent_id")
        public PropertyParent getParent() { return parent; }
        public void setParent(PropertyParent parent) { this.parent = parent; }
    }

    @Test
    void otherwiseInterceptableLazyPropertyKeepsTheSafeMapsIdFallback() {
        try (var factory = factory(PropertyParent.class, PropertyDependent.class); var em = factory.createEntityManager()) {
            var dependent = new PropertyDependent();
            dependent.parent = new PropertyParent();
            dependent.parent.id = 57;
            dependent.parent.name = "eager fallback";
            em.getTransaction().begin();
            em.persist(dependent);
            em.getTransaction().commit();
            em.clear();
            factory.getCache().evictAll();
            var found = em.find(PropertyDependent.class, 57L);
            assertThat(factory.getPersistenceUnitUtil().isLoaded(found, "parent")).isTrue();
            assertThat(found.id).isEqualTo(57);
            assertThat(found.getParent().getName()).isEqualTo("eager fallback");
            assertThat(found.getParent()).isSameAs(em.find(PropertyParent.class, 57L));
        }
    }

    @Test
    void refreshRestoresDerivedIdentityBeforeSnapshotAndCallbacks() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var shared = new Shared(new Parent("refresh"));
            var named = new Named("one", shared.parent);
            em.getTransaction().begin();
            em.persist(shared);
            em.persist(named);
            em.getTransaction().commit();
            em.getTransaction().begin();
            em.refresh(shared);
            em.refresh(named);
            assertThat(shared.id).isEqualTo(shared.parent.id).isPositive();
            assertThat(shared.loadedId).isEqualTo(shared.id);
            assertThat(named.id).isEqualTo(new Key("one", shared.id));
            assertThat(em.createQuery("select n from MapsNamed n", Named.class).getSingleResult()).isSameAs(named);
            em.getTransaction().commit();
        }
    }

    @Test
    void unmappedEmbeddedIdentifierPartsCannotChange() {
        try (var factory = factory(); var em = factory.createEntityManager()) {
            var named = new Named("one", new Parent("immutable"));
            em.getTransaction().begin();
            em.persist(named);
            em.getTransaction().commit();
            em.getTransaction().begin();
            named.id.label = "changed";
            assertThatThrownBy(em::flush).isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId");
            em.getTransaction().rollback();
        }
    }

    @Entity public static class InvalidSimpleManyToOne {
        @Id public long id;
        @MapsId @ManyToOne public Parent parent;
    }

    @Test
    void simpleSharedIdentifierRequiresOneToOnePerSpecification() {
        // §2.4.2.1: a simple dependent ID (unlike a named embedded component) requires OneToOne.
        assertThatThrownBy(() -> factory(Parent.class, InvalidSimpleManyToOne.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("MapsId").hasMessageContaining("OneToOne");
    }
}
