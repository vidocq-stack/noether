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
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.util.List;

/** Reads the runtime-visible annotations of the members of a class, from its class file. */
final class ClassFileReader {

    private ClassFileReader() {
    }

    /** The runtime-visible annotations of the declared field {@code name} of {@code type}. */
    static List<Annotation> fieldAnnotations(Class<?> type, String name) {
        ClassModel model = ClassFiles.parse(type);
        for (FieldModel field : model.fields()) {
            if (field.fieldName().stringValue().equals(name)) {
                return field.findAttribute(Attributes.runtimeVisibleAnnotations())
                    .map(a -> a.annotations().stream().map(raw -> AnnotationFactory.of(raw, type.getClassLoader())).toList())
                    .orElse(List.of());
            }
        }
        throw new ValidationException(type.getName() + " has no field " + name);
    }
}
