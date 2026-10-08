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

import jakarta.validation.ValidationException;
import java.lang.annotation.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The members of an annotation type, read from its class file: names, types and defaults. This is
 * all the validation engine needs to build annotation values without calling a single reflective method.
 */
final class AnnotationTypeInfo {

    /** One member of the annotation type; {@code defaultValue} is meaningful only if {@code hasDefault}. */
    record Member(String name, Class<?> type, boolean hasDefault, Object defaultValue) {
    }

    private static final ClassValue<AnnotationTypeInfo> CACHE = new ClassValue<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected AnnotationTypeInfo computeValue(Class<?> type) {
            return new AnnotationTypeInfo((Class<? extends Annotation>) type);
        }
    };

    private final Class<? extends Annotation> type;
    private final List<Member> members;
    private final Map<String, Integer> indexes;
    private final ClassValue<MethodHandle[]> accessors = new ClassValue<>() {
        @Override
        protected MethodHandle[] computeValue(Class<?> ignored) {
            return buildAccessors();
        }
    };

    private AnnotationTypeInfo(Class<? extends Annotation> type) {
        this.type = type;
        ClassModel model = ClassFiles.parse(type);
        ClassLoader loader = type.getClassLoader();
        List<Member> found = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            if ((method.flags().flagsMask() & ClassFile.ACC_ABSTRACT) == 0 || (method.flags().flagsMask() & ClassFile.ACC_STATIC) != 0) {
                continue;
            }
            MethodTypeDesc descriptor = method.methodTypeSymbol();
            Class<?> returnType = ClassFiles.load(descriptor.returnType(), loader);
            var defaultAttribute = method.findAttribute(Attributes.annotationDefault());
            Object defaultValue = defaultAttribute
                .map(a -> AnnotationValues.convert(a.defaultValue(), returnType, loader)).orElse(null);
            found.add(new Member(method.methodName().stringValue(), returnType, defaultAttribute.isPresent(), defaultValue));
        }
        this.members = List.copyOf(found);
        this.indexes = members.stream().collect(Collectors.toUnmodifiableMap(Member::name, m -> members.indexOf(m)));
    }

    static AnnotationTypeInfo of(Class<? extends Annotation> type) {
        return CACHE.get(type);
    }

    Class<? extends Annotation> type() {
        return type;
    }

    List<Member> members() {
        return members;
    }

    Member member(String name) {
        Integer index = indexes.get(name);
        if (index == null) {
            throw new IllegalArgumentException(type.getName() + " has no member " + name);
        }
        return members.get(index);
    }

    boolean hasMember(String name) {
        return indexes.containsKey(name);
    }

    /** Reads member {@code index} of any instance of the annotation type, whoever implements it. */
    Object read(int index, Annotation instance) {
        try {
            return accessors.get(type)[index].invoke(instance);
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new ValidationException("Unable to read " + type.getName() + "." + members.get(index).name(), e);
        }
    }

    /**
     * Handles on the members. Our own lookup reaches public members of any public type in a package
     * exported (or opened) to this module; a non-public annotation type needs {@code privateLookupIn},
     * i.e. its package opened to this module.
     */
    private MethodHandle[] buildAccessors() {
        Modules.canRead(type);
        MethodHandle[] handles = new MethodHandle[members.size()];
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            for (int i = 0; i < handles.length; i++) {
                Member member = members.get(i);
                MethodType memberType = MethodType.methodType(member.type());
                MethodHandle handle;
                try {
                    handle = lookup.findVirtual(type, member.name(), memberType);
                } catch (IllegalAccessException notAccessible) {
                    handle = MethodHandles.privateLookupIn(type, lookup).findVirtual(type, member.name(), memberType);
                }
                handles[i] = handle.asType(MethodType.methodType(Object.class, Annotation.class));
            }
            return handles;
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Unable to access the members of " + type.getName(), e);
        }
    }

    @Override
    public String toString() {
        return "AnnotationTypeInfo[" + type.getName() + "]";
    }

    /** Convenience: member values by name for an instance. */
    Map<String, Object> valuesOf(Annotation instance) {
        return members.stream().collect(Collectors.toMap(Member::name, m -> read(indexes.get(m.name()), instance),
            (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
