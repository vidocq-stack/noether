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
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates annotation instances without the JDK's annotation proxies: one class per annotation type is
 * generated with the Class-File API, as a hidden subclass of {@link GeneratedAnnotation} that implements
 * the annotation interface and returns the stored member values.
 */
final class AnnotationFactory {

    private static final String PACKAGE = AnnotationFactory.class.getPackageName();
    private static final ClassDesc BASE = ClassDesc.of(PACKAGE, "GeneratedAnnotation");
    private static final ClassDesc INFO = ClassDesc.of(PACKAGE, "AnnotationTypeInfo");
    private static final ClassDesc OBJECT_ARRAY = ConstantDescs.CD_Object.arrayType();

    private static final ClassValue<MethodHandle> CONSTRUCTORS = new ClassValue<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected MethodHandle computeValue(Class<?> type) {
            return generate(AnnotationTypeInfo.of((Class<? extends Annotation>) type));
        }
    };

    private AnnotationFactory() {
    }

    /** Builds an instance from explicit member values; the others take their default, a missing required one is an error. */
    static Annotation create(AnnotationTypeInfo info, Map<String, Object> explicit) {
        for (String name : explicit.keySet()) {
            if (!info.hasMember(name)) {
                throw new IllegalArgumentException(info.type().getName() + " has no member " + name);
            }
        }
        List<AnnotationTypeInfo.Member> members = info.members();
        Object[] values = new Object[members.size()];
        for (int i = 0; i < values.length; i++) {
            AnnotationTypeInfo.Member member = members.get(i);
            if (explicit.containsKey(member.name())) {
                values[i] = explicit.get(member.name());
            } else if (member.hasDefault()) {
                values[i] = member.defaultValue();
            } else {
                throw new IllegalArgumentException("The member " + member.name() + " of " + info.type().getName()
                    + " has no default and was not given");
            }
        }
        try {
            return (Annotation) CONSTRUCTORS.get(info.type()).invokeExact(info, values);
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new ValidationException("Unable to create an instance of " + info.type().getName(), e);
        }
    }

    /** Builds the instance a class file declares: raw element values converted, defaults filled in. */
    @SuppressWarnings("unchecked")
    static Annotation of(java.lang.classfile.Annotation raw, ClassLoader loader) {
        Class<? extends Annotation> type = (Class<? extends Annotation>) ClassFiles.load(raw.classSymbol(), loader);
        AnnotationTypeInfo info = AnnotationTypeInfo.of(type);
        Map<String, Object> explicit = new HashMap<>();
        for (java.lang.classfile.AnnotationElement element : raw.elements()) {
            String name = element.name().stringValue();
            explicit.put(name, AnnotationValues.convert(element.value(), info.member(name).type(), loader));
        }
        return create(info, explicit);
    }

    private static MethodHandle generate(AnnotationTypeInfo info) {
        Class<? extends Annotation> type = info.type();
        Modules.canRead(type);
        ClassDesc self = ClassDesc.of(PACKAGE, "Generated$" + type.getSimpleName());
        ClassDesc annotation = type.describeConstable().orElseThrow();
        MethodTypeDesc constructor = MethodTypeDesc.of(ConstantDescs.CD_void, INFO, OBJECT_ARRAY);
        byte[] bytes = ClassFile.of().build(self, builder -> {
            builder.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            builder.withSuperclass(BASE);
            builder.withInterfaceSymbols(annotation);
            builder.withMethodBody(ConstantDescs.INIT_NAME, constructor, ClassFile.ACC_PUBLIC, code -> code
                .aload(0).aload(1).aload(2)
                .invokespecial(BASE, ConstantDescs.INIT_NAME, constructor)
                .return_());
            List<AnnotationTypeInfo.Member> members = info.members();
            for (int i = 0; i < members.size(); i++) {
                int index = i;
                Class<?> memberType = members.get(i).type();
                builder.withMethodBody(members.get(i).name(), MethodTypeDesc.of(memberType.describeConstable().orElseThrow()),
                    ClassFile.ACC_PUBLIC, code -> {
                        code.aload(0).loadConstant(index)
                            .invokevirtual(BASE, "member", MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int));
                        returnAs(code, memberType);
                    });
            }
        });
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(bytes, true);
            return lookup.findConstructor(lookup.lookupClass(), MethodType.methodType(void.class, AnnotationTypeInfo.class, Object[].class))
                .asType(MethodType.methodType(Annotation.class, AnnotationTypeInfo.class, Object[].class));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ValidationException("Unable to generate the implementation of " + type.getName()
                + " (is it public, and is its package exported?)", e);
        }
    }

    /** The accessor returns an Object: cast or unbox it to the member type and return it. */
    private static void returnAs(CodeBuilder code, Class<?> type) {
        if (!type.isPrimitive()) {
            code.checkcast(type.describeConstable().orElseThrow()).areturn();
            return;
        }
        String name = type.getName();
        ClassDesc box = ClassDesc.of("java.lang", switch (name) {
            case "int" -> "Integer";
            case "char" -> "Character";
            default -> Character.toUpperCase(name.charAt(0)) + name.substring(1);
        });
        code.checkcast(box).invokevirtual(box, name + "Value", MethodTypeDesc.of(type.describeConstable().orElseThrow()));
        switch (name) {
            case "long" -> code.lreturn();
            case "float" -> code.freturn();
            case "double" -> code.dreturn();
            default -> code.ireturn();
        }
    }
}
