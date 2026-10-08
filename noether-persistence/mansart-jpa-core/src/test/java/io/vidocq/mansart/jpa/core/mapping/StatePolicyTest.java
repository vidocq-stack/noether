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

import io.vidocq.mansart.jpa.core.model.build.fixtures.Addr;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Geo;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Invoice;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Money;
import io.vidocq.mansart.jpa.core.model.build.fixtures.MoneyConverter;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Shop;
import io.vidocq.mansart.jpa.core.model.build.fixtures.UpperCaseConverter;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §3.2.4 synchronisation: what changed since the last load or flush, without enhancement nor interception. */
class StatePolicyTest {

    private static final MappedUnit UNIT = MappedUnit.of(List.of(Customer.class.getName(), Shop.class.getName(),
        Invoice.class.getName(), MoneyConverter.class.getName(), UpperCaseConverter.class.getName()),
        StatePolicyTest.class.getClassLoader());

    private static MappedEntity type(Class<?> entity) {
        return UNIT.entity(entity).orElseThrow();
    }

    private static int index(MappedEntity type, String attribute) {
        return type.model().attributes().indexOf(type.model().attribute(attribute).orElseThrow());
    }

    private static Object[] state(MappedEntity type, Object instance) {
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(instance, state);
        return state;
    }

    private static Object customer() {
        MappedEntity type = type(Customer.class);
        Object customer = type.access().instantiate();
        type.access().set(customer, index(type, "id"), 1L);
        type.access().set(customer, index(type, "name"), "Vidocq");
        type.access().set(customer, index(type, "balance"), new BigDecimal("1.0"));
        type.access().set(customer, index(type, "legacy"), new Date(1_000));
        type.access().set(customer, index(type, "picture"), new byte[] {1, 2});
        return customer;
    }

    @Test
    void anUnchangedInstanceIsNotDirty() {
        MappedEntity type = type(Customer.class);
        Object customer = customer();
        Object[] snapshot = type.state().snapshot(state(type, customer));
        assertThat(type.state().changes(snapshot, state(type, customer))).isEmpty();
    }

    @Test
    void replacingAValueIsAChange() {
        MappedEntity type = type(Customer.class);
        Object customer = customer();
        Object[] snapshot = type.state().snapshot(state(type, customer));
        type.access().set(customer, index(type, "name"), "Eugène");
        assertThat(type.state().changes(snapshot, state(type, customer))).containsExactly(index(type, "name"));
    }

    @Test
    void mutableValuesChangedInPlaceAreChanges() { // arrays and dates are copied into the snapshot
        MappedEntity type = type(Customer.class);
        Object customer = customer();
        Object[] snapshot = type.state().snapshot(state(type, customer));
        ((byte[]) type.access().get(customer, index(type, "picture")))[0] = 9;
        ((Date) type.access().get(customer, index(type, "legacy"))).setTime(2_000);
        assertThat(type.state().changes(snapshot, state(type, customer)))
            .containsExactlyInAnyOrder(index(type, "picture"), index(type, "legacy"));
    }

    @Test
    void numbersAreComparedByValueNotByScale() {
        MappedEntity type = type(Customer.class);
        Object customer = customer();
        Object[] snapshot = type.state().snapshot(state(type, customer));
        type.access().set(customer, index(type, "balance"), new BigDecimal("1.00"));
        assertThat(type.state().changes(snapshot, state(type, customer))).isEmpty();
    }

    @Test
    void anEmbeddableChangedInPlaceIsAChangeOfItsAttribute() {
        MappedEntity shop = type(Shop.class);
        Object instance = shop.access().instantiate();
        var addressType = ((io.vidocq.mansart.jpa.core.model.EmbeddedAttribute) shop.model().attribute("address").orElseThrow()).embeddable();
        var address = UNIT.access(addressType);
        Object addr = address.instantiate();
        address.set(addr, addressType.attributes().indexOf(addressType.attribute("street").orElseThrow()), "rue de Jérusalem");
        address.set(addr, addressType.attributes().indexOf(addressType.attribute("geo").orElseThrow()), new Geo(1, 2));
        shop.access().set(instance, index(shop, "address"), addr);
        Object[] snapshot = shop.state().snapshot(state(shop, instance));

        address.set(addr, addressType.attributes().indexOf(addressType.attribute("street").orElseThrow()), "quai des Orfèvres");
        assertThat(addr).isInstanceOf(Addr.class);
        assertThat(shop.state().changes(snapshot, state(shop, instance))).containsExactly(index(shop, "address"));

        Object[] again = shop.state().snapshot(state(shop, instance));
        address.set(addr, addressType.attributes().indexOf(addressType.attribute("geo").orElseThrow()), new Geo(1, 2));
        assertThat(shop.state().changes(again, state(shop, instance))).isEmpty(); // an equal record
    }

    @Test
    void convertedAndSerializedValuesAreComparedAsTheDatabaseStoresThem() {
        MappedEntity invoice = type(Invoice.class);
        Object instance = invoice.access().instantiate();
        invoice.access().set(instance, index(invoice, "total"), new Money(100));
        invoice.access().set(instance, index(invoice, "raw"), new Money(5));
        invoice.access().set(instance, index(invoice, "code"), "abc");
        Object[] snapshot = invoice.state().snapshot(state(invoice, instance));
        invoice.access().set(instance, index(invoice, "code"), "ABC"); // UpperCaseConverter: the same column value
        invoice.access().set(instance, index(invoice, "raw"), new Money(5));
        assertThat(invoice.state().changes(snapshot, state(invoice, instance))).isEmpty();
        invoice.access().set(instance, index(invoice, "total"), new Money(101));
        assertThat(invoice.state().changes(snapshot, state(invoice, instance))).containsExactly(index(invoice, "total"));
    }

    @Test
    void theSnapshotIsIndependentOfTheInstance() {
        MappedEntity type = type(Customer.class);
        Object customer = customer();
        Object[] state = state(type, customer);
        Object[] snapshot = type.state().snapshot(state);
        assertThat(snapshot[index(type, "picture")]).isNotSameAs(state[index(type, "picture")]);
        assertThat(snapshot[index(type, "legacy")]).isNotSameAs(state[index(type, "legacy")]);
        assertThat(snapshot[index(type, "name")]).isSameAs(state[index(type, "name")]); // immutable: shared
    }
}
