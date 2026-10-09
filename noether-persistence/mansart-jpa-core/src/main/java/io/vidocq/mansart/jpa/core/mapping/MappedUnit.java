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
import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.CallbackModel;
import io.vidocq.mansart.jpa.core.model.ColumnModel;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import io.vidocq.mansart.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider;
import jakarta.persistence.FetchType;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.Set;

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
    private final Map<Class<?>, MappedEntity> mapped;

    private MappedUnit(PersistenceUnitModel model, Map<Class<?>, ManagedAccess> entities,
            Map<EmbeddableModel, ManagedAccess> embeddables, Map<BasicAttribute, ValueBinder> binders,
            Map<Class<?>, MappedEntity> mapped) {
        this.model = model;
        this.entities = entities;
        this.embeddables = embeddables;
        this.binders = binders;
        this.mapped = mapped;
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
            List<String> callbacks = entity.callbacks().stream().map(CallbackModel::descriptor).toList();
            entities.put(entity.javaType(), generated.find(entity.javaType(), entity.attributes(), callbacks)
                .orElseGet(() -> Accesses.of(entity)));
            prepare(entity.attributes(), valueBinders, generated, embeddables, binders);
        }
        Map<Class<?>, Integer> ranks = ranks(model);
        Map<Class<?>, MappedEntity> mapped = new IdentityHashMap<>();
        for (EntityModel entity : model.entities()) {
            ManagedAccess embeddedId = entity.id() instanceof IdModel.Embedded embedded
                ? embeddables.get(embedded.attribute().embeddable()) : null;
            ManagedAccess idClass = entity.id() instanceof IdModel.ByIdClass byIdClass ? idClassAccess(byIdClass, loader) : null;
            StatePolicy state = StatePolicy.of(entity.attributes(), embeddables::get, valueBinders);
            mapped.put(entity.javaType(), new MappedEntity(entity, entities.get(entity.javaType()), root(model, entity), embeddedId,
                idClass, state, m -> EntityStatements.of(m, MappedEntity.idIndexes(m), binders::get, embeddables::get,
                    target -> model.entity(target).orElse(null), mapped::get),
                ranks.get(entity.javaType())));
        }
        return new MappedUnit(model, entities, embeddables, binders, mapped);
    }

    /**
     * Orders the entities for inserts (Kahn): an entity after those its owned to-one relationships reference. Entities
     * of a cycle keep the order of the unit, after the others; the flush writes the foreign keys a cycle leaves
     * dangling with an update once the rows exist.
     */
    private static Map<Class<?>, Integer> ranks(PersistenceUnitModel model) {
        Set<Class<?>> entities = new LinkedHashSet<>();
        model.entities().forEach(e -> entities.add(e.javaType()));
        Map<Class<?>, Set<Class<?>>> references = new LinkedHashMap<>();
        for (EntityModel entity : model.entities()) {
            Set<Class<?>> targets = new LinkedHashSet<>();
            for (AttributeModel attribute : entity.attributes()) {
                if (attribute instanceof AssociationAttribute association && association.owning() && association.singleValued()
                        && entities.contains(association.targetEntity()) && association.targetEntity() != entity.javaType()) {
                    targets.add(association.targetEntity());
                }
            }
            references.put(entity.javaType(), targets);
        }
        Map<Class<?>, Integer> ranks = new IdentityHashMap<>();
        int rank = 0;
        Set<Class<?>> placed = new LinkedHashSet<>();
        boolean progress = true;
        while (placed.size() < references.size() && progress) {
            progress = false;
            for (Map.Entry<Class<?>, Set<Class<?>>> entry : references.entrySet()) {
                if (!placed.contains(entry.getKey()) && placed.containsAll(entry.getValue())) {
                    ranks.put(entry.getKey(), rank++);
                    placed.add(entry.getKey());
                    progress = true;
                }
            }
        }
        for (Class<?> remaining : references.keySet()) {
            if (placed.add(remaining)) {
                ranks.put(remaining, rank++);
            }
        }
        return ranks;
    }

    /**
     * The access of an {@code @IdClass} (§2.4.1): one attribute per identifier attribute of the entity, in the same
     * order, read by field when the id class declares a field of that name, else by property. Relationship
     * identifiers (derived identities) come with P5.
     */
    private static ManagedAccess idClassAccess(IdModel.ByIdClass id, ClassLoader loader) {
        if (id.attributes().stream().anyMatch(a -> !(a instanceof BasicAttribute))) {
            return null;
        }
        ClassFileSource source = new ClassFileSource(loader);
        List<AttributeModel> attributes = new ArrayList<>();
        for (AttributeModel part : id.attributes()) {
            Class<?> owner = fieldOwner(id.idClass(), part.name(), source);
            attributes.add(new BasicAttribute(part.name(), part.javaType(), owner != null ? AccessKind.FIELD : AccessKind.PROPERTY,
                owner != null ? owner : id.idClass(), ColumnModel.defaultFor(part.name()), true, FetchType.EAGER, false,
                new ValueConversion.None(), false));
        }
        return Accesses.of(id.idClass(), id.idClass().isRecord(), attributes);
    }

    /** The class of {@code type}'s hierarchy that declares the field {@code name}, or {@code null}. */
    private static Class<?> fieldOwner(Class<?> type, String name, ClassFileSource source) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            if (source.read(current.getName()).flatMap(info -> info.field(name)).filter(f -> !f.isStatic()).isPresent()) {
                return current;
            }
        }
        return null;
    }

    /** The topmost entity class of the hierarchy of {@code entity}. */
    private static Class<?> root(PersistenceUnitModel model, EntityModel entity) {
        EntityModel current = entity;
        while (current.superEntity().isPresent()) {
            Optional<EntityModel> parent = model.entity(current.superEntity().get());
            if (parent.isEmpty()) {
                return current.superEntity().get();
            }
            current = parent.get();
        }
        return current.javaType();
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

        Optional<ManagedAccess> find(Class<?> type, List<AttributeModel> attributes, List<String> callbacks) {
            List<ManagedAccess> candidates = byType.getOrDefault(type, List.of());
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            List<String> descriptor = Accesses.descriptor(attributes);
            Optional<ManagedAccess> fitting = candidates.stream()
                .filter(a -> a.attributes().equals(descriptor) && a.callbacks().equals(callbacks)).findFirst();
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
                        embeddables.put(embeddable, generated.find(embeddable.javaType(), embeddable.attributes(), List.of())
                            .orElseGet(() -> Accesses.of(embeddable)));
                        prepare(embeddable.attributes(), valueBinders, generated, embeddables, binders);
                    }
                }
                case ElementCollectionAttribute elements when elements.element() != null ->
                    prepare(List.of(elements.element()), valueBinders, generated, embeddables, binders);
                default -> {
                    // relationships are bound with their target; pending attributes with their mapping file
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

    /** The entity {@code type} as the persistence context and the flush engine use it. */
    public Optional<MappedEntity> entity(Class<?> type) {
        return Optional.ofNullable(mapped.get(type));
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
