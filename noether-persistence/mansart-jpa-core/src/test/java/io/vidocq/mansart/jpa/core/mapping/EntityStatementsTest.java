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
package io.vidocq.mansart.jpa.core.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Geo;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Note;
import io.vidocq.mansart.jpa.core.model.build.fixtures.OrderLine;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Shop;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Ticket;
import io.vidocq.mansart.jpa.dialect.Dialect;
import io.vidocq.mansart.jpa.dialect.StandardDialect;
import io.vidocq.mansart.jpa.dialect.sql.Identifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The statements of an entity, built once from its mapping (§2.3, §2.4, §2.6, §3.4.2 version). */
class EntityStatementsTest {

    private static final MappedUnit UNIT = MappedUnit.of(List.of(Customer.class.getName(), Note.class.getName(),
        Ticket.class.getName(), OrderLine.class.getName(), Shop.class.getName()), EntityStatementsTest.class.getClassLoader());

    private static final Dialect ANSI = new StandardDialect() {
        @Override
        public String name() {
            return "ansi";
        }
    };

    private static EntityStatements statements(Class<?> entity) {
        return UNIT.entity(entity).orElseThrow().statements();
    }

    private static List<String> names(List<EntityStatements.Column> columns) {
        return columns.stream().map(c -> c.name().name()).toList();
    }

    @Test
    void anEntityIsInsertedUpdatedAndDeletedThroughItsColumns() {
        EntityStatements customer = statements(Customer.class);
        assertThat(ANSI.render(customer.insert())).isEqualTo("INSERT INTO Customer (id, name, E_MAIL, visits, balance, credit, "
            + "since, legacy, birthDate, token, level, preferredLevel, grade, notes, picture, version, biography) VALUES (?, ?, ?, "
            + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        assertThat(ANSI.render(customer.update())).isEqualTo("UPDATE Customer SET name = ?, E_MAIL = ?, visits = ?, balance = ?, "
            + "credit = ?, since = ?, legacy = ?, birthDate = ?, token = ?, level = ?, preferredLevel = ?, grade = ?, notes = ?, "
            + "picture = ?, version = ?, biography = ? WHERE id = ? AND version = ?");
        assertThat(ANSI.render(customer.delete())).isEqualTo("DELETE FROM Customer WHERE id = ? AND version = ?");
        assertThat(ANSI.render(customer.select())).startsWith("SELECT id, name, E_MAIL").endsWith(" FROM Customer WHERE id = ?");
    }

    @Test
    void theOldVersionMatchesTheRowTheNewOneIsWritten() { // §3.4.2
        EntityStatements customer = statements(Customer.class);
        List<EntityStatements.Parameter> update = customer.updateParameters();
        EntityStatements.Parameter last = update.getLast();
        assertThat(customer.columns().get(last.column()).name()).isEqualTo(Identifier.of("version"));
        assertThat(last.previous()).isTrue();
        assertThat(update.stream().filter(EntityStatements.Parameter::previous)).hasSize(1);
        assertThat(customer.version()).isPresent();
    }

    @Test
    void anIdentityIdentifierIsLeftToTheDatabaseAndReadBack() { // GenerationType.IDENTITY
        EntityStatements note = statements(Note.class);
        assertThat(ANSI.render(note.insert())).isEqualTo("INSERT INTO Note (CREATED, text) VALUES (?, ?)");
        assertThat(note.insert().generatedKey()).isEqualTo(Identifier.of("id"));
    }

    @Test
    void compositeIdentifiersAreSeveralColumns() { // §2.4
        assertThat(ANSI.render(statements(Ticket.class).delete())).isEqualTo("DELETE FROM Ticket WHERE office = ? AND number = ?");
        assertThat(ANSI.render(statements(OrderLine.class).delete())).isEqualTo("DELETE FROM OrderLine WHERE orderId = ? AND line = ?");
    }

    @Test
    void embeddedAttributesAreFlattenedWithTheirOverrides() { // §2.6, §11.1.4
        EntityStatements shop = statements(Shop.class);
        assertThat(names(shop.columns())).containsSubsequence("street", "TOWN", "lat", "lon", "BILL_STREET", "TOWN", "BILL_LAT", "lon");

        MappedEntity type = UNIT.entity(Shop.class).orElseThrow();
        Object instance = type.access().instantiate();
        var address = ((io.vidocq.mansart.jpa.core.model.EmbeddedAttribute) type.model().attribute("location").orElseThrow());
        type.access().set(instance, type.model().attributes().indexOf(address), new Geo(48.85, 2.35));
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(instance, state);
        Object[] values = shop.values(state);
        List<String> names = names(shop.columns());
        assertThat(values[names.lastIndexOf("lat")]).isEqualTo(48.85);
        assertThat(values[names.indexOf("street")]).isNull(); // a null embeddable: null columns
    }
}
