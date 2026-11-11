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
package io.vidocq.noether.jpa.core.spi;

import java.util.Optional;

/**
 * Supplies the transaction bridge of a JTA unit that its container bootstrapped without one (§9.6), found through
 * {@link java.util.ServiceLoader} in the unit's class loader: for instance {@code noether-jpa-jta}, which reads the
 * synchronization registry every Jakarta EE server binds. An explicit {@link TransactionIntegration#PROPERTY} wins.
 */
public interface TransactionIntegrationProvider {
    /** The bridge of the current environment, or empty when this environment does not offer one. */
    Optional<TransactionIntegration> integration();
}
