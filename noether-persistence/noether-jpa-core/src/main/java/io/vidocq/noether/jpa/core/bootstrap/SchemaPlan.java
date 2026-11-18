package io.vidocq.noether.jpa.core.bootstrap;

import io.vidocq.noether.jpa.core.mapping.*;
import io.vidocq.noether.jpa.core.model.*;
import io.vidocq.noether.jpa.core.model.source.*;
import io.vidocq.noether.jpa.dialect.sql.*;
import jakarta.persistence.GenerationType;
import jakarta.persistence.TemporalType;
import java.sql.JDBCType;
import java.util.*;
import static io.vidocq.noether.jpa.dialect.sql.SchemaStatement.Operation.*;

/** Relational schema projected from the same mapped columns the flush engine uses (§9.4). */
final class SchemaPlan {
    private static final String JOIN_TABLE = "jakarta.persistence.JoinTable";
    private static final String JOIN_COLUMN = "jakarta.persistence.JoinColumn";
    private static final String FOREIGN_KEY = "foreignKey";
    private static final String VALUE = "value";
    private static final java.util.regex.Pattern INDEX_COLUMN = java.util.regex.Pattern.compile("(?is)^(.*?)(?:\\s+(ASC|DESC))?$");
    private static final Map<Class<?>, JDBCType> SQL_TYPES = Map.ofEntries(
        Map.entry(boolean.class, JDBCType.BOOLEAN), Map.entry(Boolean.class, JDBCType.BOOLEAN),
        Map.entry(byte.class, JDBCType.TINYINT), Map.entry(Byte.class, JDBCType.TINYINT),
        Map.entry(short.class, JDBCType.SMALLINT), Map.entry(Short.class, JDBCType.SMALLINT),
        Map.entry(int.class, JDBCType.INTEGER), Map.entry(Integer.class, JDBCType.INTEGER),
        Map.entry(java.time.Year.class, JDBCType.INTEGER),
        Map.entry(long.class, JDBCType.BIGINT), Map.entry(Long.class, JDBCType.BIGINT),
        Map.entry(float.class, JDBCType.REAL), Map.entry(Float.class, JDBCType.REAL),
        Map.entry(double.class, JDBCType.DOUBLE), Map.entry(Double.class, JDBCType.DOUBLE),
        Map.entry(java.math.BigDecimal.class, JDBCType.NUMERIC), Map.entry(java.math.BigInteger.class, JDBCType.NUMERIC),
        Map.entry(java.time.LocalDate.class, JDBCType.DATE), Map.entry(java.sql.Date.class, JDBCType.DATE),
        Map.entry(java.time.LocalTime.class, JDBCType.TIME), Map.entry(java.sql.Time.class, JDBCType.TIME),
        Map.entry(java.time.OffsetTime.class, JDBCType.TIME_WITH_TIMEZONE),
        Map.entry(java.time.OffsetDateTime.class, JDBCType.TIMESTAMP_WITH_TIMEZONE),
        Map.entry(java.time.Instant.class, JDBCType.TIMESTAMP_WITH_TIMEZONE),
        Map.entry(java.time.LocalDateTime.class, JDBCType.TIMESTAMP),
        Map.entry(byte[].class, JDBCType.VARBINARY), Map.entry(Byte[].class, JDBCType.VARBINARY));
    private final MappedUnit unit;
    private final Map<Table, TableColumns> tables = new LinkedHashMap<>();
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
            var hierarchy = unit.inheritance(model.javaType());
            if (!hierarchy.tablePerClass() || !hierarchy.abstractEntity(model)) {
                plan(entity);
            }
        }
    }

    /** The tables, columns, keys, indexes and constraints one entity maps, in the order the schema creates them. */
    private void plan(MappedEntity entity) {
        EntityModel model = entity.model();
        EntityStatements statements = entity.statements();
        for (EntityStatements.TableStatements mapped : statements.tables()) {
            entityTable(entity, mapped);
        }
        for (CollectionMapping collection : statements.collections()) {
            collection(entity, collection);
        }
        for (EntityStatements.Reference reference : statements.references()) {
            reference(entity, reference);
        }
        for (ElementCollectionMapping collection : statements.elementCollections()) {
            elementCollection(entity, collection);
        }
        generators(model);
        source.read(model.javaType().getName()).ifPresent(info -> tableConstraints(entity, info));
    }

    /** The columns of one table of an entity, with the primary key columns a joined subclass table repeats. */
    private void entityTable(MappedEntity entity, EntityStatements.TableStatements mapped) {
        EntityModel model = entity.model();
        EntityStatements statements = entity.statements();
        List<EntityStatements.Column> columns = statements.columns();
        Table table = mapped.select().table();
        var target = columns(table);
        keys.putIfAbsent(table, mapped.select().conditions());
        for (int c : mapped.selected()) {
            var column = columns.get(c);
            target.putIfAbsent(column.name(), columnDefinition(entity, mapped, column));
        }
        for (Identifier key : mapped.select().conditions()) {
            if (!target.contains(key)) {
                var first = statements.keyColumns().getFirst();
                target.put(key, definition(key, basic(model.attributes().get(first.attribute()), first.path()), true, false));
            }
        }
    }

    /** The definition of a column an entity table selects: a basic value, a foreign key, or the discriminator. */
    private SchemaStatement.Column columnDefinition(MappedEntity entity, EntityStatements.TableStatements mapped,
            EntityStatements.Column column) {
        EntityModel model = entity.model();
        BasicAttribute basic = basic(entity, column);
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
        } else if (subclassState(model, model.attributes().get(column.attribute()))) {
            definition = new SchemaStatement.Column(definition.name(), definition.type(), definition.length(),
                definition.precision(), definition.scale(), true, definition.identity(), definition.unique(),
                definition.definition());
        }
        return definition;
    }

    /**
     * Whether an attribute is state a subclass adds to a single-table hierarchy. The rows of the other classes of the
     * hierarchy leave its columns null, so they are nullable whatever the attribute maps (§2.12.1).
     */
    private boolean subclassState(EntityModel model, AttributeModel attribute) {
        var inheritance = unit.inheritance(model.javaType());
        if (inheritance == null || inheritance.joined() || inheritance.tablePerClass()) return false;
        Class<?> root = inheritance.root().javaType();
        return attribute.declaringClass() != root && root.isAssignableFrom(attribute.declaringClass());
    }

    /** The columns of {@code table}, created empty the first time. */
    private TableColumns columns(Table table) {
        return tables.computeIfAbsent(table, _ -> new TableColumns());
    }

    /** The tables, columns and constraints a collection of entities maps. */
    private void collection(MappedEntity entity, CollectionMapping collection) {
        switch (collection) {
            case CollectionMapping.JoinTable join -> joinTable(entity, join);
            case CollectionMapping.ForeignKey foreign -> foreignKeyCollection(entity, foreign);
            case CollectionMapping.MappedBy inverse when inverse.indexUpdate() != null -> {
                var target = unit.entity(inverse.association().targetEntity()).orElseThrow();
                for (Table table : unit.collectionTables(target, inverse.indexUpdate().table())) {
                    addIndex(columns(table), inverse.index());
                }
            }
            case CollectionMapping.MappedBy _, CollectionMapping.Unsupported _ -> {
                // the owning side maps it, or nothing does
            }
        }
    }

    /** A collection mapped by a join table: the table, its keys, its index and its constraints. */
    private void joinTable(MappedEntity entity, CollectionMapping.JoinTable join) {
        var columnsByName = columns(join.table());
        addKeys(columnsByName, join.ownerColumns(), entity);
        addKeys(columnsByName, join.targetColumns(), unit.entity(join.association().targetEntity()).orElseThrow());
        addIndex(columnsByName, join.index());
        AnnotationInfo annotation = attributeAnnotation(entity.model(), join.association(), JOIN_TABLE);
        foreignKey(join.table(), join.ownerColumns(), entity, nested(annotation, FOREIGN_KEY));
        foreignKey(join.table(), join.targetColumns(), unit.entity(join.association().targetEntity()).orElseThrow(),
            nested(annotation, "inverseForeignKey"));
        if (annotation != null) constraints(join.table(), annotation);
        if (join.association().singleValued()) {
            joinNullability(columnsByName, join.ownerColumns(), join.ownerJoins());
            joinNullability(columnsByName, join.targetColumns(), join.targetJoins());
            unique(join.table(), join.ownerColumns(), "_owner");
            if (join.association().kind() == AssociationAttribute.Kind.ONE_TO_ONE) {
                unique(join.table(), join.targetColumns(), "_target");
            }
        }
    }

    /** A one-to-many mapped by a foreign key in the target tables: the key columns, the index and the foreign key. */
    private void foreignKeyCollection(MappedEntity entity, CollectionMapping.ForeignKey foreign) {
        var target = unit.entity(foreign.association().targetEntity()).orElseThrow();
        for (Table table : unit.collectionTables(target, foreign.table())) {
            var columnsByName = columns(table);
            addKeys(columnsByName, foreign.ownerColumns(), entity);
            for (int k = 0; k < foreign.ownerColumns().size(); k++) {
                Identifier name = foreign.ownerColumns().get(k);
                var definition = columnsByName.get(name);
                columnsByName.put(name, new SchemaStatement.Column(name, definition.type(), definition.length(),
                    definition.precision(), definition.scale(), foreign.joins().get(k).nullable(), false, false, null));
            }
            addIndex(columnsByName, foreign.index());
            AnnotationInfo annotation = attributeAnnotation(entity.model(), foreign.association(), JOIN_COLUMN);
            foreignKey(table, foreign.ownerColumns(), entity, nested(annotation, FOREIGN_KEY));
        }
    }

    /** The foreign key of a to-one reference, and the unique constraint of a one-to-one by business key. */
    private void reference(MappedEntity entity, EntityStatements.Reference reference) {
        EntityModel model = entity.model();
        EntityStatements statements = entity.statements();
        List<EntityStatements.Column> columns = statements.columns();
        var association = (AssociationAttribute) model.attributes().get(reference.attribute());
        AnnotationInfo annotation = attributeAnnotation(model, association, JOIN_COLUMN);
        AnnotationInfo override = associationOverride(model, association);
        var names = Arrays.stream(reference.columns()).mapToObj(c -> columns.get(c).name()).toList();
        foreignKey(statements.select().table(), names, unit.entity(reference.target()).orElseThrow(),
            override != null && override.isWritten(FOREIGN_KEY) ? override.annotation(FOREIGN_KEY) : nested(annotation, FOREIGN_KEY),
            statements.referencedColumns(reference).stream().map(EntityStatements.Column::name).toList(),
            statements.referencedTable(reference).select().table());
        if (reference.businessKey() && association.kind() == AssociationAttribute.Kind.ONE_TO_ONE) {
            unique(statements.select().table(), names, "_business_" + reference.attribute());
        }
    }

    /** The collection table of an element collection: its keys, index, columns and foreign keys. */
    private void elementCollection(MappedEntity entity, ElementCollectionMapping collection) {
        var columnsByName = columns(collection.table());
        addKeys(columnsByName, collection.ownerColumns(), entity);
        addIndex(columnsByName, collection.index());
        for (var column : collection.columns()) {
            columnsByName.put(column.name(), elementColumn(collection, column));
        }
        Set<String> elementReferences = new HashSet<>();
        for (var column : collection.columns()) {
            if (column.foreignKey() != null && elementReferences.add(Arrays.toString(column.path()))) {
                elementReference(collection, column, elementReferences.size());
            }
        }
        foreignKey(collection.table(), collection.ownerColumns(), entity, null);
    }

    /** The definition of a column of an element: a basic value, or a column of a reference to an entity. */
    private SchemaStatement.Column elementColumn(ElementCollectionMapping collection, EntityStatements.Column column) {
        if (column.foreignKey() == null) {
            return definition(column.name(), basic(collection.model().element(), column.path()), false, false);
        }
        var association = collection.association(column);
        var target = unit.entity(column.foreignKey().target()).orElseThrow();
        var key = target.statements().referencedColumn(column.foreignKey());
        var definition = definition(column.name(), basic(target, key), false, false);
        boolean nullable = association.optional()
            && (association.joinColumns().isEmpty() || association.joinColumns().getFirst().nullable());
        return new SchemaStatement.Column(column.name(), definition.type(), definition.length(), definition.precision(),
            definition.scale(), nullable, false,
            association.kind() == AssociationAttribute.Kind.ONE_TO_ONE && target.statements().keyColumns().size() == 1, null);
    }

    /**
     * The foreign key of the reference of an element to an entity, from all its columns ({@code column} is the first
     * one), and its unique constraint for a one-to-one; {@code count} numbers the references of the element.
     */
    private void elementReference(ElementCollectionMapping collection, EntityStatements.Column column, int count) {
        var association = collection.association(column);
        var target = unit.entity(column.foreignKey().target()).orElseThrow();
        var reference = collection.columns().stream()
            .filter(candidate -> candidate.foreignKey() != null
                && candidate.foreignKey().target() == column.foreignKey().target()
                && Arrays.equals(candidate.path(), column.path()))
            .sorted(Comparator.comparingInt(candidate -> candidate.foreignKey().part())).toList();
        List<Identifier> names = reference.stream().map(EntityStatements.Column::name).toList();
        foreignKey(collection.table(), names, target, null);
        if (association.kind() == AssociationAttribute.Kind.ONE_TO_ONE && names.size() > 1) {
            unique(collection.table(), names, "_element_" + collection.attribute() + "_" + count);
        }
    }

    /** The unique constraints and indexes of the {@code @Table} and {@code @SecondaryTable}s of an entity class. */
    private void tableConstraints(MappedEntity entity, ClassInfo info) {
        EntityModel model = entity.model();
        for (AnnotationInfo annotation : info.annotations()) {
            if (annotation.typeName().equals("jakarta.persistence.Table")) {
                constraints(table(model.table()), annotation);
            }
            if (annotation.typeName().equals("jakarta.persistence.SecondaryTable")) {
                constraints(annotationTable(annotation), annotation);
                secondaryKey(entity, annotation);
            }
            if (annotation.typeName().equals("jakarta.persistence.SecondaryTables")) {
                for (AnnotationInfo secondary : annotation.annotations(VALUE)) {
                    constraints(annotationTable(secondary), secondary);
                    secondaryKey(entity, secondary);
                }
            }
        }
    }

    private void unique(Table table, List<Identifier> columns, String suffix) {
        indexes.add(new SchemaStatement(CREATE_INDEX, table, List.of(), List.of(),
            identifier(table.name().name() + suffix),
            columns.stream().map(c -> new SchemaStatement.IndexColumn(c, false)).toList(), true, 1, 1));
    }

    private void joinNullability(TableColumns columns, List<Identifier> names,
            List<JoinColumnModel> joins) {
        for (int k = 0; k < names.size(); k++) {
            var column = columns.get(names.get(k));
            columns.put(names.get(k), new SchemaStatement.Column(column.name(), column.type(), column.length(),
                column.precision(), column.scale(), joins.get(k).nullable(), false, column.unique(), column.definition()));
        }
    }

    private void addKeys(TableColumns target, List<Identifier> names, MappedEntity entity) {
        var keyColumns = entity.statements().keyColumns();
        for (int i = 0; i < names.size(); i++) {
            var key = keyColumns.get(i);
            target.putIfAbsent(names.get(i), definition(names.get(i),
                basic(entity, key), true, false));
        }
    }

    private void addIndex(TableColumns columns, IndexMapping index) {
        if (index == null) return;
        for (int i = 0; i < index.columns().size(); i++) {
            Identifier name = index.columns().get(i);
            BasicAttribute attribute = switch (index.index()) {
                case CollectionIndex.ByColumn(var key) -> key;
                case CollectionIndex.ByPosition(var position) -> position;
                case CollectionIndex.ByEmbedded(var key) -> embeddedKeyAttribute(key, index.keyColumns().get(i).path());
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

    private BasicAttribute basic(MappedEntity entity, EntityStatements.Column column) {
        while (column.foreignKey() != null) {
            var foreign = column.foreignKey();
            column = entity.statements().referencedColumn(foreign);
            entity = unit.entity(foreign.target()).orElseThrow();
        }
        return column.attribute() < 0 ? null : basic(entity.model().attributes().get(column.attribute()), column.path());
    }

    private static SchemaStatement.Column definition(Identifier name, BasicAttribute basic, boolean key, boolean identity) {
        ColumnModel column = basic == null ? ColumnModel.defaultFor(name.name()) : basic.column();
        JDBCType type = basic == null ? JDBCType.INTEGER : sqlType(basic);
        return new SchemaStatement.Column(name, type, column.length(), column.precision(), column.scale(),
            !key && column.nullable() && (basic == null || basic.optional()), identity, column.unique(), column.columnDefinition());
    }

    private static JDBCType sqlType(BasicAttribute attribute) {
        return switch (attribute.conversion()) {
            case ValueConversion.Converted converted -> sqlType(converted.databaseType(), attribute.lob());
            case ValueConversion.EnumOrdinal _ -> JDBCType.INTEGER;
            case ValueConversion.EnumString _ -> JDBCType.VARCHAR;
            case ValueConversion.EnumByValue value -> sqlType(value.valueType(), attribute.lob());
            case ValueConversion.Temporal(var temporalType) -> sqlType(temporalType);
            case ValueConversion.None _ -> sqlType(attribute.javaType(), attribute.lob());
        };
    }

    private static JDBCType sqlType(TemporalType temporalType) {
        return switch (temporalType) {
            case DATE -> JDBCType.DATE;
            case TIME -> JDBCType.TIME;
            case TIMESTAMP -> JDBCType.TIMESTAMP;
        };
    }

    /** The SQL type of the values of a Java type: a large object when {@code lob}, else by type, a string by default. */
    private static JDBCType sqlType(Class<?> type, boolean lob) {
        if (lob) {
            return type == String.class || type == char[].class || type == Character[].class ? JDBCType.CLOB : JDBCType.BLOB;
        }
        JDBCType known = SQL_TYPES.get(type);
        if (known != null) {
            return known;
        }
        if (java.util.Date.class.isAssignableFrom(type) || Calendar.class.isAssignableFrom(type)) {
            return JDBCType.TIMESTAMP;
        }
        return JDBCType.VARCHAR;
    }

    private void generators(EntityModel model) {
        if (!(model.id() instanceof IdModel.Single(var attribute, var idGeneration)) || idGeneration.isEmpty()) return;
        GenerationModel generation = idGeneration.orElseThrow();
        GenerationType strategy = generation.strategy();
        if (strategy == GenerationType.SEQUENCE || strategy == GenerationType.AUTO && generation.sequence().isPresent()) {
            sequenceGenerator(generation);
        } else if (strategy == GenerationType.TABLE || strategy == GenerationType.AUTO
                && attribute.javaType() != java.util.UUID.class) {
            tableGenerator(generation);
        }
    }

    /** The sequence of a {@code SEQUENCE} generator, or of an {@code AUTO} one that names a sequence. */
    private void sequenceGenerator(GenerationModel generation) {
        var sequence = generation.sequence().orElseGet(() -> new SequenceGeneratorModel(
            generation.generatorName() != null ? generation.generatorName() : "SEQ_GEN_SEQUENCE", null, null, null, 1, 50));
        Table table = new Table(identifier(sequence.sequenceName() != null ? sequence.sequenceName() : sequence.name()),
            identifier(sequence.schema()), identifier(sequence.catalog()));
        var statement = SchemaStatement.sequence(CREATE_SEQUENCE, table, sequence.initialValue(), sequence.allocationSize());
        if (sequences.stream().noneMatch(s -> s.table().equals(table))) sequences.add(statement);
    }

    /** The generator table of a {@code TABLE} generator, or of an {@code AUTO} one that is not a sequence or a UUID. */
    private void tableGenerator(GenerationModel generation) {
        var generator = generation.table().orElseGet(() -> new TableGeneratorModel(null, null, null, null, null, null, null, 1, 50));
        Table table = new Table(identifier(generator.table() != null ? generator.table() : "SEQUENCE"),
            identifier(generator.schema()), identifier(generator.catalog()));
        var columns = columns(table);
        Identifier key = identifier(generator.pkColumnName() != null ? generator.pkColumnName() : "SEQ_NAME");
        Identifier value = identifier(generator.valueColumnName() != null ? generator.valueColumnName() : "SEQ_COUNT");
        columns.put(key, new SchemaStatement.Column(key, JDBCType.VARCHAR, 255, 0, 0, false, false, false, null));
        columns.put(value, new SchemaStatement.Column(value, JDBCType.BIGINT, 0, 0, 0, false, false, false, null));
        keys.put(table, List.of(key));
    }

    private void constraints(Table table, AnnotationInfo annotation) {
        for (AnnotationInfo index : annotation.annotations("indexes")) {
            String name = index.string("name");
            List<SchemaStatement.IndexColumn> columns = indexColumns(index.string("columnList"));
            indexes.add(new SchemaStatement(CREATE_INDEX, table, List.of(), List.of(),
                identifier(name == null || name.isBlank() ? table.name().name() + "_idx_" + indexes.size() : name),
                columns, index.bool("unique"), 1, 1));
        }
        for (AnnotationInfo constraint : annotation.annotations("uniqueConstraints")) {
            String name = constraint.string("name");
            indexes.add(new SchemaStatement(ADD_UNIQUE_CONSTRAINT, table, List.of(), List.of(),
                identifier(name == null || name.isBlank() ? table.name().name() + "_uk_" + indexes.size() : name),
                constraint.strings("columnNames").stream().map(c -> new SchemaStatement.IndexColumn(identifier(c), false)).toList(),
                true, 1, 1));
        }
    }

    private List<SchemaStatement.IndexColumn> indexColumns(String list) {
        List<SchemaStatement.IndexColumn> columns = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        int i = 0;
        while (i <= list.length()) {
            if (i < list.length() && list.charAt(i) == '"') {
                if (quoted && i + 1 < list.length() && list.charAt(i + 1) == '"') {
                    i++; // a doubled quote inside a quoted identifier
                } else {
                    quoted = !quoted;
                }
            } else if (i == list.length() || !quoted && list.charAt(i) == ',') {
                columns.add(indexColumn(list.substring(start, i), list));
                start = i + 1;
            }
            i++;
        }
        if (quoted) {
            throw new jakarta.persistence.PersistenceException("Unclosed quoted identifier in index column list: " + list);
        }
        return columns;
    }

    /** One column of an index column list: its name, then {@code ASC} or {@code DESC}. */
    private SchemaStatement.IndexColumn indexColumn(String part, String list) {
        var matcher = INDEX_COLUMN.matcher(part.strip());
        if (!matcher.matches() || matcher.group(1).isBlank()) {
            throw new jakarta.persistence.PersistenceException("Invalid index column list: " + list);
        }
        return new SchemaStatement.IndexColumn(identifier(matcher.group(1)), "DESC".equalsIgnoreCase(matcher.group(2)));
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
            columns.values(), keys.getOrDefault(table, List.of()))));
        statements.addAll(indexes);
        statements.addAll(foreignKeys);
        return statements;
    }

    List<Statement> drop(boolean dropSchemas) {
        List<Statement> statements = new ArrayList<>();
        foreignKeys.forEach(k -> statements.add(new ForeignKeyStatement(k.table(), k.name(), k.columns(),
            k.referencedTable(), k.referencedColumns(), k.definition(), true)));
        new ArrayList<>(tables.keySet()).reversed().forEach(t -> statements.add(SchemaStatement.table(DROP_TABLE, t, List.of(), List.of())));
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
            if (type.equals(JOIN_TABLE) && override.isWritten("joinTable")) {
                return override.annotation("joinTable");
            }
            if (type.equals(JOIN_COLUMN)) {
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
        element.annotation("jakarta.persistence.AssociationOverrides").ifPresent(a -> overrides.addAll(a.annotations(VALUE)));
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
        foreignKey(table, columns, target, annotation, target.statements().keyColumns().stream().map(EntityStatements.Column::name).toList());
    }

    private void foreignKey(Table table, List<Identifier> columns, MappedEntity target, AnnotationInfo annotation,
            List<Identifier> referencedColumns) {
        foreignKey(table, columns, target, annotation, referencedColumns, target.statements().select().table());
    }

    private void foreignKey(Table table, List<Identifier> columns, MappedEntity target, AnnotationInfo annotation,
            List<Identifier> referencedColumns, Table referencedTable) {
        var inheritance = unit.inheritance(target.model().javaType());
        // There is no single referenced table for a polymorphic table-per-class key.
        if (inheritance.tablePerClass() && (inheritance.abstractEntity(target.model())
                || inheritance.hierarchy().stream().anyMatch(e -> e.javaType() != target.model().javaType()
                    && target.model().javaType().isAssignableFrom(e.javaType())))) return;
        if (annotation != null && "NO_CONSTRAINT".equals(annotation.enumConstant(VALUE))) return;
        String name = annotation == null ? null : annotation.string("name");
        Identifier constraint = identifier(name != null && !name.isBlank() ? name : table.name().name() + "_fk_" + foreignKeys.size());
        var statement = new ForeignKeyStatement(table, constraint, columns, referencedTable,
            referencedColumns,
            annotation == null ? null : annotation.string("foreignKeyDefinition"), false);
        if (foreignKeys.stream().noneMatch(k -> k.table().equals(table) && k.columns().equals(columns))) foreignKeys.add(statement);
    }

    private void secondaryKey(MappedEntity entity, AnnotationInfo annotation) {
        Table table = annotationTable(annotation);
        List<Identifier> columns = keys.get(table);
        if (columns != null) foreignKey(table, columns, entity, annotation.annotation(FOREIGN_KEY));
    }

    /** The annotation {@code member} of {@code annotation}, or {@code null} without an annotation. */
    private static AnnotationInfo nested(AnnotationInfo annotation, String member) {
        return annotation == null ? null : annotation.annotation(member);
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

    /**
     * The columns of one table, in the order the schema creates them. An undelimited name is not case-sensitive
     * (§2.13): when two entities that map one table name a column {@code ID} and {@code id}, the table has one column.
     */
    private static final class TableColumns {
        private final Map<String, SchemaStatement.Column> byName = new LinkedHashMap<>();

        private static String key(Identifier name) {
            return name.quoted() ? '"' + name.name() : name.name().toUpperCase(Locale.ROOT);
        }

        SchemaStatement.Column get(Identifier name) {
            return byName.get(key(name));
        }

        boolean contains(Identifier name) {
            return byName.containsKey(key(name));
        }

        void put(Identifier name, SchemaStatement.Column column) {
            byName.put(key(name), column);
        }

        void putIfAbsent(Identifier name, SchemaStatement.Column column) {
            byName.putIfAbsent(key(name), column);
        }

        List<SchemaStatement.Column> values() {
            return List.copyOf(byName.values());
        }
    }
}
