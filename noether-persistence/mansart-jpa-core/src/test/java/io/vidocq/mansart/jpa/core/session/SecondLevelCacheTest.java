/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import jakarta.persistence.Cache;
import jakarta.persistence.Cacheable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, §3.10: shared cache state is committed, isolated and explicitly evictable. */
class SecondLevelCacheTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private Connection database;
    private EntityManagerFactory emf;

    @BeforeEach
    void open() throws Exception {
        String url = "jdbc:h2:mem:l2-cache-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        database = DriverManager.getConnection(url, "sa", "");
        emf = factory(url, SharedCacheMode.ENABLE_SELECTIVE, Map.of());
    }

    @AfterEach
    void close() throws Exception {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
        database.close();
    }

    @Test
    void publicationUsesFlushedStateRatherThanLiveInstancesAfterJdbcCommit() throws Exception {
        String url = "jdbc:h2:mem:cache-stage-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                Statement ddl = connection.createStatement()) {
            ddl.execute("create table CacheEntry (id bigint primary key, name varchar(100))");
        }
        var live = new java.util.concurrent.atomic.AtomicReference<CacheEntry>();
        DataSource source = new CountingDataSource(url) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection delegate = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (method.getName().equals("commit") && live.get() != null) {
                                live.get().name = "not flushed";
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
        };
        try (EntityManagerFactory staged = new PersistenceConfiguration("cache-stage")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(CacheEntry.class).property("jakarta.persistence.nonJtaDataSource", source)
                .sharedCacheMode(SharedCacheMode.ENABLE_SELECTIVE).createEntityManagerFactory();
                EntityManager writer = staged.createEntityManager()) {
            writer.getTransaction().begin();
            CacheEntry entry = new CacheEntry(85L, "flushed");
            live.set(entry);
            writer.persist(entry);
            writer.getTransaction().commit();
            try (EntityManager reader = staged.createEntityManager()) {
                assertThat(reader.find(CacheEntry.class, 85L).name).isEqualTo("flushed");
            }
        }
    }

    @Test
    void classEvictionFencesStaleFillsAndEvictsGraphsReferencingThatClass() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        CacheTarget target = new CacheTarget(80L, "target");
        em.persist(target);
        em.persist(new CacheOwner(81L, target));
        em.getTransaction().commit();
        SecondLevelCache cache = (SecondLevelCache) ((EntityManagerFactoryImpl) emf).cache();
        long generation = cache.generation();
        cache.evict(CacheTarget.class);
        assertThat(cache.contains(CacheOwner.class, 81L)).isFalse();
        cache.store(((EntityManagerFactoryImpl) emf).mapping().entity(CacheTarget.class).orElseThrow(),
            target, generation);
        assertThat(cache.contains(CacheTarget.class, 80L)).isFalse();
        em.close();
    }

    @Test
    void subtypeMissDoesNotPopulateTheContextOrClaimToContainSiblingTypes() {
        EntityManagerFactoryImpl implementation = (EntityManagerFactoryImpl) emf;
        SecondLevelCache cache = (SecondLevelCache) implementation.cache();
        MappedEntity root = implementation.mapping().entity(CacheEntry.class).orElseThrow();
        MappedEntity sibling = implementation.mapping().entity(CacheSibling.class).orElseThrow();
        cache.store(root, new CacheEntry(82L, "root"), cache.generation());
        var context = new io.vidocq.mansart.jpa.core.context.PersistenceContext();
        assertThat(cache.restore(sibling, 82L, context)).isEmpty();
        assertThat(context.entries()).isEmpty();
        assertThat(cache.contains(CacheSibling.class, 82L)).isFalse();
        assertThat(cache.contains(CacheEntry.class, 82L)).isTrue();
    }

    @Test
    void storageUsesActualSubtypeAndItsCacheabilityForEverySharedCacheMode() {
        EntityManagerFactoryImpl implementation = (EntityManagerFactoryImpl) emf;
        MappedEntity root = implementation.mapping().entity(CacheEntry.class).orElseThrow();
        MappedEntity child = implementation.mapping().entity(CacheChild.class).orElseThrow();
        for (SharedCacheMode mode : SharedCacheMode.values()) {
            SecondLevelCache cache = new SecondLevelCache(implementation.mapping(), mode);
            CacheChild instance = new CacheChild();
            instance.id = 83L;
            instance.name = "child";
            cache.store(root, instance, cache.generation());
            boolean enabled = mode == SharedCacheMode.ALL;
            assertThat(cache.contains(CacheEntry.class, 83L)).isEqualTo(enabled);
            assertThat(cache.contains(CacheChild.class, 83L)).isEqualTo(enabled);
            var context = new io.vidocq.mansart.jpa.core.context.PersistenceContext();
            assertThat(cache.restore(root, 83L, context).isPresent()).isEqualTo(enabled);
            if (enabled) {
                assertThat(cache.restore(child, 83L, context).orElseThrow()).isInstanceOf(CacheChild.class);
            }
        }
    }

    @Test
    void dirtyTransactionalReadCandidatesAreNotPublishedAsCommittedState() throws Exception {
        try (Statement insert = database.createStatement()) {
            insert.executeUpdate("insert into CacheEntry (id, name, DTYPE) values (84, 'committed', 'CacheEntry')");
        }
        try (EntityManager em = emf.createEntityManager()) {
            em.getTransaction().begin();
            em.setFlushMode(jakarta.persistence.FlushModeType.COMMIT);
            CacheEntry entry = em.find(CacheEntry.class, 84L);
            entry.name = "dirty";
            em.flush();
            try (EntityManager other = emf.createEntityManager()) {
                assertThat(other.find(CacheEntry.class, 84L).name).isEqualTo("committed");
            }
            entry.name = "last committed";
            em.getTransaction().commit();
        }
        try (EntityManager em = emf.createEntityManager()) {
            assertThat(em.find(CacheEntry.class, 84L).name).isEqualTo("last committed");
        }
    }

    @Test
    void publishesOnlyCommittedDeepCopiesAndEvictsByHierarchyIdentity() {
        EntityManager first = emf.createEntityManager();
        CacheEntry entry = new CacheEntry(1L, "committed");
        first.getTransaction().begin();
        first.persist(entry);
        assertThat(emf.getCache().contains(CacheEntry.class, 1L)).isFalse();
        first.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 1L)).isTrue();

        entry.name = "uncommitted in-memory mutation";
        first.clear();
        EntityManager second = emf.createEntityManager();
        assertThat(second.find(CacheEntry.class, 1L).name).isEqualTo("committed");
        second.close();

        Cache cache = emf.getCache();
        cache.evict(CacheEntry.class, 1L);
        assertThat(cache.contains(CacheEntry.class, 1L)).isFalse();
        first.close();
    }

    @Test
    void doesNotPublishBeforeCommitOrAfterRollback() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(2L, "rolled back"));
        em.getTransaction().rollback();
        assertThat(emf.getCache().contains(CacheEntry.class, 2L)).isFalse();
        em.close();
    }

    @Test
    void cacheEntriesAreScopedToTheirEntityManagerFactory() throws Exception {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(3L, "factory one"));
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 3L)).isTrue();

        String otherUrl = "jdbc:h2:mem:l2-cache-isolated-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        try (EntityManagerFactory other = factory(otherUrl, SharedCacheMode.ENABLE_SELECTIVE, Map.of())) {
            assertThat(other.getCache().contains(CacheEntry.class, 3L)).isFalse();
            EntityManager second = other.createEntityManager();
            second.getTransaction().begin();
            second.persist(new CacheEntry(3L, "factory two"));
            second.getTransaction().commit();
            assertThat(other.getCache().contains(CacheEntry.class, 3L)).isTrue();
            assertThat(emf.getCache().contains(CacheEntry.class, 3L)).isTrue();
            second.close();
        }
        em.close();
    }

    @Test
    void cachedAssociationGraphsUseIdentityReferencesAndRestoreThePersistenceContext() {
        EntityManager em = emf.createEntityManager();
        CacheTarget target = new CacheTarget(10L, "committed target");
        CacheOwner owner = new CacheOwner(11L, target);
        em.getTransaction().begin();
        em.persist(target);
        em.persist(owner);
        em.getTransaction().commit();

        target.name = "later in-memory mutation";
        em.clear();
        CacheOwner restored = em.find(CacheOwner.class, 11L);
        assertThat(restored.target.name).isEqualTo("committed target");
        assertThat(em.find(CacheTarget.class, 10L)).isSameAs(restored.target);
        em.close();
    }

    @Test
    void cacheModesAndBulkUpdatesControlRetrievalStorageAndInvalidation() {
        EntityManager em = emf.createEntityManager(Map.of("jakarta.persistence.cache.storeMode", CacheStoreMode.BYPASS));
        em.getTransaction().begin();
        em.persist(new CacheEntry(20L, "original"));
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 20L)).isFalse();
        em.close();

        em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(21L, "original"));
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 21L)).isTrue();

        try (Statement update = database.createStatement()) {
            update.executeUpdate("update CacheEntry set name='database value' where id=21");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        EntityManager bypass = emf.createEntityManager();
        bypass.setCacheRetrieveMode(CacheRetrieveMode.BYPASS);
        assertThat(bypass.find(CacheEntry.class, 21L).name).isEqualTo("database value");
        bypass.close();

        em.getTransaction().begin();
        em.createQuery("update CacheEntry c set c.name = :name").setParameter("name", "bulk value").executeUpdate();
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 21L)).isFalse();
        em.clear();
        assertThat(em.find(CacheEntry.class, 21L).name).isEqualTo("bulk value");
        em.close();
    }

    @Test
    void refreshedFindReplacesCachedStateAndFlushedChangesAreVisibleWithinTheTransaction() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(22L, "original"));
        em.getTransaction().commit();

        try (Statement update = database.createStatement()) {
            update.executeUpdate("update CacheEntry set name='database refresh' where id=22");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        EntityManager refresh = emf.createEntityManager();
        refresh.setCacheStoreMode(CacheStoreMode.REFRESH);
        assertThat(refresh.find(CacheEntry.class, 22L).name).isEqualTo("database refresh");
        refresh.close();
        EntityManager cached = emf.createEntityManager();
        assertThat(cached.find(CacheEntry.class, 22L).name).isEqualTo("database refresh");
        cached.getTransaction().begin();
        CacheEntry changed = cached.find(CacheEntry.class, 22L);
        changed.name = "transaction update";
        cached.flush();
        cached.clear();
        assertThat(cached.find(CacheEntry.class, 22L).name).isEqualTo("transaction update");
        cached.getTransaction().rollback();
        cached.close();

        EntityManager unchanged = emf.createEntityManager();
        assertThat(unchanged.find(CacheEntry.class, 22L).name).isEqualTo("database refresh");
        unchanged.close();
    }

    @Test
    void rolledBackNativeBulkUpdatesRetainCommittedCacheEntries() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(23L, "committed"));
        em.getTransaction().commit();
        em.getTransaction().begin();
        em.createNativeQuery("update CacheEntry set name='rolled back' where id=23").executeUpdate();
        em.getTransaction().rollback();
        assertThat(emf.getCache().contains(CacheEntry.class, 23L)).isTrue();
        em.clear();
        assertThat(em.find(CacheEntry.class, 23L).name).isEqualTo("committed");
        em.close();
    }

    @Test
    void committedNativeBulkUpdatesInvalidateSharedEntries() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(27L, "original"));
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 27L)).isTrue();

        em.getTransaction().begin();
        em.createNativeQuery("update CacheEntry set name='native value' where id=27").executeUpdate();
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 27L)).isFalse();
        em.clear();
        assertThat(em.find(CacheEntry.class, 27L).name).isEqualTo("native value");
        em.close();
    }

    @Test
    void publishesTransactionalReadsOnlyAfterCommitAndFencesStaleFills() throws Exception {
        try (Statement insert = database.createStatement()) {
            insert.executeUpdate("insert into CacheEntry (id, name) values (24, 'database row')");
        }
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        assertThat(em.find(CacheEntry.class, 24L).name).isEqualTo("database row");
        assertThat(emf.getCache().contains(CacheEntry.class, 24L)).isFalse();
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 24L)).isTrue();
        em.close();

        EntityManagerFactoryImpl implementation = (EntityManagerFactoryImpl) emf;
        SecondLevelCache cache = (SecondLevelCache) implementation.cache();
        long generation = cache.generation();
        MappedEntity mapped = implementation.mapping().entity(CacheEntry.class).orElseThrow();
        cache.invalidateAll();
        cache.store(mapped, new CacheEntry(25L, "stale database read"), generation);
        assertThat(emf.getCache().contains(CacheEntry.class, 25L)).isFalse();
    }

    @Test
    void runsCacheCompletionAfterCommitWhenConnectionReleaseFails() throws Exception {
        String url = "jdbc:h2:mem:cache-release-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        try (Connection keepAlive = DriverManager.getConnection(url, "sa", "");
                Statement ddl = keepAlive.createStatement()) {
            ddl.execute("create table CacheEntry (id bigint primary key, name varchar(100))");
        }
        AtomicBoolean failClose = new AtomicBoolean(true);
        DataSource source = new CountingDataSource(url) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection delegate = super.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(delegate, arguments);
                            if (method.getName().equals("close") && failClose.compareAndSet(true, false)) {
                                throw new SQLException("simulated connection release failure");
                            }
                            return result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
        };
        EntityManagerFactory failingEmf = new PersistenceConfiguration("cache-release")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(CacheEntry.class)
            .property("jakarta.persistence.nonJtaDataSource", source)
            .sharedCacheMode(SharedCacheMode.ENABLE_SELECTIVE)
            .createEntityManagerFactory();
        try {
            EntityManager em = failingEmf.createEntityManager();
            em.getTransaction().begin();
            em.persist(new CacheEntry(26L, "committed"));
            assertThatThrownBy(() -> em.getTransaction().commit()).isInstanceOf(PersistenceException.class);
            assertThat(failingEmf.getCache().contains(CacheEntry.class, 26L)).isTrue();
            em.close();
        } finally {
            failingEmf.close();
        }
    }

    @Test
    void sharedCacheModesRespectCacheableDefaults() throws Exception {
        String url = "jdbc:h2:mem:cache-modes-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        emf.close();
        emf = factory(url, SharedCacheMode.NONE, Map.of());
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(new CacheEntry(30L, "disabled"));
        em.getTransaction().commit();
        assertThat(emf.getCache().contains(CacheEntry.class, 30L)).isFalse();
        em.close();
    }

    private EntityManagerFactory factory(String url, SharedCacheMode mode, Map<String, Object> overrides) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "sa", ""); Statement ddl = connection.createStatement()) {
            ddl.execute("create table CacheEntry (id bigint primary key, name varchar(100), DTYPE varchar(31) default 'CacheEntry')");
            ddl.execute("create table CacheTarget (id bigint primary key, name varchar(100))");
            ddl.execute("create table CacheOwner (id bigint primary key, target_id bigint)");
        }
        PersistenceConfiguration configuration = new PersistenceConfiguration("cache-" + DATABASES.get())
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(CacheEntry.class).managedClass(CacheChild.class).managedClass(CacheSibling.class)
            .managedClass(CacheOwner.class).managedClass(CacheTarget.class)
            .property(PersistenceConfiguration.JDBC_URL, url)
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .sharedCacheMode(mode);
        overrides.forEach(configuration::property);
        return configuration.createEntityManagerFactory();
    }

    @Entity
    @Cacheable
    public static class CacheEntry {
        @Id
        public Long id;
        public String name;

        public CacheEntry() {
        }

        CacheEntry(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Cacheable
    public static class CacheTarget {
        @Id
        public Long id;
        public String name;

        public CacheTarget() {
        }

        CacheTarget(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    @Entity
    @Cacheable(false)
    public static class CacheChild extends CacheEntry {
    }

    @Entity
    @Cacheable
    public static class CacheSibling extends CacheEntry {
    }

    @Entity
    @Cacheable
    public static class CacheOwner {
        @Id
        public Long id;
        @ManyToOne
        public CacheTarget target;

        public CacheOwner() {
        }

        CacheOwner(Long id, CacheTarget target) {
            this.id = id;
            this.target = target;
        }
    }
}
