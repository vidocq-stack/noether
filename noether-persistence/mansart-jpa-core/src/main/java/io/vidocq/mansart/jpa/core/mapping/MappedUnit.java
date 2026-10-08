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

import io.vidocq.mansart.jpa.core.access.Accesses;
import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.mansart.jpa.core.jdbc.type.ValueBinders;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;

/**
 * A persistence unit ready to run: its entity model, the generated access of every entity and embeddable, and the
 * binder of every basic attribute. Everything is built when the factory is created, so that a mapping error, a
 * package not opened to Mansart or a converter that cannot be instantiated fails {@code createEntityManagerFactory}
 * rather than the first operation that needs it. Immutable once built, shared by every entity manager of the factory.
 */
public final class MappedUnit {

    private static final System.Logger LOGGER = System.getLogger(MappedUnit.class.getName());

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
        return of(classNames, loader, false, List.of());
    }

    /**
     * Maps the managed classes named {@code classNames}, loaded with {@code loader}.
     *
     * @param mappingFiles whether the unit has XML mapping files, which may map what the annotations leave unmapped
     * @param providers the accesses generated at build time; a managed class without one, or whose generated access
     *        does not list the attributes of the model (a class changed since it was compiled), gets a hidden class
     */
    public static MappedUnit of(Collection<String> classNames, ClassLoader loader, boolean mappingFiles,
            Iterable<ManagedAccessProvider> providers) {
        PersistenceUnitModel model = EntityModelBuilder.build(classNames, loader, mappingFiles);
        Generated generated = new Generated(providers);
        ValueBinders valueBinders = new ValueBinders(loader);
        Map<Class<?>, ManagedAccess> entities = new IdentityHashMap<>();
        Map<EmbeddableModel, ManagedAccess> embeddables = new IdentityHashMap<>();
        Map<BasicAttribute, ValueBinder> binders = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            entities.put(entity.javaType(), generated.find(entity.javaType(), entity.attributes())
                .orElseGet(() -> Accesses.of(entity)));
            prepare(entity.attributes(), valueBinders, generated, embeddables, binders);
        }
        return new MappedUnit(model, entities, embeddables, binders);
    }

    /** The accesses generated at build time, by managed class. */
    private static final class Generated {
        private final Map<Class<?>, List<ManagedAccess>> byType = new IdentityHashMap<>();

        Generated(Iterable<ManagedAccessProvider> providers) {
            try {
                for (ManagedAccessProvider provider : providers) {
                    for (ManagedAccess access : provider.accesses()) {
                        byType.computeIfAbsent(access.type(), t -> new ArrayList<>()).add(access);
                    }
                }
            } catch (ServiceConfigurationError e) {
                throw new PersistenceException("An access generated by mansart-jpa-processor cannot be loaded: " + e.getMessage(), e);
            }
        }

        Optional<ManagedAccess> find(Class<?> type, List<AttributeModel> attributes) {
            List<ManagedAccess> candidates = byType.getOrDefault(type, List.of());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            List<String> descriptor = Accesses.descriptor(attributes);
            Optional<ManagedAccess> fitting = candidates.stream().filter(a -> a.attributes().equals(descriptor)).findFirst();
            if (fitting.isEmpty()) {
                LOGGER.log(System.Logger.Level.WARNING, "The access generated for {0} lists {1}, but the class maps {2}: it "
                    + "was compiled from another version of the class; Mansart generates its access at bootstrap instead",
                    type.getName(), candidates.getFirst().attributes(), descriptor);
            }
            return fitting;
        }
    }

    private static void prepare(List<AttributeModel> attributes, ValueBinders valueBinders, Generated generated,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders) {
        for (AttributeModel attribute : attributes) {
            switch (attribute) {
                case BasicAttribute basic -> binders.put(basic, bind(valueBinders, basic));
                case EmbeddedAttribute embedded -> {
                    EmbeddableModel embeddable = embedded.embeddable();
                    if (!embeddables.containsKey(embeddable)) {
                        embeddables.put(embeddable, generated.find(embeddable.javaType(), embeddable.attributes())
                            .orElseGet(() -> Accesses.of(embeddable)));
                        prepare(embeddable.attributes(), valueBinders, generated, embeddables, binders);
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
