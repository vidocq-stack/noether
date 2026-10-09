package io.vidocq.mansart.jpa.core.bootstrap;

import io.vidocq.mansart.jpa.core.mapping.*;
import io.vidocq.mansart.jpa.core.model.*;
import io.vidocq.mansart.jpa.core.model.source.*;
import io.vidocq.mansart.jpa.dialect.sql.*;
import jakarta.persistence.GenerationType;
import java.sql.JDBCType;
import java.util.*;
import static io.vidocq.mansart.jpa.dialect.sql.SchemaStatement.Operation.*;

/** Relational schema projected from the same mapped columns the flush engine uses (§9.4). */
final class SchemaPlan {
    private final MappedUnit unit;
    private final Map<Table, LinkedHashMap<Identifier, SchemaStatement.Column>> tables = new LinkedHashMap<>();
    private final Map<Table, List<Identifier>> keys = new LinkedHashMap<>();
    private final List<SchemaStatement> sequences = new ArrayList<>();
    private final List<SchemaStatement> indexes = new ArrayList<>();
    private final List<ForeignKeyStatement> foreignKeys = new ArrayList<>();
    private final ClassInfos source;

    SchemaPlan(MappedUnit unit) {
        this.unit = unit;
        source = unit.source();
        for (EntityModel semantic : unit.model().entities()) {
            MappedEntity entity = unit.entity(semantic.javaType()).orElseThrow();
            EntityModel model = entity.model();
            EntityStatements statements = entity.statements();
            List<EntityStatements.Column> columns = statements.columns();
            List<EntityStatements.TableStatements> mappedTables = statements.tables();
            for (int t = 0; t < mappedTables.size(); t++) {
                var mapped = mappedTables.get(t);
                Table table = mapped.select().table();
                var target = tables.computeIfAbsent(table, _ -> new LinkedHashMap<>());
                keys.putIfAbsent(table, mapped.select().conditions());
                for (int c : mapped.selected()) {
                    var column = columns.get(c);
                    BasicAttribute basic = column.attribute() < 0 ? null
                        : basic(model.attributes().get(column.attribute()), column.path());
                    if (column.foreignKey() != null) {
                        var referenced = unit.entity(column.foreignKey().target()).orElseThrow();
                        var key = referenced.statements().keyColumns().get(column.foreignKey().part());
                        basic = basic(referenced.model().attributes().get(key.attribute()), key.path());
                    }
                    SchemaStatement.Column definition = definition(column.name(), basic, column.id(),
                        column.name().equals(mapped.insert().generatedKey()));
                    if (column.foreignKey() != null) {
                        AssociationAttribute association = (AssociationAttribute) model.attributes().get(column.attribute());
                        boolean nullable = association.joinColumns().stream()
                            .filter(j -> j.name() == null || identifier(j.name()).equals(column.name()))
                            .allMatch(JoinColumnModel::nullable);
                        definition = new SchemaStatement.Column(definition.name(), definition.type(), definition.length(),
                            definition.precision(), definition.scale(), !column.id() && association.optional() && nullable,
                            definition.identity(), false, null);
                    }
                    if (column.attribute() < 0) {
                        var inheritance = unit.inheritance(model.javaType());
                        Class<?> discriminatorType = inheritance != null && inheritance.value() instanceof Number ? int.class : String.class;
                        definition = new SchemaStatement.Column(column.name(),
                            discriminatorType == int.class ? JDBCType.INTEGER : JDBCType.VARCHAR, 31, 0, 0, true, false, false, null);
                    }
                    target.merge(column.name(), definition, (previous, next) -> previous);
                }
                for (Identifier key : mapped.select().conditions()) {
                    if (!target.containsKey(key)) {
                        var first = statements.keyColumns().getFirst();
                        target.put(key, definition(key, basic(model.attributes().get(first.attribute()), first.path()), true, false));
                    }
                }
            }
            for (var collection : statements.collections()) {
                if (collection instanceof CollectionMapping.JoinTable join) {
                    var columnsByName = tables.computeIfAbsent(join.table(), _ -> new LinkedHashMap<>());
                    addKeys(columnsByName, join.ownerColumns(), entity);
                    addKeys(columnsByName, join.targetColumns(), unit.entity(join.association().targetEntity()).orElseThrow());
                    addIndex(columnsByName, join.index());
                    AnnotationInfo annotation = attributeAnnotation(model, join.association(), "jakarta.persistence.JoinTable");
                    foreignKey(join.table(), join.ownerColumns(), entity, annotation == null ? null : annotation.annotation("foreignKey"));
                    foreignKey(join.table(), join.targetColumns(), unit.entity(join.association().targetEntity()).orElseThrow(),
                        annotation == null ? null : annotation.annotation("inverseForeignKey"));
                    if (annotation != null) constraints(join.table(), annotation);
                } else if (collection instanceof CollectionMapping.MappedBy inverse && inverse.indexUpdate() != null) {
                    addIndex(tables.computeIfAbsent(inverse.indexUpdate().table(), _ -> new LinkedHashMap<>()), inverse.index());
                }
            }
            for (var reference : statements.references()) {
                var association = (AssociationAttribute) model.attributes().get(reference.attribute());
                AnnotationInfo annotation = attributeAnnotation(model, association, "jakarta.persistence.JoinColumn");
                AnnotationInfo override = associationOverride(model, association);
                var names = Arrays.stream(reference.columns()).mapToObj(c -> columns.get(c).name()).toList();
                foreignKey(statements.select().table(), names, unit.entity(reference.target()).orElseThrow(),
                    override != null && override.isWritten("foreignKey") ? override.annotation("foreignKey")
                        : annotation == null ? null : annotation.annotation("foreignKey"));
            }
            for (var collection : statements.elementCollections()) {
                var columnsByName = tables.computeIfAbsent(collection.table(), _ -> new LinkedHashMap<>());
                addKeys(columnsByName, collection.ownerColumns(), entity);
                addIndex(columnsByName, collection.index());
                for (var column : collection.columns()) {
                    columnsByName.put(column.name(), definition(column.name(),
                        basic(collection.model().element(), column.path()), false, false));
                }
            }
            generators(model);
            source.read(model.javaType().getName()).ifPresent(info -> {
                for (AnnotationInfo annotation : info.annotations()) {
                    if (annotation.typeName().equals("jakarta.persistence.Table")) {
                        constraints(table(model.table()), annotation);
                    }
                    if (annotation.typeName().equals("jakarta.persistence.SecondaryTable")) {
                        constraints(annotationTable(annotation), annotation);
                        secondaryKey(entity, annotation);
                    }
                    if (annotation.typeName().equals("jakarta.persistence.SecondaryTables")) {
                        for (Object value : annotation.annotations("value")) {
                            var secondary = (AnnotationInfo) value;
                            constraints(annotationTable(secondary), secondary);
                            secondaryKey(entity, secondary);
                        }
                    }
                }
            });
        }
    }

