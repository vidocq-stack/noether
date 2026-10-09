package io.vidocq.mansart.jpa.cdi.moduleit;

import io.vidocq.mansart.jpa.cdi.*;
import io.vidocq.mansart.jpa.core.spi.TransactionIntegration;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.persistence.*;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** §7.7/ch.9, real Vauban CDI Lite on the module path plus real H2/Mansart transactions. */
class ContainerModuleTest {
    @Test
    void existingInjectionPointsResolveSynthesizedPersistenceFields() throws Exception {
        try (var container = VaubanContainer.builder().addBeanClass(MansartPersistenceExtension.class)
                .addBeanClass(Services.class).addBeanClass(ExplicitInjectionRepository.class).build()) {
            var repository = container.select(ExplicitInjectionRepository.class);
            assertThat(repository.manager).isNotNull();
            assertThat(repository.factory).isNotNull();
            assertThatThrownBy(repository.factory::close).isInstanceOf(IllegalStateException.class);
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            Record record = new Record(23);
            repository.manager.persist(record);
            assertThat(repository.manager.find(Record.class, 23)).isSameAs(record);
            tm.commit();
        }
    }

    @Test
    void persistenceSetterParametersReceiveTheirEnhancedQualifier() {
        try (var container = VaubanContainer.builder().addBeanClass(MansartPersistenceExtension.class)
                .addBeanClass(Services.class).addBeanClass(SetterRepository.class).build()) {
            assertThat(container.select(SetterRepository.class).manager).isNotNull();
        }
    }

    @Test
    void persistenceAnnotationsInjectThroughTheActualContainer() throws Exception {
        try (var container = VaubanContainer.builder().addBeanClass(MansartPersistenceExtension.class)
                .addBeanClass(Services.class).addBeanClass(Repository.class).build()) {
            Repository repository = container.select(Repository.class);
            assertThat(repository.manager).isNotNull();
            assertThat(repository.factory).isNotNull();
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            repository.manager.persist(new Record(1));
            tm.commit();
            assertThat(repository.manager.find(Record.class, 1)).isNotNull();
        }
    }

    @Test
    void actualContainerTransactionManagerDrivesScopedIdentityAndExtendedLifecycle() throws Exception {
        try (var container = VaubanContainer.builder().addBeanClass(Services.class).build()) {
            TransactionManager tm = container.select(TransactionManager.class);
            var integration = new JtaIntegration(tm);
            try (var factory = container.select(PersistenceUnitBootstrap.class).create("scope-tests", integration)) {
                var first = new ContainerEntityManager(factory, integration, PersistenceContextType.TRANSACTION,
                    SynchronizationType.SYNCHRONIZED, java.util.Map.of());
                var second = new ContainerEntityManager(factory, integration, PersistenceContextType.TRANSACTION,
                    SynchronizationType.SYNCHRONIZED, java.util.Map.of());
                assertThatThrownBy(first::close).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> first.persist(new Record(1))).isInstanceOf(TransactionRequiredException.class);
                tm.begin();
                var record = new Record(1);
                first.persist(record);
                assertThat(second.find(Record.class, 1)).isSameAs(record);
                tm.commit();
                tm.begin();
                Record different = first.find(Record.class, 1);
                assertThat(different).isNotSameAs(record);
                tm.rollback();
                var extended = new ContainerEntityManager(factory, integration, PersistenceContextType.EXTENDED,
                    SynchronizationType.UNSYNCHRONIZED, java.util.Map.of());
                var deferred = new Record(2);
                extended.persist(deferred);
                tm.begin();
                assertThat(extended.isJoinedToTransaction()).isFalse();
                tm.commit();
                assertThat(extended.contains(deferred)).isTrue();
                tm.begin();
                extended.joinTransaction();
                tm.commit();
                assertThat(extended.find(Record.class, 2)).isSameAs(deferred);
                extended.destroy();
                assertThat(extended.isOpen()).isFalse();
                first.destroy();
                second.destroy();
            }
        }
    }
}
