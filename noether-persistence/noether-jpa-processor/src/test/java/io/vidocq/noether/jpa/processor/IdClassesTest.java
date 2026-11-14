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

import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import java.nio.file.Path;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The id class of an entity (§2.4.1) gets an access, so that its package needs no {@code opens} (BUG-20261114-03). It
 * lists the attributes the bootstrap would: one per identifier attribute, in their order, by field when the id class
 * declares a field of that name, else by property.
 */
class IdClassesTest {

    private static final Path KEYS = Compilation.FIXTURES.resolve("keys");

    @Test
    void theAccessOfAnIdClassReadsAndWritesItsFields(@TempDir Path out) throws Exception {
        Compilation keys = compiled(out);
        ManagedAccess seat = Compilation.accesses("keys", keys.classes()).get("keys.SeatKey");

        assertThat(seat.attributes()).containsExactly("aisle:FIELD", "place:FIELD");
        Object key = seat.instantiate();
        seat.set(key, 0, "B");
        seat.set(key, 1, 7);
        assertThat(seat.get(key, 0)).isEqualTo("B");
        assertThat(seat.get(key, 1)).isEqualTo(7);
    }

    @Test
    void theAccessOfAnIdClassWithoutTheFieldsGoesThroughItsProperties(@TempDir Path out) throws Exception {
        Compilation keys = compiled(out);
        ManagedAccess gate = Compilation.accesses("keys", keys.classes()).get("keys.GateKey");

        assertThat(gate.attributes()).containsExactly("code:PROPERTY", "number:PROPERTY");
        Object key = gate.instantiate();
        gate.set(key, 0, "N");
        gate.set(key, 1, 4);
        assertThat(gate.get(key, 0)).isEqualTo("N");
        assertThat(gate.get(key, 1)).isEqualTo(4);
    }

    @Test
    void theAccessOfARecordIdClassBuildsItFromTheIdentifierAttributes(@TempDir Path out) throws Exception {
        Compilation keys = compiled(out);
        ManagedAccess bench = Compilation.accesses("keys", keys.classes()).get("keys.BenchKey");

        assertThat(bench.attributes()).containsExactly("aisle:FIELD", "row:FIELD");
        Object key = bench.construct("C", 2);
        assertThat(bench.get(key, 0)).isEqualTo("C");
        assertThat(bench.get(key, 1)).isEqualTo(2);
    }

    @Test
    void aRecordIdClassWhoseComponentsComeInAnotherOrderIsLeftToTheBootstrap(@TempDir Path out) throws Exception {
        Compilation keys = compiled(out);

        assertThat(Compilation.accesses("keys", keys.classes())).doesNotContainKey("keys.BoothKey");
    }

    @Test
    void thePartOfADerivedIdentityHasTheTypeTheIdClassDeclares(@TempDir Path out) throws Exception {
        Compilation keys = compiled(out);
        ManagedAccess stand = Compilation.accesses("keys", keys.classes()).get("keys.StandKey");

        assertThat(stand.attributes()).containsExactly("hall:FIELD", "rank:FIELD");
        Object key = stand.instantiate();
        stand.set(key, 0, 5L);
        assertThat(stand.get(key, 0)).isEqualTo(5L);
    }

    @Test
    void anIdClassWithoutAFieldForTheRelationshipIsLeftToTheBootstrap(@TempDir Path out) throws Exception {
        Compilation keys = compiled(out);

        assertThat(Compilation.accesses("keys", keys.classes())).doesNotContainKey("keys.KioskKey");
    }

    @Test
    void recompilingOneEntityKeepsTheAccessesOfTheIdClassesOfThePackage(@TempDir Path first, @TempDir Path second)
            throws Exception {
        Compilation full = compiled(first);
        Compilation partial = Compilation.incrementally(second, full, KEYS.resolve("keys/Hall.java"));
        assertThat(partial.success()).as("%s", partial.diagnostics()).isTrue();

        assertThat(Compilation.accesses("keys", partial.classes(), full.classes()))
            .containsKeys("keys.SeatKey", "keys.GateKey", "keys.BenchKey", "keys.StandKey");
    }

    private static Compilation compiled(Path out) throws Exception {
        Compilation keys = Compilation.onClassPath(out, KEYS);
        assertThat(keys.success()).as("%s", keys.diagnostics()).isTrue();
        assertThat(keys.messages(Diagnostic.Kind.WARNING)).isEmpty();
        return keys;
    }
}
