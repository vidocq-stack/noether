package io.vidocq.mansart.jpa.core.bootstrap;

import io.vidocq.mansart.jpa.core.flush.Dialects;
import io.vidocq.mansart.jpa.core.jdbc.ConnectionSource;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.spi.JdbcExecution;
import io.vidocq.mansart.jpa.dialect.Dialect;
import io.vidocq.mansart.jpa.dialect.sql.SchemaStatement;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.SchemaManager;
import jakarta.persistence.SchemaValidationException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** §9.4/§7.12 schema lifecycle; owns only resources it opens, never supplied JDBC connections, readers or writers. */
public final class SchemaGeneration implements SchemaManager {
    private static final String PREFIX = "jakarta.persistence.schema-generation.";
    private final UnitSettings settings;
    private final ConnectionSource connections;
    private final MappedUnit mapping;
    private final Runnable checkOpen;
    private volatile List<String> loadCommands;
    private final java.util.concurrent.locks.ReentrantLock loadLock = new java.util.concurrent.locks.ReentrantLock();

    public SchemaGeneration(UnitSettings settings, ConnectionSource connections, MappedUnit mapping, Runnable checkOpen) {
        this.settings = settings;
        this.connections = connections;
        this.mapping = mapping;
        this.checkOpen = checkOpen;
    }

    public void generate() {
        String database = action("database.action");
        String scripts = action("scripts.action");
        if ("none".equals(database) && "none".equals(scripts)) return;
        if (creates(scripts) && settings.property(PREFIX + "scripts.create-target") == null
                || drops(scripts) && settings.property(PREFIX + "scripts.drop-target") == null) {
            throw new PersistenceException("Schema script actions require their scripts.create-target / scripts.drop-target");
        }
        JdbcExecution.call(() -> {
            withConnection(!"none".equals(database) || settings.string("jakarta.persistence.database-product-name") == null, connection -> {
                Dialect dialect = dialect(connection);
                SchemaPlan plan = new SchemaPlan(mapping);
                boolean schemas = Boolean.parseBoolean(settings.string(PREFIX + "create-database-schemas"));
                List<String> create = creates(database) || creates(scripts) ? statements("create", plan.create(schemas), dialect) : List.of();
                List<String> drop = drops(database) || drops(scripts) ? statements("drop", plan.drop(false), dialect) : List.of();
                List<String> load = creates(database) || creates(scripts)
                    ? loadCommands() : List.of();
                if (drops(scripts)) write(settings.property(PREFIX + "scripts.drop-target"), drop);
                if (creates(scripts)) {
                    var commands = new ArrayList<>(create);
                    commands.addAll(load);
                    write(settings.property(PREFIX + "scripts.create-target"), commands);
                }
                if (drops(database)) execute(connection, drop);
                if (creates(database)) {
                    execute(connection, create);
                    execute(connection, load);
                }
            });
            return null;
        });
    }

    private String action(String name) {
        String value = settings.string(PREFIX + name);
        String action = value == null ? "none" : value.strip();
        if (!Set.of("none", "create", "drop", "drop-and-create").contains(action)) {
            throw new PersistenceException("Invalid schema generation action " + name + ": " + action);
        }
        return action;
    }

    private static boolean creates(String action) { return action.equals("create") || action.equals("drop-and-create"); }
    private static boolean drops(String action) { return action.equals("drop") || action.equals("drop-and-create"); }

