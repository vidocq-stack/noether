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
package io.vidocq.mansart.jpa.core.bootstrap;

/**
 * Whether Jakarta Validation is there, without linking a single class of it: the persistence provider must work
 * with no Bean Validation on the class path (§3.7, and the TCK checks it).
 *
 * <p>Presence of the API is what is checked here; whether a provider can actually build a {@code ValidatorFactory}
 * is the business of milestone P11.
 */
public final class BeanValidation {

    private BeanValidation() {
    }

    public static boolean isAvailable(ClassLoader loader) {
        try {
            Class.forName("jakarta.validation.Validation", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
