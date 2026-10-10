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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.bench;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 4, time = 2)
@Threads(1)
public class JpaProviderBenchmark {
    private static final int INITIAL_ROWS = 128;
    private static final String PROVIDER = System.getProperty("bench.provider", "");
    private static final String JDBC_URL = "jdbc:h2:mem:jpa_bench;DB_CLOSE_DELAY=-1";

    @State(Scope.Benchmark)
    public static class Database {
        EntityManagerFactory factory;

        @Setup(Level.Trial)
        public void createDatabase() throws Exception {
            if (PROVIDER.isBlank()) {
                throw new IllegalStateException("Set -Dbench.provider to one explicit PersistenceProvider class");
            }
            Map<String, Object> properties = new HashMap<>();
            properties.put("jakarta.persistence.jdbc.driver", "org.h2.Driver");
            properties.put("jakarta.persistence.jdbc.url", JDBC_URL);
            properties.put("jakarta.persistence.jdbc.user", "sa");
            properties.put("jakarta.persistence.jdbc.password", "");
            properties.put("jakarta.persistence.schema-generation.database.action", "none");
            properties.put("jakarta.persistence.sharedCache.mode", "NONE");
            properties.put("jakarta.persistence.validation.mode", "NONE");
            properties.put("hibernate.hbm2ddl.auto", "none");
            properties.put("hibernate.cache.use_second_level_cache", "false");
            properties.put("hibernate.cache.use_query_cache", "false");
            properties.put("eclipselink.ddl-generation", "none");
            properties.put("eclipselink.cache.shared.default", "false");
            properties.put("eclipselink.weaving", "false");

            var provider = PersistenceProviderResolverHolder.getPersistenceProviderResolver()
                .getPersistenceProviders().stream()
                .filter(candidate -> candidate.getClass().getName().equals(PROVIDER))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Provider not present in this isolated profile: " + PROVIDER));

            PersistenceConfiguration configuration = new PersistenceConfiguration("jpa-benchmark")
                .provider(PROVIDER)
                .managedClass(BenchRecord.class);
            properties.forEach(configuration::property);

            try (var connection = java.sql.DriverManager.getConnection(JDBC_URL, "sa", "");
                    var statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS bench_record");
                statement.execute("CREATE TABLE bench_record (id BIGINT PRIMARY KEY, category INTEGER NOT NULL, "
                    + "label VARCHAR(100) NOT NULL, revision BIGINT NOT NULL)");
            }

            factory = provider.createEntityManagerFactory(configuration);
            if (factory == null) {
                throw new IllegalStateException("Selected provider declined the benchmark persistence unit: " + PROVIDER);
            }
            inTransaction(factory, em -> {
                for (long id = 1; id <= INITIAL_ROWS; id++) {
                    em.persist(new BenchRecord(id, (int) (id % 4), "seed-" + id));
                }
            });
            verifyFixture(factory);
            verifyWrites(factory);
            verifyTimedUpdateWritesEachInvocation(this);
        }

