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

import io.vidocq.noether.jpa.core.spi.ResultConstructorAccess;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classes a query of the sources builds without them being entities get their public constructors generated:
 * {@code SELECT NEW} in a {@code @NamedQuery} (§4.8.2) and {@code @ConstructorResult} (§3.11.15). Their package then
 * needs no {@code exports} (BUG-20261114-04).
 */
class ResultConstructorsTest {

    private static final Path RESULTS = Compilation.FIXTURES.resolve("results");

    @Test
    void theClassesTheQueriesNameGetTheirConstructors(@TempDir Path out) throws Exception {
        Compilation results = compiled(out);

        assertThat(Compilation.resultConstructors("results", results.classes()))
            .containsOnlyKeys("results.Summary", "results.Line");
    }

    @Test
    void theConstructorsAreThePublicOnesInDeclarationOrder(@TempDir Path out) throws Exception {
        Compilation results = compiled(out);
        ResultConstructorAccess summary = Compilation.resultConstructors("results", results.classes()).get("results.Summary");

        assertThat(summary.constructors()).containsExactly(List.of(String.class, long.class), List.of(String.class));
        assertThat(summary.construct(0, new Object[] {"ada", 7L})).hasToString("ada:7");
        assertThat(summary.construct(1, new Object[] {"bob"})).hasToString("bob:-1");
        assertThatThrownBy(() -> summary.construct(2, new Object[0])).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void aRecordOfAResultSetMappingIsBuiltByItsCanonicalConstructor(@TempDir Path out) throws Exception {
        Compilation results = compiled(out);
        ResultConstructorAccess line = Compilation.resultConstructors("results", results.classes()).get("results.Line");

        assertThat(line.constructors()).containsExactly(List.of(String.class, Long.class));
        assertThat(line.construct(0, new Object[] {"ada", 3L})).hasToString("Line[owner=ada, total=3]");
    }

    @Test
    void recompilingOneClassKeepsTheConstructorsOfThePackage(@TempDir Path first, @TempDir Path second)
            throws Exception {
        Compilation full = compiled(first);
        Compilation partial = Compilation.incrementally(second, full, RESULTS.resolve("results/Unused.java"));
        assertThat(partial.success()).as("%s", partial.diagnostics()).isTrue();

        assertThat(Compilation.resultConstructors("results", partial.classes(), full.classes()))
            .containsOnlyKeys("results.Summary", "results.Line");
    }

    private static Compilation compiled(Path out) {
        Compilation results = Compilation.onClassPath(out, RESULTS);
        assertThat(results.success()).as("%s", results.diagnostics()).isTrue();
        assertThat(results.messages(Diagnostic.Kind.WARNING)).isEmpty();
        return results;
    }
}
