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
package io.vidocq.noether.jpa.core.model.build;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.model.build.fixtures.Money;
import io.vidocq.noether.jpa.core.model.build.fixtures.MoneyConverter;
import io.vidocq.noether.jpa.core.model.source.ClassFileSource;
import jakarta.persistence.AttributeConverter;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The type arguments a class gives to a generic interface, read from the generic signatures of its class files. */
class GenericSignaturesTest {

    private static final ClassLoader LOADER = GenericSignaturesTest.class.getClassLoader();
    private static final String CONVERTER = AttributeConverter.class.getName();

    private static List<Class<?>> typeArguments(Class<?> type) {
        return GenericSignatures.typeArguments(type.getName(), CONVERTER, new ClassFileSource(LOADER), LOADER);
    }

    @Test
    void theArgumentsOfAnInterfaceTheClassImplements() {
        assertThat(typeArguments(MoneyConverter.class)).containsExactly(Money.class, Long.class);
    }

    @Test
    void theArgumentsASubclassBindsToTheTypeVariablesOfItsSuperclass() {
        assertThat(typeArguments(IntegerConverter.class)).containsExactly(Integer.class, String.class);
    }

    @Test
    void noArgumentsForAClassWithoutGenericSignature() {
        assertThat(typeArguments(Money.class)).isEmpty();
    }

    @Test
    void noArgumentsWhenNoSupertypeImplementsTheInterface() {
        assertThat(typeArguments(Named.class)).isEmpty();
    }

    abstract static class ToStringConverter<T> implements AttributeConverter<T, String> {
    }

    static final class IntegerConverter extends ToStringConverter<Integer> {
        @Override
        public String convertToDatabaseColumn(Integer value) {
            return String.valueOf(value);
        }

        @Override
        public Integer convertToEntityAttribute(String value) {
            return Integer.valueOf(value);
        }
    }

    static final class Named implements Supplier<String> {
        @Override
        public String get() {
            return "named";
        }
    }
}
