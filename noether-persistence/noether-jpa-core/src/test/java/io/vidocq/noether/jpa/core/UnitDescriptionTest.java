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
package io.vidocq.noether.jpa.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.model.build.fixtures.Account;
import io.vidocq.noether.jpa.core.model.build.fixtures.Addr;
import io.vidocq.noether.jpa.core.model.build.fixtures.Shop;
import io.vidocq.noether.jpa.core.model.build.fixtures.Stamp;
import io.vidocq.noether.jpa.core.model.build.fixtures.Ticket;
import io.vidocq.noether.jpa.core.model.build.fixtures.TicketKey;
import io.vidocq.noether.jpa.core.model.build.fixtures.coll.Suspect;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Emp;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Player;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Skill;
import io.vidocq.noether.jpa.core.model.build.fixtures.rel.Team;
import io.vidocq.noether.jpa.core.spi.UnitDescription;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Attribute.Kind;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Entity;
import io.vidocq.noether.jpa.core.spi.UnitDescription.Inheritance;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceConfiguration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A running unit described for development tools, through {@code unwrap(UnitDescription.class)}. */
class UnitDescriptionTest {

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @jakarta.persistence.Entity
    public static class Animal {
        @Id public long id;
        public String name;
        public Animal() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @jakarta.persistence.Entity
    public static class Dog extends Animal {
        public boolean barks;
        public Dog() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @jakarta.persistence.Entity
    public static class Rack {
        @Id public long id;
        public Rack() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "SHELF",
        indexes = @jakarta.persistence.Index(name = "SHELF_LABEL", columnList = "label"),
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(name = "SHELF_CODE", columnNames = "code"))
    public static class Shelf {
        @Id public long id;
        public String label;
        public String code;
        @jakarta.persistence.Column(unique = true) public String serial;
        @jakarta.persistence.ManyToOne public Rack rack;
        public Shelf() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Inheritance(strategy = jakarta.persistence.InheritanceType.TABLE_PER_CLASS)
    public abstract static class Vehicle {
        @Id public long id;
        protected Vehicle() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    @jakarta.persistence.Entity
    public static class Bike extends Vehicle {
        public int gears;
        public Bike() {
            // Jakarta Persistence needs a no-arg constructor (§2.1)
        }
    }

    static EntityManagerFactory factory(String name, Class<?>... classes) {
        PersistenceConfiguration configuration = new PersistenceConfiguration(name)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:describe-" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa");
        for (Class<?> type : classes) {
            configuration.managedClass(type);
        }
        return configuration.createEntityManagerFactory();
    }

    static Entity entity(UnitDescription unit, String name) {
        return unit.entities().stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
    }

    static Attribute attribute(Entity entity, String name) {
        return entity.attributes().stream().filter(a -> a.name().equals(name)).findFirst().orElseThrow();
    }

    static List<String> upper(List<String> names) {
        return names.stream().map(n -> n.toUpperCase(Locale.ROOT)).toList();
    }

