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
package io.vidocq.mansart.jpa.core.model.source;

import jakarta.persistence.PersistenceException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads classes as data from their class files, with the Class-File API: the path for classes compiled without the
 * Mansart annotation processor (the TCK, any pre-compiled jar). Nothing is loaded and no reflection is used; a class
 * is read once per source. Thread-safe.
 */
public final class ClassFileSource implements ClassInfos {

    /** The defaults of annotation types, per annotation type name; shared, the annotation types do not change. */
    private static final Map<String, Map<String, Object>> SHARED_DEFAULTS = new ConcurrentHashMap<>();
    /** The member types of the Jakarta Persistence annotation types, shared like their defaults. */
    private static final Map<String, Map<String, ClassDesc>> SHARED_MEMBERS = new ConcurrentHashMap<>();

    private final ClassLoader loader;
    private final Map<String, Optional<ClassInfo>> classes = new ConcurrentHashMap<>();
    /** Defaults of the other annotation types met by this source, absent ones included (an empty map). */
    private final Map<String, Map<String, Object>> defaults = new ConcurrentHashMap<>();

    public ClassFileSource(ClassLoader loader) {
        this.loader = loader;
    }

    /** The class named {@code binaryName}, or empty if its class file cannot be found. */
    @Override
    public Optional<ClassInfo> read(String binaryName) {
        return classes.computeIfAbsent(binaryName, this::parse);
    }

    /** Reads the class file at {@code bytes}, e.g. while scanning a jar. */
    public ClassInfo read(byte[] bytes) {
        ClassInfo info = toInfo(ClassFile.of().parse(bytes));
        classes.putIfAbsent(info.name(), Optional.of(info));
        return info;
    }

    private Optional<ClassInfo> parse(String binaryName) {
        byte[] bytes = bytes(binaryName);
        return bytes == null ? Optional.empty() : Optional.of(toInfo(ClassFile.of().parse(bytes)));
    }

    private byte[] bytes(String binaryName) {
        String resource = binaryName.replace('.', '/') + ".class";
        try (InputStream in = loader == null ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new PersistenceException("Unable to read the class file of " + binaryName, e);
        }
    }

    private ClassInfo toInfo(ClassModel model) {
        String name = binaryName(model.thisClass().asSymbol());
        String superclass = model.superclass().map(c -> binaryName(c.asSymbol())).orElse(null);
        if ("java.lang.Object".equals(name)) {
            superclass = null;
        }
        List<String> interfaces = model.interfaces().stream().map(c -> binaryName(c.asSymbol())).toList();
        String signature = model.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse(null);
        boolean isRecord = model.findAttribute(Attributes.record()).isPresent();
        List<FieldInfo> fields = new ArrayList<>();
        for (FieldModel field : model.fields()) {
            fields.add(new FieldInfo(field.fieldName().stringValue(), field.fieldTypeSymbol(),
                field.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse(null),
                field.flags().flagsMask(), annotations(field.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .map(a -> a.annotations()).orElse(List.of()))));
        }
        List<MethodInfo> methods = new ArrayList<>();
        for (MethodModel method : model.methods()) {
            methods.add(new MethodInfo(method.methodName().stringValue(), method.methodTypeSymbol(),
                method.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse(null),
                method.flags().flagsMask(), annotations(method.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .map(a -> a.annotations()).orElse(List.of()))));
        }
        List<AnnotationInfo> annotations = annotations(model.findAttribute(Attributes.runtimeVisibleAnnotations())
            .map(a -> a.annotations()).orElse(List.of()));
        return new ClassInfo(name, superclass, interfaces, model.flags().flagsMask(), signature, isRecord, annotations, fields, methods);
    }

    private List<AnnotationInfo> annotations(List<java.lang.classfile.Annotation> raw) {
        List<AnnotationInfo> result = new ArrayList<>(raw.size());
        for (java.lang.classfile.Annotation annotation : raw) {
            result.add(annotation(annotation));
        }
        return result;
    }

