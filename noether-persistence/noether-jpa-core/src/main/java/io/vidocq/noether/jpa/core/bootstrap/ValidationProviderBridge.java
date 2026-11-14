/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.bootstrap;

import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.groups.Default;
import jakarta.validation.Path;
import jakarta.validation.TraversableResolver;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The isolated link to the optional Jakarta Validation API and provider (§3.7). */
final class ValidationProviderBridge {

    private static final String GROUP_PREFIX = "jakarta.persistence.validation.group.";

    private ValidationProviderBridge() {
    }

    static BeanValidation.Validator open(UnitSettings settings, ClassLoader loader, MappedUnit mapping) {
        Map<String, Class<?>[]> eventGroups = Map.of(
            "pre-persist", groups(settings.property(GROUP_PREFIX + "pre-persist"), loader, true),
            "pre-update", groups(settings.property(GROUP_PREFIX + "pre-update"), loader, true),
            "pre-remove", groups(settings.property(GROUP_PREFIX + "pre-remove"), loader, false));
        Object configured = settings.property("jakarta.persistence.validation.factory");
        boolean owned = configured == null;
        ValidatorFactory factory = owned ? defaultFactory(loader) : supplied(configured);
        return new EventValidator(eventGroups, validator(factory, owned, mapping), factory, owned);
    }

    /** The factory the unit is given: it must be a {@link ValidatorFactory}. */
    private static ValidatorFactory supplied(Object configured) {
        if (configured instanceof ValidatorFactory supplied) {
            return supplied;
        }
        throw new PersistenceException("jakarta.persistence.validation.factory must be a ValidatorFactory");
    }

    /** A factory of the default provider, the providers being looked up with the class loader of the unit. */
    private static ValidatorFactory defaultFactory(ClassLoader loader) {
        return Validation.byDefaultProvider().providerResolver(() -> providers(loader)).configure().buildValidatorFactory();
    }

    /**
     * The validator of {@code factory} that does not cascade to entity associations (§3.7.1); when it cannot be made,
     * a factory the unit {@code owned} is closed before the failure propagates.
     */
    private static jakarta.validation.Validator validator(ValidatorFactory factory, boolean owned, MappedUnit mapping) {
        try {
            return factory.usingContext().traversableResolver(
                new PersistenceTraversableResolver(factory.getTraversableResolver(), mapping)).getValidator();
        } catch (RuntimeException | Error failure) {
            if (owned) {
                try {
                    factory.close();
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    /** Validates an entity, at each lifecycle event, against the groups of that event; closes the factory it owns. */
    private record EventValidator(Map<String, Class<?>[]> eventGroups, jakarta.validation.Validator validator,
            ValidatorFactory factory, boolean owned) implements BeanValidation.Validator {
        @Override
        public void validate(String event, Object entity) {
            Class<?>[] groups = eventGroups.get(event);
            if (groups == null || groups.length == 0) {
                return;
            }
            Set<?> violations = validator.validate(entity, groups);
            if (!violations.isEmpty()) {
                @SuppressWarnings({ "rawtypes", "unchecked" })
                ConstraintViolationException failure = new ConstraintViolationException((Set) violations);
                throw failure;
            }
        }

        @Override
        public void close() {
            if (owned) {
                factory.close();
            }
        }
    }

    private static List<jakarta.validation.spi.ValidationProvider<?>> providers(ClassLoader loader) {
        List<jakarta.validation.spi.ValidationProvider<?>> providers = new ArrayList<>();
        java.util.ServiceLoader.load(jakarta.validation.spi.ValidationProvider.class, loader).forEach(providers::add);
        return providers;
    }

    private static Class<?>[] groups(Object configured, ClassLoader loader, boolean defaultGroup) {
        if (configured == null) {
            return defaultGroup ? new Class<?>[] { Default.class } : new Class<?>[0];
        }
        List<?> values = groupValues(configured);
        List<Class<?>> groups = new ArrayList<>(values.size());
        for (Object value : values) {
            Class<?> group = switch (value) {
                case Class<?> type -> type;
                case String name -> group(name, loader);
                case null, default -> throw new PersistenceException("Unsupported validation group value " + value);
            };
            if (!group.isInterface()) {
                throw new PersistenceException("Validation group " + group.getName() + " must be an interface");
            }
            groups.add(group);
        }
        return groups.toArray(Class<?>[]::new);
    }

    /** The groups a validation group property lists, as classes or as class names. */
    private static List<?> groupValues(Object configured) {
        if (configured instanceof String names) {
            return names.isBlank() ? List.of() : List.of(names.split(",", -1));
        }
        if (configured instanceof Class<?>[] array) {
            return List.of(array);
        }
        if (configured instanceof Class<?> type) {
            return List.of(type);
        }
        if (configured instanceof List<?> list) {
            return list;
        }
        throw new PersistenceException("Validation group property must be a comma-separated String, Class, Class[] or List, not "
            + configured.getClass().getName());
    }

    private static Class<?> group(String name, ClassLoader loader) {
        try {
            return Class.forName(name.strip(), false, loader);
        } catch (ClassNotFoundException e) {
            throw new PersistenceException("Validation group " + name + " cannot be loaded", e);
        }
    }

    private record PersistenceTraversableResolver(TraversableResolver delegate, MappedUnit mapping)
            implements TraversableResolver {
        @Override
        public boolean isReachable(Object traversableObject, Path.Node traversableProperty, Class<?> rootBeanType,
                Path pathToTraversableObject, ElementType elementType) {
            return delegate.isReachable(traversableObject, traversableProperty, rootBeanType,
                pathToTraversableObject, elementType);
        }

        @Override
        public boolean isCascadable(Object traversableObject, Path.Node traversableProperty, Class<?> rootBeanType,
                Path pathToTraversableObject, ElementType elementType) {
            return !association(rootBeanType, pathToTraversableObject, traversableProperty)
                && delegate.isCascadable(traversableObject,
                traversableProperty, rootBeanType, pathToTraversableObject, elementType);
        }

        private boolean association(Class<?> rootBeanType, Path path, Path.Node property) {
            if (mapping == null) {
                return false;
            }
            List<AttributeModel> attributes = mapping.entity(rootBeanType)
                .map(entity -> entity.model().attributes()).orElse(null);
            if (attributes == null) {
                return false;
            }
            for (Path.Node node : path) {
                if (node.getName() == null) {
                    continue;
                }
                AttributeModel reached = attribute(attributes, node.getName());
                if (!(reached instanceof io.vidocq.noether.jpa.core.model.EmbeddedAttribute embedded)) {
                    return false;
                }
                attributes = embedded.embeddable().attributes();
            }
            return property.getName() != null
                && attribute(attributes, property.getName()) instanceof AssociationAttribute;
        }

        private static AttributeModel attribute(List<AttributeModel> attributes, String name) {
            return attributes.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
        }
    }
}
