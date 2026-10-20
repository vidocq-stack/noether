/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** §3.3.5, §3.6.3, §4.8: result-row hydration keeps managed identity and lifecycle semantics. */
class QueryRowHydrationTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private EntityManagerFactory factory;
    private EntityManager em;
    private Connection database;

    @BeforeEach
    void open() throws SQLException {
        String url = "jdbc:h2:mem:query-rows-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        factory = new PersistenceConfiguration("query-rows")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(Row.class).managedClass(LabelConverter.class).managedClass(Node.class)
            .sharedCacheMode(SharedCacheMode.ALL)
            .property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property("jakarta.persistence.schema-generation.database.action", "create")
            .createEntityManagerFactory();
        em = factory.createEntityManager();
        em.getTransaction().begin();
        for (int id = 1; id <= 32; id++) em.persist(new Row(id, "row-" + id));
        Node parent = new Node(2, "parent", null);
        em.persist(parent);
        em.persist(new Node(1, "child", parent));
        em.getTransaction().commit();
        em.clear();
        factory.getCache().evictAll();
        Row.LOADED.clear();
        Node.LOADED.clear();
        resetStatistics();
    }

    @AfterEach
    void close() throws SQLException {
        if (factory != null) factory.close();
        if (database != null) database.close();
    }

    private void resetStatistics() throws SQLException {
        try (var sql = database.createStatement()) {
            sql.execute("SET QUERY_STATISTICS FALSE");
            sql.execute("SET QUERY_STATISTICS TRUE");
        }
    }

    private long selects() throws SQLException {
        try (var sql = database.createStatement();
             var result = sql.executeQuery("""
                 SELECT COALESCE(SUM(EXECUTION_COUNT), 0) FROM INFORMATION_SCHEMA.QUERY_STATISTICS
                 WHERE LOWER(SQL_STATEMENT) LIKE 'select%'
                   AND LOWER(SQL_STATEMENT) NOT LIKE '%information_schema.query_statistics%'
                 """)) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    void thirtyTwoEntitiesAreHydratedInOneSelectWithConvertedStateAndCallbacks() throws SQLException {
        List<Row> rows = em.createQuery("select r from QrRow r order by r.id", Row.class).getResultList();
        assertThat(rows).hasSize(32);
        assertThat(rows.getFirst().label).isEqualTo("row-1");
        assertThat(rows.getLast().label).isEqualTo("row-32");
        assertThat(Row.LOADED).hasSize(32).containsExactlyElementsOf(rows);
        assertThat(rows).allSatisfy(row -> {
            assertThat(em.contains(row)).isTrue();
            assertThat(em.find(Row.class, row.id)).isSameAs(row);
        });
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void criteriaPageUsesOneSelectAndDoesNotLoadOutsideThePage() throws SQLException {
        var builder = em.getCriteriaBuilder();
        var query = builder.createQuery(Row.class);
        var root = query.from(Row.class);
        query.select(root).orderBy(builder.asc(root.get("id")));
        List<Row> rows = em.createQuery(query).setFirstResult(10).setMaxResults(3).getResultList();
        assertThat(rows).extracting(row -> row.id).containsExactly(11, 12, 13);
        assertThat(Row.LOADED).containsExactlyElementsOf(rows);
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void queryKeepsDirtyManagedStateAndDoesNotRepeatPostLoad() throws SQLException {
        Row managed = em.find(Row.class, 1);
        managed.label = "dirty";
        resetStatistics();
        List<Row> rows = em.createQuery("select r from QrRow r order by r.id", Row.class)
            .setFlushMode(FlushModeType.COMMIT).getResultList();
        assertThat(rows.getFirst()).isSameAs(managed);
        assertThat(managed.label).isEqualTo("dirty");
        assertThat(Row.LOADED).hasSize(32);
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void snapshotsAvoidSpuriousWritesAndRetainVersionedUpdates() throws SQLException {
        Row row = em.createQuery("select r from QrRow r where r.id = 1", Row.class).getSingleResult();
        long initial = row.version;
        em.getTransaction().begin();
        em.getTransaction().commit();
        assertThat(row.version).isEqualTo(initial);
        em.getTransaction().begin();
        row.label = "updated";
        em.getTransaction().commit();
        assertThat(row.version).isEqualTo(initial + 1);
        em.clear();
        factory.getCache().evictAll();
        assertThat(em.find(Row.class, 1).label).isEqualTo("updated");
    }

    @Test
    void allSelectedIdentitiesAreRegisteredBeforeEagerRelationshipsAndCallbacks() throws SQLException {
        List<Node> nodes = em.createQuery("select n from QrNode n order by n.id", Node.class).getResultList();
        assertThat(nodes).hasSize(2);
        assertThat(nodes.getFirst().parent).isSameAs(nodes.getLast());
        assertThat(Node.LOADED).containsExactlyInAnyOrder("child:parent", "parent");
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void queryUsesSharedCacheStateLikeFindAndStoresNewRows() throws SQLException {
        Row original = em.find(Row.class, 1);
        em.clear();
        try (var sql = database.createStatement()) {
            sql.executeUpdate("UPDATE QR_ROW SET label = 'db:external' WHERE id = 1");
        }
        resetStatistics();
        Row cached = em.createQuery("select r from QrRow r where r.id = 1", Row.class).getSingleResult();
        assertThat(cached.label).isEqualTo(original.label);
        assertThat(selects()).isEqualTo(1);
        em.clear();
        Row fresh = em.createQuery("select r from QrRow r where r.id = 2", Row.class).getSingleResult();
        assertThat(factory.getCache().contains(Row.class, fresh.id)).isTrue();
        em.clear();
        resetStatistics();
        assertThat(em.find(Row.class, 2).label).isEqualTo("row-2");
        assertThat(selects()).isZero();
    }

    @Test
    void cacheBypassLoadsDatabaseRowsWithoutPublishingThem() throws SQLException {
        em.setProperty("jakarta.persistence.cache.retrieveMode", CacheRetrieveMode.BYPASS);
        em.setProperty("jakarta.persistence.cache.storeMode", CacheStoreMode.BYPASS);
        Row row = em.createQuery("select r from QrRow r where r.id = 1", Row.class).getSingleResult();
        assertThat(row.label).isEqualTo("row-1");
        assertThat(factory.getCache().contains(Row.class, 1)).isFalse();
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void scalarAggregateAndDistinctQueriesRetainTheirResults() throws SQLException {
        assertThat(em.createQuery("select r.label from QrRow r where r.id = 1", String.class).getSingleResult())
            .isEqualTo("row-1");
        assertThat(Row.LOADED).isEmpty();
        assertThat(em.createQuery("select count(r) from QrRow r", Long.class).getSingleResult()).isEqualTo(32);
        assertThat(em.createQuery("select distinct r from QrRow r order by r.id", Row.class).getResultList()).hasSize(32);
        assertThat(selects()).isEqualTo(35); // two scalar queries, then the unchanged DISTINCT key/reload path
    }

    @Test
    void repeatedRowsKeepResultMultiplicityButRegisterAndNotifyEachEntityOnce() throws SQLException {
        List<Row> rows = em.createQuery("""
            select r from QrRow r, QrRow other
            where r.id = 1 and other.id <= 3 order by other.id
            """, Row.class).getResultList();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(row).isSameAs(rows.getFirst()));
        assertThat(Row.LOADED).containsExactly(rows.getFirst());
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void multipleSelectedRootsAndNullableScalarsKeepTheirColumnOffsets() throws SQLException {
        try (var sql = database.createStatement()) {
            sql.executeUpdate("UPDATE QR_ROW SET label = NULL WHERE id = 1");
        }
        List<Object[]> rows = em.createQuery("""
            select a, a.label, b from QrRow a, QrRow b
            where a.id = 1 and b.id = 2
            """, Object[].class).getResultList();
        assertThat(rows).hasSize(1);
        assertThat(((Row) rows.getFirst()[0]).label).isNull();
        assertThat(rows.getFirst()[1]).isNull();
        assertThat(((Row) rows.getFirst()[2]).label).isEqualTo("row-2");
        assertThat(Row.LOADED).hasSize(2);
        assertThat(selects()).isEqualTo(1);
    }

    @Test
    void rebindingAndEmptyPagesDoNotReuseEarlierHydratedRows() throws SQLException {
        var query = em.createQuery("select r from QrRow r where r.id = :id", Row.class);
        assertThat(query.setParameter("id", 1).getSingleResult().id).isEqualTo(1);
        assertThat(query.setParameter("id", 2).getSingleResult().id).isEqualTo(2);
        assertThat(query.setParameter("id", 99).getResultList()).isEmpty();
        assertThat(query.setMaxResults(0).getResultList()).isEmpty();
        assertThat(Row.LOADED).hasSize(2);
        assertThat(selects()).isEqualTo(3);
    }

    @Test
    void hydrationWithinATransactionPublishesCacheOnlyAfterCommit() throws SQLException {
        em.getTransaction().begin();
        Row row = em.createQuery("select r from QrRow r where r.id = 1", Row.class).getSingleResult();
        assertThat(factory.getCache().contains(Row.class, 1)).isFalse();
        row.label = "transactional";
        em.getTransaction().commit();
        assertThat(factory.getCache().contains(Row.class, 1)).isTrue();
        em.clear();
        resetStatistics();
        assertThat(em.find(Row.class, 1).label).isEqualTo("transactional");
        assertThat(selects()).isZero();
    }

    @Entity(name = "QrRow")
    @Table(name = "QR_ROW")
    @Cacheable
    public static class Row {
        static final List<Row> LOADED = new ArrayList<>();
        @Id int id;
        @Convert(converter = LabelConverter.class) String label;
        @Version long version;
        public Row() {}
        Row(int id, String label) { this.id = id; this.label = label; }
        @PostLoad void loaded() { LOADED.add(this); }
    }

    @Converter
    public static class LabelConverter implements AttributeConverter<String, String> {
        public LabelConverter() {}
        @Override public String convertToDatabaseColumn(String value) { return value == null ? null : "db:" + value; }
        @Override public String convertToEntityAttribute(String value) { return value == null ? null : value.substring(3); }
    }

    @Entity(name = "QrNode")
    @Table(name = "QR_NODE")
    public static class Node {
        static final List<String> LOADED = new ArrayList<>();
        @Id int id;
        String name;
        @ManyToOne Node parent;
        public Node() {}
        Node(int id, String name, Node parent) { this.id = id; this.name = name; this.parent = parent; }
        @PostLoad void loaded() { LOADED.add(parent == null ? name : name + ":" + parent.name); }
    }
}
