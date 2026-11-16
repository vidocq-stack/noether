# Unit description for tools: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a development tool reads a running persistence unit's mapping, its tables and its DDL through
`EntityManagerFactory.unwrap`, without any access to Noether's internal packages.

**Architecture:** two new types in the exported package `io.vidocq.noether.jpa.core.spi`.
- `UnitDescription` is an immutable record tree that holds names only. `UnitDescriptions` builds it in package
  `bootstrap` from `MappedUnit` and from `SchemaPlan.create(false)`, the statements schema generation would issue.
- `SchemaScripts` is implemented by `SchemaGeneration`. It renders those statements with the unit's dialect and
  never executes them.
- `EntityManagerFactoryImpl.unwrap` hands out both.

**Tech Stack:** Java 25, Java Modules, JUnit 5 + AssertJ, H2 in memory (as in `SchemaGenerationTest`).

**Spec:** `docs/superpowers/specs/2026-11-16-persistence-dev-panel-design.md`, part 1.

## Global Constraints

- No new dependency. No reflection, and no application `Class` in the description: strings only, since a dev reload
  must not keep application classes alive.
- `module-info.java` is unchanged: `io.vidocq.noether.jpa.core.spi` is already exported.
- `noether-architecture-tests` stays green: the `spi` package depends only on the JDK.
- Tests are written before the code, and every new test name is a sentence.
- English everywhere. The doc is the Antora page `docs/en/modules/ROOT/pages/reference.adoc`.
- Commits use `git commit -S -s` and end with the `Co-Authored-By` / `Claude-Session` trailers.

## Review Focus

- **A composite identifier** (`@EmbeddedId`, `@IdClass`). It is one `ID` or `EMBEDDED_ID` attribute whose
  `columns` are all the key columns. Test: `aCompositeIdentifierListsEveryKeyColumn` (Task 1).
- **An abstract table-per-class root** has no table: its `table` is `null` and it is absent from `tables()`. Test:
  `anAbstractTablePerClassRootHasNoTable` (Task 2).
- **A schema-qualified table** is named `SCHEMA.NAME`, in entities and tables alike. Test:
  `aSchemaQualifiedTableKeepsItsSchema` (Task 2).
- **A unit whose create source is a script.** `create()` returns what schema generation would run, so the script
  too. Test: `theCreateScriptFollowsTheConfiguredSource` (Task 3).
- **A closed factory** refuses `unwrap`, as today: `IllegalStateException`. Test:
  `aClosedFactoryDescribesNothing` (Task 1).

---

### Task 1: the description of entities, embeddables and named queries

**Files:**
- Create: `noether-persistence/noether-jpa-core/src/main/java/io/vidocq/noether/jpa/core/spi/UnitDescription.java`
- Create: `noether-persistence/noether-jpa-core/src/main/java/io/vidocq/noether/jpa/core/bootstrap/UnitDescriptions.java`
- Modify: `noether-persistence/noether-jpa-core/src/main/java/io/vidocq/noether/jpa/core/session/EntityManagerFactoryImpl.java` (`unwrap`, around line 374)
- Test: `noether-persistence/noether-jpa-core/src/test/java/io/vidocq/noether/jpa/core/UnitDescriptionTest.java`

