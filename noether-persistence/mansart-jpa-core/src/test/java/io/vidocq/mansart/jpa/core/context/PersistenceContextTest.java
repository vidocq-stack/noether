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
package io.vidocq.mansart.jpa.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.mapping.MappedEntity;
import io.vidocq.mansart.jpa.core.mapping.MappedUnit;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.OrderLine;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Ticket;
import io.vidocq.mansart.jpa.core.model.build.fixtures.TicketKey;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import jakarta.persistence.EntityExistsException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** §3.2 and §3.3: one managed instance per identity, and the life cycle of the instances of a persistence context. */
class PersistenceContextTest {

    private static final MappedUnit UNIT = MappedUnit.of(List.of(Customer.class.getName(), Ticket.class.getName(),
        OrderLine.class.getName()), PersistenceContextTest.class.getClassLoader());

    private final PersistenceContext context = new PersistenceContext();

    private static MappedEntity type(Class<?> entity) {
        return UNIT.entity(entity).orElseThrow();
    }

    private static Object customer(long id) {
        MappedEntity type = type(Customer.class);
        Object customer = type.access().instantiate();
        type.access().set(customer, type.model().attributes().indexOf(type.model().attribute("id").orElseThrow()), id);
        return customer;
    }

    private static Object[] state(MappedEntity type, Object instance) {
        Object[] state = new Object[type.model().attributes().size()];
        type.access().read(instance, state);
        return state;
    }

    @Test
    void aPersistedInstanceIsManagedAndAwaitsItsInsert() {
        Object customer = customer(1);
        ManagedEntity entry = context.persist(customer, type(Customer.class));
        assertThat(context.contains(customer)).isTrue();
        assertThat(entry.status()).isEqualTo(ManagedEntity.Status.MANAGED);
        assertThat(entry.inserted()).isFalse();
        assertThat(entry.key()).isEqualTo(new EntityKey(Customer.class, 1L));
        assertThat(context.find(new EntityKey(Customer.class, 1L))).containsSame(entry);
    }

    @Test
    void persistingAManagedInstanceAgainChangesNothing() { // §3.2.2
        Object customer = customer(1);
        ManagedEntity first = context.persist(customer, type(Customer.class));
        assertThat(context.persist(customer, type(Customer.class))).isSameAs(first);
        assertThat(context.entries()).hasSize(1);
    }

    @Test
    void anotherInstanceWithTheSameIdentityCannotBePersisted() { // §3.2.2
        context.persist(customer(1), type(Customer.class));
        assertThatThrownBy(() -> context.persist(customer(1), type(Customer.class))).isInstanceOf(EntityExistsException.class);
    }

    @Test
    void aLoadedRowIsManagedOnceWithItsSnapshot() { // §3.2.8: one managed instance per identity
        MappedEntity type = type(Customer.class);
        Object customer = customer(2);
        ManagedEntity entry = context.loaded(customer, type, state(type, customer));
        assertThat(entry.inserted()).isTrue();
        assertThat(entry.snapshot()).isEqualTo(state(type, customer));
        assertThat(context.loaded(customer(2), type, state(type, customer)).instance()).isSameAs(customer);
    }

    @Test
    void removeMakesAManagedInstanceRemovedAndPersistMakesItManagedAgain() { // §3.2.3, §3.2.2
        Object customer = customer(3);
        ManagedEntity entry = context.persist(customer, type(Customer.class));
        assertThat(context.remove(customer)).isTrue();
        assertThat(entry.status()).isEqualTo(ManagedEntity.Status.REMOVED);
        assertThat(context.contains(customer)).isFalse(); // §3.2.8: contains is false for a removed instance
        assertThat(context.remove(customer)).isTrue(); // removing it again is ignored
        assertThat(context.persist(customer, type(Customer.class))).isSameAs(entry);
        assertThat(entry.status()).isEqualTo(ManagedEntity.Status.MANAGED);
    }

    @Test
    void anInstanceOutsideTheContextIsNotRemoved() {
        assertThat(context.remove(customer(4))).isFalse();
    }

    @Test
    void detachAndClearForgetTheInstances() { // §3.2.7
        Object first = customer(5);
        Object second = customer(6);
        context.persist(first, type(Customer.class));
        context.persist(second, type(Customer.class));
        context.detach(first);
        assertThat(context.contains(first)).isFalse();
        assertThat(context.find(new EntityKey(Customer.class, 5L))).isEmpty();
        context.clear();
        assertThat(context.contains(second)).isFalse();
        assertThat(context.entries()).isEmpty();
    }

    @Test
    void anIdentifierGeneratedAtInsertIsRegisteredWhenKnown() { // IDENTITY: no key before the insert
        Object customer = type(Customer.class).access().instantiate();
        ManagedEntity entry = context.persist(customer, type(Customer.class));
        assertThat(entry.key()).isNull();
        context.inserted(entry, 42L, state(type(Customer.class), customer));
        assertThat(entry.key()).isEqualTo(new EntityKey(Customer.class, 42L));
        assertThat(context.find(entry.key())).containsSame(entry);
        assertThat(entry.inserted()).isTrue();
    }

    @Test
    void compositeIdentifiersAreComparedByValue() { // §2.4: @EmbeddedId and @IdClass
        MappedEntity ticket = type(Ticket.class);
        Object first = ticket.access().instantiate();
        Object second = ticket.access().instantiate();
        ManagedAccess key = UNIT.access(((io.vidocq.mansart.jpa.core.model.IdModel.Embedded) ticket.model().id()).attribute().embeddable());
        Object k1 = key.instantiate();
        key.set(k1, 0, "Paris");
        key.set(k1, 1, 36);
        Object k2 = key.instantiate();
        key.set(k2, 0, "Paris");
        key.set(k2, 1, 36);
        ticket.access().set(first, 0, k1);
        ticket.access().set(second, 0, k2);
        assertThat(k1).isInstanceOf(TicketKey.class).isNotEqualTo(k2); // TicketKey does not define equals
        assertThat(ticket.id(first)).isEqualTo(ticket.id(second));
        context.persist(first, ticket);
        assertThatThrownBy(() -> context.persist(second, ticket)).isInstanceOf(EntityExistsException.class);

        MappedEntity line = type(OrderLine.class);
        Object a = line.access().instantiate();
        line.access().write(a, new Object[] {7L, 1, 3});
        Object b = line.access().instantiate();
        line.access().write(b, new Object[] {7L, 1, 9});
        assertThat(line.id(a)).isEqualTo(line.id(b));
    }
}
