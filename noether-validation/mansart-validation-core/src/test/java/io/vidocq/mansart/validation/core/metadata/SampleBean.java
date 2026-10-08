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

import io.vidocq.mansart.validation.core.metadata.SampleAnnotation.Color;
import io.vidocq.mansart.validation.core.metadata.SampleAnnotation.Nested;

/** Carries annotations read both by the JDK (the oracle) and by the Class-File API reader. */
public class SampleBean {

    @SampleAnnotation(required = "r")
    public String defaults;

    @SampleAnnotation(required = "all", aByte = 9, aShort = 8, anInt = 7, aLong = 6L, aFloat = 1.25f, aDouble = 2.25d,
        aChar = 'z', aBoolean = false, aString = "other", aClass = String.class, aColor = Color.BLUE,
        aNested = @Nested(label = "n"), ints = {4, 5, 6}, strings = {"a", "b"}, classes = {int.class, String[].class, void.class},
        colors = {Color.BLUE, Color.RED}, nesteds = {@Nested, @Nested(label = "x")}, longs = {1L}, booleans = {true, false},
        chars = {'a', 'b'})
    public String everything;
}
