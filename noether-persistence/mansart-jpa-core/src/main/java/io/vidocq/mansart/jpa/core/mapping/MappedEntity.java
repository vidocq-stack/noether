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
package io.vidocq.mansart.jpa.core.mapping;

import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.session.NotYet;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * An entity of a mapped unit, as the persistence context and the flush engine use it: its model, its access, the root
 * of its hierarchy (identities are unique per root, §2.4), and how to read its identifier from an instance.
 */
public final class MappedEntity {

    private final EntityModel model;
    private final ManagedAccess access;
    private final Class<?> root;
    private final int[] idAttributes;
    private final ManagedAccess embeddedId;
    private final StatePolicy state;
    private final EntityStatements statements;
    private final int rank;

    MappedEntity(EntityModel model, ManagedAccess access, Class<?> root, ManagedAccess embeddedId, StatePolicy state,
            Function<EntityModel, EntityStatements> statements, int rank) {
        this.model = model;
        this.state = state;
        this.access = access;
        this.root = root;
        this.embeddedId = embeddedId;
        this.idAttributes = idIndexes(model);
        this.statements = statements.apply(model);
        this.rank = rank;
    }

    /**
     * Its place in the insert order of the unit: an entity comes after the entities its owned to-one relationships
     * reference, so that foreign keys are satisfied; deletes go in reverse order.
     */
    public int rank() {
        return rank;
    }

    /** Its insert, update, delete and select by identifier, and their parameters. */
    public EntityStatements statements() {
        return statements;
    }

    /** The indexes, in {@link EntityModel#attributes()}, of the attributes that make the identifier of {@code model}. */
    static int[] idIndexes(EntityModel model) {
        List<AttributeModel> parts = switch (model.id()) {
            case IdModel.Single single -> List.of(single.attribute());
            case IdModel.Embedded embedded -> List.of(embedded.attribute());
            case IdModel.ByIdClass byIdClass -> byIdClass.attributes();
            case IdModel.Derived derived -> List.of(derived.relationship());
        };
        List<Integer> indexes = new ArrayList<>();
        for (AttributeModel part : parts) {
            indexes.add(model.attributes().indexOf(part));
        }
        return indexes.stream().mapToInt(Integer::intValue).toArray();
    }

    public EntityModel model() {
        return model;
    }

    public ManagedAccess access() {
        return access;
    }

    /** How its state is kept in snapshots and compared at flush. */
    public StatePolicy state() {
        return state;
    }

    /** The root entity class of the hierarchy: identities are unique per root. */
    public Class<?> root() {
        return root;
    }

    /** The indexes, in {@link EntityModel#attributes()}, of the attributes that make the identifier. */
    public int[] idAttributes() {
        return idAttributes.clone();
    }

    /**
     * The identity of {@code instance}: the identifier value for a single identifier, a {@link CompositeId} of the
     * parts otherwise; {@code null} while it is not assigned (a {@code null} part, or a generated primitive still 0).
     */
    public Object id(Object instance) {
        return switch (model.id()) {
            case IdModel.Single single -> assigned(access.get(instance, idAttributes[0]), single.generation().isPresent());
            case IdModel.Embedded _ -> {
                Object key = access.get(instance, idAttributes[0]);
                if (key == null) {
                    yield null;
                }
                Object[] parts = new Object[embeddedId.attributes().size()];
                embeddedId.read(key, parts);
                yield parts(parts);
            }
            case IdModel.ByIdClass _ -> {
                Object[] parts = new Object[idAttributes.length];
                for (int i = 0; i < idAttributes.length; i++) {
                    parts[i] = access.get(instance, idAttributes[i]);
                }
                yield parts(parts);
            }
            case IdModel.Derived _ -> throw NotYet.milestone("P5", "derived identities");
        };
    }

    /** A generated integral identifier still 0 is a primitive the database has not assigned yet. */
    private static Object assigned(Object value, boolean generated) {
        return switch (value) {
            case null -> null;
            case Long l when generated && l == 0L -> null;
            case Integer i when generated && i == 0 -> null;
            case Short s when generated && s == 0 -> null;
            case Byte b when generated && b == 0 -> null;
            default -> value;
        };
    }

    private static CompositeId parts(Object[] parts) {
        for (Object part : parts) {
            if (part == null) {
                return null;
            }
        }
        return CompositeId.of(parts);
    }
}
