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
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolver;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.ProviderUtil;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jakarta Persistence 3.2, §9.1 and §9.6: a container hands a unit whose {@code <provider>} names another provider to
 * that provider's {@code createContainerEntityManagerFactory}, with the unit as {@code persistence.xml} declares it
 * (BUG-20261119-90).
 */
class ContainerFactoriesTest {

    private static final String PROVIDER = RecordingProvider.class.getName();

    @TempDir
    Path directory;

    private final RecordingProvider recording = new RecordingProvider();

    @AfterEach
    void defaultResolver() {
        PersistenceProviderResolverHolder.setPersistenceProviderResolver(null);
    }

    private URL root(String descriptor) throws IOException {
        Path root = directory.resolve("billing");
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/persistence.xml"), descriptor);
        return root.toUri().toURL();
    }

    private static String billing(String provider) {
        return """
            <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.2">
              <persistence-unit name="billing" transaction-type="JTA">
                <provider>%s</provider>
                <qualifier>com.example.Billing</qualifier>
                <scope>jakarta.enterprise.context.RequestScoped</scope>
                <jta-data-source>jdbc/billing</jta-data-source>
                <mapping-file>META-INF/billing.xml</mapping-file>
                <jar-file>billing-entities.jar</jar-file>
                <class>com.example.Invoice</class>
                <exclude-unlisted-classes>false</exclude-unlisted-classes>
                <shared-cache-mode>NONE</shared-cache-mode>
                <validation-mode>NONE</validation-mode>
                <properties>
                  <property name="vendor.flag" value="on"/>
                </properties>
              </persistence-unit>
            </persistence>
            """.formatted(provider);
    }

    private static void resolving(PersistenceProvider... providers) {
        PersistenceProviderResolverHolder.setPersistenceProviderResolver(new PersistenceProviderResolver() {
            @Override public List<PersistenceProvider> getPersistenceProviders() { return List.of(providers); }
            @Override public void clearCachedProviders() { /* a fixed list: nothing is cached */ }
        });
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void aUnitNamingAnotherProviderGoesToItsContainerFactoryWithTheUnitItsDescriptorDeclares() throws IOException {
        URL root = root(billing(PROVIDER));
        DataSource pool = new NoDataSource();
        resolving(recording);
        try (var loader = new URLClassLoader(new URL[] {root}, null)) {
            EntityManagerFactory factory = ContainerFactories.create("billing", loader, Map.of(
                "jakarta.persistence.jtaDataSource", pool,
                TransactionIntegration.PROPERTY, "the bridge of Noether's own units",
                "vendor.extra", "x"));

            assertThat(factory).isSameAs(recording.factory);
            PersistenceUnitInfo info = recording.info;
            assertThat(info.getPersistenceUnitName()).isEqualTo("billing");
            assertThat(info.getPersistenceProviderClassName()).isEqualTo(PROVIDER);
            assertThat(info.getTransactionType()).isEqualTo(jakarta.persistence.spi.PersistenceUnitTransactionType.JTA);
            assertThat(info.getJtaDataSource()).isSameAs(pool);
            assertThat(info.getNonJtaDataSource()).isNull();
            assertThat(info.getMappingFileNames()).containsExactly("META-INF/billing.xml");
            assertThat(info.getJarFileUrls()).singleElement().asString().endsWith("billing-entities.jar");
            assertThat(info.getPersistenceUnitRootUrl()).isEqualTo(root);
            assertThat(info.getManagedClassNames()).containsExactly("com.example.Invoice");
            assertThat(info.excludeUnlistedClasses()).isFalse();
            assertThat(info.getSharedCacheMode()).isEqualTo(SharedCacheMode.NONE);
            assertThat(info.getValidationMode()).isEqualTo(ValidationMode.NONE);
            assertThat(info.getProperties()).containsEntry("vendor.flag", "on");
            assertThat(info.getPersistenceXMLSchemaVersion()).isEqualTo("3.2");
            assertThat(info.getClassLoader()).isSameAs(loader);
            assertThat(info.getQualifierAnnotationNames()).containsExactly("com.example.Billing");
            assertThat(info.getScopeAnnotationName()).isEqualTo("jakarta.enterprise.context.RequestScoped");
            try (var temporary = (URLClassLoader) info.getNewTempClassLoader()) {
                assertThat(temporary).isNotSameAs(loader);
            }
            ClassTransformer transformer = (definer, name, redefined, domain, bytes) -> bytes;
            info.addTransformer(transformer);
            assertThatThrownBy(() -> info.addTransformer(null)).isInstanceOf(NullPointerException.class);
            assertThat(recording.map).as("the bridge of Noether's own units is no property of another provider")
                .containsEntry("vendor.extra", "x").doesNotContainKey(TransactionIntegration.PROPERTY);
        }
    }

    @Test
    void aUnitNamingAProviderTheDeploymentDoesNotDeclareIsRefusedWithItsName() throws IOException {
        URL root = root(billing("com.example.MissingProvider"));
        resolving(recording);
        try (var loader = new URLClassLoader(new URL[] {root}, null)) {
            Map<String, Object> none = Map.of();
            assertThatThrownBy(() -> ContainerFactories.create("billing", loader, none))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("billing")
                .hasMessageContaining("com.example.MissingProvider")
                .hasMessageContaining("META-INF/services/jakarta.persistence.spi.PersistenceProvider");
        }
    }

    /** A provider that records what the container hands it. */
    static final class RecordingProvider implements PersistenceProvider {

        final EntityManagerFactory factory = (EntityManagerFactory) Proxy.newProxyInstance(
            ContainerFactoriesTest.class.getClassLoader(), new Class<?>[] {EntityManagerFactory.class},
            (proxy, method, arguments) -> {
                throw new UnsupportedOperationException(method.getName());
            });
        PersistenceUnitInfo info;
        Map<Object, Object> map;

        @Override
        public EntityManagerFactory createContainerEntityManagerFactory(PersistenceUnitInfo unit, Map<?, ?> properties) {
            this.info = unit;
            this.map = new HashMap<Object, Object>(properties);
            return factory;
        }

        @Override public EntityManagerFactory createEntityManagerFactory(String name, Map<?, ?> properties) { return null; }
        @Override public EntityManagerFactory createEntityManagerFactory(PersistenceConfiguration configuration) { return null; }
        @Override public void generateSchema(PersistenceUnitInfo unit, Map<?, ?> properties) { throw new UnsupportedOperationException(); }
        @Override public boolean generateSchema(String name, Map<?, ?> properties) { return false; }
        @Override public ProviderUtil getProviderUtil() { throw new UnsupportedOperationException(); }
    }

    /** The data source a container would hand over; the provider under test never opens it. */
    private static final class NoDataSource implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String user, String password) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { /* a stand-in: nothing is logged */ }
        @Override public void setLoginTimeout(int seconds) { /* a stand-in: no timeout */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getGlobal(); }
        @Override public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
