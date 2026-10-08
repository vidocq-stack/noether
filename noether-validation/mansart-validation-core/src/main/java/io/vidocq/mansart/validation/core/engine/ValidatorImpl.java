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
package io.vidocq.mansart.validation.core.engine;

import io.vidocq.mansart.validation.core.descriptor.BeanDescriptorImpl;
import io.vidocq.mansart.validation.core.metadata.BeanMetadata;
import io.vidocq.mansart.validation.core.metadata.ConstraintDef;
import io.vidocq.mansart.validation.core.metadata.PropertyMetadata;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import java.util.List;
import java.util.Set;

/** The {@link Validator}: beans, properties and values. Executables and the metadata API come later. */
public final class ValidatorImpl implements Validator {

    private final Components components;

    public ValidatorImpl(Components components) {
        this.components = components;
    }

    public Components components() {
        return components;
    }

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
        ValidationRun<T> run = new ValidationRun<>(components, bean, type);
        PathImpl path = PathImpl.root().add(NodeImpl.property(propertyName));
        for (Class<?> group : requested) {
            for (BeanMetadata metadata : hierarchy) {
                for (PropertyMetadata property : metadata.properties()) {
                    if (!property.name().equals(propertyName)) {
                        continue;
                    }
                    if (property.constraints().stream().noneMatch(c -> ValidationRun.appliesTo(c, group))
                            || !ValidationRun.isReachable(bean, property, PathImpl.root(), components, type)) {
                        continue;
                    }
                    for (ConstraintDef constraint : property.constraints()) {
                        if (ValidationRun.appliesTo(constraint, group)) {
                            Object value = explicit ? given : property.get(bean);
                            run.evaluate(constraint, value, property.valueType(), path, path, bean);
                        }
                    }
                }
            }
        }
        return run.violations();
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
