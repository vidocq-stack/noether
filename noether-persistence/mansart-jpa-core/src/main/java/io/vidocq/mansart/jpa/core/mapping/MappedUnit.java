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

import io.vidocq.mansart.jpa.core.access.ManagedAccess;
import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinders;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.build.EntityModelBuilder;
import jakarta.persistence.PersistenceException;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistence unit ready to run: its entity model, the generated access of every entity and embeddable, and the
 * binder of every basic attribute. Everything is built when the factory is created, so that a mapping error, a
 * package not opened to Mansart or a converter that cannot be instantiated fails {@code createEntityManagerFactory}
 * rather than the first operation that needs it. Immutable once built, shared by every entity manager of the factory.
 */
public final class MappedUnit {

    private final PersistenceUnitModel model;
    private final Map<Class<?>, ManagedAccess> entities;
    private final Map<EmbeddableModel, ManagedAccess> embeddables;
    private final Map<BasicAttribute, ValueBinder> binders;

    private MappedUnit(PersistenceUnitModel model, Map<Class<?>, ManagedAccess> entities,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders) {
        this.model = model;
        this.entities = entities;
        this.embeddables = embeddables;
        this.binders = binders;
    }

    /** Maps the managed classes named {@code classNames}, loaded with {@code loader}. */
    public static MappedUnit of(Collection<String> classNames, ClassLoader loader) {
        return of(classNames, loader, false);
    }

    /**
     * Maps the managed classes named {@code classNames}, loaded with {@code loader}; {@code mappingFiles} tells that
     * the unit has XML mapping files, which may map what the annotations leave unmapped.
     */
    public static MappedUnit of(Collection<String> classNames, ClassLoader loader, boolean mappingFiles) {
        PersistenceUnitModel model = EntityModelBuilder.build(classNames, loader, mappingFiles);
        ValueBinders valueBinders = new ValueBinders(loader);
        Map<Class<?>, ManagedAccess> entities = new IdentityHashMap<>();
        Map<EmbeddableModel, ManagedAccess> embeddables = new IdentityHashMap<>();
        Map<BasicAttribute, ValueBinder> binders = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            entities.put(entity.javaType(), ManagedAccess.of(entity));
            prepare(entity.attributes(), valueBinders, embeddables, binders);
        }
        return new MappedUnit(model, entities, embeddables, binders);
    }

    private static void prepare(List<AttributeModel> attributes, ValueBinders valueBinders,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders) {
        for (AttributeModel attribute : attributes) {
            switch (attribute) {
                case BasicAttribute basic -> binders.put(basic, bind(valueBinders, basic));
                case EmbeddedAttribute embedded -> {
                    EmbeddableModel embeddable = embedded.embeddable();
                    if (!embeddables.containsKey(embeddable)) {
                        embeddables.put(embeddable, ManagedAccess.of(embeddable));
                        prepare(embeddable.attributes(), valueBinders, embeddables, binders);
                    }
                }
                default -> {
                    // relationships and element collections are bound with their target (P5); pending attributes
                    // with their mapping file
                }
            }
        }
    }

    private static ValueBinder bind(ValueBinders valueBinders, BasicAttribute attribute) {
        try {
            return valueBinders.of(attribute);
        } catch (PersistenceException e) {
            throw new PersistenceException("The attribute " + attribute.name() + " of " + attribute.declaringClass().getName()
                + " cannot be stored: " + e.getMessage(), e);
        }
    }

    public PersistenceUnitModel model() {
        return model;
    }

    /** The access of {@code entity}. */
    public ManagedAccess access(EntityModel entity) {
        return entities.get(entity.javaType());
    }

    /** The access of {@code embeddable}, an embeddable model of this unit. */
    public ManagedAccess access(EmbeddableModel embeddable) {
        return embeddables.get(embeddable);
    }

    /** The binder of {@code attribute}, a basic attribute of this unit. */
    public ValueBinder binder(BasicAttribute attribute) {
        return binders.get(attribute);
    }
}