    private void addKeys(Map<Identifier, SchemaStatement.Column> target, List<Identifier> names, MappedEntity entity) {
        var keys = entity.statements().keyColumns();
        for (int i = 0; i < names.size(); i++) {
            var key = keys.get(i);
            target.putIfAbsent(names.get(i), definition(names.get(i),
                basic(entity.model().attributes().get(key.attribute()), key.path()), true, false));
        }
    }

    private void addIndex(Map<Identifier, SchemaStatement.Column> columns, IndexMapping index) {
        if (index == null) return;
        for (int i = 0; i < index.columns().size(); i++) {
            Identifier name = index.columns().get(i);
            BasicAttribute attribute = switch (index.index()) {
                case CollectionIndex.ByColumn key -> key.key();
                case CollectionIndex.ByPosition position -> position.position();
                case CollectionIndex.ByEmbedded key ->
                    embeddedKeyAttribute(key.key(), index.keyColumns().get(i).path());
                default -> null;
            };
            columns.putIfAbsent(name, definition(name, attribute, false, false));
        }
    }

    private BasicAttribute embeddedKeyAttribute(EmbeddedAttribute key, int[] path) {
        AttributeModel attribute = key;
        StringBuilder name = new StringBuilder();
        for (int step : path) {
            attribute = ((EmbeddedAttribute) attribute).embeddable().attributes().get(step);
            if (!name.isEmpty()) name.append('.');
            name.append(attribute.name());
        }
        BasicAttribute basic = (BasicAttribute) attribute;
        ColumnModel column = key.columns().getOrDefault(name.toString(), basic.column());
        return new BasicAttribute(basic.name(), basic.javaType(), basic.access(), basic.declaringClass(), column,
            basic.optional(), basic.fetch(), basic.lob(), basic.conversion(), basic.version());
    }

