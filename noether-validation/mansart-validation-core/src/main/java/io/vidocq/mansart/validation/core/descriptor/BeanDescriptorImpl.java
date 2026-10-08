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
package io.vidocq.mansart.validation.core.descriptor;

import io.vidocq.mansart.validation.core.metadata.BeanMetadata;
import io.vidocq.mansart.validation.core.metadata.ConstraintDef;
import io.vidocq.mansart.validation.core.metadata.PropertyMetadata;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstructorDescriptor;
import jakarta.validation.metadata.MethodDescriptor;
import jakarta.validation.metadata.MethodType;
import jakarta.validation.metadata.PropertyDescriptor;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The {@link BeanDescriptor} of a class: its class-level constraints and its constrained or cascaded properties. */
public final class BeanDescriptorImpl extends ElementDescriptorImpl implements BeanDescriptor {

    private static final ClassValue<BeanDescriptorImpl> CACHE = new ClassValue<>() {
        @Override
        protected BeanDescriptorImpl computeValue(Class<?> type) {
            return new BeanDescriptorImpl(type);
        }
    };

    private final Map<String, PropertyDescriptorImpl> properties;

    private BeanDescriptorImpl(Class<?> type) {
        super(type, type, classLevel(type));
        this.properties = properties(type);
    }

    /** The descriptor of {@code type}; descriptors are immutable and cached. */
    public static BeanDescriptor of(Class<?> type) {
        return CACHE.get(type);
    }

    private static List<HostedConstraint> classLevel(Class<?> type) {
        List<HostedConstraint> hosted = new ArrayList<>();
        for (BeanMetadata metadata : BeanMetadata.hierarchy(type)) {
            for (ConstraintDef constraint : metadata.classConstraints()) {
                hosted.add(new HostedConstraint(constraint, ElementType.TYPE, metadata.type()));
            }
        }
        return hosted;
    }

    private static Map<String, PropertyDescriptorImpl> properties(Class<?> type) {
        Map<String, List<PropertyMetadata>> byName = new LinkedHashMap<>();
        for (BeanMetadata metadata : BeanMetadata.hierarchy(type)) {
            for (PropertyMetadata property : metadata.properties()) {
                byName.computeIfAbsent(property.name(), n -> new ArrayList<>()).add(property);
            }
        }
        Map<String, PropertyDescriptorImpl> result = new LinkedHashMap<>();
        byName.forEach((name, declarations) -> {
            List<HostedConstraint> hosted = new ArrayList<>();
            boolean cascaded = false;
            for (PropertyMetadata declaration : declarations) {
                cascaded |= declaration.cascaded();
                ElementType hostedOn = declaration.kind() == PropertyMetadata.Kind.FIELD ? ElementType.FIELD : ElementType.METHOD;
                for (ConstraintDef constraint : declaration.constraints()) {
                    hosted.add(new HostedConstraint(constraint, hostedOn, declaration.declaringClass()));
                }
            }
            result.put(name, new PropertyDescriptorImpl(name, declarations.get(0).valueType(), type, cascaded, hosted));
        });
        return Collections.unmodifiableMap(result);
    }

    @Override
    public boolean isBeanConstrained() {
        return hasConstraints() || !properties.isEmpty();
    }

    @Override
    public PropertyDescriptor getConstraintsForProperty(String propertyName) {
        if (propertyName == null) {
            throw new IllegalArgumentException("The property name must not be null");
        }
        return properties.get(propertyName);
    }

    @Override
    public Set<PropertyDescriptor> getConstrainedProperties() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(properties.values()));
    }

    /** Executables are described by milestone V4. */
    @Override
    public MethodDescriptor getConstraintsForMethod(String methodName, Class<?>... parameterTypes) {
        if (methodName == null) {
            throw new IllegalArgumentException("The method name must not be null");
        }
        return null;
    }

    @Override
    public Set<MethodDescriptor> getConstrainedMethods(MethodType methodType, MethodType... methodTypes) {
        return Set.of();
    }

    @Override
    public ConstructorDescriptor getConstraintsForConstructor(Class<?>... parameterTypes) {
        return null;
    }

    @Override
    public Set<ConstructorDescriptor> getConstrainedConstructors() {
        return Set.of();
    }
}
