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
package io.vidocq.noether.jpa.core.bootstrap;

import io.vidocq.noether.jpa.core.mapping.CollectionMapping;
import io.vidocq.noether.jpa.core.mapping.ElementCollectionMapping;
import io.vidocq.noether.jpa.core.mapping.EntityStatements;
import io.vidocq.noether.jpa.core.mapping.InheritanceMapping;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.model.AssociationAttribute;
import io.vidocq.noether.jpa.core.model.AttributeModel;
import io.vidocq.noether.jpa.core.model.BasicAttribute;
import io.vidocq.noether.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.noether.jpa.core.model.EmbeddableModel;
import io.vidocq.noether.jpa.core.model.EmbeddedAttribute;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.model.IdModel;
import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import io.vidocq.noether.jpa.dialect.sql.Table;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Builds the {@link UnitDescription} of a mapped unit: names only, read from its model and its statements. */
public final class UnitDescriptions {

    private final MappedUnit mapping;
    private final Map<Class<?>, UnitDescription.Embeddable> embeddables = new LinkedHashMap<>();

    private UnitDescriptions(MappedUnit mapping) {
        this.mapping = mapping;
    }

    /** The description of the unit {@code unitName}, mapped as {@code mapping}. */
    public static UnitDescription of(String unitName, MappedUnit mapping) {
        UnitDescriptions builder = new UnitDescriptions(mapping);
        List<UnitDescription.Entity> entities = mapping.model().entities().stream().map(builder::entity).toList();
        List<UnitDescription.NamedQuery> queries = mapping.model().namedQueries().stream()
            .map(q -> new UnitDescription.NamedQuery(q.name(), q.query(), q.nativeQuery())).toList();
        return new UnitDescription(unitName, entities, List.copyOf(builder.embeddables.values()), queries, List.of());
    }

    private UnitDescription.Entity entity(EntityModel semantic) {
        MappedEntity entity = mapping.entity(semantic.javaType()).orElseThrow();
        EntityModel model = entity.model();
        InheritanceMapping hierarchy = mapping.inheritance(model.javaType());
        boolean isAbstract = hierarchy.abstractEntity(model);
        boolean noTable = hierarchy.tablePerClass() && isAbstract;
        String table = noTable || !entity.statements().supported() ? null
            : name(entity.statements().tables().getFirst().insert().table());
        String superEntity = model.superEntity().flatMap(mapping::entity).map(e -> e.model().entityName()).orElse(null);
        List<Attribute> attributes = new ArrayList<>();
        for (int i = 0; i < model.attributes().size(); i++) {
            attributes.add(attribute(entity, i));
        }
        return new UnitDescription.Entity(model.entityName(), model.javaType().getName(), table, superEntity,
            inheritance(hierarchy), isAbstract, attributes);
    }

    private static Inheritance inheritance(InheritanceMapping hierarchy) {
        if (hierarchy.hierarchy().size() < 2) return Inheritance.NONE;
        if (hierarchy.tablePerClass()) return Inheritance.TABLE_PER_CLASS;
        return hierarchy.joined() ? Inheritance.JOINED : Inheritance.SINGLE_TABLE;
    }

    /** Attribute {@code index} of {@code entity}, with the columns its statements give it. */
    private Attribute attribute(MappedEntity entity, int index) {
        EntityModel model = entity.model();
        AttributeModel attribute = model.attributes().get(index);
        List<String> columns = !entity.statements().supported() ? List.of() : entity.statements().columns().stream()
            .filter(c -> c.attribute() == index).map(c -> c.name().name()).distinct().toList();
        return switch (attribute) {
            case BasicAttribute basic -> new Attribute(basic.name(), basic.javaType().getName(),
                basic.declaringClass().getName(), basicKind(model, basic), columns, null, null, null);
            case EmbeddedAttribute embedded -> {
                embeddable(embedded.embeddable());
                Kind kind = model.id() instanceof IdModel.Embedded(EmbeddedAttribute id) && id == embedded
                    ? Kind.EMBEDDED_ID : Kind.EMBEDDED;
                yield new Attribute(embedded.name(), embedded.javaType().getName(), embedded.declaringClass().getName(),
                    kind, columns, embedded.javaType().getName(), null, null);
            }
            case AssociationAttribute association -> new Attribute(association.name(),
                association.javaType().getName(), association.declaringClass().getName(), kind(association.kind()),
                association.owning() ? columns : List.of(), entityName(association.targetEntity()),
                association.owning() ? null : association.mappedBy(), joinTable(entity, index));
            case ElementCollectionAttribute collection -> {
                if (collection.element() instanceof EmbeddedAttribute element) embeddable(element.embeddable());
                yield new Attribute(collection.name(), collection.javaType().getName(),
                    collection.declaringClass().getName(), Kind.ELEMENT_COLLECTION, List.of(),
                    collection.element().javaType().getName(), null, collectionTable(entity, index));
            }
        };
    }

