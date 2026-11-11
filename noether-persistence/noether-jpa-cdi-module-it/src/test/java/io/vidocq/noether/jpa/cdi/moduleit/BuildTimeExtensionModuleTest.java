package io.vidocq.noether.jpa.cdi.moduleit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.transaction.TransactionManager;
import org.junit.jupiter.api.Test;

/**
 * The path a Vauban application takes: noether-jpa-cdi on the annotation processor path, its build compatible
 * extension run by the Vauban processor at build time, and a container that boots from what the build recorded
 * ({@code scanClasspath()}, the boot of an application with CDI dependencies) — nothing registered by hand.
 */
class BuildTimeExtensionModuleTest {

    @Test
    void persistenceInjectionComesFromTheExtensionTheBuildRan() throws Exception {
        try (var container = VaubanContainer.builder().scanClasspath().build()) {
            Repository repository = container.select(Repository.class);
            assertThat(repository.manager).isNotNull();
            assertThat(repository.factory).isNotNull();
            TransactionManager tm = container.select(TransactionManager.class);
            tm.begin();
            repository.manager.persist(new Record(41));
            tm.commit();
            assertThat(repository.manager.find(Record.class, 41)).isNotNull();
        }
    }
}