    private BasicAttribute basic(AttributeModel attribute, int[] path) {
        for (int step : path) {
            if (attribute instanceof EmbeddedAttribute embedded) attribute = embedded.embeddable().attributes().get(step);
        }
        return attribute instanceof BasicAttribute basic ? basic : null;
    }

    private static SchemaStatement.Column definition(Identifier name, BasicAttribute basic, boolean key, boolean identity) {
        ColumnModel column = basic == null ? ColumnModel.defaultFor(name.name()) : basic.column();
        JDBCType type = basic == null ? JDBCType.INTEGER : sqlType(basic);
        return new SchemaStatement.Column(name, type, column.length(), column.precision(), column.scale(),
            !key && column.nullable() && (basic == null || basic.optional()), identity, column.unique(), column.columnDefinition());
    }

    private static JDBCType sqlType(BasicAttribute attribute) {
        Class<?> type = attribute.javaType();
        switch (attribute.conversion()) {
            case ValueConversion.Converted converted -> type = converted.databaseType();
            case ValueConversion.EnumOrdinal ignored -> { return JDBCType.INTEGER; }
            case ValueConversion.EnumString ignored -> { return JDBCType.VARCHAR; }
            case ValueConversion.EnumByValue value -> type = value.valueType();
            case ValueConversion.Temporal temporal -> {
                return switch (temporal.type()) {
                    case DATE -> JDBCType.DATE;
                    case TIME -> JDBCType.TIME;
                    case TIMESTAMP -> JDBCType.TIMESTAMP;
                };
            }
            default -> {}
        }
        if (attribute.lob()) return type == String.class || type == char[].class || type == Character[].class
            ? JDBCType.CLOB : JDBCType.BLOB;
        if (type == boolean.class || type == Boolean.class) return JDBCType.BOOLEAN;
        if (type == byte.class || type == Byte.class) return JDBCType.TINYINT;
        if (type == short.class || type == Short.class) return JDBCType.SMALLINT;
        if (type == int.class || type == Integer.class || type == java.time.Year.class) return JDBCType.INTEGER;
        if (type == long.class || type == Long.class) return JDBCType.BIGINT;
        if (type == float.class || type == Float.class) return JDBCType.REAL;
        if (type == double.class || type == Double.class) return JDBCType.DOUBLE;
        if (type == java.math.BigDecimal.class || type == java.math.BigInteger.class) return JDBCType.NUMERIC;
        if (type == java.time.LocalDate.class || type == java.sql.Date.class) return JDBCType.DATE;
        if (type == java.time.LocalTime.class || type == java.sql.Time.class) return JDBCType.TIME;
        if (type == java.time.OffsetTime.class) return JDBCType.TIME_WITH_TIMEZONE;
        if (type == java.time.OffsetDateTime.class || type == java.time.Instant.class) return JDBCType.TIMESTAMP_WITH_TIMEZONE;
        if (java.util.Date.class.isAssignableFrom(type) || java.util.Calendar.class.isAssignableFrom(type)
                || type == java.time.LocalDateTime.class) return JDBCType.TIMESTAMP;
        if (type == byte[].class || type == Byte[].class) return JDBCType.VARBINARY;
        return JDBCType.VARCHAR;
    }

