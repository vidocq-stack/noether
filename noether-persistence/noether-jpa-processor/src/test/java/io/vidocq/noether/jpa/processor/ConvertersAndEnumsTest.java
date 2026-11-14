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
package io.vidocq.noether.jpa.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import io.vidocq.noether.jpa.core.spi.ManagedAccessProvider;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The code a unit runs of a class it does not manage: a converter (§3.9) and an enum mapped by its
 * {@code @EnumeratedValue} field (§11.1.18) get an access, so that their package needs no {@code opens}
 * (BUG-20261114-01, BUG-20261114-02).
 */
class ConvertersAndEnumsTest {

    private static final Path CODES = Compilation.FIXTURES.resolve("codes");

    @Test
    void aPackageWithAConverterAndNoEntityGetsItsProvider(@TempDir Path out) throws Exception {
        Compilation codes = Compilation.onClassPath(out, CODES);
        assertThat(codes.success()).as("%s", codes.diagnostics()).isTrue();
        assertThat(codes.messages(Diagnostic.Kind.WARNING)).isEmpty();
        assertThat(Files.readAllLines(codes.classes().resolve("META-INF/services/" + NoetherJpaProcessor.SPI)))
            .containsExactly("codes._NoetherJpaAccess");
    }

    @Test
    void theAccessOfAConverterInstantiatesIt(@TempDir Path out) throws Throwable {
        Compilation codes = Compilation.onClassPath(out, CODES);
        Map<String, ManagedAccess> accesses = accesses(codes.classes());

        ManagedAccess trim = accesses.get("codes.Trim");
        assertThat(trim.attributes()).isEmpty();
        assertThat(trim.instantiate()).isExactlyInstanceOf(trim.type());
        assertThat(codes.source("codes/Trim$$NoetherAccess.java")).contains("new codes.Trim()");
        assertThatThrownBy(() -> trim.get(new Object(), 0)).isInstanceOf(IndexOutOfBoundsException.class);

        ManagedAccess quiet = accesses.get("codes.Quiet");
        assertThat(quiet.instantiate()).isExactlyInstanceOf(quiet.type()); // its constructor is private: a handle
        assertThat(codes.source("codes/Quiet$$NoetherAccess.java")).contains("NEW.invokeExact()");
    }

    @Test
    void theAccessOfAnEnumReadsItsEnumeratedValueAndNothingElse(@TempDir Path out) throws Throwable {
        Compilation codes = Compilation.onClassPath(out, CODES);
        ManagedAccess grade = accesses(codes.classes()).get("codes.Grade");

        assertThat(grade.attributes()).containsExactly("letter:FIELD");
        Object[] constants = grade.type().getEnumConstants();
        assertThat(grade.get(constants[0], 0)).isEqualTo("P");
        assertThat(grade.get(constants[1], 0)).isEqualTo("F");
        assertThatThrownBy(grade::instantiate).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> grade.set(constants[0], 0, "X")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void recompilingOneClassKeepsTheAccessesOfTheConvertersAndEnumsOfThePackage(@TempDir Path first,
            @TempDir Path second) throws Throwable {
        Compilation full = Compilation.onClassPath(first, CODES);
        assertThat(full.success()).isTrue();
        Compilation partial = Compilation.incrementally(second, full, CODES.resolve("codes/Trim.java"));
        assertThat(partial.success()).as("%s", partial.diagnostics()).isTrue();
        assertThat(accesses(partial.classes(), full.classes())).containsOnlyKeys("codes.Trim", "codes.Quiet", "codes.Grade");
    }

    private static Map<String, ManagedAccess> accesses(Path... classes) throws Throwable {
        URL[] urls = new URL[classes.length];
        for (int i = 0; i < classes.length; i++) {
            urls[i] = classes[i].toUri().toURL();
        }
        URLClassLoader loader = new URLClassLoader(urls, ConvertersAndEnumsTest.class.getClassLoader());
        Class<?> type = Class.forName("codes._NoetherJpaAccess", true, loader);
        ManagedAccessProvider provider = (ManagedAccessProvider) MethodHandles.publicLookup()
            .findConstructor(type, MethodType.methodType(void.class)).invoke();
        return provider.accesses().stream().collect(Collectors.toMap(a -> a.type().getName(), Function.identity()));
    }
}
