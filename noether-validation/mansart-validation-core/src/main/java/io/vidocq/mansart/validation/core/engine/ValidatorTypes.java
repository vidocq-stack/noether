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

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ValidationException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.Signature;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The type a {@link ConstraintValidator} validates, i.e. the {@code T} of {@code ConstraintValidator<A, T>},
 * read from the generic signatures in the class files of the validator and of its supertypes. Type
 * variables are resolved through the hierarchy; the result is the erasure.
 */
final class ValidatorTypes {

    private static final ClassValue<Class<?>> CACHE = new ClassValue<>() {
        @Override
        protected Class<?> computeValue(Class<?> validator) {
            Class<?> found = search(validator, Map.of());
            return found != null ? found : Object.class;
        }
    };

    private ValidatorTypes() {
    }

    static Class<?> validatedType(Class<?> validator) {
        return CACHE.get(validator);
    }

    private static Class<?> search(Class<?> type, Map<String, Class<?>> bindings) {
        ClassModel model = io.vidocq.mansart.validation.core.metadata.Classes.parse(type);
        ClassSignature signature = model.findAttribute(Attributes.signature()).map(a -> a.asClassSignature()).orElse(null);
        if (signature == null) {
            return null;
        }
        ClassLoader loader = type.getClassLoader();
        java.util.ArrayList<Signature.ClassTypeSig> supertypes = new java.util.ArrayList<>();
        if (signature.superclassSignature() != null) {
            supertypes.add(signature.superclassSignature());
        }
        supertypes.addAll(signature.superinterfaceSignatures());
        for (Signature.ClassTypeSig supertype : supertypes) {
            Class<?> raw = load(supertype, loader);
            if (raw == ConstraintValidator.class) {
                return supertype.typeArgs().size() < 2 ? Object.class : argument(supertype.typeArgs().get(1), bindings, loader);
            }
            if (ConstraintValidator.class.isAssignableFrom(raw)) {
                Map<String, Class<?>> inner = new HashMap<>();
                List<Signature.TypeParam> parameters = io.vidocq.mansart.validation.core.metadata.Classes.parse(raw)
                    .findAttribute(Attributes.signature()).map(a -> a.asClassSignature().typeParameters()).orElse(List.of());
                for (int i = 0; i < parameters.size() && i < supertype.typeArgs().size(); i++) {
                    inner.put(parameters.get(i).identifier(), argument(supertype.typeArgs().get(i), bindings, loader));
                }
                Class<?> found = search(raw, inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Class<?> argument(Signature.TypeArg argument, Map<String, Class<?>> bindings, ClassLoader loader) {
        if (argument instanceof Signature.TypeArg.Bounded bounded) {
            return erasure(bounded.boundType(), bindings, loader);
        }
        return Object.class;
    }

    private static Class<?> erasure(Signature signature, Map<String, Class<?>> bindings, ClassLoader loader) {
        return switch (signature) {
            case Signature.ClassTypeSig c -> load(c, loader);
            case Signature.ArrayTypeSig a -> erasure(a.componentSignature(), bindings, loader).arrayType();
            case Signature.TypeVarSig v -> bindings.getOrDefault(v.identifier(), Object.class);
            case Signature.BaseTypeSig b -> primitive(b.baseType());
            default -> Object.class;
        };
    }

    private static Class<?> primitive(char code) {
        return switch (code) {
            case 'Z' -> boolean.class;
            case 'B' -> byte.class;
            case 'S' -> short.class;
            case 'C' -> char.class;
            case 'I' -> int.class;
            case 'J' -> long.class;
            case 'F' -> float.class;
            case 'D' -> double.class;
            default -> void.class;
        };
    }

    private static Class<?> load(Signature.ClassTypeSig signature, ClassLoader loader) {
        String name = signature.outerType().map(outer -> outer.className() + "$" + signature.className()).orElse(signature.className())
            .replace('/', '.');
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ValidationException("Unable to load the class " + name, e);
        }
    }
}