        @TearDown(Level.Trial)
        public void closeFactory() {
            if (factory != null) {
                factory.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class Cursor {
        private final AtomicInteger next = new AtomicInteger();

        long nextId() {
            return 1L + Math.floorMod(next.getAndIncrement(), INITIAL_ROWS);
        }
    }

    @Benchmark
    public long findById(Database database, Cursor cursor) {
        long id = cursor.nextId();
        try (EntityManager em = database.factory.createEntityManager()) {
            em.getTransaction().begin();
            BenchRecord record = em.find(BenchRecord.class, id);
            if (record == null || record.id() != id) {
                throw new IllegalStateException("Missing fixture row " + id);
            }
            em.getTransaction().commit();
            return record.id();
        }
    }

    @Benchmark
    public int jpqlQuery(Database database) {
        try (EntityManager em = database.factory.createEntityManager()) {
            em.getTransaction().begin();
            var rows = em.createQuery("select r from BenchRecord r where r.category = :category order by r.id",
                BenchRecord.class).setParameter("category", 1).getResultList();
            if (rows.size() != INITIAL_ROWS / 4) {
                throw new IllegalStateException("Unexpected query result size: " + rows.size());
            }
            em.getTransaction().commit();
            return rows.size();
        }
    }

    @Benchmark
    public long update(Database database, Cursor cursor) {
        long id = cursor.nextId();
        try (EntityManager em = database.factory.createEntityManager()) {
            em.getTransaction().begin();
            BenchRecord record = em.find(BenchRecord.class, id);
            if (record == null) {
                throw new IllegalStateException("Missing fixture row " + id);
            }
            // The label embeds the next version, so every invocation is a real dirty change and UPDATE.
            record.label("updated-" + id + "-r" + (record.revision() + 1));
            em.getTransaction().commit();
            return record.revision();
        }
    }

    @Benchmark
    public long createUpdateDelete(Database database, Cursor cursor) {
        long id = INITIAL_ROWS + 1L + Math.floorMod(cursor.next.getAndIncrement(), 1_000_000);
        try (EntityManager em = database.factory.createEntityManager()) {
            em.getTransaction().begin();
            BenchRecord created = new BenchRecord(id, 3, "created-" + id);
            em.persist(created);
            em.flush();
            created.label("updated-" + id);
            em.flush();
            em.remove(created);
            em.getTransaction().commit();
            return id;
        }
    }

    private static void verifyFixture(EntityManagerFactory factory) throws Exception {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            long count = em.createQuery("select count(r) from BenchRecord r", Long.class).getSingleResult();
            var rows = em.createQuery("select r from BenchRecord r where r.category = :category order by r.id",
                BenchRecord.class).setParameter("category", 1).getResultList();
            BenchRecord found = em.find(BenchRecord.class, 1L);
            if (count != INITIAL_ROWS || rows.size() != INITIAL_ROWS / 4 || found == null
                    || !"seed-1".equals(found.label())) {
                throw new IllegalStateException("Provider fixture verification failed: count=" + count
                    + ", categoryRows=" + rows.size() + ", id1=" + found);
            }
            em.getTransaction().commit();
        }
        try (var connection = java.sql.DriverManager.getConnection(JDBC_URL, "sa", "");
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT COUNT(*) FROM bench_record")) {
            result.next();
            if (result.getLong(1) != INITIAL_ROWS) {
                throw new IllegalStateException("Direct database count differs from the prepared fixture");
            }
        }
    }

    private static void verifyWrites(EntityManagerFactory factory) {
        inTransaction(factory, em -> {
            BenchRecord record = em.find(BenchRecord.class, 1L);
            record.label("preflight-update");
        });
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            BenchRecord updated = em.find(BenchRecord.class, 1L);
            if (!"preflight-update".equals(updated.label()) || updated.revision() < 1) {
                throw new IllegalStateException("Provider did not persist a versioned update");
            }
            updated.label("seed-1");
            em.getTransaction().commit();
        }

        inTransaction(factory, em -> {
            BenchRecord created = new BenchRecord(INITIAL_ROWS + 1L, 3, "preflight-create");
            em.persist(created);
            em.flush();
            em.clear();
            BenchRecord reloaded = em.find(BenchRecord.class, INITIAL_ROWS + 1L);
            if (reloaded == null || !"preflight-create".equals(reloaded.label())) {
                throw new IllegalStateException("Provider did not persist a created row");
            }
            reloaded.label("preflight-create-update");
            em.flush();
            em.clear();
            reloaded = em.find(BenchRecord.class, INITIAL_ROWS + 1L);
            if (reloaded == null || !"preflight-create-update".equals(reloaded.label())) {
                throw new IllegalStateException("Provider did not persist an update after insert");
            }
            em.remove(reloaded);
        });
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            long count = em.createQuery("select count(r) from BenchRecord r", Long.class).getSingleResult();
            if (count != INITIAL_ROWS || em.find(BenchRecord.class, INITIAL_ROWS + 1L) != null) {
                throw new IllegalStateException("Provider CRUD preflight left an unexpected fixture state");
            }
            em.getTransaction().commit();
        }
    }

    /**
     * Runs the timed {@code update} workload twice on the same row (a fresh cursor always yields id 1)
     * and checks through plain JDBC that each invocation persisted a new label and a new version.
     */
    private static void verifyTimedUpdateWritesEachInvocation(Database database) throws Exception {
        JpaProviderBenchmark benchmark = new JpaProviderBenchmark();
        RowState before = readRow(1L);
        long firstResult = benchmark.update(database, new Cursor());
        RowState afterFirst = readRow(1L);
        requireUpdated("first", before, afterFirst);
        long secondResult = benchmark.update(database, new Cursor());
        RowState afterSecond = readRow(1L);
        requireUpdated("second", afterFirst, afterSecond);
        if (firstResult != afterFirst.revision() || secondResult != afterSecond.revision()) {
            throw new IllegalStateException("Timed update must return the persisted version: returned "
                + firstResult + "/" + secondResult + ", persisted " + afterFirst.revision() + "/"
                + afterSecond.revision());
        }
    }

    private record RowState(String label, long revision) {
    }

    private static RowState readRow(long id) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(JDBC_URL, "sa", "");
                var statement = connection.prepareStatement("SELECT label, revision FROM bench_record WHERE id = ?")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Missing fixture row " + id);
                }
                return new RowState(result.getString(1), result.getLong(2));
            }
        }
    }

    private static void requireUpdated(String invocation, RowState before, RowState after) {
        if (after.label().equals(before.label()) || after.revision() != before.revision() + 1) {
            throw new IllegalStateException("Timed update invocation " + invocation
                + " did not persist a label change and one version increment: before=" + before
                + ", after=" + after);
        }
    }

    private static void inTransaction(EntityManagerFactory factory, java.util.function.Consumer<EntityManager> work) {
        try (EntityManager em = factory.createEntityManager()) {
            em.getTransaction().begin();
            work.accept(em);
            em.getTransaction().commit();
        }
    }
}
