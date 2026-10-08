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

import java.nio.file.Path;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** On the module path, the accesses are handed over by a {@code provides} clause the processor checks. */
class ModuleProvidesTest {

    private static final String LINE =
        "provides io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider with app._MansartJpaAccess;";

    @Test
    void aMissingProvidesClauseIsReportedWithTheLineToAdd(@TempDir Path out) {
        Compilation missing = Compilation.onModulePath(out, Compilation.FIXTURES.resolve("modular-missing"));
        assertThat(missing.success()).as("%s", missing.diagnostics()).isTrue();
        assertThat(missing.messages(Diagnostic.Kind.WARNING)).anySatisfy(m -> assertThat(m).contains(LINE));
    }

    @Test
    void aModuleThatDoesNotReadTheProviderIsToldToRequireIt(@TempDir Path out) {
        Compilation norequires = Compilation.onModulePath(out, Compilation.FIXTURES.resolve("modular-norequires"));
        assertThat(norequires.success()).isFalse();
        assertThat(norequires.messages(Diagnostic.Kind.ERROR)).anySatisfy(m -> assertThat(m)
            .contains("requires io.vidocq.mansart.jpa.core;"));
    }

    @Test
    void aDeclaredProvidesClauseCompilesWithoutWarnings(@TempDir Path out) {
        Compilation declared = Compilation.onModulePath(out, Compilation.FIXTURES.resolve("modular-declared"));
        assertThat(declared.success()).as("%s", declared.diagnostics()).isTrue();
        assertThat(declared.messages(Diagnostic.Kind.WARNING)).isEmpty();
        assertThat(declared.classes().resolve("app/_MansartJpaAccess.class")).exists();
    }
}
