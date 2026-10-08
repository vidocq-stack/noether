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
/**
 * An application module compiled with mansart-jpa-processor: its entity package is neither opened nor exported; the
 * accesses generated for it are handed over to Mansart by the provides clause the processor asks for.
 */
module io.vidocq.mansart.jpa.processorit {
    requires jakarta.persistence;
    requires io.vidocq.mansart.jpa.core;

    provides io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider
        with io.vidocq.mansart.jpa.processorit.closed._MansartJpaAccess;
}
