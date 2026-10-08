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
package io.vidocq.mansart.jpa.processor;

import io.vidocq.mansart.jpa.core.model.source.AnnotationInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassInfos;
import io.vidocq.mansart.jpa.core.model.source.EnumValue;
import io.vidocq.mansart.jpa.core.model.source.FieldInfo;
import io.vidocq.mansart.jpa.core.model.source.MethodInfo;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The classes of the compilation, and those it compiles against, read as the bootstrap reads class files: the same
 * {@link ClassInfo}, so that {@code AccessPlanner} plans the same members in the same order. Members keep their
 * declaration order, which is the order javac writes them to the class file.
 */
final class ElementInfos implements ClassInfos {

    private final Elements elements;
    private final Types types;
    private final Map<String, Optional<ClassInfo>> cache = new HashMap<>();
    private boolean unresolved;

    ElementInfos(Elements elements, Types types) {
        this.elements = elements;
        this.types = types;
    }

    @Override
    public Optional<ClassInfo> read(String binaryName) {
        Optional<ClassInfo> known = cache.get(binaryName);
        if (known == null) {
            known = Optional.ofNullable(elements.getTypeElement(binaryName.replace('$', '.'))).map(this::read);
            cache.put(binaryName, known);
        }
        return known;
    }

    /**
     * Whether a type met since the last call could not be resolved yet (a class another processor generates in a
     * later round): its erasure is not known, so nothing planned with it can be trusted. Resets the flag.
     */
    boolean unresolved() {
        boolean was = unresolved;
        unresolved = false;
        return was;
    }

    /** Forgets what was read: the next round may resolve what this one could not. */
    void clear() {
        cache.clear();
    }

    /** The element of a class read by {@link #read(String)}. */
    TypeElement element(String binaryName) {
        return elements.getTypeElement(binaryName.replace('$', '.'));
    }

    ClassInfo read(TypeElement type) {
        List<FieldInfo> fields = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        for (Element member : type.getEnclosedElements()) {
            switch (member.getKind()) {
                case FIELD -> fields.add(new FieldInfo(member.getSimpleName().toString(), desc(member.asType()), null,
                    flags(member.getModifiers()), annotations(member)));
                case METHOD, CONSTRUCTOR -> methods.add(method((ExecutableElement) member));
                default -> {
                }
            }
        }
        if (type.getKind() == ElementKind.RECORD && fields.isEmpty()) {
            // the component fields are implicit: javac writes them private final, in component order
            for (RecordComponentElement component : type.getRecordComponents()) {
                fields.add(new FieldInfo(component.getSimpleName().toString(), desc(component.asType()), null,
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL, annotations(component)));
            }
        }
        TypeMirror superclass = type.getSuperclass();
        String superclassName = superclass.getKind() == TypeKind.DECLARED ? binaryName(superclass) : null;
        List<String> interfaces = type.getInterfaces().stream().map(this::binaryName).toList();
        int flags = flags(type.getModifiers()) | switch (type.getKind()) {
            case INTERFACE, ANNOTATION_TYPE -> ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT;
            case ENUM -> ClassFile.ACC_ENUM;
            default -> 0;
        };
        return new ClassInfo(elements.getBinaryName(type).toString(), superclassName, interfaces, flags, null,
            type.getKind() == ElementKind.RECORD, annotations(type), fields, methods);
    }

    private MethodInfo method(ExecutableElement method) {
        String name = method.getKind() == ElementKind.CONSTRUCTOR ? ConstantDescs.INIT_NAME : method.getSimpleName().toString();
        ClassDesc returned = method.getKind() == ElementKind.CONSTRUCTOR ? ConstantDescs.CD_void : desc(method.getReturnType());
        ClassDesc[] parameters = method.getParameters().stream().map(p -> desc(p.asType())).toArray(ClassDesc[]::new);
        return new MethodInfo(name, MethodTypeDesc.of(returned, parameters), null, flags(method.getModifiers()),
            annotations(method));
    }

