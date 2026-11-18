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
package io.vidocq.noether.jpa.cdi.moduleit;

import jakarta.enterprise.context.Dependent;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceUnit;
import jakarta.persistence.SynchronizationType;

/**
 * A component that declares its persistence references on its class, as a servlet of a Jakarta EE application does,
 * and looks them up under {@code java:comp/env} instead of having them injected.
 */
@Dependent
@PersistenceContext(name = "persistence/container", unitName = "container")
@PersistenceContext(name = "persistence/container-unsynchronized", unitName = "container",
    synchronization = SynchronizationType.UNSYNCHRONIZED)
@PersistenceUnit(name = "persistence/container-factory", unitName = "container")
public class ReferencingComponent {
}
