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
package io.vidocq.mansart.jpa.core.model.build;

import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.Signature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the type arguments a class gives to a generic interface it implements, directly or through superclasses,
 * from the generic signatures of the class files — e.g. the {@code X} and {@code Y} of
 * {@code AttributeConverter<X, Y>}. The results are erasures.
 */
final class GenericSignatures {

    private GenericSignatures() {
    }

    /** The erased type arguments given to {@code interfaceName}, or {@code null} if they cannot be resolved. */
    static List<Class<?>> typeArguments(String className, String interfaceName, ClassFileSource source, ClassLoader loader) {
        return search(className, interfaceName, Map.of(), source, loader);
    }

    private static List<Class<?>> search(String className, String interfaceName, Map<String, Class<?>> bindings,
            ClassFileSource source, ClassLoader loader) {
        ClassInfo info = source.read(className).orElse(null);
        if (info == null || info.genericSignature() == null) {
            return null;
        }
        ClassSignature signature = ClassSignature.parseFrom(info.genericSignature());
        List<Signature.ClassTypeSig> supertypes = new ArrayList<>(signature.superinterfaceSignatures());
        if (signature.superclassSignature() != null) {
            supertypes.add(signature.superclassSignature());
        }
        for (Signature.ClassTypeSig supertype : supertypes) {
            String name = supertype.className().replace('/', '.');
            if (name.equals(interfaceName)) {
                return supertype.typeArgs().stream().<Class<?>>map(a -> argument(a, bindings, loader)).toList();
            }
            if (!name.startsWith("java.")) {
                Map<String, Class<?>> inner = new HashMap<>();
                ClassInfo superInfo = source.read(name).orElse(null);
                if (superInfo != null && superInfo.genericSignature() != null) {
                    List<Signature.TypeParam> parameters = ClassSignature.parseFrom(superInfo.genericSignature()).typeParameters();
                    for (int i = 0; i < parameters.size() && i < supertype.typeArgs().size(); i++) {
                        inner.put(parameters.get(i).identifier(), argument(supertype.typeArgs().get(i), bindings, loader));
                    }
                }
                List<Class<?>> found = search(name, interfaceName, inner, source, loader);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Class<?> argument(Signature.TypeArg argument, Map<String, Class<?>> bindings, ClassLoader loader) {
        return argument instanceof Signature.TypeArg.Bounded bounded ? erasure(bounded.boundType(), bindings, loader) : Object.class;
    }

    private static Class<?> erasure(Signature signature, Map<String, Class<?>> bindings, ClassLoader loader) {
        return switch (signature) {
            case Signature.ClassTypeSig c -> Types.load(c.className().replace('/', '.'), loader);
            case Signature.ArrayTypeSig a -> erasure(a.componentSignature(), bindings, loader).arrayType();
            case Signature.TypeVarSig v -> bindings.getOrDefault(v.identifier(), Object.class);
            default -> Object.class;
        };
    }
}
