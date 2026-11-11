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
package io.vidocq.noether.jpa.jta;

import io.vidocq.noether.jpa.core.spi.TransactionIntegration;
import io.vidocq.noether.jpa.core.spi.TransactionIntegrationProvider;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.Optional;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * The bridge of a Jakarta EE server: every server binds its transaction synchronization registry at {@value #NAME}
 * (Jakarta EE Platform). Outside a server, where no naming is configured, this provider offers nothing.
 */
public final class JndiTransactionIntegrationProvider implements TransactionIntegrationProvider {
    static final String NAME = "java:comp/TransactionSynchronizationRegistry";

    @Override
    public Optional<TransactionIntegration> integration() {
        try {
            return Optional.of(new SynchronizationRegistryIntegration(InitialContext.<TransactionSynchronizationRegistry>doLookup(NAME)));
        } catch (NamingException | ClassCastException absent) {
            return Optional.empty();
        }
    }
}
