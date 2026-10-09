package io.vidocq.mansart.jpa.dialect.sql;

import java.util.List;

/** Foreign-key lifecycle, including explicitly named constraints and user-supplied SQL definitions (§11.1.19). */
public record ForeignKeyStatement(Table table, Identifier name, List<Identifier> columns, Table referencedTable,
        List<Identifier> referencedColumns, String definition, boolean drop) implements Statement {
    public ForeignKeyStatement {
        columns = List.copyOf(columns);
        referencedColumns = List.copyOf(referencedColumns);
    }
}
