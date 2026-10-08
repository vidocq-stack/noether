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
package io.vidocq.mansart.jpa.core.session;

import jakarta.persistence.Cache;
import jakarta.persistence.PersistenceException;

/**
 * The {@link Cache} of a factory without a second-level cache (§3.10.1): it never contains anything, evicting is a
 * no-op. The second-level cache itself is milestone P11.
 */
final class NoSecondLevelCache implements Cache {

    @Override
    public boolean contains(Class<?> cls, Object primaryKey) {
        return false;
    }

    @Override
    public void evict(Class<?> cls, Object primaryKey) {
    }

    @Override
    public void evict(Class<?> cls) {
    }

    @Override
    public void evictAll() {
    }

    @Override
    public <T> T unwrap(Class<T> cls) {
        if (cls.isInstance(this)) {
            return cls.cast(this);
        }
        throw new PersistenceException("Unsupported unwrap type " + cls.getName());
    }
}
