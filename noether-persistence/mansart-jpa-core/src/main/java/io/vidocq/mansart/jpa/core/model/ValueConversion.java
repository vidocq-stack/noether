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
package io.vidocq.mansart.jpa.core.model;

import jakarta.persistence.TemporalType;

/** How the value of a basic attribute becomes a column value (§2.8, §3.9, §11.1.18, §11.1.53). */
public sealed interface ValueConversion {

    /** The value goes to the column as it is. */
    record None() implements ValueConversion {
    }

    /** An enum, as its ordinal. */
    record EnumOrdinal() implements ValueConversion {
    }

    /** An enum, as its name. */
    record EnumString() implements ValueConversion {
    }

    /** An enum, as the value of its field annotated {@code @EnumeratedValue} (3.2). */
    record EnumByValue(String field, Class<?> valueType) implements ValueConversion {
    }

    /** A {@code java.util.Date} or {@code Calendar}, as a date, a time or a timestamp. */
    record Temporal(TemporalType type) implements ValueConversion {
    }

    /** Through an {@code AttributeConverter}; {@code databaseType} is its column-side type. */
    record Converted(Class<?> converter, Class<?> databaseType) implements ValueConversion {
    }
}
