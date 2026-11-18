package io.vidocq.noether.jpa.core;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.DriverManager;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Jakarta Persistence 3.2 §9.4 and §7.12: schema operations use the mapped unit, not entity reflection. */
class SchemaGenerationTest {
    @Entity @Table(name = "p9_record")
    public static class Record {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        @Column(name = "description", length = 42, nullable = false) public String text;
        public Record() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @Entity @jakarta.persistence.DiscriminatorColumn(name = "kind")
    public static class Parent {
        @Id public int id;
        @Column(name = "year_value") public java.time.Year year;
        public Parent() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }
    @Entity public static class Child extends Parent {
        public String label;
        public Child() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @Entity @Table(name = "p9_product")
    public static class Product {
        @Id public int id;
        public String name;
        public Product() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }
    @Entity public static class Hardware extends Product {
        @Column(name = "model") public int modelNumber;
        public Hardware() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @Entity @Table(name = "p9_employee")
    public static class Employee {
        @Id @Column(name = "ID") public int id;
        @Column(name = "FIRSTNAME") public String firstName;
        public Employee() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }
    @Entity @Table(name = "p9_employee")
    public static class EmployeeName {
        @Id public int id;
        public String firstName;
        public EmployeeName() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @Test
    void theStateASubclassAddsToASingleTableIsNullable() { // §2.12.1, BUG-20261118-66
        try (var factory = new PersistenceConfiguration("p9-single-table").managedClass(Product.class).managedClass(Hardware.class)
                .property("jakarta.persistence.jdbc.url", "jdbc:h2:mem:p9-single-table;DB_CLOSE_DELAY=-1")
                .property("jakarta.persistence.schema-generation.database.action", "drop-and-create").createEntityManagerFactory()) {
            Product product = new Product();
            product.id = 1;
            product.name = "plain";
            factory.runInTransaction(em -> em.persist(product));
            Product found = factory.callInTransaction(em -> em.find(Product.class, 1));
            assertThat(found).isNotInstanceOf(Hardware.class);
            assertThat(found.name).isEqualTo("plain");
        }
    }

    @Test
    void twoEntitiesOfOneTableShareAColumnWhoseNamesDifferInCase() { // §2.13, BUG-20261118-67
        try (var factory = new PersistenceConfiguration("p9-shared-table").managedClass(Employee.class)
                .managedClass(EmployeeName.class)
                .property("jakarta.persistence.jdbc.url", "jdbc:h2:mem:p9-shared-table;DB_CLOSE_DELAY=-1")
                .property("jakarta.persistence.schema-generation.database.action", "drop-and-create").createEntityManagerFactory()) {
            Employee employee = new Employee();
            employee.id = 1;
            employee.firstName = "Ada";
            factory.runInTransaction(em -> em.persist(employee));
            assertThat(factory.callInTransaction(em -> em.find(EmployeeName.class, 1)).firstName).isEqualTo("Ada");
        }
    }

    @Test
    void inheritanceDiscriminatorsAndYearAreSchemaColumns() { // §2.14 and §2.8
        try (var factory = new PersistenceConfiguration("p9-inheritance").managedClass(Parent.class).managedClass(Child.class)
                .property("jakarta.persistence.jdbc.url", "jdbc:h2:mem:p9-inheritance;DB_CLOSE_DELAY=-1")
                .property("jakarta.persistence.schema-generation.database.action", "create").createEntityManagerFactory()) {
            Child child = new Child();
            child.id = 1;
            child.year = java.time.Year.of(2026);
            child.label = "child";
            factory.runInTransaction(em -> em.persist(child));
            Parent found = factory.callInTransaction(em -> em.find(Parent.class, 1));
            assertThat(found).isInstanceOf(Child.class);
            assertThat(found.year).isEqualTo(child.year);
        }
    }

    private PersistenceConfiguration configuration(String database) {
        return new PersistenceConfiguration("schema-" + database).managedClass(Record.class)
            .property("jakarta.persistence.jdbc.url", "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    }

    @Test
    void databaseActionCreatesMappedColumnsAndIdentity() throws Exception { // §9.4.1
        try (var factory = configuration("p9-create")
                .property("jakarta.persistence.schema-generation.database.action", "drop-and-create")
                .createEntityManagerFactory()) {
            Record entity = new Record();
            entity.text = "created";
            factory.runInTransaction(em -> em.persist(entity));
            assertThat(entity.id).isPositive();
            factory.getSchemaManager().validate();
            factory.getSchemaManager().truncate();
            Record missing = factory.callInTransaction(em -> em.find(Record.class, entity.id));
            assertThat(missing).isNull();
            factory.getSchemaManager().drop(true);
            assertThatThrownBy(() -> factory.getSchemaManager().validate()).isInstanceOf(jakarta.persistence.SchemaValidationException.class);
            factory.getSchemaManager().create(true);
            factory.getSchemaManager().validate();
        }
    }

    @Test
    void scriptsUseCallerWritersWithoutClosingThem() { // §9.4.2
        var create = new StringWriter();
        var drop = new StringWriter();
        try (var _ = configuration("p9-writer")
                .property("jakarta.persistence.schema-generation.database.action", "none")
                .property("jakarta.persistence.schema-generation.scripts.action", "drop-and-create")
                .property("jakarta.persistence.schema-generation.scripts.create-target", create)
                .property("jakarta.persistence.schema-generation.scripts.drop-target", drop)
                .createEntityManagerFactory()) {
            assertThat(create.toString()).containsIgnoringCase("CREATE TABLE p9_record")
                .contains("VARCHAR(42)").contains("GENERATED BY DEFAULT AS IDENTITY");
            assertThat(drop.toString()).containsIgnoringCase("DROP TABLE");
        }
        create.write("-- still usable");
    }

    @Test
    void metadataAndScriptOrderingAndLoadAreHonored() throws Exception { // §9.4.1
        try (var factory = configuration("p9-order")
                .property("jakarta.persistence.schema-generation.database.action", "create")
                .property("jakarta.persistence.schema-generation.create-source", "metadata-then-script")
                .property("jakarta.persistence.schema-generation.create-script-source",
                    new StringReader("INSERT INTO p9_record(description) VALUES ('a; b'); -- comment\n"))
                .property("jakarta.persistence.sql-load-script-source",
                    new StringReader("INSERT INTO p9_record(description) VALUES ('loaded');"))
                .createEntityManagerFactory();
             var connection = DriverManager.getConnection("jdbc:h2:mem:p9-order");
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM p9_record")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(2);
            factory.getSchemaManager().truncate();
            try (var reloaded = statement.executeQuery("SELECT description FROM p9_record")) {
                assertThat(reloaded.next()).isTrue();
                assertThat(reloaded.getString(1)).isEqualTo("loaded");
                assertThat(reloaded.next()).isFalse();
            }
        }
    }

    @Test
    void generateSchemaReturnsFalseForAnUnknownUnit() {
        assertThat(new NoetherPersistenceProvider().generateSchema("not-a-unit", Map.of())).isFalse();
    }

    @Test
    void invalidActionsAndMissingTargetsAreRejectedBeforeExecutingDdl() {
        var invalidAction = configuration("p9-invalid")
            .property("jakarta.persistence.schema-generation.database.action", "invalid");
        assertThatThrownBy(invalidAction::createEntityManagerFactory).isInstanceOf(PersistenceException.class);
        var missingTarget = configuration("p9-invalid")
            .property("jakarta.persistence.schema-generation.scripts.action", "create");
        assertThatThrownBy(missingTarget::createEntityManagerFactory).isInstanceOf(PersistenceException.class);
    }
}
