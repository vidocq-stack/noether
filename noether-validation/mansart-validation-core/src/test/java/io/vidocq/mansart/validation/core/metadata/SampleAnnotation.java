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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** A runtime annotation with a member of every kind, some with defaults. */
@Retention(RetentionPolicy.RUNTIME)
public @interface SampleAnnotation {

    enum Color { RED, GREEN, BLUE }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Nested {
        String label() default "nested";
    }

    byte aByte() default 1;
    short aShort() default 2;
    int anInt() default 3;
    long aLong() default 4L;
    float aFloat() default 5.5f;
    double aDouble() default 6.5d;
    char aChar() default 'c';
    boolean aBoolean() default true;
    String aString() default "text";
    Class<?> aClass() default Object.class;
    Color aColor() default Color.GREEN;
    Nested aNested() default @Nested;

    int[] ints() default {1, 2};
    String[] strings() default {};
    Class<?>[] classes() default {};
    Color[] colors() default {Color.RED};
    Nested[] nesteds() default {};
    long[] longs() default {};
    boolean[] booleans() default {};
    char[] chars() default {};

    /** No default: must be given. */
    String required();
}