**Interfaces:**
- Produces:
  ```java
  public record UnitDescription(String unitName, List<Entity> entities, List<Embeddable> embeddables,
          List<NamedQuery> namedQueries, List<Table> tables) {            // lists copied, in model order
      public record Entity(String name, String className, String table, String superEntity,
              Inheritance inheritance, boolean isAbstract, List<Attribute> attributes) {}
      public enum Inheritance { NONE, SINGLE_TABLE, JOINED, TABLE_PER_CLASS }
      public record Embeddable(String className, List<Attribute> attributes) {}
      public record Attribute(String name, String javaType, String declaringClass, Kind kind,
              List<String> columns, String target, String mappedBy, String joinTable) {
          public enum Kind { ID, EMBEDDED_ID, VERSION, BASIC, EMBEDDED, ELEMENT_COLLECTION,
                             ONE_TO_ONE, MANY_TO_ONE, ONE_TO_MANY, MANY_TO_MANY }
          public boolean owning() { return mappedBy == null; }
      }
      public record NamedQuery(String name, String query, boolean nativeQuery) {}
      public record Table(String name, List<Column> columns, List<String> primaryKey,
              List<Index> indexes, List<ForeignKey> foreignKeys) {}
      public record Column(String name, String sqlType, int length, int precision, int scale, boolean nullable) {}
      public record Index(String name, List<String> columns, boolean unique) {}   // name null: the database names it
      public record ForeignKey(String name, List<String> columns, String referencedTable,
              List<String> referencedColumns) {}
  }
  ```
  - `table` and `superEntity` are null when absent; `superEntity` is an entity name.
  - `inheritance` is `NONE` for an entity alone in its hierarchy.
  - `target` is the entity name of a relationship, the class name of an `EMBEDDED` or `EMBEDDED_ID` attribute, the
    element class of an `ELEMENT_COLLECTION`, and null otherwise.
  - `columns` holds the attribute's own columns: one for a basic attribute, the join columns of an owning to-one,
    every flattened column of an embedded attribute, and none for an inverse side, a join table or a collection
    table.
  - `joinTable` is the join table or the collection table, or null.
  - `UnitDescriptions.of(String unitName, MappedUnit mapping)` returns `UnitDescription`. In this task `tables` is
    `List.of()`.
  - `emf.unwrap(UnitDescription.class)` builds it once per factory and returns the same instance afterwards.

- [ ] **Step 1: Write the failing tests** in `UnitDescriptionTest`.
  Each test opens a `PersistenceConfiguration` on `jdbc:h2:mem:describe-<n>` with the listed managed classes, the
  same way `SchemaGenerationTest` does. Column names are compared upper-cased.
  - `relationshipsNameTheirTargetAndTheirOwningSide`, with `rel.Team`, `rel.Player` and `rel.Skill`:
    - `Player.team` is `MANY_TO_ONE`, `target "Team"`, `owning()`, `columns ["TEAM_ID"]`.
    - `Team.players` is `ONE_TO_MANY`, `mappedBy "team"`, `columns []`.
    - `Player.skills` is `MANY_TO_MANY`, `owning()`, `joinTable` equal ignoring case to `"Player_Skill"`.
    - `Skill.players` has `mappedBy "skills"`.
    - `Team.id` is `ID`, `columns ["ID"]`.
    - `Team.version` is `VERSION`.
  - `inheritanceIsDescribedOnEveryEntityOfItsHierarchy`, with nested `@Entity Parent` and `@Entity Child extends
    Parent` plus `rel.Skill`:
    - `Child.superEntity` is `"Parent"`, and both entities have `inheritance` `SINGLE_TABLE`.
    - `Skill.inheritance` is `NONE`.
  - `embeddablesAreListedOnceWithTheirColumns`, with `Customer` (its `Addr`), counted once even if two attributes
    embed it:
    - the `embeddables` class names contain `Addr`'s;
    - the embedding attribute is `EMBEDDED`, `target` is `Addr`'s class name, and its `columns` are the flattened
      address columns.
  - `aCompositeIdentifierListsEveryKeyColumn`, with `Ticket` (`@EmbeddedId TicketKey`): its id attribute is
    `EMBEDDED_ID`, and its `columns` are the `TicketKey` columns.
  - `anElementCollectionNamesItsCollectionTable`, with `coll.Suspect`: the `@ElementCollection` attribute is
    `ELEMENT_COLLECTION`, `columns []`, `joinTable` not null.
  - `namedQueriesKeepTheirTextAndKind`, with `rel.Dept` and `rel.Emp`:
    - the names contain `Dept.byName`, `Emp.count`, `Emp.forUpdate` and `Emp.rename`;
    - `Emp.rename` is `nativeQuery`, and `Emp.count`'s query is `"SELECT COUNT(e) FROM Emp e"`.
  - `theDescriptionIsBuiltOncePerFactory`: two `unwrap` calls return the same instance (`isSameAs`), and
    `unitName` equals the configuration's name.
  - `aClosedFactoryDescribesNothing`: `unwrap(UnitDescription.class)` after `close()` throws
    `IllegalStateException`.
