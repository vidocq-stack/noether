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
import java.lang.classfile.AnnotationValue;
import java.lang.invoke.MethodHandles;
import java.util.List;

/** Turns the raw element values of a class file into the Java objects an annotation instance returns. */
final class AnnotationValues {

    private AnnotationValues() {
    }

    /** {@code expected} is the declared type of the annotation member the value belongs to. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convert(AnnotationValue value, Class<?> expected, ClassLoader loader) {
        return switch (value) {
            case AnnotationValue.OfBoolean v -> v.booleanValue();
            case AnnotationValue.OfByte v -> v.byteValue();
            case AnnotationValue.OfChar v -> v.charValue();
            case AnnotationValue.OfShort v -> v.shortValue();
            case AnnotationValue.OfInt v -> v.intValue();
            case AnnotationValue.OfLong v -> v.longValue();
            case AnnotationValue.OfFloat v -> v.floatValue();
            case AnnotationValue.OfDouble v -> v.doubleValue();
            case AnnotationValue.OfString v -> v.stringValue();
            case AnnotationValue.OfClass v -> ClassFiles.load(v.classSymbol(), loader);
            case AnnotationValue.OfEnum v -> Enum.valueOf((Class) ClassFiles.load(v.classSymbol(), loader), v.constantName().stringValue());
            case AnnotationValue.OfAnnotation v -> AnnotationFactory.of(v.annotation(), loader);
            case AnnotationValue.OfArray v -> array(v.values(), expected.getComponentType(), loader);
            default -> throw new ValidationException("Unsupported annotation value " + value);
        };
    }

    /** Builds a typed array, primitives included, through method handles rather than java.lang.reflect.Array. */
    private static Object array(List<AnnotationValue> values, Class<?> component, ClassLoader loader) {
        try {
            Object array = MethodHandles.arrayConstructor(component.arrayType()).invoke(values.size());
            var setter = MethodHandles.arrayElementSetter(component.arrayType());
            for (int i = 0; i < values.size(); i++) {
                Object element = convert(values.get(i), component, loader);
                setter.invoke(array, i, element);
            }
            return array;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new ValidationException("Unable to build an annotation array of " + component.getName(), e);
        }
    }
}
