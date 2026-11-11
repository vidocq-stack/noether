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
package io.vidocq.noether.it.quarkus;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BUG-20261111-04: a TABLE generator commits each allocation on a connection of its own. Agroal refuses that commit on a
 * connection of the JTA data source, which it enlists in Narayana's transaction, so the unit allocates through its
 * non-JTA data source: the datasource {@code allocations}, without transactions.
 */
@QuarkusTest
class TableGeneratorTest {
    private final AgroalWarnings warnings = new AgroalWarnings();

    @Inject
    Vouchers vouchers;

    @BeforeEach
    void listen() {
        warnings.listen();
    }

    @AfterEach
    void stopListening() {
        warnings.stop();
    }

    /** §11.1.52: the block an allocation committed stays allocated when the business transaction rolls back. */
    @Test
    void identifiersAllocatedForARolledBackTransactionAreNeverHandedOutAgain() {
        List<Long> handedOut = new ArrayList<>();
        handedOut.add(vouchers.add("Germinal"));
        long[] doomed = new long[1];
        assertThrows(IllegalStateException.class, () -> vouchers.addThenFail("Doomed", doomed));
        handedOut.add(doomed[0]);
        for (int i = 0; i < 10; i++) {
            handedOut.add(vouchers.add("Voucher " + i));
        }

        assertEquals(12, handedOut.stream().distinct().count(), handedOut::toString);
        List<Long> committed = new ArrayList<>(handedOut);
        committed.remove(Long.valueOf(doomed[0]));
        assertEquals(committed, vouchers.ids());
        assertEquals(List.of(), warnings.messages);
    }
}