    private List<String> statements(String kind, List<io.vidocq.mansart.jpa.dialect.sql.Statement> metadata, Dialect dialect) {
        Object script = settings.property(PREFIX + kind + "-script-source");
        String source = settings.string(PREFIX + kind + "-source");
        if (source == null) source = script == null ? "metadata" : "script";
        if (!Set.of("metadata", "script", "metadata-then-script", "script-then-metadata").contains(source)) {
            throw new PersistenceException("Invalid schema source " + source);
        }
        if (!source.equals("metadata") && script == null) {
            throw new PersistenceException(kind + "-source requires a " + kind + "-script-source");
        }
        List<String> generated = metadata.stream().map(dialect::render).toList();
        if (source.equals("metadata")) return generated;
        List<String> supplied = script(script);
        if (source.equals("script")) return supplied;
        List<String> combined = new ArrayList<>();
        combined.addAll(source.equals("script-then-metadata") ? supplied : generated);
        combined.addAll(source.equals("script-then-metadata") ? generated : supplied);
        return combined;
    }

    private Dialect dialect(Connection connection) {
        return connection != null ? Dialects.resolve(connection, settings.string(Dialects.PROPERTY), mapping.loader())
            : Dialects.resolve(settings.string("jakarta.persistence.database-product-name"),
                integer("jakarta.persistence.database-major-version"), integer("jakarta.persistence.database-minor-version"),
                settings.string(Dialects.PROPERTY), mapping.loader());
    }

    private int integer(String name) {
        String value = settings.string(name);
        return value == null ? 0 : Integer.parseInt(value);
    }

    @FunctionalInterface
    private interface Work { void run(Connection connection) throws SQLException; }

    private void withConnection(boolean needed, Work work) {
        Object supplied = settings.property(PREFIX + "connection");
        if (supplied != null && !(supplied instanceof Connection)) {
            throw new PersistenceException(PREFIX + "connection must be a JDBC Connection");
        }
        try {
            if (supplied instanceof Connection connection) {
                work.run(connection);
            } else if (needed) {
                try (Connection connection = connections.acquire()) { work.run(connection); }
            } else {
                work.run(null);
            }
        } catch (SQLException failure) {
            throw new PersistenceException("Schema generation failed: " + failure.getMessage(), failure);
        }
    }

