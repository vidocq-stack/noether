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

import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Optional;

/**
 * A class as declared in its class file. Names are binary names ({@code com.acme.Outer$Inner}).
 *
 * @param superclassName the binary name of the superclass, {@code null} for {@code java.lang.Object} and interfaces
 *        without one
 * @param genericSignature the generic signature, or {@code null}
 */
public record ClassInfo(String name, String superclassName, List<String> interfaceNames, int flags, String genericSignature,
        boolean isRecord, List<AnnotationInfo> annotations, List<FieldInfo> fields, List<MethodInfo> methods) implements Annotated {

    public ClassInfo {
        interfaceNames = List.copyOf(interfaceNames);
        annotations = List.copyOf(annotations);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
    }

    public Optional<FieldInfo> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }

    public boolean isInterface() {
        return (flags & ClassFile.ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (flags & ClassFile.ACC_ABSTRACT) != 0;
    }

    public boolean isFinal() {
        return (flags & ClassFile.ACC_FINAL) != 0;
    }

    public boolean isEnum() {
        return (flags & ClassFile.ACC_ENUM) != 0;
    }
}
