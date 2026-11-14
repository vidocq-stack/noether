/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.mapping;

import io.vidocq.noether.jpa.core.jdbc.type.ValueBinder;
import io.vidocq.noether.jpa.core.model.EntityModel;
import io.vidocq.noether.jpa.core.model.PersistenceUnitModel;
import io.vidocq.noether.jpa.core.model.SecondaryTableModel;
import io.vidocq.noether.jpa.core.model.source.AnnotationInfo;
import io.vidocq.noether.jpa.core.model.source.ClassInfos;
import io.vidocq.noether.jpa.core.model.source.ClassInfo;
import io.vidocq.noether.jpa.dialect.Dialect;
import io.vidocq.noether.jpa.dialect.sql.Identifier;
import jakarta.persistence.PersistenceException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Relational inheritance metadata (§2.14), read from class files, never entity annotations by reflection. */
public final class InheritanceMapping {
    private static final String JPA = "jakarta.persistence.";
    private static final String VALUE = "value";
    /** The {@code DiscriminatorType} whose values are numbers; the others are strings. */
    private static final String INTEGER = "INTEGER";
    private final List<EntityModel> chain;
    private final List<EntityModel> hierarchy;
    private final boolean joined;
    private final boolean tablePerClass;
    private final String discriminator;
    private final String discriminatorType;
    private final Object value;
    private final Map<Class<?>, Object> discriminatorValues;
    private final ClassInfos source;

    InheritanceMapping(EntityModel model, PersistenceUnitModel unit, ClassInfos source) {
        this.source = source;
        List<EntityModel> ancestors = new ArrayList<>();
        for (EntityModel current = model; current != null;
                current = current.superEntity().map(t -> unit.entity(t).orElseThrow(() ->
                    new PersistenceException("Entity superclass " + t.getName() + " is not managed"))).orElse(null)) {
            ancestors.add(current);
        }
        Collections.reverse(ancestors);
        chain = List.copyOf(ancestors);
        EntityModel root = chain.getFirst();
        ClassInfo info = info(root);
        String strategy = info.annotation(JPA + "Inheritance").map(a -> a.enumConstant("strategy")).orElse("SINGLE_TABLE");
        tablePerClass = "TABLE_PER_CLASS".equals(strategy);
        if (tablePerClass && root.id() instanceof io.vidocq.noether.jpa.core.model.IdModel.Single single
                && single.generation().map(g -> g.strategy() == jakarta.persistence.GenerationType.IDENTITY).orElse(false)) {
            throw new PersistenceException("TABLE_PER_CLASS cannot use IDENTITY: concrete tables cannot allocate shared hierarchy keys: "
                + root.javaType().getName());
        }
        joined = "JOINED".equals(strategy);
        hierarchy = unit.entities().stream().filter(e -> root.javaType().isAssignableFrom(e.javaType())).toList();
        var column = info.annotation(JPA + "DiscriminatorColumn");
        boolean inherited = hierarchy.size() > 1 || info.isAnnotated(JPA + "Inheritance") || column.isPresent();
        discriminator = !tablePerClass && inherited && (!joined || column.isPresent())
            ? column.map(a -> a.string("name")).orElse("DTYPE") : null;
        discriminatorType = column.map(a -> a.enumConstant("discriminatorType")).orElse("STRING");
        discriminatorValues = values();
        value = discriminatorValue(model);
        if (discriminator != null) {
            var values = new java.util.HashSet<>();
            for (EntityModel entity : hierarchy) {
                if (!abstractEntity(entity) && !values.add(discriminatorValue(entity))) {
                    throw new PersistenceException("Duplicate discriminator value in " + root.entityName());
                }
            }
        }
    }

    private ClassInfo info(EntityModel entity) {
        return source.read(entity.javaType().getName()).orElseThrow();
    }

    public EntityModel root() { return chain.getFirst(); }
    public boolean joined() { return joined; }
    public boolean tablePerClass() { return tablePerClass; }
    public String typeColumn() { return tablePerClass ? "_noether_type" : discriminator; }
    public String discriminator() { return discriminator; }
    public Object value() { return value; }
    public List<EntityModel> hierarchy() { return hierarchy; }
    public List<EntityModel> chain() { return chain; }
    public boolean abstractEntity(EntityModel entity) { return info(entity).isAbstract(); }

    public Object discriminatorValue(EntityModel entity) {
        return discriminatorValues.get(entity.javaType());
    }

