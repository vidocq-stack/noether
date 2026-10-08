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

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.Objects;

/**
 * Base class of the generated annotation implementations: the generated subclass only returns member
 * values, everything the {@link Annotation} contract asks for (equality with the JDK's own instances,
 * hash code, string form) lives here.
 */
abstract class GeneratedAnnotation implements Annotation {

    private final AnnotationTypeInfo info;
    private final Object[] values;

    protected GeneratedAnnotation(AnnotationTypeInfo info, Object[] values) {
        this.info = info;
        this.values = values;
    }

    /** Member {@code index}; arrays are copied, as the JDK does. Called by the generated accessors. */
    protected final Object member(int index) {
        Object value = values[index];
        return switch (value) {
            case boolean[] a -> a.clone();
            case byte[] a -> a.clone();
            case char[] a -> a.clone();
            case short[] a -> a.clone();
            case int[] a -> a.clone();
            case long[] a -> a.clone();
            case float[] a -> a.clone();
            case double[] a -> a.clone();
            case Object[] a -> a.clone();
            default -> value;
        };
    }

    @Override
    public final Class<? extends Annotation> annotationType() {
        return info.type();
    }

    @Override
    public final boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!info.type().isInstance(other)) {
            return false;
        }
        Annotation that = (Annotation) other;
        for (int i = 0; i < values.length; i++) {
            if (!Objects.deepEquals(values[i], info.read(i, that))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public final int hashCode() {
        int result = 0;
        for (int i = 0; i < values.length; i++) {
            result += (127 * info.members().get(i).name().hashCode()) ^ valueHash(values[i]);
        }
        return result;
    }

    private static int valueHash(Object value) {
        return switch (value) {
            case boolean[] a -> Arrays.hashCode(a);
            case byte[] a -> Arrays.hashCode(a);
            case char[] a -> Arrays.hashCode(a);
            case short[] a -> Arrays.hashCode(a);
            case int[] a -> Arrays.hashCode(a);
            case long[] a -> Arrays.hashCode(a);
            case float[] a -> Arrays.hashCode(a);
            case double[] a -> Arrays.hashCode(a);
            case Object[] a -> Arrays.hashCode(a);
            default -> value.hashCode();
        };
    }

    @Override
    public final String toString() {
        StringBuilder out = new StringBuilder("@").append(info.type().getCanonicalName()).append('(');
        boolean single = values.length == 1 && info.members().get(0).name().equals("value");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            if (!single) {
                out.append(info.members().get(i).name()).append('=');
            }
            append(out, values[i]);
        }
        return out.append(')').toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case String s -> out.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            case Character c -> out.append('\'').append(c).append('\'');
            case Long l -> out.append(l).append('L');
            case Float f -> out.append(f).append('f');
            case Byte b -> out.append("(byte)0x").append(String.format("%02x", b));
            case Class<?> c -> out.append(c.getCanonicalName()).append(".class");
            case Enum<?> e -> out.append(e.name());
            case boolean[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case byte[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case char[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case short[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case int[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case long[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case float[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case double[] a -> out.append(Arrays.toString(a).replace('[', '{').replace(']', '}'));
            case Object[] a -> {
                out.append('{');
                for (int i = 0; i < a.length; i++) {
                    if (i > 0) {
                        out.append(", ");
                    }
                    append(out, a[i]);
                }
                out.append('}');
            }
            default -> out.append(value);
        }
    }
}