    private void generators(EntityModel model) {
        if (!(model.id() instanceof IdModel.Single single) || single.generation().isEmpty()) return;
        GenerationModel generation = single.generation().get();
        if (generation.strategy() == GenerationType.SEQUENCE || generation.strategy() == GenerationType.AUTO
                && generation.sequence().isPresent()) {
            var sequence = generation.sequence().orElseGet(() -> new SequenceGeneratorModel(
                generation.generatorName() != null ? generation.generatorName() : "SEQ_GEN_SEQUENCE", null, null, null, 1, 50));
            Table table = new Table(identifier(sequence.sequenceName() != null ? sequence.sequenceName() : sequence.name()),
                identifier(sequence.schema()), identifier(sequence.catalog()));
            var statement = SchemaStatement.sequence(CREATE_SEQUENCE, table, sequence.initialValue(), sequence.allocationSize());
            if (sequences.stream().noneMatch(s -> s.table().equals(table))) sequences.add(statement);
        } else if (generation.strategy() == GenerationType.TABLE || generation.strategy() == GenerationType.AUTO
                && single.attribute().javaType() != java.util.UUID.class) {
            var generator = generation.table().orElseGet(() -> new TableGeneratorModel(null, null, null, null, null, null, null, 1, 50));
            Table table = new Table(identifier(generator.table() != null ? generator.table() : "SEQUENCE"),
                identifier(generator.schema()), identifier(generator.catalog()));
            var columns = tables.computeIfAbsent(table, _ -> new LinkedHashMap<>());
            Identifier key = identifier(generator.pkColumnName() != null ? generator.pkColumnName() : "SEQ_NAME");
            Identifier value = identifier(generator.valueColumnName() != null ? generator.valueColumnName() : "SEQ_COUNT");
            columns.put(key, new SchemaStatement.Column(key, JDBCType.VARCHAR, 255, 0, 0, false, false, false, null));
            columns.put(value, new SchemaStatement.Column(value, JDBCType.BIGINT, 0, 0, 0, false, false, false, null));
            keys.put(table, List.of(key));
        }
    }

    private void constraints(Table table, AnnotationInfo annotation) {
        for (Object value : annotation.annotations("indexes")) {
            var index = (AnnotationInfo) value;
            String name = index.string("name");
            List<SchemaStatement.IndexColumn> columns = indexColumns(index.string("columnList"));
            indexes.add(new SchemaStatement(CREATE_INDEX, table, List.of(), List.of(),
                identifier(name == null || name.isBlank() ? table.name().name() + "_idx_" + indexes.size() : name),
                columns, index.bool("unique"), 1, 1));
        }
        for (Object value : annotation.annotations("uniqueConstraints")) {
            var constraint = (AnnotationInfo) value;
            String name = constraint.string("name");
            indexes.add(new SchemaStatement(CREATE_INDEX, table, List.of(), List.of(),
                identifier(name == null || name.isBlank() ? table.name().name() + "_uk_" + indexes.size() : name),
                constraint.strings("columnNames").stream().map(c -> new SchemaStatement.IndexColumn(identifier(c), false)).toList(),
                true, 1, 1));
        }
    }