    private static void execute(Connection connection, List<String> statements) throws SQLException {
        if (statements.isEmpty()) return;
        try (java.sql.Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                try { statement.execute(sql); }
                catch (SQLException failure) {
                    // A named sequence can be provisioned by the container before a unit's create action.
                    if (!sql.startsWith("CREATE SEQUENCE ") || !"42P07".equals(failure.getSQLState())) throw failure;
                }
            }
        }
    }

    private List<String> script(Object source) {
        if (source == null) return List.of();
        try {
            String contents;
            if (source instanceof Reader reader) {
                contents = read(reader);
            } else {
                URL url = url(source);
                try (Reader reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) { contents = read(reader); }
            }
            return split(contents);
        } catch (IOException | IllegalArgumentException failure) {
            throw new PersistenceException("Cannot read schema SQL source " + source, failure);
        }
    }

    private URL url(Object source) throws IOException {
        if (source instanceof URL url) return url;
        if (source instanceof URI uri) return uri.toURL();
        String value = source.toString();
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute()) return uri.toURL();
        } catch (IllegalArgumentException ignored) {}
        URL resource = mapping.loader().getResource(value);
        return resource != null ? resource : Path.of(value).toUri().toURL();
    }

    private static String read(Reader reader) throws IOException {
        StringWriter contents = new StringWriter();
        reader.transferTo(contents);
        return contents.toString();
    }

    /** Semicolons inside quoted literals/identifiers and comments are not statement boundaries. */
    static List<String> split(String contents) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < contents.length(); i++) {
            char c = contents.charAt(i);
            char next = i + 1 < contents.length() ? contents.charAt(i + 1) : 0;
            if (lineComment) {
                if (c == '\n' || c == '\r') { lineComment = false; current.append(' '); }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++; current.append(' '); }
                continue;
            }
            if (quote == 0 && c == '-' && next == '-') { lineComment = true; i++; continue; }
            if (quote == 0 && c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c;
                else if (quote == c) {
                    if (next == c) { current.append(c).append(next); i++; continue; }
                    quote = 0;
                }
            }
            if (c == ';' && quote == 0) {
                if (!current.toString().isBlank()) statements.add(current.toString().strip());
                current.setLength(0);
            } else current.append(c);
        }
        if (quote != 0 || blockComment) throw new PersistenceException("Unterminated SQL literal or block comment");
        if (!current.toString().isBlank()) statements.add(current.toString().strip());
        return statements;
    }

    private void write(Object target, List<String> statements) {
        try {
            if (target instanceof Writer writer) {
                write(writer, statements);
            } else {
                URL url = url(target);
                if (!url.getProtocol().equals("file")) throw new PersistenceException("Schema output requires a writable file URL: " + url);
                try (Writer writer = Files.newBufferedWriter(Path.of(url.toURI()), StandardCharsets.UTF_8)) { write(writer, statements); }
            }
        } catch (IOException | URISyntaxException failure) {
            throw new PersistenceException("Cannot write schema script target " + target, failure);
        }
    }

    private static void write(Writer writer, List<String> statements) throws IOException {
        for (String sql : statements) writer.write(sql + ";\n");
        writer.flush();
        if (writer instanceof PrintWriter printer && printer.checkError()) throw new IOException("Script PrintWriter failed");
    }

    @Override public void create(boolean createSchemas) {
        checkOpen.run();
        operate(plan -> plan.create(createSchemas));
    }

    @Override public void drop(boolean dropSchemas) {
        checkOpen.run();
        operate(plan -> plan.drop(dropSchemas));
    }

    @Override public void truncate() {
        checkOpen.run();
        JdbcExecution.call(() -> {
            withConnection(true, connection -> {
                execute(connection, new SchemaPlan(mapping).truncate().stream().map(dialect(connection)::render).toList());
                execute(connection, loadCommands());
            });
            return null;
        });
    }

    private List<String> loadCommands() {
        List<String> commands = loadCommands;
        if (commands != null) return commands;
        loadLock.lock();
        try {
            if (loadCommands == null) loadCommands = script(settings.property("jakarta.persistence.sql-load-script-source"));
            return loadCommands;
        } finally { loadLock.unlock(); }
    }

    private void operate(java.util.function.Function<SchemaPlan, List<io.vidocq.mansart.jpa.dialect.sql.Statement>> action) {
        JdbcExecution.call(() -> {
            withConnection(true, connection -> execute(connection, action.apply(new SchemaPlan(mapping)).stream()
                .map(dialect(connection)::render).toList()));
            return null;
        });
    }

    @Override public void validate() throws SchemaValidationException {
        checkOpen.run();
        List<Exception> failures = new ArrayList<>();
        JdbcExecution.call(() -> {
            withConnection(true, connection -> {
                DatabaseMetaData metadata = connection.getMetaData();
                for (var statement : new SchemaPlan(mapping).create(false)) {
                    if (!(statement instanceof SchemaStatement table) || table.operation() != SchemaStatement.Operation.CREATE_TABLE) continue;
                    Set<String> found = new HashSet<>();
                    String name = table.table().name().quoted() ? table.table().name().name()
                        : metadata.storesUpperCaseIdentifiers() ? table.table().name().name().toUpperCase(Locale.ROOT)
                        : table.table().name().name().toLowerCase(Locale.ROOT);
                    String schema = table.table().schema() == null ? connection.getSchema() : table.table().schema().name();
                    try (ResultSet columns = metadata.getColumns(connection.getCatalog(), schema, name, null)) {
                        while (columns.next()) found.add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                    }
                    for (var column : table.columns()) {
                        if (!found.contains(column.name().name().toLowerCase(Locale.ROOT))) {
                            failures.add(new Exception("Missing table/column " + table.table().name().name() + "." + column.name().name()));
                        }
                    }
                }
            });
            return null;
        });
        if (!failures.isEmpty()) throw new SchemaValidationException("Schema does not match the persistence unit",
            failures.toArray(Exception[]::new));
    }
}
