/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.PersistenceException;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.PersistenceUnitTransactionType;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BeanValidationTest {
    @Test
    void suppliedFactoryWorksWithoutRegisteredProvider() throws Exception {
        try (var loader = isolated(false)) {
            Class<?> provider = loader.loadClass("jakarta.validation.spi.ValidationProvider");
            assertThat(java.util.ServiceLoader.load(provider, loader).iterator().hasNext()).isFalse();
            Class<?> handle = loader.loadClass(BeanValidation.Validator.class.getName());
            Object noop = openIsolated(loader, settings(ValidationMode.AUTO, Map.of()));
            handle.getMethod("validate", String.class, Object.class).invoke(noop, "pre-persist", new Object());
            handle.getMethod("close").invoke(noop);
            assertThatThrownBy(() -> openIsolated(loader, settings(ValidationMode.CALLBACK, Map.of())))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .cause().isInstanceOf(PersistenceException.class);
            Class<?> factoryType = loader.loadClass("jakarta.validation.ValidatorFactory");
            Class<?> contextType = loader.loadClass("jakarta.validation.ValidatorContext");
            Class<?> validatorType = loader.loadClass("jakarta.validation.Validator");
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var groups = new java.util.concurrent.atomic.AtomicReference<Class<?>[]>();
            Object validator = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] { validatorType },
                (proxy, method, args) -> {
                    if (method.getName().equals("validate")) {
                        calls.incrementAndGet();
                        groups.set((Class<?>[]) args[1]);
                    }
                    return java.util.Set.of();
                });
            Object context = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] { contextType },
                (proxy, method, args) -> method.getName().equals("getValidator") ? validator : proxy);
            Object supplied = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] { factoryType },
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        throw new AssertionError("Caller-owned factory must not be closed");
                    }
                    return method.getName().equals("usingContext") ? context : null;
                });
            Object opened = openIsolated(loader, settings(ValidationMode.CALLBACK,
                Map.of("jakarta.persistence.validation.factory", supplied,
                    "jakarta.persistence.validation.group.pre-persist", " " + FixtureGroup.class.getName()
                        + ", jakarta.validation.groups.Default ")));
            handle.getMethod("validate", String.class, Object.class).invoke(opened, "pre-persist", new Object());
            handle.getMethod("close").invoke(opened);
            assertThat(calls).hasValue(1);
            assertThat(groups.get()).containsExactly(loader.loadClass(FixtureGroup.class.getName()),
                loader.loadClass("jakarta.validation.groups.Default"));
        }
    }

    @Test
    void closesOwnedFactoryWhenUsingContextFailsAndSurfacesProviderFailureInAuto() throws Exception {
        try (var loader = isolated(true)) {
            assertThatThrownBy(() -> openIsolated(loader, settings(ValidationMode.AUTO,
                Map.of("jakarta.persistence.validation.group.pre-update", "missing.Group"))))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .cause().isInstanceOf(PersistenceException.class).hasMessageContaining("missing.Group");
            Class<?> provider = loader.loadClass(BrokenProvider.class.getName());
            assertThat(provider.getField("builds").getInt(null)).isZero();
            assertThat(provider.getField("closes").getInt(null)).isZero();
            assertThatThrownBy(() -> openIsolated(loader, settings(ValidationMode.AUTO, Map.of())))
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .cause().isInstanceOf(PersistenceException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(loader.loadClass(BrokenProvider.class.getName()).getField("closes").getInt(null))
                .isEqualTo(1);
        }
    }

    @Test
    void autoDoesNotHideInvalidAvailableProviderConfiguration() {
        assertThatThrownBy(() -> BeanValidation.open(settings(ValidationMode.AUTO,
            Map.of("jakarta.persistence.validation.group.pre-remove", "missing.Group")),
            getClass().getClassLoader(), null)).isInstanceOf(PersistenceException.class);
    }

    @Test
    void absentApiIsOptionalButBrokenApiLinkageIsNot() {
        ClassLoader broken = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("jakarta.validation.Validation")) {
                    throw new NoClassDefFoundError("broken installed validation API");
                }
                return super.loadClass(name, resolve);
            }
        };
        assertThatThrownBy(() -> BeanValidation.open(settings(ValidationMode.AUTO, Map.of()), broken, null))
            .isInstanceOf(PersistenceException.class);
        ClassLoader absent = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("jakarta.validation.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        assertThat(BeanValidation.isAvailable(absent)).isFalse();
        try (BeanValidation.Validator validator = BeanValidation.open(settings(ValidationMode.AUTO, Map.of()),
                absent, null)) {
            validator.validate("pre-persist", new Object());
        }
    }

    private java.net.URLClassLoader isolated(boolean withProvider) throws Exception {
        URL service = new URL(null, "fixture:validation-provider", new java.net.URLStreamHandler() {
            @Override
            protected java.net.URLConnection openConnection(URL url) {
                return new java.net.URLConnection(url) {
                    @Override
                    public void connect() {
                    }

                    @Override
                    public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream((BrokenProvider.class.getName() + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                };
            }
        });
        return new java.net.URLClassLoader(new URL[] {
            Validation.class.getProtectionDomain().getCodeSource().getLocation(),
            BeanValidation.class.getProtectionDomain().getCodeSource().getLocation(),
            BeanValidationTest.class.getProtectionDomain().getCodeSource().getLocation()
        }, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("jakarta.validation.") || name.startsWith(BeanValidation.class.getName())
                        || name.startsWith(ValidationProviderBridge.class.getName())
                        || name.startsWith(BeanValidationTest.class.getName())) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }

            @Override
            public Enumeration<URL> getResources(String name) throws java.io.IOException {
                return name.equals("META-INF/services/jakarta.validation.spi.ValidationProvider")
                    ? Collections.enumeration(withProvider ? List.of(service) : List.of())
                    : super.getResources(name);
            }
        };
    }

    private static Object openIsolated(ClassLoader loader, UnitSettings settings) throws Exception {
        return loader.loadClass(BeanValidation.class.getName()).getMethod("open", UnitSettings.class,
            ClassLoader.class, io.vidocq.mansart.jpa.core.mapping.MappedUnit.class).invoke(null, settings, loader, null);
    }

    public interface FixtureConfiguration extends jakarta.validation.Configuration<FixtureConfiguration> {
    }

    public interface FixtureGroup {
    }

    public static class BrokenProvider implements jakarta.validation.spi.ValidationProvider<FixtureConfiguration> {
        public static int closes;
        public static int builds;

        @Override
        public FixtureConfiguration createSpecializedConfiguration(jakarta.validation.spi.BootstrapState state) {
            return (FixtureConfiguration) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { FixtureConfiguration.class }, (proxy, method, args) ->
                    method.getName().equals("buildValidatorFactory") ? buildValidatorFactory(null) : proxy);
        }

        @Override
        public jakarta.validation.Configuration<?> createGenericConfiguration(jakarta.validation.spi.BootstrapState state) {
            return createSpecializedConfiguration(state);
        }

        @Override
        public ValidatorFactory buildValidatorFactory(jakarta.validation.spi.ConfigurationState state) {
            builds++;
            return (ValidatorFactory) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ValidatorFactory.class }, (proxy, method, args) -> {
                    if (method.getName().equals("usingContext")) {
                        throw new IllegalStateException("broken available provider context");
                    }
                    if (method.getName().equals("close")) {
                        closes++;
                    }
                    return null;
                });
        }
    }

    private static UnitSettings settings(ValidationMode mode, Map<String, Object> properties) {
        return UnitSettings.of(new PersistenceUnitDefinition("validation-test", null, null,
            PersistenceUnitTransactionType.RESOURCE_LOCAL, null, null, List.of(), List.of(), List.of(), true,
            SharedCacheMode.NONE, mode, properties, null, null, List.of(), null,
            BeanValidationTest.class.getClassLoader()), Map.of(), Map.of());
    }
}
