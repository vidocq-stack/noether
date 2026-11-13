package io.vidocq.noether.jpa.cdi.moduleit;

import io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer;
import io.vidocq.noether.jpa.cdi.NoetherPersistenceExtension;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.transaction.TransactionManager;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * Units of {@code META-INF/persistence.xml} without a container bootstrap, on actual Vauban (§7.5, §9.6): a JTA unit
 * runs on the container's {@code DataSource} bean, whose connections the container enlists, and on its
 * {@code TransactionSynchronizationRegistry} bean; a resource-local unit needs neither.
 */
class DescriptorUnitModuleTest {

    private static long rows(String url, int id) throws SQLException {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM container_record WHERE id = ?")) {
            statement.setInt(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static VaubanContainer container(Class<?>... beans) {
        var builder = VaubanContainer.builder().addBeanClass(NoetherPersistenceExtension.class);
        for (Class<?> bean : beans) builder.addBeanClass(bean);
        return builder.build();
    }

    private static Stream<Throwable> causes(Throwable failure) {
        return Stream.iterate(failure, cause -> cause != null, Throwable::getCause);
    }

    @Test
    void aJtaUnitThatNamesNoDataSourceRunsOnTheDefaultDataSourceBean() throws Exception {
        try (var container = container(MansartTransactionsProducer.class, DataSources.class, DefaultSourceRepository.class)) {
            EntityManager manager = container.select(DefaultSourceRepository.class).manager;
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            manager.persist(new Record(5));
            tm.commit();
            tm.begin();
            manager.persist(new Record(6));
            manager.flush();
            assertThat(rows(DataSources.DEFAULT_URL, 6)).as("flushed on the enlisted connection, not committed").isZero();
            tm.rollback();

            assertThat(rows(DataSources.DEFAULT_URL, 5)).isEqualTo(1);
            assertThat(rows(DataSources.DEFAULT_URL, 6)).as("the container rolled the enlisted connection back").isZero();
        }
    }

    @Test
    void aJtaUnitNamingItsJtaDataSourceRunsOnTheBeanOfThatName() throws Exception {
        try (var container = container(MansartTransactionsProducer.class, DataSources.class, NamedSourceRepository.class)) {
            EntityManager manager = container.select(NamedSourceRepository.class).manager;
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            manager.persist(new Record(8));
            tm.commit();

            assertThat(rows(DataSources.LEDGER_URL, 8)).isEqualTo(1);
        }
    }

    @Test
    void withoutARegistryAJtaUnitFailsAtFactoryCreationSayingWhatToProvide() {
        try (var container = container(DefaultSourceRepository.class)) {
            assertThatThrownBy(() -> container.select(DefaultSourceRepository.class))
                .satisfies(failure -> assertThat(causes(failure)).anyMatch(PersistenceException.class::isInstance))
                .hasStackTraceContaining("xml-default")
                .hasStackTraceContaining("jakarta.transaction.TransactionSynchronizationRegistry bean")
                .hasStackTraceContaining("javax.sql.DataSource bean");
        }
    }

    @Test
    void withoutTheNamedDataSourceAJtaUnitFailsAtFactoryCreation() {
        try (var container = container(MansartTransactionsProducer.class, NamedSourceRepository.class)) {
            assertThatThrownBy(() -> container.select(NamedSourceRepository.class))
                .satisfies(failure -> assertThat(causes(failure)).anyMatch(PersistenceException.class::isInstance))
                .hasStackTraceContaining("xml-ledger")
                .hasStackTraceContaining("javax.sql.DataSource bean @Named(\"ledger\")");
        }
    }

    private static long persistVoucher(EntityManager manager, TransactionManager tm, String code, boolean commit)
            throws Exception {
        tm.begin();
        Voucher voucher = new Voucher(code);
        manager.persist(voucher);
        manager.flush();
        if (commit) tm.commit(); else tm.rollback();
        return voucher.id;
    }

    /**
     * BUG-20261111-04: a JTA unit allocates its TABLE identifiers through the data source bean {@code @Named} after its
     * {@code non-jta-data-source}; a rolled-back transaction does not give its identifiers back.
     */
    @Test
    void aJtaUnitAllocatesTableIdentifiersThroughTheBeanNamedAfterItsNonJtaDataSource() throws Exception {
        try (var container = container(MansartTransactionsProducer.class, DataSources.class, VoucherRepository.class)) {
            EntityManager manager = container.select(VoucherRepository.class).manager;
            TransactionManager tm = container.select(TransactionManager.class);

            long doomed = persistVoucher(manager, tm, "doomed", false);
            long first = persistVoucher(manager, tm, "first", true);
            long second = persistVoucher(manager, tm, "second", true);

            assertThat(doomed).isEqualTo(1);
            assertThat(first).isEqualTo(2);
            assertThat(second).isEqualTo(3);
        }
    }

    @Test
    void withoutTheNonJtaDataSourceBeanAJtaUnitFailsAtFactoryCreation() {
        try (var container = container(MansartTransactionsProducer.class, VoucherRepository.class, DefaultSource.class)) {
            assertThatThrownBy(() -> container.select(VoucherRepository.class))
                .satisfies(failure -> assertThat(causes(failure)).anyMatch(PersistenceException.class::isInstance))
                .hasStackTraceContaining("xml-vouchers")
                .hasStackTraceContaining("javax.sql.DataSource bean @Named(\"allocations\")");
        }
    }

    @Test
    void aResourceLocalUnitNeedsNoTransactionBean() {
        try (var container = container(ResourceLocalRepository.class)) {
            var factory = container.select(ResourceLocalRepository.class).factory;
            assertThat(factory.getTransactionType()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL);
            try (var em = factory.createEntityManager()) {
                em.getTransaction().begin();
                em.persist(new Record(7));
                em.getTransaction().commit();
            }
            try (var em = factory.createEntityManager()) {
                assertThat(em.find(Record.class, 7)).isNotNull();
            }
        }
    }
}
