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
package io.vidocq.mansart.validation.core.metadata;

/** Java Modules plumbing: this module must read the module of a class before it can look inside it. */
final class Modules {

    private Modules() {
    }

    /**
     * Makes this module read the module of {@code target}. A named module does not read the unnamed
     * module (the class path) or an application module it does not require, so this is needed before
     * any lookup. It never opens anything: access to non-public members still needs an {@code opens}.
     */
    static void canRead(Class<?> target) {
        Module ours = Modules.class.getModule();
        Module theirs = target.getModule();
        if (ours.isNamed() && !ours.canRead(theirs)) {
            ours.addReads(theirs);
        }
    }
}
