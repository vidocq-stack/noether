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
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BUG-20261111-05: Noether hands every connection back to Agroal before Narayana completes the transaction. Agroal
 * closes, with a warning, each connection still open when the transaction commits ("Closing 1 open connection(s)
 * prior to commit") or ends ("Closing open connection(s) on after completion").
 */
@QuarkusTest
class AgroalConnectionReleaseTest {

    private final AgroalWarnings warnings = new AgroalWarnings();

    @Inject
    Library library;

    @BeforeEach
    void listen() {
        warnings.listen();
    }

    @AfterEach
    void stopListening() {
        warnings.stop();
    }

    @Test
    void aCommitLeavesNoConnectionForAgroalToClose() {
        library.add("Au Bonheur des Dames");

        assertEquals(List.of(), warnings.messages);
    }

    /** §7.9.1: the flush runs in Noether's synchronization, before Agroal checks its connections. */
    @Test
    void aCommitThatFlushesBeforeCompletionLeavesNoConnectionForAgroalToClose() {
        long id = library.add("La Curée");

        library.rename(id, "La Faute de l'abbé Mouret");

        assertEquals("La Faute de l'abbé Mouret", library.title(id));
        assertEquals(List.of(), warnings.messages);
    }

    @Test
    void aRollbackByAnExceptionLeavesNoConnectionForAgroalToClose() {
        assertThrows(IllegalStateException.class, () -> library.addThenFail("Le Rêve"));

        assertEquals(0, library.count("Le Rêve"));
        assertEquals(List.of(), warnings.messages);
    }

    @Test
    void aRollbackThroughTheRegistryLeavesNoConnectionForAgroalToClose() throws SQLException {
        library.addThenMarkRollbackOnly("L'Œuvre");

        assertEquals(0, library.committedRows("L'Œuvre"));
        assertEquals(List.of(), warnings.messages);
    }

    /**
     * The application took a connection first, so Agroal checks its connections before Noether flushes: Noether holds
     * none at that point, and the flush takes one of its own.
     */
    @Test
    void aConnectionTheApplicationTookFirstDoesNotStopTheFlushBeforeCompletion() throws SQLException {
        long id = library.add("La Terre");

        library.readThenRename(id, "La Débâcle");

        assertEquals(1, library.committedRows("La Débâcle"));
        assertEquals(List.of(), warnings.messages);
    }
}
