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
package io.vidocq.noether.jpa.cdi;

import java.util.List;
import java.util.Objects;

/**
 * The persistence contexts and units the deployment's classes declare on themselves, each with a name: references a
 * component looks up under {@code java:comp/env} instead of having them injected (Jakarta EE 11 Platform §5.13,
 * §5.14). A naming service binds each name to the bean of its reference; Noether binds no name itself.
 *
 * <p>The extension runs on each part of a deployment: a module the annotation processor handled at build, or the
 * classes it runs on at start. Each part that declares references gets one {@code @Singleton} bean of this type, which
 * lists them sorted by name; a part without any gets none. A container can hold several: a consumer reads every bean of
 * this type ({@code Instance} iterates them), never a single selection.</p>
 *
 * @param references the references, sorted by name
 */
public record PersistenceReferences(List<Reference> references) {

    /**
     * A declared reference.
     *
     * @param name      the name relative to {@code java:comp/env}, as the annotation gives it
     * @param type      {@code EntityManager} for a persistence context, {@code EntityManagerFactory} for a unit
     * @param qualifier the qualifier of the bean the name is bound to: a {@code @Singleton} bean, which a lookup can
     *                  return without anyone having to destroy it
     */
    public record Reference(String name, Class<?> type, PersistenceBinding qualifier) {
        /** Checks the components. */
        public Reference {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(qualifier, "qualifier");
        }
    }

    /** Copies the references. */
    public PersistenceReferences {
        references = List.copyOf(references);
    }
}
