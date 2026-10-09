/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §2.7, §11.1.4 and §11.1.8: embeddable keys use generated accesses and the owning entity's collection defaults. */
class EmbeddableMapKeyTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private Connection database;
    private EntityManagerFactory emf;
    private EntityManager em;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:embeddable-keys-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        emf = new PersistenceConfiguration("embeddable-keys")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Parcel.class).managedClass(Warehouse.class).managedClass(Address.class)
            .managedClass(Zip.class).managedClass(Region.class)
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa")
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
    }

    @AfterEach
    void close() throws SQLException {
        if (emf != null) emf.close();
        database.close();
    }

    private void transaction(Runnable work) {
        em.getTransaction().begin();
        work.run();
        em.getTransaction().commit();
    }

    private String scalar(String sql) throws SQLException {
        try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private Parcel persist() {
        Parcel parcel = new Parcel();
        parcel.id = 1;
        parcel.address = new Address();
        parcel.address.codes.put(new Zip(75001, "Paris"), "home");
        parcel.address.list.add(new Zip(62000, "Arras"));
        parcel.address.set.add(new Zip(69001, "Lyon"));
        parcel.codes.put(new Zip(76000, "Rouen"), "office");
        parcel.regions.put(new Region("North"), "regional");
        parcel.pairs.put(new Zip(45000, "Orleans"), new Zip(37000, "Tours"));
        transaction(() -> em.persist(parcel));
        return parcel;
    }

    @Test
    void defaultTablesBelongToTheEntityAndAllCollectionKindsRoundTrip() throws SQLException {
        persist();
        assertThat(scalar("select codes from Parcel_codes where Parcel_id = 1 and code = 75001 and label = 'Paris'"))
            .isEqualTo("home");
        assertThat(scalar("select codes from ROOT_CODES where Parcel_id = 1 and ROOT_CODE = 76000 and ROOT_REGION = 'Rouen'"))
            .isEqualTo("office");
        assertThat(scalar("select code from Parcel_list where Parcel_id = 1")).isEqualTo("62000");
        assertThat(scalar("select code from Parcel_set where Parcel_id = 1")).isEqualTo("69001");
        em.clear();
        Parcel loaded = em.find(Parcel.class, 1L);
        assertThat(loaded.address.codes).containsEntry(new Zip(75001, "Paris"), "home");
        assertThat(loaded.codes).containsEntry(new Zip(76000, "Rouen"), "office");
        assertThat(loaded.address.list).containsExactly(new Zip(62000, "Arras"));
        assertThat(loaded.address.set).containsExactly(new Zip(69001, "Lyon"));
        assertThat(loaded.regions).containsEntry(new Region("North"), "regional");
        assertThat(emf.getMetamodel().embeddable(Address.class).getMap("codes").getKeyType().getPersistenceType())
            .isEqualTo(jakarta.persistence.metamodel.Type.PersistenceType.EMBEDDABLE);
    }

    @Test
    void reusableEmbeddedMapsAreScopedByTheContainingEntity() throws SQLException {
        persist();
        Warehouse warehouse = new Warehouse();
        warehouse.id = 1;
        warehouse.address = new Address();
        warehouse.address.codes.put(new Zip(31000, "Toulouse"), "depot");
        transaction(() -> em.persist(warehouse));
        assertThat(scalar("select codes from Warehouse_codes where Warehouse_id = 1")).isEqualTo("depot");
        assertThat(scalar("select codes from Parcel_codes where Parcel_id = 1")).isEqualTo("home");
        em.clear();
        assertThat(em.find(Warehouse.class, 1L).address.codes).containsOnlyKeys(new Zip(31000, "Toulouse"));
        assertThat(em.find(Parcel.class, 1L).address.codes).containsOnlyKeys(new Zip(75001, "Paris"));
    }

    @Test
    void mutableKeysAndValuesAreSnapshottedByPersistentState() throws SQLException {
        Parcel parcel = persist();
        transaction(() -> {
            Zip key = parcel.address.codes.keySet().iterator().next();
            key.code = 75002;
            key.region = new Region("Paris Centre");
        });
        assertThat(scalar("select code from Parcel_codes where Parcel_id = 1")).isEqualTo("75002");
        em.clear();
        Parcel loaded = em.find(Parcel.class, 1L);
        transaction(() -> {
            loaded.address.codes.remove(new Zip(75002, "Paris Centre"));
            loaded.address.codes.put(new Zip(44000, "Nantes"), "new");
        });
        em.clear();
        assertThat(em.find(Parcel.class, 1L).address.codes).containsExactlyEntriesOf(Map.of(new Zip(44000, "Nantes"), "new"));
    }

    @Test
    void mergeCopiesKeysAndDeleteRemovesEveryCollectionRow() throws SQLException {
        Parcel detached = persist();
        em.clear();
        transaction(() -> {
            Parcel merged = em.merge(detached);
            assertThat(merged.address.codes).isNotSameAs(detached.address.codes);
            assertThat(merged.address.codes.keySet().iterator().next())
                .isEqualTo(detached.address.codes.keySet().iterator().next())
                .isNotSameAs(detached.address.codes.keySet().iterator().next());
            em.remove(merged);
        });
        assertThat(scalar("select count(*) from Parcel_codes")).isEqualTo("0");
        assertThat(scalar("select count(*) from ROOT_CODES")).isEqualTo("0");
        assertThat(scalar("select count(*) from Parcel_list")).isEqualTo("0");
        assertThat(scalar("select count(*) from Parcel_set")).isEqualTo("0");
    }

    @Test
    void keyProjectionReconstructsNestedEmbeddablesAndRecords() {
        persist();
        assertThat(em.createQuery("select key(c) from Parcel p join p.address.codes c", Zip.class).getResultList())
            .containsExactly(new Zip(75001, "Paris"));
        assertThat(em.createQuery("select key(r) from Parcel p join p.regions r", Region.class).getResultList())
            .containsExactly(new Region("North"));
    }

    @Test
    void keyAndValueOverridesConversionsAndQuotedColumnsRemainIndependent() throws SQLException {
        persist();
        assertThat(scalar("select \"KeyRegion\" from Parcel_pairs where \"KeyCode\" = 45000")).isEqualTo("db:Orleans");
        assertThat(scalar("select VALUE_REGION from Parcel_pairs where VALUE_CODE = 37000")).isEqualTo("Tours");
        em.clear();
        assertThat(em.find(Parcel.class, 1L).pairs).containsEntry(new Zip(45000, "Orleans"), new Zip(37000, "Tours"));
        try (ResultSet column = database.getMetaData().getColumns(null, null, "PARCEL_PAIRS", "KeyRegion")) {
            assertThat(column.next()).isTrue();
            assertThat(column.getInt("COLUMN_SIZE")).isEqualTo(512);
            assertThat(column.getInt("NULLABLE")).isZero();
        }
    }

    @Test
    void relationshipMapsUseTheSameEmbeddableKeyMappingAndMergeCopiesKeys() throws SQLException {
        Parcel parcel = persist();
        Warehouse warehouse = new Warehouse();
        warehouse.id = 2;
        transaction(() -> {
            em.persist(warehouse);
            parcel.depots.put(new Zip(59000, "Lille"), warehouse);
        });
        assertThat(scalar("select DEPOT_REGION from DEPOT_KEYS where DEPOT_CODE = 59000")).isEqualTo("Lille");
        em.clear();
        Parcel detached = em.find(Parcel.class, 1L);
        assertThat(detached.depots).containsOnlyKeys(new Zip(59000, "Lille"));
        em.clear();
        transaction(() -> {
            Parcel merged = em.merge(detached);
            assertThat(merged.depots.keySet().iterator().next()).isNotSameAs(detached.depots.keySet().iterator().next());
            merged.depots.keySet().iterator().next().code = 59001;
        });
        em.clear();
        assertThat(em.find(Parcel.class, 1L).depots).containsOnlyKeys(new Zip(59001, "Lille"));
    }

    @Entity(name = "Parcel")
    @Table(name = "PARCEL_TABLE")
    public static class Parcel {
        @Id public long id;
        @Embedded public Address address;
        @ElementCollection
        @jakarta.persistence.CollectionTable(name = "ROOT_CODES")
        @AttributeOverride(name = "key.code", column = @Column(name = "ROOT_CODE"))
        @AttributeOverride(name = "key.region.label", column = @Column(name = "ROOT_REGION"))
        public Map<Zip, String> codes = new LinkedHashMap<>();
        @ElementCollection public Map<Region, String> regions = new LinkedHashMap<>();
        @ElementCollection
        @AttributeOverride(name = "key.code", column = @Column(name = "\"KeyCode\""))
        @AttributeOverride(name = "key.region.label", column = @Column(name = "\"KeyRegion\"", length = 512, nullable = false))
        @AttributeOverride(name = "value.code", column = @Column(name = "VALUE_CODE"))
        @AttributeOverride(name = "value.region.label", column = @Column(name = "VALUE_REGION"))
        @Convert(attributeName = "key.region.label", converter = LabelConverter.class)
        public Map<Zip, Zip> pairs = new LinkedHashMap<>();
        @jakarta.persistence.ManyToMany
        @jakarta.persistence.JoinTable(name = "DEPOT_KEYS")
        @AttributeOverride(name = "key.code", column = @Column(name = "DEPOT_CODE"))
        @AttributeOverride(name = "key.region.label", column = @Column(name = "DEPOT_REGION"))
        public Map<Zip, Warehouse> depots = new LinkedHashMap<>();
        public Parcel() {}
    }

    @Entity(name = "Warehouse")
    public static class Warehouse {
        @Id public long id;
        @Embedded public Address address;
        public Warehouse() {}
    }

    @Embeddable
    public static class Address {
        @ElementCollection public Map<Zip, String> codes = new LinkedHashMap<>();
        @ElementCollection public List<Zip> list = new ArrayList<>();
        @ElementCollection public Set<Zip> set = new LinkedHashSet<>();
        public Address() {}
    }

    @Embeddable
    public static class Zip {
        public int code;
        @Embedded public Region region;
        public Zip() {}
        Zip(int code, String region) {
            this.code = code;
            this.region = new Region(region);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Zip zip && code == zip.code && Objects.equals(region, zip.region);
        }
        @Override public int hashCode() { return Objects.hash(code, region); }
    }

    @Embeddable
    public record Region(String label) {}

    @Converter
    public static class LabelConverter implements AttributeConverter<String, String> {
        public LabelConverter() {}
        @Override public String convertToDatabaseColumn(String label) { return label == null ? null : "db:" + label; }
        @Override public String convertToEntityAttribute(String label) { return label == null ? null : label.substring(3); }
    }
}