    private AnnotationInfo annotation(java.lang.classfile.Annotation raw) {
        String type = binaryName(raw.classSymbol());
        Map<String, Object> written = new LinkedHashMap<>();
        for (var element : raw.elements()) {
            written.put(element.name().stringValue(), value(element.value()));
        }
        return new AnnotationInfo(type, written, defaults(type));
    }

    private Object value(AnnotationValue value) {
        return switch (value) {
            case AnnotationValue.OfString v -> v.stringValue();
            case AnnotationValue.OfBoolean v -> v.booleanValue();
            case AnnotationValue.OfInt v -> v.intValue();
            case AnnotationValue.OfLong v -> v.longValue();
            case AnnotationValue.OfShort v -> v.shortValue();
            case AnnotationValue.OfByte v -> v.byteValue();
            case AnnotationValue.OfChar v -> v.charValue();
            case AnnotationValue.OfFloat v -> v.floatValue();
            case AnnotationValue.OfDouble v -> v.doubleValue();
            case AnnotationValue.OfEnum v -> new EnumValue(binaryName(v.classSymbol()), v.constantName().stringValue());
            case AnnotationValue.OfClass v -> v.classSymbol();
            case AnnotationValue.OfAnnotation v -> annotation(v.annotation());
            case AnnotationValue.OfArray v -> v.values().stream().map(this::value).toList();
            default -> throw new PersistenceException("Unsupported annotation value " + value);
        };
    }

    /** The members' defaults of an annotation type, read from its own class file; empty if it cannot be found. */
    private Map<String, Object> defaults(String annotationType) {
        Map<String, Object> known = annotationType.startsWith("jakarta.persistence.") ? SHARED_DEFAULTS.get(annotationType)
            : this.defaults.get(annotationType);
        if (known != null) {
            return known;
        }
        byte[] bytes = bytes(annotationType);
        if (bytes == null) {
            this.defaults.putIfAbsent(annotationType, Map.of());
            return Map.of();
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
            method.findAttribute(Attributes.annotationDefault())
                .ifPresent(d -> defaults.put(method.methodName().stringValue(), value(d.defaultValue())));
        }
        Map<String, Object> result = Map.copyOf(defaults);
        // put, not computeIfAbsent: reading the defaults may read nested annotation types
        (annotationType.startsWith("jakarta.persistence.") ? SHARED_DEFAULTS : this.defaults).putIfAbsent(annotationType, result);
        return result;
    }

    /**
     * An annotation of {@code annotationType} written with the members {@code written} (encoded as this source encodes
     * them), the other members by default: what a mapping file says in place of an annotation (chapter 12).
     */
    public AnnotationInfo annotation(String annotationType, Map<String, Object> written) {
        return new AnnotationInfo(annotationType, written, defaults(annotationType));
    }

    /** The members of an annotation type and their declared types, in declaration order; empty if it cannot be found. */
    public Map<String, ClassDesc> annotationMembers(String annotationType) {
        Map<String, ClassDesc> known = SHARED_MEMBERS.get(annotationType);
        if (known != null) {
            return known;
        }
        byte[] bytes = bytes(annotationType);
        if (bytes == null) {
            return Map.of();
        }
        Map<String, ClassDesc> members = new LinkedHashMap<>();
        for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
            if ((method.flags().flagsMask() & ClassFile.ACC_ABSTRACT) != 0) {
                members.put(method.methodName().stringValue(), method.methodTypeSymbol().returnType());
            }
        }
        Map<String, ClassDesc> result = java.util.Collections.unmodifiableMap(members);
        if (annotationType.startsWith("jakarta.persistence.")) {
            SHARED_MEMBERS.putIfAbsent(annotationType, result);
        }
        return result;
    }

    /** {@code Lcom/acme/Outer$Inner;} to {@code com.acme.Outer$Inner}; arrays and primitives keep their descriptor. */
    public static String binaryName(ClassDesc desc) {
        if (desc.isClassOrInterface()) {
            String descriptor = desc.descriptorString();
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return desc.descriptorString();
    }
}
