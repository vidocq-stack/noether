/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.model.build;

import io.vidocq.noether.jpa.core.model.AccessKind;
import io.vidocq.noether.jpa.core.model.source.ClassInfo;
import io.vidocq.noether.jpa.core.model.source.MethodInfo;
import io.vidocq.noether.jpa.core.spi.LazyReference;
import static java.lang.constant.ConstantDescs.*;
import java.lang.classfile.ClassFile;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/** One Class-File generator shared by APT and the opaque-archive bootstrap path. */
public final class LazySubclassGenerator {
    private static final ClassDesc REFERENCE = ClassDesc.of(LazyReference.class.getName());
    private static final ClassDesc STATE = ClassDesc.of(LazyReference.State.class.getName());
    private static final MethodTypeDesc VOID = MethodTypeDesc.of(CD_void);

    private LazySubclassGenerator() {}

    public static String name(String entity) {
        return entity + "$$NoetherLazy";
    }

    /**
     * Conservative scope: no hierarchy, exposed fields, final instance methods, composite/embedded/derived id,
     * or non-property persistent state. These shapes keep the provider's eager path.
     */
    public static boolean supports(ClassInfo info, List<AccessPlanner.Member> members) {
        if (info.isFinal() || info.isAbstract() || info.isRecord()
                || !"java.lang.Object".equals(info.superclassName()) || !info.interfaceNames().isEmpty()
                || (info.flags() & ClassFile.ACC_PUBLIC) == 0
                || members.stream().anyMatch(m -> m.access() != AccessKind.PROPERTY)) return false;
        var ids = members.stream().filter(m -> m.element().isAnnotated("jakarta.persistence.Id")).toList();
        if (ids.size() != 1 || members.stream().anyMatch(m -> m.element().isAnnotated("jakarta.persistence.EmbeddedId"))
                || !ids.getFirst().type().isPrimitive() && !List.of("Ljava/lang/String;", "Ljava/lang/Long;",
                    "Ljava/lang/Integer;", "Ljava/lang/Short;", "Ljava/lang/Byte;", "Ljava/lang/Character;")
                    .contains(ids.getFirst().type().descriptorString())) return false;
        if (info.fields().stream().anyMatch(f -> (f.flags() & ClassFile.ACC_STATIC) == 0
                && (f.flags() & ClassFile.ACC_PRIVATE) == 0)) return false;
        if (info.methods().stream().anyMatch(m -> !m.isStatic() && !m.isPrivate()
                && (m.flags() & (ClassFile.ACC_FINAL | ClassFile.ACC_NATIVE | ClassFile.ACC_ABSTRACT)) != 0)) return false;
        return info.methods().stream().anyMatch(m -> m.name().equals(INIT_NAME) && m.type().equals(VOID)
            && !m.isPrivate());
    }

    public static byte[] bytes(ClassInfo info, List<AccessPlanner.Member> members) {
        return bytes(info, members, name(info.name()));
    }

    /** The bootstrap uses a distinct name so stale build-time output cannot substitute for freshly planned code. */
    public static byte[] bytes(ClassInfo info, List<AccessPlanner.Member> members, String className) {
        ClassDesc parent = ClassDesc.of(info.name()), self = ClassDesc.of(className);
        String identifier = ((MethodInfo) members.stream()
            .filter(m -> m.element().isAnnotated("jakarta.persistence.Id")).findFirst().orElseThrow().element()).name();
        return ClassFile.of().build(self, builder -> {
            builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC);
            builder.withSuperclass(parent);
            builder.withInterfaceSymbols(REFERENCE);
            builder.withField("$noetherState", STATE, ClassFile.ACC_PRIVATE);
            builder.withMethodBody(INIT_NAME, VOID, ClassFile.ACC_PUBLIC,
                code -> code.aload(0).invokespecial(parent, INIT_NAME, VOID).return_());
            builder.withMethodBody("$noetherState", MethodTypeDesc.of(STATE), ClassFile.ACC_PUBLIC,
                code -> code.aload(0).getfield(self, "$noetherState", STATE).areturn());
            builder.withMethodBody("$noetherState", MethodTypeDesc.of(CD_void, STATE), ClassFile.ACC_PUBLIC,
                code -> code.aload(0).aload(1).putfield(self, "$noetherState", STATE).return_());
            for (MethodInfo method : info.methods()) {
                if (method.name().startsWith("<") || method.isPrivate() || method.isStatic() || method.isSynthetic()
                        || method.name().equals(identifier) && method.type().parameterCount() == 0) continue;
                builder.withMethodBody(method.name(), method.type(), method.flags() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED),
                    code -> {
                        code.aload(0).getfield(self, "$noetherState", STATE)
                            .invokestatic(STATE, "initialize", MethodTypeDesc.of(CD_void, STATE)).aload(0);
                        int local = 1;
                        for (ClassDesc parameter : method.type().parameterList()) {
                            TypeKind kind = TypeKind.from(parameter);
                            code.loadLocal(kind, local);
                            local += kind.slotSize();
                        }
                        code.invokespecial(parent, method.name(), method.type()).return_(TypeKind.from(method.type().returnType()));
                    });
            }
        });
    }
}
