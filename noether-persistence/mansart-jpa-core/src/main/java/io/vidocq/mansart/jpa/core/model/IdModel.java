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
package io.vidocq.mansart.jpa.core.model;

import java.util.List;
import java.util.Optional;

/** The identifier of an entity (§2.4). */
public sealed interface IdModel {

    /** A single {@code @Id} attribute, possibly generated. */
    record Single(BasicAttribute attribute, Optional<GenerationModel> generation) implements IdModel {
    }

    /** Several {@code @Id} attributes, matching the fields or properties of an {@code @IdClass}. */
    record ByIdClass(Class<?> idClass, List<BasicAttribute> attributes) implements IdModel {
        public ByIdClass {
            attributes = List.copyOf(attributes);
        }
    }

    /** An {@code @EmbeddedId}. */
    record Embedded(EmbeddedAttribute attribute) implements IdModel {
    }
}
