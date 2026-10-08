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
package io.vidocq.mansart.jpa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Cache;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.LoadState;
import jakarta.persistence.spi.PersistenceUnitInfo;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, chapter 9: the provider contracts, and §7.3 for the factory life cycle. */
class MansartPersistenceProviderTest {

    private static final String PROVIDER = MansartPersistenceProvider.class.getName();

    @Test
    void persistenceBootstrapsAUnitOfPersistenceXmlWithThisProvider() { // §9.2
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("h2")) {
            assertThat(emf.isOpen()).isTrue();
            assertThat(emf.getName()).isEqualTo("h2");
            assertThat(emf.getTransactionType()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL);
            assertThat(emf.getClass().getModule()).isEqualTo(MansartPersistenceProvider.class.getModule());
        }
    }

    @Test
    void anUnknownUnitIsNotOurs() { // §9.2: a provider that does not qualify returns null
        assertThat(new MansartPersistenceProvider().createEntityManagerFactory("nope", Map.of())).isNull();
        assertThatThrownBy(() -> Persistence.createEntityManagerFactory("nope")).isInstanceOf(PersistenceException.class);
    }

    @Test
    void aUnitNamingAnotherProviderIsNotOurs() {
        assertThat(new MansartPersistenceProvider().createEntityManagerFactory("other-provider", Map.of())).isNull();
        assertThatThrownBy(() -> Persistence.createEntityManagerFactory("other-provider")).isInstanceOf(PersistenceException.class);
    }

    @Test
    void theProviderNamedInTheMapWinsOverPersistenceXml() { // §8.2.1.4, §9.2
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("other-provider",
                Map.of("jakarta.persistence.provider", PROVIDER))) {
            assertThat(emf).isNotNull();
        }
        assertThat(new MansartPersistenceProvider().createEntityManagerFactory("ours-by-name",
            Map.of("jakarta.persistence.provider", "com.acme.OtherProvider"))).isNull();
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("ours-by-name")) {
            assertThat(emf.getName()).isEqualTo("ours-by-name");
        }
    }

    @Test
    void theMapOverridesThePropertiesOfPersistenceXml() { // §9.2: properties passed to createEntityManagerFactory
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("h2", Map.of("overridden", "map", "from.map", "map"))) {
            Map<String, Object> properties = emf.getProperties();
            assertThat(properties).containsEntry("from.xml", "xml").containsEntry("overridden", "map").containsEntry("from.map", "map");
            assertThat(properties).containsEntry("jakarta.persistence.jdbc.url", "jdbc:h2:mem:core-tests;DB_CLOSE_DELAY=-1");
        }
    }

    @Test
    void thePropertiesAreACopy() {
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("h2")) {
            assertThatThrownBy(() -> emf.getProperties().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void aClosedFactoryRefusesEverythingButIsOpen() { // §7.3
        EntityManagerFactory emf = Persistence.createEntityManagerFactory("h2");
        emf.close();
        assertThat(emf.isOpen()).isFalse();
        assertThatThrownBy(emf::close).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::createEntityManager).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> emf.createEntityManager(Map.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::getProperties).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::getCache).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::getCriteriaBuilder).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::getMetamodel).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::getPersistenceUnitUtil).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(emf::getSchemaManager).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> emf.unwrap(EntityManagerFactory.class)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> emf.runInTransaction(em -> { })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unwrapReturnsTheFactoryOrRefuses() {
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("h2")) {
            assertThat(emf.unwrap(EntityManagerFactory.class)).isSameAs(emf);
            assertThat(emf.unwrap(Object.class)).isSameAs(emf);
            assertThatThrownBy(() -> emf.unwrap(String.class)).isInstanceOf(PersistenceException.class);
        }
    }

    @Test
    void withoutASecondLevelCacheTheCacheContainsNothing() { // §3.10.1
        try (EntityManagerFactory emf = Persistence.createEntityManagerFactory("h2")) {
            Cache cache = emf.getCache();
            assertThat(cache.contains(Object.class, 1)).isFalse();
            cache.evict(Object.class, 1);
            cache.evict(Object.class);
            cache.evictAll();
            assertThat(cache.unwrap(Cache.class)).isSameAs(cache);
        }
    }

    @Test
    void aJtaUnitNeedsTheContainerIntegration() { // JTA comes with mansart-jpa-cdi (milestone P9)
        assertThatThrownBy(() -> Persistence.createEntityManagerFactory("jta")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("JTA");
    }

    @Test
    void callbackValidationWithoutBeanValidationIsRefused() { // §3.7.1: no provider and mode CALLBACK
        assertThatThrownBy(() -> Persistence.createEntityManagerFactory("callback")).isInstanceOf(PersistenceException.class);
        assertThatThrownBy(() -> Persistence.createEntityManagerFactory("h2", Map.of("jakarta.persistence.validation.mode", "callback")))
            .isInstanceOf(PersistenceException.class);
    }

    @Test
    void aPersistenceConfigurationBootstrapsAUnitDefinedInCode() { // §9.2, PersistenceConfiguration (3.2)
        try (EntityManagerFactory emf = new PersistenceConfiguration("in-code")
                .provider(PROVIDER)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:in-code")
                .property("custom", "value")
                .createEntityManagerFactory()) {
            assertThat(emf.getName()).isEqualTo("in-code");
            assertThat(emf.getTransactionType()).isEqualTo(PersistenceUnitTransactionType.RESOURCE_LOCAL);
            assertThat(emf.getProperties()).containsEntry("custom", "value");
        }
        assertThat(new MansartPersistenceProvider().createEntityManagerFactory(
            new PersistenceConfiguration("elsewhere").provider("com.acme.OtherProvider"))).isNull();
    }

    @Test
    void aContainerBootstrapsAUnitFromItsPersistenceUnitInfo() { // §9.6
        DataSource dataSource = new io.vidocq.mansart.jpa.core.session.CountingDataSource("jdbc:h2:mem:container");
        try (EntityManagerFactory emf = new MansartPersistenceProvider()
                .createContainerEntityManagerFactory(new Info("container", dataSource), Map.of("custom", "value"))) {
            assertThat(emf.getName()).isEqualTo("container");
            assertThat(emf.getProperties()).containsEntry("from.info", "info").containsEntry("custom", "value");
        }
    }

    @Test
    void theProviderUtilCannotTellBeforeTheEntityModelExists() { // §9.8; the entity model comes with P2
        assertThat(new MansartPersistenceProvider().getProviderUtil().isLoaded(new Object())).isEqualTo(LoadState.UNKNOWN);
        assertThat(new MansartPersistenceProvider().getProviderUtil().isLoadedWithoutReference(new Object(), "x"))
            .isEqualTo(LoadState.UNKNOWN);
        assertThat(new MansartPersistenceProvider().getProviderUtil().isLoadedWithReference(new Object(), "x"))
            .isEqualTo(LoadState.UNKNOWN);
    }

    /** A hand-written PersistenceUnitInfo, as a container would pass it. */
    private record Info(String name, DataSource dataSource) implements PersistenceUnitInfo {
        @Override
        public String getPersistenceUnitName() {
            return name;
        }

        @Override
        public String getPersistenceProviderClassName() {
            return PROVIDER;
        }

        @Override
        public String getScopeAnnotationName() {
            return null;
        }

        @Override
        public List<String> getQualifierAnnotationNames() {
            return List.of();
        }

        @Override
        @SuppressWarnings("deprecation")
        public jakarta.persistence.spi.PersistenceUnitTransactionType getTransactionType() {
            return jakarta.persistence.spi.PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }

        @Override
        public DataSource getJtaDataSource() {
            return null;
        }

        @Override
        public DataSource getNonJtaDataSource() {
            return dataSource;
        }

        @Override
        public List<String> getMappingFileNames() {
            return List.of();
        }

        @Override
        public List<URL> getJarFileUrls() {
            return List.of();
        }

        @Override
        public URL getPersistenceUnitRootUrl() {
            return null;
        }

        @Override
        public List<String> getManagedClassNames() {
            return List.of();
        }

        @Override
        public boolean excludeUnlistedClasses() {
            return true;
        }

        @Override
        public SharedCacheMode getSharedCacheMode() {
            return SharedCacheMode.UNSPECIFIED;
        }

        @Override
        public ValidationMode getValidationMode() {
            return ValidationMode.NONE;
        }

        @Override
        public Properties getProperties() {
            Properties properties = new Properties();
            properties.setProperty("from.info", "info");
            return properties;
        }

        @Override
        public String getPersistenceXMLSchemaVersion() {
            return "3.2";
        }

        @Override
        public ClassLoader getClassLoader() {
            return Info.class.getClassLoader();
        }

        @Override
        public void addTransformer(ClassTransformer transformer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClassLoader getNewTempClassLoader() {
            return getClassLoader();
        }
    }
}
