package io.vidocq.mansart.jpa.dialect.sql;

import java.sql.JDBCType;
import java.util.List;

/** Schema-generation AST (§9.4); database-specific SQL belongs to the dialect, not the provider core. */
public record SchemaStatement(Operation operation, Table table, List<Column> columns, List<Identifier> primaryKey,
        Identifier objectName, List<String> indexColumns, boolean unique, long initialValue, int increment) implements Statement {
    public enum Operation { CREATE_TABLE, DROP_TABLE, CREATE_SEQUENCE, DROP_SEQUENCE, CREATE_SCHEMA, DROP_SCHEMA, CREATE_INDEX, TRUNCATE }

    public record Column(Identifier name, JDBCType type, int length, int precision, int scale,
            boolean nullable, boolean identity, boolean unique, String definition) {}

    public SchemaStatement {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        indexColumns = List.copyOf(indexColumns);
    }

    public static SchemaStatement table(Operation operation, Table table, List<Column> columns, List<Identifier> keys) {
        return new SchemaStatement(operation, table, columns, keys, null, List.of(), false, 1, 1);
    }

    public static SchemaStatement sequence(Operation operation, Table sequence, long initialValue, int increment) {
        return new SchemaStatement(operation, sequence, List.of(), List.of(), null, List.of(), false, initialValue, increment);
    }
}
