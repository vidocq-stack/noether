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
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;

/** Reading class files: the bytes of a loaded class, and the classes that descriptors name. */
final class ClassFiles {

    private ClassFiles() {
    }

    /** Parses the class file of {@code type}; class files are never encapsulated, even in a named module. */
    static ClassModel parse(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ValidationException("Unable to find the class file of " + type.getName());
            }
            return ClassFile.of().parse(in.readAllBytes());
        } catch (IOException e) {
            throw new ValidationException("Unable to read the class file of " + type.getName(), e);
        }
    }

    /** The class a descriptor names (primitives and arrays included), loaded without initialization. */
    static Class<?> load(ClassDesc desc, ClassLoader loader) {
        if (desc.isPrimitive()) {
            return switch (desc.descriptorString()) {
                case "Z" -> boolean.class;
                case "B" -> byte.class;
                case "S" -> short.class;
                case "C" -> char.class;
                case "I" -> int.class;
                case "J" -> long.class;
                case "F" -> float.class;
                case "D" -> double.class;
                default -> void.class;
            };
        }
        if (desc.isArray()) {
            return load(desc.componentType(), loader).arrayType();
        }
        String name = desc.packageName().isEmpty() ? desc.displayName() : desc.packageName() + "." + desc.displayName();
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new ValidationException("Unable to load the class " + name, e);
        }
    }
}
