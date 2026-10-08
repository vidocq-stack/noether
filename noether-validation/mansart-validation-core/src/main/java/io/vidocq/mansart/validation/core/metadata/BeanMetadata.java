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
package io.vidocq.mansart.validation.core.metadata;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one class declares about validation, read from its class file: constraints on the class,
 * constrained or cascaded fields and getters. Inherited members are the business of the supertypes'
 * own metadata, see {@link #hierarchy(Class)}.
 */
public final class BeanMetadata {

    private static final String VALID = "Ljakarta/validation/Valid;";

    private static final ClassValue<BeanMetadata> CACHE = new ClassValue<>() {
        @Override
        protected BeanMetadata computeValue(Class<?> type) {
            return new BeanMetadata(type);
        }
    };

    private final Class<?> type;
    private final List<ConstraintDef> classConstraints;
    private final List<PropertyMetadata> properties;
    private final Set<String> declaredPropertyNames;

    private BeanMetadata(Class<?> type) {
        this.type = type;
        ClassModel model = ClassFiles.parse(type);
        ClassLoader loader = type.getClassLoader();
        this.classConstraints = List.copyOf(constraintsOf(
            model.findAttribute(Attributes.runtimeVisibleAnnotations()).map(a -> a.annotations()).orElse(List.of()), loader));
        List<PropertyMetadata> found = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (FieldModel field : model.fields()) {
            if (isStaticOrSynthetic(field.flags().flagsMask())) {
                continue;
            }
            names.add(field.fieldName().stringValue());
            List<java.lang.classfile.Annotation> raw = field.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(a -> a.annotations()).orElse(List.of());
            List<ConstraintDef> constraints = constraintsOf(raw, loader);
            boolean cascaded = hasValid(raw);
            if (!constraints.isEmpty() || cascaded) {
                String name = field.fieldName().stringValue();
                found.add(new PropertyMetadata(type, name, PropertyMetadata.Kind.FIELD, name,
                    ClassFiles.load(field.fieldTypeSymbol(), loader), cascaded, constraints));
            }
        }
        for (MethodModel method : model.methods()) {
            int flags = method.flags().flagsMask();
            if (isStaticOrSynthetic(flags) || (flags & ClassFile.ACC_BRIDGE) != 0 || method.methodTypeSymbol().parameterCount() != 0) {
                continue;
            }
            Class<?> returnType = ClassFiles.load(method.methodTypeSymbol().returnType(), loader);
            String propertyName = propertyName(method.methodName().stringValue(), returnType);
            if (propertyName == null) {
                continue;
            }
            names.add(propertyName);
            List<java.lang.classfile.Annotation> raw = method.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(a -> a.annotations()).orElse(List.of());
            List<ConstraintDef> constraints = constraintsOf(raw, loader);
            boolean cascaded = hasValid(raw);
            if (!constraints.isEmpty() || cascaded) {
                found.add(new PropertyMetadata(type, propertyName, PropertyMetadata.Kind.GETTER, method.methodName().stringValue(),
                    returnType, cascaded, constraints));
            }
        }
        this.properties = List.copyOf(found);
        this.declaredPropertyNames = Set.copyOf(names);
    }

    public static BeanMetadata of(Class<?> type) {
        return CACHE.get(type);
    }

    /**
     * The metadata of {@code type} followed by the ones of its superclasses and interfaces, each once;
     * {@code Object} and types without any validation declaration are not special-cased, only Object is left out.
     */
    public static List<BeanMetadata> hierarchy(Class<?> type) {
        Set<Class<?>> types = new LinkedHashSet<>();
        collect(type, types);
        List<BeanMetadata> result = new ArrayList<>();
        for (Class<?> t : types) {
            result.add(of(t));
        }
        return result;
    }

    private static void collect(Class<?> type, Set<Class<?>> into) {
        if (type == null || type == Object.class || !into.add(type)) {
            return;
        }
        collect(type.getSuperclass(), into);
        for (Class<?> iface : type.getInterfaces()) {
            collect(iface, into);
        }
    }

    public Class<?> type() {
        return type;
    }

    public List<ConstraintDef> classConstraints() {
        return classConstraints;
    }

    public List<PropertyMetadata> properties() {
        return properties;
    }

    /** Every non-static field and getter name the class declares, constrained or not. */
    public Set<String> declaredPropertyNames() {
        return declaredPropertyNames;
    }

    // ---- reading ---------------------------------------------------------------------------------

    private static boolean isStaticOrSynthetic(int flags) {
        return (flags & (ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC)) != 0;
    }

    private static boolean hasValid(List<java.lang.classfile.Annotation> raw) {
        return raw.stream().anyMatch(a -> a.className().stringValue().equals(VALID));
    }

    private static List<ConstraintDef> constraintsOf(List<java.lang.classfile.Annotation> raw, ClassLoader loader) {
        return ConstraintDef.fromRaw(raw, loader);
    }

    /** JavaBeans naming: getX, and isX or hasX for a boolean; X is decapitalized unless it starts with two capitals. */
    static String propertyName(String methodName, Class<?> returnType) {
        if (returnType == void.class) {
            return null;
        }
        String suffix;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            suffix = methodName.substring(3);
        } else if (methodName.startsWith("is") && methodName.length() > 2 && returnType == boolean.class) {
            suffix = methodName.substring(2);
        } else if (methodName.startsWith("has") && methodName.length() > 3 && returnType == boolean.class) {
            suffix = methodName.substring(3);
        } else {
            return null;
        }
        if (suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))) {
            return suffix;
        }
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }
}
