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

import java.util.List;

/**
 * The scripts schema generation would write for a running persistence unit, rendered for its database and never
 * executed: what a development tool shows as the unit's DDL.
 * <p>Obtained with {@code entityManagerFactory.unwrap(SchemaScripts.class)}. Each call follows the unit's schema
 * generation settings ({@code create-source}, {@code drop-source}, their script sources and the load script) and its
 * dialect. It opens a connection, only to detect the database, unless the unit names its database with
 * {@code jakarta.persistence.database-product-name}. A failure is a {@code PersistenceException}.
 */
public interface SchemaScripts {

    /** The statements {@code scripts.create-target} would receive: the create statements, then the load script. */
    List<String> createScript();

    /** The statements {@code scripts.drop-target} would receive. */
    List<String> dropScript();
}
