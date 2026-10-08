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
package io.vidocq.mansart.jpa.core.access;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_MethodHandle;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import jakarta.persistence.PersistenceException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Generates the {@link ManagedAccess} of a managed class with the Class-File API: a hidden class of this package
 * whose class data is the list of the method handles reaching the class (obtained through a private lookup, so the
 * package of the managed class must be opened to this module). Each handle is loaded with an {@code ldc} of a dynamic
 * constant ({@link MethodHandles#classDataAt}), which the JIT treats as a constant and inlines.
 *
 * <pre>{@code
 * final class Access_Customer extends ManagedAccess {
 *     Object instantiate()                  { return ldc(H0).invokeExact(); }
 *     Object get(Object o, int i)           { switch (i) { case 0: return ldc(H1).invokeExact(o); ... } }
 *     void set(Object o, int i, Object v)   { switch (i) { case 0: ldc(Hn).invokeExact(o, v); return; ... } }
 * }
 * }</pre>
 *
 * The hidden class is not strongly bound to its loader: it is unloaded with the factory that holds it.
 */
final class AccessGenerator {

    private static final ClassDesc CD_MANAGED_ACCESS = ClassDesc.of(ManagedAccess.class.getName());
    private static final ClassDesc CD_OUT_OF_BOUNDS = ClassDesc.of(IndexOutOfBoundsException.class.getName());
    private static final MethodTypeDesc CONSTRUCTOR = MethodTypeDesc.of(CD_void, CD_Class, ClassDesc.of(List.class.getName()));
    private static final MethodTypeDesc INSTANTIATE = MethodTypeDesc.of(CD_Object);
    private static final MethodTypeDesc CONSTRUCT = MethodTypeDesc.of(CD_Object, CD_Object.arrayType());
    private static final MethodTypeDesc GET = MethodTypeDesc.of(CD_Object, CD_Object, CD_int);
    private static final MethodTypeDesc SET = MethodTypeDesc.of(CD_void, CD_Object, CD_int, CD_Object);
    private static final MethodTypeDesc GETTER = MethodTypeDesc.of(CD_Object, CD_Object);
    private static final MethodTypeDesc SETTER = MethodTypeDesc.of(CD_void, CD_Object, CD_Object);
    private static final MethodTypeDesc BULK = MethodTypeDesc.of(CD_void, CD_Object, CD_Object.arrayType());

    private AccessGenerator() {
    }

    static ManagedAccess generate(Class<?> type, boolean record, List<AttributeModel> attributes) {
        List<MethodHandle> handles = new ArrayList<>();
        try {
            MethodHandles.Lookup lookup = Handles.lookupIn(type);
            int creator = -1;
            if (record) {
                creator = add(handles, canonicalConstructor(lookup, type, attributes));
            } else if (!type.accessFlags().contains(AccessFlag.ABSTRACT)) {
                creator = add(handles, lookup.findConstructor(type, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class)));
            }
            int[] getters = new int[attributes.size()];
            int[] setters = new int[record ? 0 : attributes.size()];
            for (int i = 0; i < attributes.size(); i++) {
                AttributeModel attribute = attributes.get(i);
                MethodHandles.Lookup declaring = Handles.lookupIn(attribute.declaringClass());
                getters[i] = add(handles, getter(declaring, attribute).asType(MethodType.methodType(Object.class, Object.class)));
                if (!record) {
                    setters[i] = add(handles, setter(declaring, attribute)
                        .asType(MethodType.methodType(void.class, Object.class, Object.class)));
                }
            }
            byte[] bytes = bytes(type, record, creator, getters, setters);
            MethodHandles.Lookup hidden = Handles.own().defineHiddenClassWithClassData(bytes, List.copyOf(handles), true);
            return (ManagedAccess) hidden.findConstructor(hidden.lookupClass(),
                MethodType.methodType(void.class, Class.class, List.class)).invoke(type, Accesses.descriptor(attributes));
        } catch (PersistenceException e) {
            throw e;
        } catch (ReflectiveOperationException e) {
            throw new PersistenceException("Mansart cannot reach a persistent attribute of " + type.getName() + ": " + e.getMessage(), e);
        } catch (Throwable e) {
            throw new PersistenceException("Mansart cannot generate the access of " + type.getName(), e);
        }
    }

    private static int add(List<MethodHandle> handles, MethodHandle handle) {
        handles.add(handle);
        return handles.size() - 1;
    }

    private static MethodHandle canonicalConstructor(MethodHandles.Lookup lookup, Class<?> type, List<AttributeModel> components)
            throws ReflectiveOperationException {
        Class<?>[] parameters = components.stream().map(AttributeModel::javaType).toArray(Class<?>[]::new);
        return lookup.findConstructor(type, MethodType.methodType(void.class, parameters))
            .asType(MethodType.genericMethodType(parameters.length))
            .asSpreader(Object[].class, parameters.length);
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, AttributeModel attribute) throws ReflectiveOperationException {
        Class<?> owner = attribute.declaringClass();
        if (attribute.access() == AccessKind.FIELD) {
            return lookup.findGetter(owner, attribute.name(), attribute.javaType());
        }
        List<String> names = attribute.javaType() == boolean.class
            ? List.of("is" + capitalized(attribute.name()), "is" + attribute.name(), "get" + capitalized(attribute.name()),
                "get" + attribute.name())
            : List.of("get" + capitalized(attribute.name()), "get" + attribute.name());
        return accessor(lookup, owner, names, MethodType.methodType(attribute.javaType()));
    }

    private static MethodHandle setter(MethodHandles.Lookup lookup, AttributeModel attribute) throws ReflectiveOperationException {
        Class<?> owner = attribute.declaringClass();
        if (attribute.access() == AccessKind.FIELD) {
            return lookup.findSetter(owner, attribute.name(), attribute.javaType());
        }
        return accessor(lookup, owner, List.of("set" + capitalized(attribute.name()), "set" + attribute.name()),
            MethodType.methodType(void.class, attribute.javaType()));
    }

    /**
     * The first of {@code names} declared with {@code type}: the property {@code description} may come from
     * {@code getDescription} or from {@code getdescription}, and {@code URL} from {@code getURL} (JavaBeans
     * decapitalisation, as {@code EntityModelBuilder} reads them).
     */
    private static MethodHandle accessor(MethodHandles.Lookup lookup, Class<?> owner, List<String> names, MethodType type)
            throws NoSuchMethodException, IllegalAccessException {
        NoSuchMethodException missing = null;
        for (String name : names.stream().distinct().toList()) {
            try {
                return lookup.findVirtual(owner, name, type);
            } catch (NoSuchMethodException e) {
                missing = missing == null ? e : missing;
            }
        }
        throw missing;
    }

    private static String capitalized(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // ---- bytecode ---------------------------------------------------------------------------------------

    private static byte[] bytes(Class<?> type, boolean record, int creator, int[] getters, int[] setters) {
        ClassDesc self = ClassDesc.of(AccessGenerator.class.getPackageName(), "Access_" + type.getSimpleName());
        return ClassFile.of().build(self, cb -> {
            cb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            cb.withSuperclass(CD_MANAGED_ACCESS);
            cb.withMethodBody(INIT_NAME, CONSTRUCTOR, ClassFile.ACC_PUBLIC, code -> code
                .aload(0).aload(1).aload(2).invokespecial(CD_MANAGED_ACCESS, INIT_NAME, CONSTRUCTOR).return_());
            if (creator >= 0 && record) {
                cb.withMethodBody("construct", CONSTRUCT, ClassFile.ACC_PUBLIC | ClassFile.ACC_VARARGS, code -> code
                    .ldc(handle(creator)).aload(1).invokevirtual(CD_MethodHandle, "invokeExact", CONSTRUCT).areturn());
            } else if (creator >= 0) {
                cb.withMethodBody("instantiate", INSTANTIATE, ClassFile.ACC_PUBLIC, code -> code
                    .ldc(handle(creator)).invokevirtual(CD_MethodHandle, "invokeExact", INSTANTIATE).areturn());
            }
            cb.withMethodBody("get", GET, ClassFile.ACC_PUBLIC, code -> dispatch(code, getters, index -> code
                .ldc(handle(index)).aload(1).invokevirtual(CD_MethodHandle, "invokeExact", GETTER).areturn()));
            if (!record) {
                cb.withMethodBody("set", SET, ClassFile.ACC_PUBLIC, code -> dispatch(code, setters, index -> code
                    .ldc(handle(index)).aload(1).aload(3).invokevirtual(CD_MethodHandle, "invokeExact", SETTER).return_()));
            }
            // straight-line bulk copies: state[i] = getter(instance), setter(instance, state[i])
            cb.withMethodBody("read", BULK, ClassFile.ACC_PUBLIC, code -> {
                for (int i = 0; i < getters.length; i++) {
                    code.aload(2).loadConstant(i).ldc(handle(getters[i])).aload(1)
                        .invokevirtual(CD_MethodHandle, "invokeExact", GETTER).aastore();
                }
                code.return_();
            });
            if (!record) {
                cb.withMethodBody("write", BULK, ClassFile.ACC_PUBLIC, code -> {
                    for (int i = 0; i < setters.length; i++) {
                        code.ldc(handle(setters[i])).aload(1).aload(2).loadConstant(i).aaload()
                            .invokevirtual(CD_MethodHandle, "invokeExact", SETTER);
                    }
                    code.return_();
                });
            }
        });
    }

    /** {@code switch (attribute)}, local 2, over the handles; any other index is out of bounds. */
    private static void dispatch(CodeBuilder code, int[] handles, IntConsumer body) {
        Label outside = code.newLabel();
        List<SwitchCase> cases = new ArrayList<>();
        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < handles.length; i++) {
            Label label = code.newLabel();
            labels.add(label);
            cases.add(SwitchCase.of(i, label));
        }
        if (!cases.isEmpty()) {
            code.iload(2).tableswitch(0, handles.length - 1, outside, cases);
            for (int i = 0; i < handles.length; i++) {
                code.labelBinding(labels.get(i));
                body.accept(handles[i]);
            }
        }
        code.labelBinding(outside);
        code.new_(CD_OUT_OF_BOUNDS).dup().iload(2).invokespecial(CD_OUT_OF_BOUNDS, INIT_NAME, MethodTypeDesc.of(CD_void, CD_int))
            .athrow();
    }

    private static DynamicConstantDesc<MethodHandle> handle(int index) {
        return DynamicConstantDesc.ofNamed(ConstantDescs.BSM_CLASS_DATA_AT, ConstantDescs.DEFAULT_NAME, CD_MethodHandle, index);
    }
}
