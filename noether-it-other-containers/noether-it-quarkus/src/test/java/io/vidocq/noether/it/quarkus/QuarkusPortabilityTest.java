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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Noether, unchanged, as the Jakarta Persistence provider of a Quarkus application (Vidocq/noether#45): ArC runs
 * Noether's CDI 4.1 Lite build compatible extension at build time, Narayana drives the transactions. Quarkus'
 * Hibernate ORM extension is absent, so every statement here is Noether's.
 */
@QuarkusTest
class QuarkusPortabilityTest {

    @Inject
    Library library;

    @Test
    void theProviderWritesAndQueriesInsideNarayanaTransactions() {
        library.add("Germinal");
        library.add("Germinal");

        assertEquals(2, library.count("Germinal"));
    }

    @Test
    void aRolledBackTransactionUndoesTheFlushedInsert() {
        assertThrows(IllegalStateException.class, () -> library.addThenFail("Doomed"));

        assertEquals(0, library.count("Doomed"));
    }

    @Test
    void aFindOutsideATransactionReadsTheCommittedRow() {
        long id = library.add("Nana");

        assertEquals("Nana", library.title(id));
    }
}