    /** The descriptor of the erasure of {@code type}, as javac writes it to the class file. */
    ClassDesc desc(TypeMirror type) {
        TypeMirror erased = types.erasure(type);
        return switch (erased.getKind()) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case BYTE -> ConstantDescs.CD_byte;
            case SHORT -> ConstantDescs.CD_short;
            case CHAR -> ConstantDescs.CD_char;
            case INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case VOID -> ConstantDescs.CD_void;
            case ARRAY -> desc(((ArrayType) erased).getComponentType()).arrayType();
            case DECLARED -> ClassDesc.of(binaryName(erased));
            case ERROR -> {
                unresolved = true;
                yield ConstantDescs.CD_Object;
            }
            default -> ConstantDescs.CD_Object;
        };
    }

    private String binaryName(TypeMirror type) {
        return elements.getBinaryName((TypeElement) ((DeclaredType) types.erasure(type)).asElement()).toString();
    }

    private static int flags(Set<Modifier> modifiers) {
        int flags = 0;
        for (Modifier modifier : modifiers) {
            flags |= switch (modifier) {
                case PUBLIC -> ClassFile.ACC_PUBLIC;
                case PROTECTED -> ClassFile.ACC_PROTECTED;
                case PRIVATE -> ClassFile.ACC_PRIVATE;
                case STATIC -> ClassFile.ACC_STATIC;
                case FINAL -> ClassFile.ACC_FINAL;
                case ABSTRACT -> ClassFile.ACC_ABSTRACT;
                case TRANSIENT -> ClassFile.ACC_TRANSIENT;
                case VOLATILE -> ClassFile.ACC_VOLATILE;
                default -> 0;
            };
        }
        return flags;
    }

    // ---- annotations, encoded as ClassFileSource encodes them ---------------------------------------------

    /** The annotations a class file keeps visible at run time, as {@code ClassFileSource} reads them. */
    private List<AnnotationInfo> annotations(Element element) {
        return element.getAnnotationMirrors().stream().filter(this::runtimeVisible).map(this::annotation).toList();
    }

    private boolean runtimeVisible(AnnotationMirror mirror) {
        Element type = mirror.getAnnotationType().asElement();
        for (AnnotationMirror meta : type.getAnnotationMirrors()) {
            if (((TypeElement) meta.getAnnotationType().asElement()).getQualifiedName().contentEquals("java.lang.annotation.Retention")) {
                return meta.getElementValues().values().stream()
                    .anyMatch(v -> v.getValue() instanceof VariableElement policy && policy.getSimpleName().contentEquals("RUNTIME"));
            }
        }
        return false; // CLASS retention by default: not visible at run time
    }

    private AnnotationInfo annotation(AnnotationMirror mirror) {
        TypeElement type = (TypeElement) mirror.getAnnotationType().asElement();
        Map<String, Object> written = new LinkedHashMap<>();
        mirror.getElementValues().forEach((member, value) -> written.put(member.getSimpleName().toString(), value(value)));
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Element member : type.getEnclosedElements()) {
            if (member instanceof ExecutableElement method && method.getDefaultValue() != null) {
                defaults.put(method.getSimpleName().toString(), value(method.getDefaultValue()));
            }
        }
        return AnnotationInfo.of(elements.getBinaryName(type).toString(), written, defaults);
    }

    private Object value(AnnotationValue value) {
        Object raw = value.getValue();
        return switch (raw) {
            case TypeMirror type -> desc(type);
            case VariableElement constant -> new EnumValue(elements.getBinaryName((TypeElement) constant.getEnclosingElement())
                .toString(), constant.getSimpleName().toString());
            case AnnotationMirror nested -> annotation(nested);
            case List<?> values -> values.stream().map(v -> value((AnnotationValue) v)).toList();
            default -> raw;
        };
    }
}
