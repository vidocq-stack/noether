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
package io.vidocq.mansart.jpa.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider;
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

/** What application sources may legally contain, and how builds compile them. */
class EdgeCasesTest {

    private static Map<String, ManagedAccess> accesses(String provider, Path... classes) throws Throwable {
        URL[] urls = new URL[classes.length];
        for (int i = 0; i < classes.length; i++) {
            urls[i] = classes[i].toUri().toURL();
        }
        URLClassLoader loader = new URLClassLoader(urls, EdgeCasesTest.class.getClassLoader());
        Class<?> type = Class.forName(provider, true, loader);
        ManagedAccessProvider instance = (ManagedAccessProvider) MethodHandles.publicLookup()
            .findConstructor(type, MethodType.methodType(void.class)).invoke();
        return instance.accesses().stream().collect(Collectors.toMap(a -> a.type().getName(), Function.identity()));
    }

    @Test
    void genericSuperclassesFluentSettersAndThrowingConstructorsCompileAndWork(@TempDir Path out) throws Throwable {
        Compilation edge = Compilation.onClassPath(out, Compilation.FIXTURES.resolve("edge"));
        assertThat(edge.success()).as("%s", edge.diagnostics()).isTrue();
        assertThat(edge.messages(Diagnostic.Kind.WARNING)).isEmpty();
        Map<String, ManagedAccess> accesses = accesses("edge._MansartJpaAccess", edge.classes());

        ManagedAccess ticket = accesses.get("edge.Ticket");
        Object instance = ticket.instantiate();
        ticket.set(instance, ticket.attributes().indexOf("id:FIELD"), 42L);
        assertThat(ticket.get(instance, ticket.attributes().indexOf("id:FIELD"))).isEqualTo(42L);

        ManagedAccess fluent = accesses.get("edge.Fluent");
        Object fluentInstance = fluent.instantiate();
        fluent.set(fluentInstance, 0, 7L);
        assertThat(fluent.get(fluentInstance, 0)).isEqualTo(7L);

        assertThat(accesses.get("edge.Checked").instantiate()).isNotNull();
    }

    @Test
    void anEntityOfTheUnnamedPackageGetsItsAccess(@TempDir Path out) throws Throwable {
        Compilation loose = Compilation.onClassPath(out, Compilation.FIXTURES.resolve("defaultpkg"));
        assertThat(loose.success()).as("%s", loose.diagnostics()).isTrue();
        assertThat(Files.readAllLines(loose.classes().resolve("META-INF/services/" + MansartJpaProcessor.SPI)))
            .containsExactly("_MansartJpaAccess");
        assertThat(accesses("_MansartJpaAccess", loose.classes())).containsOnlyKeys("Loose");
    }

    @Test
    void recompilingPartOfAPackageKeepsTheAccessesOfTheRest(@TempDir Path first, @TempDir Path second) throws Throwable {
        Compilation full = Compilation.onClassPath(first, Compilation.FIXTURES.resolve("shop"));
        assertThat(full.success()).isTrue();
        Compilation partial = Compilation.incrementally(second, full, Compilation.FIXTURES.resolve("shop/Item.java"));
        assertThat(partial.success()).as("%s", partial.diagnostics()).isTrue();
        // the classes of the partial build first, as an output directory updated in place would hold them
        Map<String, ManagedAccess> accesses = accesses("shop._MansartJpaAccess", partial.classes(), full.classes());
        assertThat(accesses).containsKeys("shop.Item", "shop.Purchase", "shop.Address", "shop.Geo");
    }

    @Test
    void theServicesFileKeepsTheProvidersOfEarlierCompilations(@TempDir Path out) throws Exception {
        Compilation shop = Compilation.onClassPath(out, Compilation.FIXTURES.resolve("shop"));
        assertThat(shop.success()).isTrue();
        Compilation edge = Compilation.incrementally(out, shop, Compilation.FIXTURES.resolve("edge"));
        assertThat(edge.success()).as("%s", edge.diagnostics()).isTrue();
        assertThat(Files.readAllLines(out.resolve("classes/META-INF/services/" + MansartJpaProcessor.SPI)))
            .containsExactlyInAnyOrder("shop._MansartJpaAccess", "edge._MansartJpaAccess");
    }

    @Test
    void theAccessesNeverCallTheJavaLangTypesOfThePackage(@TempDir Path out) {
        Compilation edge = Compilation.onClassPath(out, Compilation.FIXTURES.resolve("edge"));
        assertThat(edge.success()).as("%s", edge.diagnostics()).isTrue(); // edge.Override shadows java.lang.Override
        assertThat(edge.source("edge/Ticket$$MansartAccess.java")).contains("@java.lang.Override");
    }
}