    private Map<Class<?>, Object> values() {
        Map<Class<?>, Object> values = new java.util.IdentityHashMap<>();
        Set<Object> used = new java.util.HashSet<>();
        for (EntityModel entity : hierarchy) {
            var written = info(entity).annotation(JPA + "DiscriminatorValue");
            if (discriminator == null || "STRING".equals(discriminatorType)) {
                Object entityValue = discriminator == null ? entity.entityName()
                    : written.map(a -> a.string(VALUE)).orElse(entity.entityName());
                values.put(entity.javaType(), entityValue);
                used.add(entityValue);
            } else if (written.isPresent()) {
                Object entityValue = writtenValue(entity, written.get().string(VALUE));
                values.put(entity.javaType(), entityValue);
                used.add(entityValue);
            }
        }
        addDefaultValues(values, used);
        return Map.copyOf(values);
    }

    /** The value a {@code @DiscriminatorValue} writes for an INTEGER or CHAR discriminator. */
    private Object writtenValue(EntityModel entity, String text) {
        if (INTEGER.equals(discriminatorType)) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                throw new PersistenceException("An INTEGER discriminator requires a numeric @DiscriminatorValue: "
                    + entity.javaType().getName(), e);
            }
        }
        if (text.length() != 1) {
            throw new PersistenceException("A CHAR discriminator requires a one-character @DiscriminatorValue: "
                + entity.javaType().getName());
        }
        return text;
    }

    /**
     * The values of the entities without a {@code @DiscriminatorValue}: §11.1.13 leaves non-STRING defaults
     * provider-defined, so they are numbered in a stable class-name order, avoiding the explicit values ({@code used}).
     */
    private void addDefaultValues(Map<Class<?>, Object> values, Set<Object> used) {
        int next = 0;
        for (EntityModel entity : hierarchy.stream().sorted(java.util.Comparator.comparing(e -> e.javaType().getName())).toList()) {
            if (values.containsKey(entity.javaType())) {
                continue;
            }
            Object candidate;
            do {
                if (INTEGER.equals(discriminatorType)) {
                    candidate = next++;
                } else {
                    int code = characterCode('A' + next);
                    next = code - 'A' + 1;
                    candidate = Character.toString((char) code);
                }
            } while (used.contains(candidate));
            values.put(entity.javaType(), candidate);
            used.add(candidate);
        }
    }

    /** The character of a CHAR discriminator default numbered {@code code}: past the surrogates, within the char range. */
    private int characterCode(int code) {
        if (code > Character.MAX_VALUE) {
            throw new PersistenceException("The CHAR discriminator range is exhausted in " + root().entityName());
        }
        return Character.isSurrogate((char) code) ? 0xE000 : code;
    }

    public Class<?> concrete(Object value) {
        return hierarchy.stream().filter(e -> !abstractEntity(e))
            .filter(e -> Objects.equals(discriminator == null ? e.entityName() : discriminatorValue(e), value))
            .map(EntityModel::javaType).findFirst().orElseThrow(() ->
                new PersistenceException("Unknown discriminator " + value + " in " + root().entityName()));
    }

    /** Tables in insert order: the root, joined subclasses, then secondary tables. */
    public List<SecondaryTableModel> tables() {
        List<SecondaryTableModel> tables = new ArrayList<>();
        for (EntityModel entity : chain) {
            if (tablePerClass ? entity == chain.getLast() : entity == root() || joined) {
                List<String> keys = new ArrayList<>();
                ClassInfo info = info(entity);
                info.annotation(JPA + "PrimaryKeyJoinColumn").ifPresent(a -> keys.add(a.string("name")));
                info.annotation(JPA + "PrimaryKeyJoinColumns").ifPresent(a ->
                    a.annotations(VALUE).forEach(k -> keys.add(k.string("name"))));
                tables.add(new SecondaryTableModel(entity.table(), keys));
            }
        }
        for (EntityModel entity : chain) {
            entity.secondaryTables().forEach(t -> {
                if (tables.stream().noneMatch(existing -> existing.table().equals(t.table()))) {
                    tables.add(t);
                }
            });
        }
        return List.copyOf(tables);
    }

    /** Joined primary keys, in root identifier order, matched to the superclass by referencedColumnName (§11.1.45). */
    public List<Identifier> keyNames(int table, List<Identifier> rootKeys) {
        List<Identifier> keys = rootKeys;
        for (int t = 1; t <= table; t++) {
            List<AnnotationInfo> joins = new ArrayList<>();
            ClassInfo info = info(chain.get(t));
            info.annotation(JPA + "PrimaryKeyJoinColumn").ifPresent(joins::add);
            info.annotation(JPA + "PrimaryKeyJoinColumns").ifPresent(a -> joins.addAll(a.annotations(VALUE)));
            if (!joins.isEmpty()) {
                keys = joinedKeys(chain.get(t), joins, keys);
            }
        }
        return keys;
    }

    /** The primary key of the table of a joined subclass, from its primary key join columns and the key it joins to. */
    private List<Identifier> joinedKeys(EntityModel entity, List<AnnotationInfo> joins, List<Identifier> keys) {
        if (joins.size() != keys.size()) {
            throw new PersistenceException("Primary key join column count differs from the key of " + entity.entityName());
        }
        List<Identifier> next = new ArrayList<>();
        for (int k = 0; k < keys.size(); k++) {
            Identifier referenced = keys.get(k);
            var join = joins.stream().filter(a -> references(a, referenced))
                .findFirst().orElse(joins.get(k).string("referencedColumnName").isBlank() ? joins.get(k) : null);
            if (join == null) {
                throw new PersistenceException("No primary key join column references " + referenced + " in " + entity.entityName());
            }
            next.add(join.string("name").isBlank() ? referenced : Identifier.of(join.string("name"), source.delimitedIdentifiers()));
        }
        return List.copyOf(next);
    }

    /** Whether a primary key join column names {@code referenced} as its referenced column. */
    private boolean references(AnnotationInfo join, Identifier referenced) {
        String name = join.string("referencedColumnName");
        if (name.isBlank()) {
            return false;
        }
        Identifier written = Identifier.of(name, source.delimitedIdentifiers());
        return written.quoted() || referenced.quoted()
            ? written.name().equals(referenced.name()) : written.name().equalsIgnoreCase(referenced.name());
    }

    public List<Identifier> secondaryKeys(SecondaryTableModel secondary, List<Identifier> rootKeys) {
        if (tablePerClass) return rootKeys;
        for (int t = 0; t < chain.size(); t++) {
            if (chain.get(t).secondaryTables().contains(secondary)) {
                return keyNames(t, rootKeys);
            }
        }
        throw new PersistenceException("No entity declares secondary table " + secondary.table().name());
    }

    /** The primary table of the entity declaring an attribute, including intervening mapped superclasses. */
    public int tableOf(Class<?> declaring) {
        if (!joined) {
            return 0;
        }
        return chain.indexOf(declaringEntity(declaring));
    }

    public EntityModel declaringEntity(Class<?> declaring) {
        for (int i = 0; i < chain.size(); i++) {
            if (declaring.isAssignableFrom(chain.get(i).javaType())) {
                return chain.get(i);
            }
        }
        throw new PersistenceException("No inheritance table for " + declaring.getName());
    }

    public ValueBinder binder(boolean entityTypes) {
        return new DiscriminatorBinder(entityTypes);
    }

    /**
     * Binds the discriminator column: an entity class as its discriminator value; and reads it, as the concrete entity
     * class when {@code entityTypes}, else as the value.
     */
    private final class DiscriminatorBinder implements ValueBinder {
        private final boolean entityTypes;

        DiscriminatorBinder(boolean entityTypes) {
            this.entityTypes = entityTypes;
        }

        @Override
        public void bind(Dialect dialect, PreparedStatement statement, int index, Object value) throws SQLException {
            Object stored = value;
            if (value instanceof Class<?> type) {
                EntityModel entity = hierarchy.stream().filter(e -> e.javaType() == type).findFirst().orElseThrow(() ->
                    new IllegalArgumentException(type.getName() + " is not in the hierarchy of " + root().entityName()));
                stored = discriminator == null ? entity.entityName() : discriminatorValue(entity);
            }
            if (INTEGER.equals(discriminatorType) && discriminator != null) {
                if (stored == null) {
                    statement.setNull(index, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(index, ((Number) stored).intValue());
                }
            } else {
                statement.setString(index, stored == null ? null : stored.toString());
            }
        }

        @Override
        public Object read(ResultSet results, int column) throws SQLException {
            Object stored = INTEGER.equals(discriminatorType) && discriminator != null
                ? results.getObject(column, Integer.class) : results.getString(column);
            return stored == null || !entityTypes ? stored : concrete(stored);
        }
    }
}