- [ ] **Step 2: Run, expect a compilation failure** (`UnitDescription` missing):
  `./mvnw -ntp -pl noether-persistence/noether-jpa-core -am test -Dtest=UnitDescriptionTest -Dsurefire.failIfNoSpecifiedTests=false`
  (through `vidocq-build.sh` on the scratch repository).
- [ ] **Step 3: Implement.**
  - Add the records, then `UnitDescriptions.of`, which walks `mapping.model().entities()`.
  - Columns come from `MappedEntity.statements().columns()`, filtered on `Column.attribute()`, and from
    `references()` for the join columns.
  - Join and collection tables come from `collections()` and `elementCollections()`.
  - Inheritance comes from `mapping.inheritance(type)`.
  - In `EntityManagerFactoryImpl`, add a lazy `UnitDescription` field set under `unwrap`, after `checkOpen()`, then
    the `UnitDescription.class` branch.
- [ ] **Step 4: Run `UnitDescriptionTest`, expect PASS.** Then run the `noether-jpa-core` tests: all pass.
- [ ] **Step 5: Commit** `feat(core): describe a running unit's entities for tools (UnitDescription)`.

### Task 2: the tables, keys, indexes and foreign keys

**Files:**
- Modify: `UnitDescriptions.java`, `SchemaPlan.java` (package-private access only, no behaviour change)
- Test: `UnitDescriptionTest.java`

**Interfaces:**
- Consumes: `SchemaPlan.create(false)` (`List<Statement>`, package `bootstrap`). It covers:
  - `SchemaStatement` `CREATE_TABLE`, which gives the columns and the primary key;
  - `CREATE_INDEX` and `ADD_UNIQUE_CONSTRAINT`, which give the indexes;
  - `ForeignKeyStatement`.
- Produces:
  - `UnitDescription.tables()`, in creation order.
  - A column-level `unique` becomes an `Index(null, [column], true)`.
  - `Column.sqlType` is the `JDBCType` name, such as `"BIGINT"`.
  - A table name is `schema.name` when the table has a schema.

- [ ] **Step 1: Write the failing tests.** They use a nested entity `@Entity @Table(name = "SHELF", indexes =
  @Index(name = "SHELF_LABEL", columnList = "label"), uniqueConstraints = @UniqueConstraint(name = "SHELF_CODE",
  columnNames = "code")) Shelf { @Id long id; String label; String code; @Column(unique = true) String serial;
  @ManyToOne rel.Team team; }`, plus `rel.Team`.
  - `tablesCarryTheirKeysIndexesAndForeignKeys`:
    - table `SHELF` has `primaryKey ["ID"]`;
    - its indexes contain `("SHELF_LABEL", ["LABEL"], false)`, `("SHELF_CODE", ["CODE"], true)` and an unnamed
      unique index on `["SERIAL"]`;
    - its foreign keys contain one with `columns ["TEAM_ID"]`, `referencedTable "TEAM"` and
      `referencedColumns ["ID"]`;
    - `Shelf`'s entity `table` is `"SHELF"`.
  - `aSchemaQualifiedTableKeepsItsSchema`, with `Account` (schema `BANK`): both its entity `table` and a `tables()`
    entry are `"BANK.ACCOUNTS"`.
  - `anAbstractTablePerClassRootHasNoTable`, with nested `@Entity @Inheritance(strategy = TABLE_PER_CLASS) abstract
    class Vehicle { @Id long id; }` and `@Entity class Bike extends Vehicle`:
    - `Vehicle.table` is null and `Vehicle.isAbstract()` is true;
    - `tables()` has `BIKE` and no `VEHICLE`;
    - both entities have `inheritance` `TABLE_PER_CLASS`.
