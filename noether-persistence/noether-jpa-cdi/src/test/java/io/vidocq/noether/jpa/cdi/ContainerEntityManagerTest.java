package io.vidocq.noether.jpa.cdi;

import io.vidocq.mansart.transactions.core.MansartTransactionManager;
import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors;
import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import io.vidocq.noether.jpa.jta.SynchronizationRegistryIntegration;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * §7.6/§7.7 and §3.3.2: the container facades of noether-jpa-cdi over what a CDI container hands a JTA unit, its
 * {@code TransactionSynchronizationRegistry} and a data source whose connections it enlists. A real transaction
 * manager completes real H2 connections; no API mock.
 */
class ContainerEntityManagerTest {
    private final MansartTransactionManager tm = new MansartTransactionManager();
    private final RegistryOverTransactionManager registry = new RegistryOverTransactionManager(tm);

    @Entity @Table(name = "jta_record")
    @NamedStoredProcedureQuery(name = "record.addOne", procedureName = "ADD_ONE", resultClasses = Integer.class,
        parameters = @StoredProcedureParameter(name = "value", mode = ParameterMode.IN, type = Integer.class))
    public static class Record {
        @Id public int id;
        public String description;
        public Record() {}
        Record(int id) { this.id = id; description = "before"; }
    }

    private static String url(String name) {
        return "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1";
    }

    private EntityManagerFactory factory(String name) {
        return new PersistenceConfiguration(name).managedClass(Record.class).managedClass(Owner.class)
            .transactionType(PersistenceUnitTransactionType.JTA)
            .property("jakarta.persistence.jtaDataSource", new EnlistingDataSource(url(name), tm))
            .property("jakarta.persistence.schema-generation.database.action", "drop-and-create")
            .property(TransactionIntegration.PROPERTY, new SynchronizationRegistryIntegration(registry))
            .createEntityManagerFactory();
    }

    @Entity @Table(name = "jta_owner")
    public static class Owner {
        @Id public int id;
        @ManyToOne public Record record;
        public Owner() {}
    }

