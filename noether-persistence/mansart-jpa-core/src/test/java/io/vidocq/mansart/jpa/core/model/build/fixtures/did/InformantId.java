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
package io.vidocq.mansart.jpa.core.model.build.fixtures.did;

import java.io.Serializable;
import java.util.Objects;

/** §2.4.1.3 example 1a: the id class of a dependent, its relationship part typed by the parent's key. */
public class InformantId implements Serializable {
    private String alias;
    private long handler;

    public InformantId() {
    }

    public InformantId(String alias, long handler) {
        this.alias = alias;
        this.handler = handler;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InformantId that && Objects.equals(alias, that.alias) && handler == that.handler;
    }

    @Override
    public int hashCode() {
        return Objects.hash(alias, handler);
    }
}
