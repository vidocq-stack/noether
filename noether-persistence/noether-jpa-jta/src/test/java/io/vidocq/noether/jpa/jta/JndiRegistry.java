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

import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;

/** Test stand-in for a server's naming: {@code java:comp/TransactionSynchronizationRegistry} only. */
public final class JndiRegistry implements InitialContextFactory {
    static volatile TransactionSynchronizationRegistry bound;

    @Override
    public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        return new InitialContext(true) {
            @Override
            public Object lookup(String name) throws NamingException {
                if (name.equals("java:comp/TransactionSynchronizationRegistry") && bound != null) return bound;
                throw new NameNotFoundException(name);
            }
        };
    }
}