    @Test
    void nestedFlushReadsReuseTheEnlistedConnectionForAutoQueriesAndCommit() throws Exception {
        assertThat(Thread.currentThread().isVirtual()).isFalse();
        for (boolean autoQuery : new boolean[] {true, false}) {
            try (var factory = factory("p9-nested-flush-" + autoQuery); var em = factory.createEntityManager()) {
                tm.begin();
                Record record = new Record(1);
                em.persist(record);
                em.flush();
                em.clear();
                Owner owner = new Owner();
                owner.id = 1;
                owner.record = record;
                em.persist(owner);
                if (autoQuery) assertThat(em.createQuery("SELECT COUNT(o) FROM Owner o", Long.class).getSingleResult()).isEqualTo(1);
                tm.commit();
                try (var reader = factory.createEntityManager()) {
                    assertThat(reader.find(Owner.class, 1).record.id).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void synchronizedCommitFlushesRollbackDetachesAndCloseDefersRelease() throws Exception {
        try (var factory = factory("p9-jta-commit")) {
            var em = factory.createEntityManager();
            assertThatThrownBy(em::getTransaction).isInstanceOf(IllegalStateException.class);
            assertThat(em.isJoinedToTransaction()).isFalse();
            tm.begin();
            Record record = new Record(1);
            em.persist(record);
            assertThat(em.isJoinedToTransaction()).isTrue();
            em.close();
            tm.commit();
            assertThat(em.isOpen()).isFalse();
            try (var reader = factory.createEntityManager()) {
                Record found = reader.find(Record.class, 1);
                assertThat(found.description).isEqualTo("before");
                tm.begin();
                found.description = "rolled back";
                reader.flush();
                tm.rollback();
                assertThat(reader.contains(found)).isFalse();
                assertThat(reader.find(Record.class, 1).description).isEqualTo("before");
            }
        }
    }

    @Test
    void unsynchronizedContextDoesNotFlushUntilExplicitJoin() throws Exception {
        try (var factory = factory("p9-jta-unsync"); var em = factory.createEntityManager(SynchronizationType.UNSYNCHRONIZED)) {
            tm.begin();
            em.persist(new Record(1));
            assertThat(em.isJoinedToTransaction()).isFalse();
            assertThatThrownBy(em::flush).isInstanceOf(TransactionRequiredException.class);
            tm.commit();
            try (var other = factory.createEntityManager()) { assertThat(other.find(Record.class, 1)).isNull(); }
            tm.begin();
            em.joinTransaction();
            assertThat(em.isJoinedToTransaction()).isTrue();
            tm.commit();
            try (var other = factory.createEntityManager()) { assertThat(other.find(Record.class, 1)).isNotNull(); }
        }
    }

    @Test
    void autoQueryFlushUsesTheCallerTransaction() throws Exception { // §3.11.2
        try (var factory = factory("p9-jta-auto-query"); var em = factory.createEntityManager()) {
            tm.begin();
            em.persist(new Record(1));
            long count = em.createQuery("SELECT COUNT(r) FROM Record r", Long.class).getSingleResult();
            assertThat(count).isEqualTo(1);
            tm.rollback();
        }
    }

    @Test
    void contextQueriesResolveIdentityAtExecutionAndReadDetachedWithoutATransaction() throws Exception { // §7.7.1
        try (var factory = factory("p9-context-query")) {
            var em = facade(factory);
            var query = em.createQuery("SELECT r FROM Record r WHERE r.id = :id", Record.class).setParameter("id", 1);
            tm.begin();
            var record = new Record(1);
            em.persist(record);
            assertThat(query.getSingleResult()).isSameAs(record);
            tm.commit();
            Record detached = query.getSingleResult();
            assertThat(detached.id).isEqualTo(1);
            assertThat(em.contains(detached)).isFalse();
            em.destroy();
        }
    }

    /** §7.6.2, §7.9.1: one context per transaction, released with it; a rollback-only transaction is not flushed. */
    @Test
    void theTransactionScopedContextFollowsTheRegistryTransaction() throws Exception {
        try (var factory = factory("p9-registry-scope")) {
            var first = facade(factory);
            var second = facade(factory);
            try {
                tm.begin();
                var record = new Record(1);
                first.persist(record);
                assertThat(second.contains(record)).isTrue();
                assertThat(first.isJoinedToTransaction()).isTrue();
                registry.setRollbackOnly();
                assertThatThrownBy(tm::commit).isInstanceOf(jakarta.transaction.RollbackException.class);
                assertThat(first.contains(record)).isFalse();
                tm.begin();
                assertThat(first.find(Record.class, 1)).isNull();
                tm.rollback();
            } finally {
                first.destroy();
                second.destroy();
            }
        }
    }

    private static void alias(String name, String definition) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(url(name), "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE ALIAS IF NOT EXISTS " + definition);
        }
    }

    private ContainerEntityManager facade(EntityManagerFactory factory) {
        return new ContainerEntityManager(factory, registry, PersistenceContextType.TRANSACTION,
            SynchronizationType.SYNCHRONIZED, java.util.Map.of());
    }

    @Test
    void storedProcedureCreatedOutsideATransactionKeepsMetadataAndExecutesBoundToTheCurrentTransaction() throws Exception { // §3.11.12, §7.7.1
        try (var factory = factory("p9-sp-context")) {
            alias("p9-sp-context", "ADD_ONE AS 'int addOne(int value) { return value + 1; }'");
            alias("p9-sp-context", "RECORD_COUNT AS 'ResultSet recordCount(Connection c) throws SQLException {"
                + " return c.createStatement().executeQuery(\"SELECT COUNT(*) FROM jta_record\"); }'");
            var em = facade(factory);
            try {
                StoredProcedureQuery add = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
                add.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
                add.setParameter(1, 41);
                assertThat(add.getParameters()).hasSize(1);
                assertThat(add.isBound(add.getParameter(1))).isTrue();
                assertThat(add.getParameterValue(1)).isEqualTo(41);
                assertThat(add.execute()).isTrue();
                assertThat(add.getResultList()).containsExactly(42);
                assertThat(add.hasMoreResults()).isFalse();
                assertThat(add.getResultList()).isNull();
                add.setParameter(1, 9);
                assertThat(add.getSingleResult()).isEqualTo(10);

                StoredProcedureQuery count = em.createStoredProcedureQuery("RECORD_COUNT", Long.class);
                tm.begin();
                em.persist(new Record(1));
                em.flush();
                assertThat(count.getSingleResult()).isEqualTo(1L);
                tm.rollback();
                assertThat(count.execute()).isTrue();
                assertThat(count.getSingleResult()).isEqualTo(0L);
            } finally { em.destroy(); }
        }
    }

    @Test
    void namedStoredProcedureIsCreatedOutsideATransactionAndExecutedLater() throws Exception {
        try (var factory = factory("p9-sp-named")) {
            alias("p9-sp-named", "ADD_ONE AS 'int addOne(int value) { return value + 1; }'");
            var em = facade(factory);
            try {
                StoredProcedureQuery query = em.createNamedStoredProcedureQuery("record.addOne");
                assertThat(query.getParameters()).hasSize(1);
                query.setParameter("value", 1);
                tm.begin();
                assertThat(query.getSingleResult()).isEqualTo(2);
                tm.commit();
            } finally { em.destroy(); }
        }
    }

    @Test
    void storedProcedureResultsSurviveTheirTransactionAndPagingAppliesToThem() throws Exception {
        try (var factory = factory("p9-sp-outputs")) {
            alias("p9-sp-outputs", "ADD_ONE AS 'int addOne(int value) { return value + 1; }'");
            var em = facade(factory);
            try {
                StoredProcedureQuery query = em.createStoredProcedureQuery("ADD_ONE", Integer.class);
                query.registerStoredProcedureParameter(1, Integer.class, ParameterMode.IN);
                query.setParameter(1, 1);
                assertThatThrownBy(() -> query.getOutputParameterValue(1)).isInstanceOf(IllegalArgumentException.class);
                assertThatThrownBy(query::getUpdateCount).isInstanceOf(IllegalStateException.class);
                tm.begin();
                query.execute();
                tm.commit();
                assertThat(query.getResultList()).containsExactly(2);
                assertThat(query.hasMoreResults()).isFalse();
                assertThat(query.getUpdateCount()).isEqualTo(-1);
                query.setFirstResult(1);
                query.setParameter(1, 2);
                assertThat(query.execute()).isTrue();
                assertThat(query.getResultList()).isEmpty();
            } finally { em.destroy(); }
        }
    }

    @Test
    void storedProcedureExecuteUpdateRequiresATransactionAndIsClosedWithItsFacade() throws Exception {
        try (var factory = factory("p9-sp-update")) {
            alias("p9-sp-update", "TOUCH AS 'void touch(Connection c) throws SQLException {"
                + " c.createStatement().executeUpdate(\"UPDATE jta_record SET description = ''touched''\"); }'");
            var em = facade(factory);
            StoredProcedureQuery query = em.createStoredProcedureQuery("TOUCH");
            assertThatThrownBy(query::executeUpdate).isInstanceOf(TransactionRequiredException.class);
            tm.begin();
            assertThatThrownBy(query::executeUpdate).isInstanceOf(PersistenceException.class).hasMessageContaining("result set");
            tm.commit();
            em.destroy();
            assertThatThrownBy(() -> query.setParameter(1, 1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(query::getParameters).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(query::execute).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> em.createStoredProcedureQuery("TOUCH")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void containerFacadeMetadataIsSafeWhenOwnedByASharedCdiBean() throws Exception {
        try (var factory = factory("p9-shared-facade");
             var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var em = facade(factory);
            try {
                var work = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 100; i++) {
                    int index = i;
                    work.add(executor.submit(() -> {
                        em.setProperty("task-" + index, index);
                        var query = em.createQuery("SELECT r FROM Record r WHERE r.id = :id", Record.class);
                        query.setParameter("id", index);
                        assertThat(query.getParameterValue("id")).isEqualTo(index);
                    }));
                }
                for (var task : work) task.get();
                assertThat(em.getProperties().keySet().stream().filter(name -> name.startsWith("task-")).count()).isEqualTo(100);
            } finally { em.destroy(); }
        }
    }

    @Test
    void defaultPersistenceUnitNameIsResolvedFromTheDeploymentDescriptor() {
        assertThat(PersistenceDescriptors.defaultUnitName(Thread.currentThread().getContextClassLoader())).isEqualTo("default-cdi");
    }

    @Test
    void containerFactoryOwnershipAndContextConfigurationDoNotRequireATransaction() throws Exception {
        var factory = new ContainerEntityManagerFactory(factory("p9-container-ownership"));
        var em = new ContainerEntityManager(factory, registry, PersistenceContextType.TRANSACTION,
            SynchronizationType.SYNCHRONIZED, java.util.Map.of());
        try {
            assertThatThrownBy(factory::close).isInstanceOf(IllegalStateException.class);
            em.setFlushMode(FlushModeType.COMMIT);
            em.setProperty("query.setting", "kept");
            assertThat(em.getFlushMode()).isEqualTo(FlushModeType.COMMIT);
            assertThat(em.getProperties()).containsEntry("query.setting", "kept");
            tm.begin();
            assertThat(em.getFlushMode()).isEqualTo(FlushModeType.COMMIT);
            tm.commit();
            tm.begin();
            assertThat(em.getFlushMode()).isEqualTo(FlushModeType.COMMIT);
            tm.commit();
            em.destroy();
            assertThatThrownBy(em::getFlushMode).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> em.contains(new Record(1))).isInstanceOf(IllegalStateException.class);
        } finally {
            em.destroy();
            factory.destroy();
        }
        assertThat(factory.isOpen()).isFalse();
    }
}