- [ ] **Step 2: Run them, expect FAIL** (`tables()` empty).
- [ ] **Step 3: Implement** the conversion from the statements in `UnitDescriptions`. A `ForeignKeyStatement` is
  attached to its `table()`, and a `drop` foreign key is ignored.
- [ ] **Step 4: Run `UnitDescriptionTest` and `SchemaGenerationTest`, expect PASS.**
- [ ] **Step 5: Commit** `feat(core): describe the tables, indexes and foreign keys a unit maps`.

### Task 3: the create and drop scripts, never run

**Files:**
- Create: `noether-persistence/noether-jpa-core/src/main/java/io/vidocq/noether/jpa/core/spi/SchemaScripts.java`
- Modify: `bootstrap/SchemaGeneration.java` (implements `SchemaScripts`), `session/EntityManagerFactoryImpl.java`
  (`unwrap`)
- Test: `UnitDescriptionTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface SchemaScripts {
      List<String> create();   // what scripts.create-target would receive: create statements then load script
      List<String> drop();     // what scripts.drop-target would receive
  }
  ```
  - Both follow the unit's `create-source` / `drop-source` settings and its dialect.
  - They open a connection only when `jakarta.persistence.database-product-name` is unset, to detect the database
    (the `withConnection` rule).
  - They never execute anything. A `SQLException` becomes a `PersistenceException`, as in `generate()`.
  - `emf.unwrap(SchemaScripts.class)` returns the factory's `SchemaGeneration`.

- [ ] **Step 1: Write the failing tests.**
  - `scriptsRenderTheSchemaWithoutRunningIt`, with `Shelf` and `rel.Team`:
    - `create()` has a statement that starts, ignoring case, with `create table shelf`;
    - `drop()` has one containing, ignoring case, `drop table`;
    - afterwards `select count(*) from information_schema.tables where table_name = 'SHELF'` on the same H2 URL
      returns 0.
  - `theCreateScriptFollowsTheConfiguredSource`: with `create-source=script` and `create-script-source` pointing to
    the test resource `orm/schema-file.xml`'s sibling script (or a new `src/test/resources/scripts/create.sql`
    holding `create table HANDMADE (id int)`), `create()` equals `["create table HANDMADE (id int)"]`.
- [ ] **Step 2: Run them, expect a compilation failure** (`SchemaScripts` missing).
- [ ] **Step 3: Implement.** Extract from `generate(Connection, String, String)` the statement building into
  `create()` and `drop()`, sharing `statements(...)`, `loadCommands()` and `dialect(...)`. `generate()` keeps its
  behaviour, so `SchemaGenerationTest` must stay green unchanged.
- [ ] **Step 4: Run `UnitDescriptionTest` and `SchemaGenerationTest`, expect PASS.**
- [ ] **Step 5: Commit** `feat(core): render a unit's create and drop scripts without running them`.

### Task 4: documentation and full verification

**Files:**
- Modify:
  - `docs/en/modules/ROOT/pages/reference.adoc`: a new section `[#tools]`, "Describing a unit for tools", placed
    after `[#inheritance]`;
  - `docs/en/modules/ROOT/pages/whats-new.adoc`: one entry.

- [ ] **Step 1: Write the section.**
  - What `unwrap(UnitDescription.class)` and `unwrap(SchemaScripts.class)` return, and that the description holds
    strings only.
  - When `SchemaScripts` opens a connection, and that it never executes anything.
  - A short example that lists the entities and their relationships.
  - The Vidocq dev console's Persistence panel is its first user.
- [ ] **Step 2: Run the full reactor:** `zsh $S/vidocq-build.sh <worktree> ud clean verify`. Expect BUILD SUCCESS,
  with `noether-architecture-tests` included.
- [ ] **Step 3: Commit** `docs(reference): describing a unit for tools`.
- [ ] **Step 4: Open the pull request** after the SonarQube branch analysis. If the local token is refused, say so in
  the body.