    @Test
    void relationshipsNameTheirTargetAndTheirOwningSide() {
        try (EntityManagerFactory emf = factory("relations", Team.class, Player.class, Skill.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            Attribute team = attribute(entity(unit, "Player"), "team");
            assertThat(team.kind()).isEqualTo(Kind.MANY_TO_ONE);
            assertThat(team.target()).isEqualTo("Team");
            assertThat(team.owning()).isTrue();
            assertThat(upper(team.columns())).containsExactly("TEAM_ID");

            Attribute players = attribute(entity(unit, "Team"), "players");
            assertThat(players.kind()).isEqualTo(Kind.ONE_TO_MANY);
            assertThat(players.mappedBy()).isEqualTo("team");
            assertThat(players.owning()).isFalse();
            assertThat(players.columns()).isEmpty();

            Attribute skills = attribute(entity(unit, "Player"), "skills");
            assertThat(skills.kind()).isEqualTo(Kind.MANY_TO_MANY);
            assertThat(skills.owning()).isTrue();
            assertThat(skills.joinTable()).isEqualToIgnoringCase("Player_Skill");
            assertThat(attribute(entity(unit, "Skill"), "players").mappedBy()).isEqualTo("skills");

            Attribute id = attribute(entity(unit, "Team"), "id");
            assertThat(id.kind()).isEqualTo(Kind.ID);
            assertThat(upper(id.columns())).containsExactly("ID");
            assertThat(attribute(entity(unit, "Team"), "version").kind()).isEqualTo(Kind.VERSION);
            assertThat(attribute(entity(unit, "Team"), "name").kind()).isEqualTo(Kind.BASIC);
        }
    }

    @Test
    void inheritanceIsDescribedOnEveryEntityOfItsHierarchy() {
        try (EntityManagerFactory emf = factory("inheritance", Animal.class, Dog.class, Stamp.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            assertThat(entity(unit, "Dog").superEntity()).isEqualTo("Animal");
            assertThat(entity(unit, "Dog").inheritance()).isEqualTo(Inheritance.SINGLE_TABLE);
            assertThat(entity(unit, "Animal").superEntity()).isNull();
            assertThat(entity(unit, "Animal").inheritance()).isEqualTo(Inheritance.SINGLE_TABLE);
            assertThat(entity(unit, "Stamp").inheritance()).isEqualTo(Inheritance.NONE);
            assertThat(entity(unit, "Dog").className()).isEqualTo(Dog.class.getName());
            assertThat(attribute(entity(unit, "Dog"), "name").declaringClass()).isEqualTo(Animal.class.getName());
        }
    }

    @Test
    void embeddablesAreListedOnceWithTheirColumns() {
        try (EntityManagerFactory emf = factory("embeddables", Shop.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            assertThat(unit.embeddables()).filteredOn(e -> e.className().equals(Addr.class.getName())).hasSize(1);
            Attribute billing = attribute(entity(unit, "Shop"), "billing");
            assertThat(billing.kind()).isEqualTo(Kind.EMBEDDED);
            assertThat(billing.target()).isEqualTo(Addr.class.getName());
            assertThat(upper(billing.columns())).contains("BILL_STREET", "TOWN", "BILL_LAT");
        }
    }

    @Test
    void aCompositeIdentifierListsEveryKeyColumn() {
        try (EntityManagerFactory emf = factory("composite", Ticket.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            Attribute key = attribute(entity(unit, "Ticket"), "key");
            assertThat(key.kind()).isEqualTo(Kind.EMBEDDED_ID);
            assertThat(key.target()).isEqualTo(TicketKey.class.getName());
            assertThat(upper(key.columns())).containsExactlyInAnyOrder("OFFICE", "NUMBER");
        }
    }

    @Test
    void anElementCollectionNamesItsCollectionTable() {
        try (EntityManagerFactory emf = factory("collections", Suspect.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            Attribute sightings = attribute(entity(unit, "Suspect"), "sightings");
            assertThat(sightings.kind()).isEqualTo(Kind.ELEMENT_COLLECTION);
            assertThat(sightings.columns()).isEmpty();
            assertThat(sightings.joinTable()).isEqualToIgnoringCase("SUSPECT_SIGHTINGS");
            assertThat(attribute(entity(unit, "Suspect"), "aliases").joinTable()).isNotNull();
        }
    }

    @Test
    void namedQueriesKeepTheirTextAndKind() {
        try (EntityManagerFactory emf = factory("queries", Dept.class, Emp.class, Desk.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            assertThat(unit.namedQueries()).extracting(UnitDescription.NamedQuery::name)
                .contains("Dept.byName", "Emp.count", "Emp.forUpdate", "Emp.rename");
            assertThat(unit.namedQueries()).filteredOn(q -> q.name().equals("Emp.rename"))
                .singleElement().satisfies(q -> assertThat(q.nativeQuery()).isTrue());
            assertThat(unit.namedQueries()).filteredOn(q -> q.name().equals("Emp.count"))
                .singleElement().satisfies(q -> assertThat(q.query()).isEqualTo("SELECT COUNT(e) FROM Emp e"));
        }
    }

    @Test
    void theDescriptionIsBuiltOncePerFactory() {
        try (EntityManagerFactory emf = factory("once", Stamp.class)) {
            UnitDescription first = emf.unwrap(UnitDescription.class);

            assertThat(emf.unwrap(UnitDescription.class)).isSameAs(first);
            assertThat(first.unitName()).isEqualTo("once");
        }
    }

    @Test
    void aClosedFactoryDescribesNothing() {
        EntityManagerFactory emf = factory("closed", Stamp.class);
        emf.close();

        assertThatThrownBy(() -> emf.unwrap(UnitDescription.class)).isInstanceOf(IllegalStateException.class);
    }

    static UnitDescription.Table table(UnitDescription unit, String name) {
        return unit.tables().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst().orElseThrow();
    }

    @Test
    void tablesCarryTheirKeysIndexesAndForeignKeys() {
        try (EntityManagerFactory emf = factory("tables", Rack.class, Shelf.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            UnitDescription.Table shelf = table(unit, "SHELF");
            assertThat(upper(shelf.primaryKey())).containsExactly("ID");
            assertThat(shelf.columns()).extracting(c -> c.name().toUpperCase(Locale.ROOT))
                .contains("ID", "LABEL", "CODE", "SERIAL", "RACK_ID");
            assertThat(shelf.columns()).filteredOn(c -> c.name().equalsIgnoreCase("ID")).singleElement()
                .satisfies(c -> assertThat(c.sqlType()).isEqualTo("BIGINT"));
            assertThat(shelf.indexes()).anySatisfy(i -> {
                assertThat(i.name()).isEqualTo("SHELF_LABEL");
                assertThat(upper(i.columns())).containsExactly("LABEL");
                assertThat(i.unique()).isFalse();
            });
            assertThat(shelf.indexes()).anySatisfy(i -> {
                assertThat(i.name()).isEqualTo("SHELF_CODE");
                assertThat(upper(i.columns())).containsExactly("CODE");
                assertThat(i.unique()).isTrue();
            });
            assertThat(shelf.indexes()).anySatisfy(i -> {
                assertThat(i.name()).isNull();
                assertThat(upper(i.columns())).containsExactly("SERIAL");
                assertThat(i.unique()).isTrue();
            });
            assertThat(shelf.foreignKeys()).singleElement().satisfies(k -> {
                assertThat(upper(k.columns())).containsExactly("RACK_ID");
                assertThat(k.referencedTable()).isEqualToIgnoringCase("Rack");
                assertThat(upper(k.referencedColumns())).containsExactly("ID");
            });
            assertThat(entity(unit, "Shelf").table()).isEqualTo("SHELF");
        }
    }

    @Test
    void aSchemaQualifiedTableKeepsItsSchema() {
        try (EntityManagerFactory emf = factory("schema", Account.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            assertThat(entity(unit, "Acct").table()).isEqualTo("BANK.ACCOUNTS");
            assertThat(unit.tables()).extracting(UnitDescription.Table::name).contains("BANK.ACCOUNTS");
        }
    }

    @Test
    void anAbstractTablePerClassRootHasNoTable() {
        try (EntityManagerFactory emf = factory("per-class", Vehicle.class, Bike.class)) {
            UnitDescription unit = emf.unwrap(UnitDescription.class);

            assertThat(entity(unit, "Vehicle").table()).isNull();
            assertThat(entity(unit, "Vehicle").isAbstract()).isTrue();
            assertThat(entity(unit, "Vehicle").inheritance()).isEqualTo(Inheritance.TABLE_PER_CLASS);
            assertThat(entity(unit, "Bike").inheritance()).isEqualTo(Inheritance.TABLE_PER_CLASS);
            assertThat(unit.tables()).extracting(t -> t.name().toUpperCase(Locale.ROOT)).contains("BIKE")
                .doesNotContain("VEHICLE");
        }
    }
}
