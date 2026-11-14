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
package io.vidocq.noether.jpa.core.access;

import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.EmbeddableModel;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.spi.ManagedAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** The accesses Noether generates at bootstrap, and the descriptor that tells whether an access fits a model. */
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

    /**
     * The access of a record whose components may come in another order than {@code attributes}, the order its owner
     * lists them in (a record {@code @IdClass}, §2.4.1): it lists {@code attributes} and builds the record from them.
     * The access in component order is the one noether-jpa-processor generated when there is one (BUG-20261114-06),
     * else a start-time hidden class.
     *
     * @param components the names of the components, in the order of the canonical constructor
     * @param generated the access generated for the record that lists these attributes, by {@link #descriptor}
     */
    public static ManagedAccess ofRecord(Class<?> type, List<AttributeModel> attributes, List<String> components,
            Function<List<String>, Optional<ManagedAccess>> generated) {
        List<String> names = attributes.stream().map(AttributeModel::name).toList();
        if (names.equals(components) || !new HashSet<>(names).equals(new HashSet<>(components))) {
            return generated.apply(descriptor(attributes)).orElseGet(() -> of(type, true, attributes));
        }
        int[] component = names.stream().mapToInt(components::indexOf).toArray();
        List<AttributeModel> ordered = components.stream().map(name -> attributes.get(names.indexOf(name))).toList();
        ManagedAccess inOrder = generated.apply(descriptor(ordered)).orElseGet(() -> of(type, true, ordered));
        return new ReorderedAccess(inOrder, descriptor(attributes), component);
    }

    /** The attributes as {@link ManagedAccess#attributes()} lists them: {@code name:FIELD} or {@code name:PROPERTY}. */
    public static List<String> descriptor(List<AttributeModel> attributes) {
        return attributes.stream().map(a -> a.name() + ":" + a.access()).toList();
    }
}