    private static Kind basicKind(EntityModel model, BasicAttribute basic) {
        if (model.version().filter(v -> v == basic).isPresent()) return Kind.VERSION;
        return switch (model.id()) {
            case IdModel.Single single when single.attribute() == basic -> Kind.ID;
            case IdModel.ByIdClass byIdClass when byIdClass.attributes().contains(basic) -> Kind.ID;
            default -> Kind.BASIC;
        };
    }

    private static Kind kind(AssociationAttribute.Kind kind) {
        return switch (kind) {
            case ONE_TO_ONE -> Kind.ONE_TO_ONE;
            case MANY_TO_ONE -> Kind.MANY_TO_ONE;
            case ONE_TO_MANY -> Kind.ONE_TO_MANY;
            case MANY_TO_MANY -> Kind.MANY_TO_MANY;
        };
    }

    private String entityName(Class<?> type) {
        return mapping.entity(type).map(e -> e.model().entityName()).orElse(type.getName());
    }

    private static String joinTable(MappedEntity entity, int index) {
        if (!entity.statements().supported()) return null;
        return entity.statements().collection(index)
            .map(c -> c instanceof CollectionMapping.JoinTable join ? name(join.table()) : null).orElse(null);
    }

    private static String collectionTable(MappedEntity entity, int index) {
        if (!entity.statements().supported()) return null;
        return entity.statements().elementCollection(index).map(ElementCollectionMapping::table)
            .map(UnitDescriptions::name).orElse(null);
    }

    /** Records {@code embeddable} and the embeddables it nests, each once. */
    private void embeddable(EmbeddableModel embeddable) {
        if (embeddables.containsKey(embeddable.javaType())) return;
        embeddables.put(embeddable.javaType(), null); // reserved before the nested ones, keeping the order of discovery
        List<Attribute> attributes = embeddable.attributes().stream().map(this::embeddableAttribute).toList();
        embeddables.put(embeddable.javaType(), new UnitDescription.Embeddable(embeddable.javaType().getName(), attributes));
    }

    private Attribute embeddableAttribute(AttributeModel attribute) {
        return switch (attribute) {
            case BasicAttribute basic -> new Attribute(basic.name(), basic.javaType().getName(),
                basic.declaringClass().getName(), Kind.BASIC, List.of(basic.column().name()), null, null, null);
            case EmbeddedAttribute embedded -> {
                embeddable(embedded.embeddable());
                yield new Attribute(embedded.name(), embedded.javaType().getName(), embedded.declaringClass().getName(),
                    Kind.EMBEDDED, List.of(), embedded.javaType().getName(), null, null);
            }
            case AssociationAttribute association -> new Attribute(association.name(),
                association.javaType().getName(), association.declaringClass().getName(), kind(association.kind()),
                List.of(), entityName(association.targetEntity()), association.owning() ? null : association.mappedBy(), null);
            case ElementCollectionAttribute collection -> new Attribute(collection.name(),
                collection.javaType().getName(), collection.declaringClass().getName(), Kind.ELEMENT_COLLECTION,
                List.of(), collection.element().javaType().getName(), null, null);
        };
    }

    /** The name of {@code table}, qualified by its schema and catalog when it has them. */
    static String name(Table table) {
        return String.join(".", Stream.of(table.catalog(), table.schema(), table.name()).filter(Objects::nonNull)
            .map(Identifier::name).toList());
    }
}
