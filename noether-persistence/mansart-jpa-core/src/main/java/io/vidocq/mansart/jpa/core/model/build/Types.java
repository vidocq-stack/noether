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

import jakarta.persistence.PersistenceException;
import java.lang.constant.ClassDesc;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Loading the classes descriptors name, and the basic types of §2.8 (3.2 adds {@code Instant} and {@code Year}). */
final class Types {

    private static final Map<String, Class<?>> PRIMITIVES = Map.of("Z", boolean.class, "B", byte.class, "S", short.class,
        "C", char.class, "I", int.class, "J", long.class, "F", float.class, "D", double.class, "V", void.class);

    private static final Map<Class<?>, Class<?>> BOXES = Map.of(boolean.class, Boolean.class, byte.class, Byte.class,
        short.class, Short.class, char.class, Character.class, int.class, Integer.class, long.class, Long.class,
        float.class, Float.class, double.class, Double.class);

    private static final Set<Class<?>> BASIC = Set.of(String.class, BigInteger.class, BigDecimal.class, Date.class, Calendar.class,
        java.sql.Date.class, java.sql.Time.class, java.sql.Timestamp.class, byte[].class, Byte[].class, char[].class,
        Character[].class, LocalDate.class, LocalTime.class, LocalDateTime.class, OffsetTime.class, OffsetDateTime.class,
        Instant.class, Year.class, UUID.class, Boolean.class, Byte.class, Short.class, Character.class, Integer.class,
        Long.class, Float.class, Double.class);

    private Types() {
    }

    static Class<?> load(ClassDesc desc, ClassLoader loader) {
        if (desc.isPrimitive()) {
            return PRIMITIVES.get(desc.descriptorString());
        }
        if (desc.isArray()) {
            return load(desc.componentType(), loader).arrayType();
        }
        String descriptor = desc.descriptorString();
        return load(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'), loader);
    }

    static Class<?> load(String binaryName, ClassLoader loader) {
        try {
            return Class.forName(binaryName, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new PersistenceException("Unable to load the class " + binaryName + " of the persistence unit", e);
        }
    }

    static Class<?> box(Class<?> type) {
        return BOXES.getOrDefault(type, type);
    }

    /** §2.8: primitives, their wrappers, strings, big numbers, temporal types, byte and char arrays, enums, UUID. */
    static boolean isBasic(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || BASIC.contains(type);
    }

    static boolean isLegacyTemporal(Class<?> type) {
        return type == Date.class || type == Calendar.class;
    }
}
