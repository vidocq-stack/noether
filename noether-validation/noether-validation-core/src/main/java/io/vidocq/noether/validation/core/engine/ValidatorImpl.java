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
package io.vidocq.noether.validation.core.engine;

import io.vidocq.noether.validation.core.descriptor.BeanDescriptorImpl;
import io.vidocq.noether.validation.core.metadata.BeanMetadata;
import io.vidocq.noether.validation.core.metadata.ConstraintDef;
import io.vidocq.noether.validation.core.metadata.PropertyMetadata;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** The {@link Validator}: beans, properties and values. Executables and the metadata API come later. */
public record ValidatorImpl(Components components) implements Validator {

    @Override
    @SuppressWarnings("unchecked")
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
        if (object == null) {
            throw new IllegalArgumentException("The object to validate must not be null");
        }
        List<Class<?>> requested = ValidationRun.groupsOrDefault(groups);
        ValidationRun<T> run = new ValidationRun<>(components, object, (Class<T>) object.getClass());
        for (Class<?> group : requested) {
            run.validateBean(object, group, new ValidationRun.Cursor(PathImpl.root(), null));
        }
        return run.violations();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
        if (object == null) {
            throw new IllegalArgumentException("The object to validate must not be null");
        }
        return property(object, (Class<T>) object.getClass(), propertyName, false, null, groups);
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value, Class<?>... groups) {
        if (beanType == null) {
            throw new IllegalArgumentException("The bean type must not be null");
        }
        return property(null, beanType, propertyName, true, value, groups);
    }

    /** Both property validations: with the value of a bean ({@code explicit} false), or a value given by the caller. */
    private <T> Set<ConstraintViolation<T>> property(T bean, Class<T> type, String propertyName, boolean explicit, Object given,
            Class<?>[] groups) {
        if (propertyName == null || propertyName.isBlank()) {
            throw new IllegalArgumentException("The property name must not be null or empty");
        }
        List<Class<?>> requested = ValidationRun.groupsOrDefault(groups);
        List<BeanMetadata> hierarchy = BeanMetadata.hierarchy(type);
        if (hierarchy.stream().noneMatch(m -> m.declaredPropertyNames().contains(propertyName))) {
            throw new IllegalArgumentException("The type " + type.getName() + " has no property " + propertyName);
        }
        List<PropertyMetadata> named = hierarchy.stream().flatMap(m -> m.properties().stream())
            .filter(p -> p.name().equals(propertyName)).toList();
        Function<PropertyMetadata, Object> valueOf = explicit ? _ -> given : p -> p.get(bean);
        ValidationRun<T> run = new ValidationRun<>(components, bean, type);
        PathImpl path = PathImpl.root().add(NodeImpl.property(propertyName));
        for (Class<?> group : requested) {
            for (PropertyMetadata property : named) {
                if (inGroupAndReachable(bean, type, property, group)) {
                    evaluate(run, property, group, valueOf, path, bean);
                }
            }
        }
        return run.violations();
    }

    /** Whether a constraint of {@code property} is in {@code group} and the traversable resolver reaches it. */
    private <T> boolean inGroupAndReachable(T bean, Class<T> type, PropertyMetadata property, Class<?> group) {
        return property.constraints().stream().anyMatch(c -> ValidationRun.appliesTo(c, group))
            && ValidationRun.isReachable(bean, property, PathImpl.root(), components, type);
    }

    /** Evaluates the constraints of {@code property} in {@code group}, reading its value for each one. */
    private static <T> void evaluate(ValidationRun<T> run, PropertyMetadata property, Class<?> group,
            Function<PropertyMetadata, Object> valueOf, PathImpl path, Object bean) {
        for (ConstraintDef constraint : property.constraints()) {
            if (ValidationRun.appliesTo(constraint, group)) {
                run.evaluate(constraint, valueOf.apply(property), property.valueType(), path, path, null, bean);
            }
        }
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        if (clazz == null) {
            throw new IllegalArgumentException("The class must not be null");
        }
        return BeanDescriptorImpl.of(clazz);
    }

    @Override
    public ExecutableValidator forExecutables() {
        throw new UnsupportedOperationException("Executable validation is not implemented yet (milestone V4)");
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
    }
}
