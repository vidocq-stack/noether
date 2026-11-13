package io.vidocq.noether.jpa.cdi.moduleit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.transactions.cdi.MansartTransactionsExtension;
import io.vidocq.mansart.transactions.cdi.MansartTransactionsProducer;
import io.vidocq.noether.jpa.cdi.NoetherPersistenceExtension;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.persistence.Cache;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.SchemaManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.TransactionScoped;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Jakarta EE 11 Platform §13.12 with Jakarta Persistence 3.2 §8.2.1.5 and §8.2.1.6: every unit of
 * {@code persistence.xml} is a set of beans an application injects with {@code @Inject}, under the qualifiers its
 * {@code <qualifier>} elements name, or {@code @Default}. The {@code EntityManager} bean takes the scope of its
 * {@code <scope>} element, or {@code @TransactionScoped}; the {@code EntityManagerFactory} bean is application scoped
 * and named after its unit; five dependent beans give the factory's criteria builder, unit utility, cache, schema
 * manager and metamodel. The units run on Mansart's transaction manager and {@code @TransactionScoped} context.
 */
class InjectedUnitModuleTest {

    static final class LedgerLiteral extends AnnotationLiteral<Ledger> implements Ledger {
    }

    static final class ArchiveLiteral extends AnnotationLiteral<Archive> implements Archive {
    }

    private static final Annotation LEDGER = new LedgerLiteral();
    private static final Annotation ARCHIVE = new ArchiveLiteral();

    private static VaubanContainer container(Class<?>... beans) {
        var builder = VaubanContainer.builder()
            .addBeanClass(MansartTransactionsExtension.class)
            .addBeanClass(MansartTransactionsProducer.class)
            .addBeanClass(NoetherPersistenceExtension.class)
            .addBeanClass(DataSources.class);
        for (Class<?> bean : beans) builder.addBeanClass(bean);
        return builder.build();
    }

    private static Bean<?> bean(VaubanContainer container, Type type, Annotation... qualifiers) {
        var manager = container.getBeanManager();
        return manager.resolve(manager.getBeans(type, qualifiers));
    }

    private static <T> T instance(VaubanContainer container, Class<T> type, Annotation... qualifiers) {
        return container.getBeanManager().createInstance().select(type, qualifiers).get();
    }

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

    @Test
    void aUnitIsAnApplicationScopedFactoryBeanNamedAfterItUnderTheQualifiersItsDescriptorNames() {
        try (var container = container()) {
            Bean<?> ledger = bean(container, EntityManagerFactory.class, LEDGER);
            assertThat(ledger.getScope()).isEqualTo(ApplicationScoped.class);
            assertThat(ledger.getName()).isEqualTo("xml-ledger");
            assertThat(ledger.getQualifiers()).contains(LEDGER).doesNotContain(Default.Literal.INSTANCE);
            assertThat(bean(container, EntityManagerFactory.class, ARCHIVE).getName()).isEqualTo("xml-archive");
            assertThat(container.getBeanManager().getBeans("xml-local"))
                .singleElement().satisfies(local -> assertThat(local.getTypes()).contains(EntityManagerFactory.class));
        }
    }

    @Test
    void aUnitWithoutQualifierIsADefaultFactoryBean() {
        try (var container = container()) {
            Set<Bean<?>> defaults = container.getBeanManager().getBeans(EntityManagerFactory.class);

            assertThat(defaults).extracting(Bean::getName)
                .containsExactlyInAnyOrder("xml-default", "xml-vouchers", "xml-local");
            assertThat(defaults).allSatisfy(factory -> assertThat(factory.getScope()).isEqualTo(ApplicationScoped.class));
        }
    }

    @Test
    void theInjectedFactoryIsTheFactoryPersistenceUnitInjects() {
        try (var container = container(LedgerUnit.class)) {
            EntityManagerFactory injected = instance(container, EntityManagerFactory.class, LEDGER);
            EntityManagerFactory declared = container.select(LedgerUnit.class).factory;

            assertThat(injected.getName()).isEqualTo("xml-ledger");
            assertThat(injected.getMetamodel()).isSameAs(declared.getMetamodel());
            assertThatThrownBy(injected::close).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void fiveDependentBeansGiveWhatTheFactoryOfTheUnitGives() {
        try (var container = container()) {
            EntityManagerFactory factory = instance(container, EntityManagerFactory.class, LEDGER);
            for (Class<?> type : new Class<?>[] {CriteriaBuilder.class, PersistenceUnitUtil.class, Cache.class,
                    SchemaManager.class, Metamodel.class}) {
                Bean<?> bean = bean(container, type, LEDGER);
                assertThat(bean.getScope()).as(type.getSimpleName()).isEqualTo(Dependent.class);
                assertThat(bean.getName()).as(type.getSimpleName()).isNull();
                assertThat(instance(container, type, LEDGER)).as(type.getSimpleName()).isNotNull();
            }
            assertThat(instance(container, Metamodel.class, LEDGER)).isSameAs(factory.getMetamodel());
            assertThat(container.getBeanManager().getBeans(Metamodel.class)).hasSize(3);
        }
    }

    @Test
    void aJtaUnitIsATransactionScopedEntityManagerSharingThePersistenceContextOfItsTransaction() throws Exception {
        try (var container = container(NamedSourceRepository.class)) {
            assertThat(bean(container, EntityManager.class, LEDGER).getScope()).isEqualTo(TransactionScoped.class);
            EntityManager injected = instance(container, EntityManager.class, LEDGER);
            EntityManager declared = container.select(NamedSourceRepository.class).manager;
            TransactionManager tm = container.select(TransactionManager.class);

            tm.begin();
            Record record = new Record(31);
            injected.persist(record);
            assertThat(declared.contains(record)).as("one persistence context per transaction (§7.6.4)").isTrue();
            tm.commit();

            assertThat(rows(DataSources.LEDGER_URL, 31)).isEqualTo(1);
            Record late = new Record(32);
            assertThatThrownBy(() -> injected.persist(late)).isInstanceOf(ContextNotActiveException.class);
        }
    }

    @Test
    void theJtaUnitsWithoutQualifierAreTheDefaultEntityManagersAndAResourceLocalUnitIsNone() {
        try (var container = container()) {
            Set<Bean<?>> defaults = container.getBeanManager().getBeans(EntityManager.class);

            assertThat(defaults).as("xml-default and xml-vouchers; xml-local is resource-local").hasSize(2);
            assertThat(defaults).allSatisfy(manager -> assertThat(manager.getScope()).isEqualTo(TransactionScoped.class));
            assertThat(defaults.stream().map(Bean::getQualifiers).flatMap(Set::stream).collect(Collectors.toSet()))
                .contains(Default.Literal.INSTANCE);
        }
    }

    @Test
    void theScopeTheDescriptorNamesReplacesTransactionScoped() throws Exception {
        try (var container = container()) {
            assertThat(bean(container, EntityManager.class, ARCHIVE).getScope()).isEqualTo(ApplicationScoped.class);
            EntityManager archive = instance(container, EntityManager.class, ARCHIVE);
            TransactionManager tm = container.select(TransactionManager.class);

            tm.begin();
            archive.persist(new Record(33));
            tm.commit();

            assertThat(rows(DataSources.DEFAULT_URL, 33)).isEqualTo(1);
            assertThat(instance(container, EntityManager.class, ARCHIVE).find(Record.class, 33)).isNotNull();
        }
    }
}