    private List<SchemaStatement.IndexColumn> indexColumns(String list) {
        List<SchemaStatement.IndexColumn> columns = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i <= list.length(); i++) {
            if (i < list.length() && list.charAt(i) == '"') {
                if (quoted && i + 1 < list.length() && list.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (i == list.length() || !quoted && list.charAt(i) == ',') {
                var part = java.util.regex.Pattern.compile("(?is)^(.*?)(?:\\s+(ASC|DESC))?$")
                    .matcher(list.substring(start, i).strip());
                if (!part.matches() || part.group(1).isBlank()) {
                    throw new jakarta.persistence.PersistenceException("Invalid index column list: " + list);
                }
                columns.add(new SchemaStatement.IndexColumn(identifier(part.group(1)), "DESC".equalsIgnoreCase(part.group(2))));
                start = i + 1;
            }
        }
        if (quoted) {
            throw new jakarta.persistence.PersistenceException("Unclosed quoted identifier in index column list: " + list);
        }
        return columns;
    }

    List<Statement> create(boolean schemas) {
        List<Statement> statements = new ArrayList<>();
        if (schemas) {
            Set<Identifier> names = new LinkedHashSet<>();
            tables.keySet().forEach(t -> { if (t.schema() != null) names.add(t.schema()); });
            sequences.forEach(s -> { if (s.table().schema() != null) names.add(s.table().schema()); });
            names.forEach(n -> statements.add(SchemaStatement.table(CREATE_SCHEMA, new Table(n, null, null), List.of(), List.of())));
        }
        statements.addAll(sequences);
        tables.forEach((table, columns) -> statements.add(SchemaStatement.table(CREATE_TABLE, table,
            List.copyOf(columns.values()), keys.getOrDefault(table, List.of()))));
        statements.addAll(indexes);
        statements.addAll(foreignKeys);
        return statements;
    }

    List<Statement> drop(boolean dropSchemas) {
        List<Statement> statements = new ArrayList<>();
        foreignKeys.forEach(k -> statements.add(new ForeignKeyStatement(k.table(), k.name(), k.columns(),
            k.referencedTable(), k.referencedColumns(), k.definition(), true)));
        var reversed = new ArrayList<>(tables.keySet());
        Collections.reverse(reversed);
        reversed.forEach(t -> statements.add(SchemaStatement.table(DROP_TABLE, t, List.of(), List.of())));
        sequences.forEach(s -> statements.add(SchemaStatement.sequence(DROP_SEQUENCE, s.table(), 1, 1)));
        if (dropSchemas) {
            Set<Identifier> schemas = new LinkedHashSet<>();
            tables.keySet().forEach(t -> { if (t.schema() != null) schemas.add(t.schema()); });
            sequences.forEach(s -> { if (s.table().schema() != null) schemas.add(s.table().schema()); });
            schemas.forEach(n -> statements.add(SchemaStatement.table(DROP_SCHEMA, new Table(n, null, null), List.of(), List.of())));
        }
        return statements;
    }

    List<Statement> truncate() {
        List<Table> ordered = new ArrayList<>();
        Set<Table> visited = new HashSet<>();
        for (Table table : tables.keySet()) childrenFirst(table, visited, ordered);
        return ordered.isEmpty() ? List.of() : List.of(new TruncateTables(ordered));
    }

    private void childrenFirst(Table table, Set<Table> visited, List<Table> ordered) {
        if (!visited.add(table)) return;
        foreignKeys.stream().filter(k -> k.referencedTable().equals(table)).forEach(k -> childrenFirst(k.table(), visited, ordered));
        ordered.add(table);
    }

    private AnnotationInfo attributeAnnotation(EntityModel model, AttributeModel attribute, String type) {
        AnnotationInfo override = associationOverride(model, attribute);
        if (override != null) {
            if (type.equals("jakarta.persistence.JoinTable") && override.isWritten("joinTable")) {
                return override.annotation("joinTable");
            }
            if (type.equals("jakarta.persistence.JoinColumn")) {
                return override.annotations("joinColumns").stream().findFirst().orElse(null);
            }
        }
        AttributeModel declared = declaredAttribute(model, attribute.name());
        Annotated member = member(declared == null ? attribute : declared);
        return member == null ? null : member.annotation(type).orElse(null);
    }

    private AnnotationInfo associationOverride(EntityModel model, AttributeModel attribute) {
        if (!(attribute instanceof AssociationAttribute)) return null;
        String owner = model.javaType().getName();
        while (owner != null) {
            var info = source.read(owner).orElse(null);
            if (info == null) break;
            AnnotationInfo found = override(info, attribute.name());
            if (found != null) return found;
            owner = info.superclassName();
        }
        String[] path = attribute.name().split("\\.");
        List<AttributeModel> attributes = unit.model().entity(model.javaType()).orElseThrow().attributes();
        for (int i = 0; i < path.length - 1; i++) {
            String segment = path[i];
            AttributeModel current = attributes.stream().filter(a -> a.name().equals(segment)).findFirst().orElse(null);
            if (!(current instanceof EmbeddedAttribute embedded)) break;
            String rest = String.join(".", Arrays.copyOfRange(path, i + 1, path.length));
            AnnotationInfo found = override(member(current), rest);
            if (found != null) return found;
            var info = source.read(embedded.javaType().getName()).orElse(null);
            found = override(info, rest);
            if (found != null) return found;
            attributes = embedded.embeddable().attributes();
        }
        return null;
    }

    private static AnnotationInfo override(Annotated element, String name) {
        if (element == null) return null;
        List<AnnotationInfo> overrides = new ArrayList<>();
        element.annotation("jakarta.persistence.AssociationOverride").ifPresent(overrides::add);
        element.annotation("jakarta.persistence.AssociationOverrides").ifPresent(a -> overrides.addAll(a.annotations("value")));
        return overrides.stream().filter(a -> a.string("name").equals(name)).findFirst().orElse(null);
    }

    private AttributeModel declaredAttribute(EntityModel model, String name) {
        List<AttributeModel> attributes = unit.model().entity(model.javaType()).orElseThrow().attributes();
        AttributeModel result = null;
        for (String segment : name.split("\\.")) {
            result = attributes.stream().filter(a -> a.name().equals(segment)).findFirst().orElse(null);
            if (result instanceof EmbeddedAttribute embedded) attributes = embedded.embeddable().attributes();
        }
        return result;
    }

    private Annotated member(AttributeModel attribute) {
        var info = source.read(attribute.declaringClass().getName()).orElse(null);
        if (info == null) return null;
        if (attribute.access() == AccessKind.FIELD) return info.field(attribute.name()).orElse(null);
        String capital = Character.toUpperCase(attribute.name().charAt(0)) + attribute.name().substring(1);
        return info.methods().stream().filter(m -> m.name().equals("get" + capital)
            || m.name().equals("is" + capital) || m.name().equals(attribute.name()))
            .filter(m -> m.type().parameterCount() == 0).findFirst().orElse(null);
    }

    private void foreignKey(Table table, List<Identifier> columns, MappedEntity target, AnnotationInfo annotation) {
        if (annotation != null && "NO_CONSTRAINT".equals(annotation.enumConstant("value"))) return;
        String name = annotation == null ? null : annotation.string("name");
        Identifier constraint = identifier(name != null && !name.isBlank() ? name : table.name().name() + "_fk_" + foreignKeys.size());
        var statement = new ForeignKeyStatement(table, constraint, columns, target.statements().select().table(),
            target.statements().keyColumns().stream().map(EntityStatements.Column::name).toList(),
            annotation == null ? null : annotation.string("foreignKeyDefinition"), false);
        if (foreignKeys.stream().noneMatch(k -> k.table().equals(table) && k.columns().equals(columns))) foreignKeys.add(statement);
    }

    private void secondaryKey(MappedEntity entity, AnnotationInfo annotation) {
        Table table = annotationTable(annotation);
        List<Identifier> columns = keys.get(table);
        if (columns != null) foreignKey(table, columns, entity, annotation.annotation("foreignKey"));
    }

    private Table annotationTable(AnnotationInfo annotation) {
        return new Table(identifier(annotation.string("name")), identifier(annotation.string("schema")),
            identifier(annotation.string("catalog")));
    }

    private Table table(TableModel table) {
        return new Table(identifier(table.name()), identifier(table.schema()), identifier(table.catalog()));
    }

    private Identifier identifier(String value) {
        return unit.identifier(value);
    }
}
