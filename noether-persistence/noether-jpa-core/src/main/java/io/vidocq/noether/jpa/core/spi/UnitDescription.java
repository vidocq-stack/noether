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
package io.vidocq.noether.jpa.core.spi;

import java.util.List;

/**
 * A running persistence unit described for development tools, such as the Persistence panel of the Vidocq dev
 * console: its entities and their relationships, its embeddables, its named queries, and the tables schema generation
 * creates for it, with their keys, indexes and foreign keys.
 * <p>Obtained with {@code entityManagerFactory.unwrap(UnitDescription.class)}, built once per factory. It holds names
 * only, never a class of the application, so a tool may keep it across a reload of the application's classes. Names of
 * tables are qualified by their schema and catalog when the mapping gives them ({@code BANK.ACCOUNTS}).
 *
 * @param unitName the name of the persistence unit
 * @param entities the entities, in the order the unit declares them
 * @param embeddables the embeddable classes the entities use, each once
 * @param namedQueries the named queries, JPQL and native
 * @param tables the tables schema generation creates for the unit, in creation order
 */
public record UnitDescription(String unitName, List<Entity> entities, List<Embeddable> embeddables,
        List<NamedQuery> namedQueries, List<Table> tables) {

    public UnitDescription {
        entities = List.copyOf(entities);
        embeddables = List.copyOf(embeddables);
        namedQueries = List.copyOf(namedQueries);
        tables = List.copyOf(tables);
    }

    /**
     * An entity.
     *
     * @param name its entity name, as queries use it
     * @param className the name of its class
     * @param table its primary table, or {@code null} when it has none (an abstract table-per-class root)
     * @param superEntity the entity name of the entity it extends, or {@code null}
     * @param inheritance the strategy of its hierarchy, {@link Inheritance#NONE} when it is alone in it
     * @param isAbstract whether its class is abstract
     * @param attributes its persistent attributes, inherited ones included
     */
    public record Entity(String name, String className, String table, String superEntity, Inheritance inheritance,
            boolean isAbstract, List<Attribute> attributes) {

        public Entity {
            attributes = List.copyOf(attributes);
        }
    }

    /** The inheritance strategy of an entity's hierarchy (§2.12). */
    public enum Inheritance { NONE, SINGLE_TABLE, JOINED, TABLE_PER_CLASS }

    /**
     * An embeddable class.
     *
     * @param className the name of the class
     * @param attributes its attributes, with the columns its mapping names before any override
     */
    public record Embeddable(String className, List<Attribute> attributes) {

        public Embeddable {
            attributes = List.copyOf(attributes);
        }
    }

    /**
     * A persistent attribute.
     *
     * @param name its name
     * @param javaType the name of its declared type
     * @param declaringClass the name of the class that declares it: a superclass of the entity for an inherited one
     * @param kind what it maps
     * @param columns its own columns, in the entity's tables: one for a basic attribute, the join columns of an owning
     *        single-valued relationship, every column of an embedded attribute; none for the inverse side of a
     *        relationship, a join table or a collection table
     * @param target the entity name a relationship targets, the class name of an embedded attribute, or the element
     *        class of an element collection; {@code null} for a basic attribute
     * @param mappedBy the owning attribute of the target, on the inverse side of a relationship; {@code null} otherwise
     * @param joinTable the join table of a relationship or the collection table of an element collection, or
     *        {@code null}
     */
    public record Attribute(String name, String javaType, String declaringClass, Kind kind, List<String> columns,
            String target, String mappedBy, String joinTable) {

        public Attribute {
            columns = List.copyOf(columns);
        }

        /** What an attribute maps. */
        public enum Kind {
            ID, EMBEDDED_ID, VERSION, BASIC, EMBEDDED, ELEMENT_COLLECTION, ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY,
            MANY_TO_MANY
        }

        /** Whether this side of a relationship owns it, having no {@code mappedBy}. */
        public boolean owning() {
            return mappedBy == null;
        }
    }

    /**
     * A named query.
     *
     * @param name its name
     * @param query its JPQL or SQL text
     * @param nativeQuery whether it is SQL
     */
    public record NamedQuery(String name, String query, boolean nativeQuery) {
    }

    /**
     * A table as schema generation creates it.
     *
     * @param name its name, qualified by its schema and catalog when the mapping gives them
     * @param columns its columns, in creation order
     * @param primaryKey the columns of its primary key
     * @param indexes its indexes and unique constraints
     * @param foreignKeys its foreign keys
     */
    public record Table(String name, List<Column> columns, List<String> primaryKey, List<Index> indexes,
            List<ForeignKey> foreignKeys) {

        public Table {
            columns = List.copyOf(columns);
            primaryKey = List.copyOf(primaryKey);
            indexes = List.copyOf(indexes);
            foreignKeys = List.copyOf(foreignKeys);
        }
    }

    /**
     * A column.
     *
     * @param name its name
     * @param sqlType the name of its {@link java.sql.JDBCType}, such as {@code BIGINT}
     * @param length its length, 0 when it has none
     * @param precision its precision, 0 when it has none
     * @param scale its scale, 0 when it has none
     * @param nullable whether it accepts {@code NULL}
     */
    public record Column(String name, String sqlType, int length, int precision, int scale, boolean nullable) {
    }

    /**
     * An index or a unique constraint.
     *
     * @param name its name, or {@code null} when the database names it (a unique column)
     * @param columns its columns, in order
     * @param unique whether it is unique
     */
    public record Index(String name, List<String> columns, boolean unique) {

        public Index {
            columns = List.copyOf(columns);
        }
    }

    /**
     * A foreign key.
     *
     * @param name its name, or {@code null} when the database names it
     * @param columns its columns, in order
     * @param referencedTable the table it references
     * @param referencedColumns the columns it references, in order; empty for the referenced table's primary key
     */
    public record ForeignKey(String name, List<String> columns, String referencedTable, List<String> referencedColumns) {

        public ForeignKey {
            columns = List.copyOf(columns);
            referencedColumns = List.copyOf(referencedColumns);
        }
    }
}
