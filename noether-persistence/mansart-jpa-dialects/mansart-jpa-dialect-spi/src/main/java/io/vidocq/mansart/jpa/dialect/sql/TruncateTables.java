package io.vidocq.mansart.jpa.dialect.sql;

import java.util.List;

/** Tables ordered child-before-parent for dialects implementing truncation as DELETE. */
public record TruncateTables(List<Table> tables) implements Statement {
    public TruncateTables { tables = List.copyOf(tables); }
    @Override public Table table() { return tables.isEmpty() ? null : tables.getFirst(); }
}
