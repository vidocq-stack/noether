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

import jakarta.persistence.Query;

/**
 * Implemented by Noether's entity manager, for a container facade that runs each query in the persistence context of
 * the current transaction: the query is made once, then copied into the context that runs it. A query made from a
 * criteria object stays as it was when it was created; a later change of that object does not reach it.
 */
public interface QueryCopies {

    /**
     * {@code query}, created by an entity manager of the same persistence unit, created again in this one as it was
     * created: its statement, result type and parameters, not its bound values nor its settings.
     *
     * @throws IllegalArgumentException if {@code query} is not a query string or criteria query Noether created
     */
    Query copy(Query query);
}
