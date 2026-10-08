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
package io.vidocq.mansart.jpa.core.access;

import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import java.util.List;

/** The accesses Mansart generates at bootstrap, and the descriptor that tells whether an access fits a model. */
public final class Accesses {

    private Accesses() {
    }

    /** The access of an entity, attributes of its mapped superclasses and entity superclasses included. */
    public static ManagedAccess of(EntityModel entity) {
        return AccessGenerator.generate(entity.javaType(), false, entity.attributes(), entity.callbacks());
    }

    /** The access of an embeddable, as one owner sees it. */
    public static ManagedAccess of(EmbeddableModel embeddable) {
        return AccessGenerator.generate(embeddable.javaType(), embeddable.isRecord(), embeddable.attributes(), List.of());
    }

    /** The access of a class that is neither an entity nor an embeddable of the model (an {@code @IdClass}). */
    public static ManagedAccess of(Class<?> type, boolean record, List<AttributeModel> attributes) {
        return AccessGenerator.generate(type, record, attributes, List.of());
    }

    /** The attributes as {@link ManagedAccess#attributes()} lists them: {@code name:FIELD} or {@code name:PROPERTY}. */
    public static List<String> descriptor(List<AttributeModel> attributes) {
        return attributes.stream().map(a -> a.name() + ":" + a.access()).toList();
    }
}
