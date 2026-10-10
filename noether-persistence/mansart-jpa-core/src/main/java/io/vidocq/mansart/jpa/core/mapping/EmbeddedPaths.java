/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.mapping;

import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Lowers embedded relationships to executable state slots. The semantic model stays hierarchical; the existing
 * relationship planners consume these dotted slots through compositions of the same generated accesses (§2.6).
 */
final class EmbeddedPaths {
    record Plan(EntityModel model, ManagedAccess access) {
    }

    private record Path(int root, List<Integer> indexes, List<ManagedAccess> accesses) {
        Object get(ManagedAccess entity, Object instance) {
            Object value = entity.get(instance, root);
            for (int i = 0; i < indexes.size() && value != null; i++) {
                value = accesses.get(i).get(value, indexes.get(i));
            }
            return value;
        }

        void set(ManagedAccess entity, Object instance, Object value) {
            Object previous = entity.get(instance, root);
            if (previous == null && value == null) return;
            Object updated = set(previous, value, 0);
            if (updated != previous) entity.set(instance, root, updated);
        }

        private Object set(Object instance, Object value, int depth) {
            ManagedAccess access = accesses.get(depth);
            int attribute = indexes.get(depth);
            if (instance == null && value == null) return null;
            if (instance == null && !access.type().isRecord()) instance = access.instantiate();
            Object nested = depth + 1 == indexes.size() ? value
                : set(instance == null ? null : access.get(instance, attribute), value, depth + 1);
            if (access.type().isRecord()) {
                Object[] components = new Object[access.attributes().size()];
                if (instance != null) access.read(instance, components);
                components[attribute] = nested;
                return access.construct(components);
            }
            access.set(instance, attribute, nested);
            return instance;
        }
    }

    static Plan of(EntityModel model, ManagedAccess access, Function<EmbeddableModel, ManagedAccess> embeddables) {
        List<AttributeModel> attributes = new ArrayList<>(model.attributes());
        List<Path> paths = new ArrayList<>();
        for (int i = 0; i < model.attributes().size(); i++) {
            if (model.attributes().get(i) instanceof EmbeddedAttribute embedded) {
                flatten(embedded.embeddable(), embedded.name() + ".", embedded.declaringClass(), i, List.of(),
                    List.of(), embeddables, attributes, paths);
            }
        }
        if (paths.isEmpty()) return new Plan(model, access);
        EntityModel executable = new EntityModel(model.javaType(), model.entityName(), model.table(), model.access(), model.id(),
            attributes, model.version(), model.superEntity(), model.secondaryTables(), model.callbacks(), model.cacheable());
        int size = model.attributes().size();
        ManagedAccess composed = new ManagedAccess(model.javaType(), attributes.stream()
                .map(a -> a.name() + ":" + a.access()).toList(), access.callbacks()) {
            @Override
            public Object instantiate() { return access.instantiate(); }

            @Override
            public void callback(Object instance, int callback) { access.callback(instance, callback); }

            @Override
            public Object get(Object instance, int attribute) {
                return attribute < size ? access.get(instance, attribute) : paths.get(attribute - size).get(access, instance);
            }

            @Override
            public void set(Object instance, int attribute, Object value) {
                if (attribute < size) access.set(instance, attribute, value);
                else paths.get(attribute - size).set(access, instance, value);
            }
        };
        return new Plan(executable, composed);
    }

    private static void flatten(EmbeddableModel model, String prefix, Class<?> declaring, int root,
            List<Integer> indexes, List<ManagedAccess> accesses, Function<EmbeddableModel, ManagedAccess> embeddables,
            List<AttributeModel> attributes, List<Path> paths) {
        ManagedAccess access = embeddables.apply(model);
        for (int i = 0; i < model.attributes().size(); i++) {
            AttributeModel attribute = model.attributes().get(i);
            List<Integer> nested = new ArrayList<>(indexes);
            nested.add(i);
            List<ManagedAccess> along = new ArrayList<>(accesses);
            along.add(access);
            if (attribute instanceof EmbeddedAttribute embedded) {
                flatten(embedded.embeddable(), prefix + embedded.name() + ".", declaring, root, nested, along,
                    embeddables, attributes, paths);
            } else if (attribute instanceof AssociationAttribute a) {
                attributes.add(new AssociationAttribute(prefix + a.name(), a.javaType(), a.access(), declaring,
                    a.kind(), a.targetEntity(), a.mappedBy(), a.cascade(), a.orphanRemoval(), a.fetch(), a.optional(),
                    a.joinColumns(), a.joinTable(), a.orderBy(), a.index(), a.mapsId()));
                paths.add(new Path(root, List.copyOf(nested), List.copyOf(along)));
            } else if (attribute instanceof ElementCollectionAttribute e) {
                attributes.add(new ElementCollectionAttribute(prefix + e.name(), e.javaType(), e.access(), declaring,
                    e.genericSignature(), e.element(), e.table(), e.orderBy(), e.index(), e.fetch()));
                paths.add(new Path(root, List.copyOf(nested), List.copyOf(along)));
            }
        }
    }
}
