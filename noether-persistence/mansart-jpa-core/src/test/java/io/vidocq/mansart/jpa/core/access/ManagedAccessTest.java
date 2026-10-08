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
package io.vidocq.mansart.jpa.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Account;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Addr;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Contractor;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Geo;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Level;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Lowercase;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Note;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Shop;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedAccessTest {

    private static EntityModel model(Class<?> entity) {
        PersistenceUnitModel unit = EntityModelBuilder.build(List.of(entity.getName()), entity.getClassLoader());
        return unit.entity(entity).orElseThrow();
    }

    private static int index(EntityModel model, String attribute) {
        return model.attributes().indexOf(model.attribute(attribute).orElseThrow());
    }

    private static int index(EmbeddableModel model, String attribute) {
        return model.attributes().indexOf(model.attribute(attribute).orElseThrow());
    }

    @Test
    void theAccessIsAHiddenClassOfThisModuleWithoutReflection() {
        ManagedAccess access = Accesses.of(model(Customer.class));
        assertThat(access.getClass().isHidden()).isTrue();
        assertThat(access.getClass().getModule()).isEqualTo(ManagedAccess.class.getModule());
        assertThat(access.type()).isEqualTo(Customer.class);
    }

    @Test
    void theAccessListsItsAttributesAsTheModelDoes() {
        EntityModel model = model(Account.class);
        assertThat(Accesses.of(model).attributes()).containsExactly("number:PROPERTY", "owner:PROPERTY", "active:PROPERTY");
        assertThat(Accesses.of(model(Contractor.class)).attributes()).containsExactly("id:FIELD", "name:FIELD", "rate:PROPERTY");
    }

    @Test
    void fieldAccessReadsAndWritesPrivateFieldsThroughAProtectedConstructor() {
        EntityModel model = model(Customer.class);
        ManagedAccess access = Accesses.of(model);
        Object customer = access.instantiate();
        assertThat(customer).isInstanceOf(Customer.class);

        access.set(customer, index(model, "id"), 7L);
        access.set(customer, index(model, "name"), "Vidocq");
        access.set(customer, index(model, "visits"), 3);
        access.set(customer, index(model, "level"), Level.GOLD);
        access.set(customer, index(model, "picture"), new byte[] {1, 2});

        assertThat(access.get(customer, index(model, "id"))).isEqualTo(7L);
        assertThat(access.get(customer, index(model, "name"))).isEqualTo("Vidocq");
        assertThat(access.get(customer, index(model, "visits"))).isEqualTo(3);
        assertThat(access.get(customer, index(model, "level"))).isEqualTo(Level.GOLD);
        assertThat((byte[]) access.get(customer, index(model, "picture"))).containsExactly(1, 2);
        assertThat(access.get(customer, index(model, "email"))).isNull();
        assertThat(((Customer) customer).getDisplayName()).isEqualTo("Vidocq");
    }

    @Test
    void propertyAccessCallsTheGettersAndSetters() {
        EntityModel model = model(Account.class);
        ManagedAccess access = Accesses.of(model);
        Account account = (Account) access.instantiate();

        access.set(account, index(model, "number"), 42L);
        access.set(account, index(model, "owner"), "Eugène");
        access.set(account, index(model, "active"), true);

        assertThat(account.getNumber()).isEqualTo(42L);
        assertThat(account.getOwner()).isEqualTo("Eugène");
        assertThat(account.isActive()).isTrue();
        assertThat(access.get(account, index(model, "owner"))).isEqualTo("Eugène");
        assertThat(access.get(account, index(model, "active"))).isEqualTo(true);
    }

    @Test
    void attributesOfAMappedSuperclassAreReachedFromTheEntity() {
        EntityModel model = model(Note.class);
        ManagedAccess access = Accesses.of(model);
        Object note = access.instantiate();
        Instant now = Instant.now();

        access.set(note, index(model, "id"), 9L);
        access.set(note, index(model, "createdAt"), now);
        access.set(note, index(model, "text"), "hello");

        assertThat(access.get(note, index(model, "id"))).isEqualTo(9L);
        assertThat(access.get(note, index(model, "createdAt"))).isEqualTo(now);
        assertThat(access.get(note, index(model, "text"))).isEqualTo("hello");
    }

    @Test
    void uncapitalisedAccessorsAreCalled() {
        EntityModel model = model(Lowercase.class);
        ManagedAccess access = Accesses.of(model);
        Lowercase entity = (Lowercase) access.instantiate();
        access.set(entity, index(model, "description"), "plain");
        assertThat(entity.getdescription()).isEqualTo("plain");
        assertThat(access.get(entity, index(model, "description"))).isEqualTo("plain");
    }

    @Test
    void eachClassOfTheHierarchyIsAccessedTheWayItIsMapped() {
        EntityModel model = model(Contractor.class);
        ManagedAccess access = Accesses.of(model);
        Contractor contractor = (Contractor) access.instantiate();
        access.set(contractor, index(model, "id"), 3);
        access.set(contractor, index(model, "rate"), 1.5f);
        assertThat(contractor.getId()).isEqualTo(3);
        assertThat(contractor.getRate()).isEqualTo(1.5f);
    }

    @Test
    void anEmbeddableClassIsInstantiatedAndWrittenLikeAnEntity() {
        EntityModel shop = model(Shop.class);
        EmbeddableModel addr = ((EmbeddedAttribute) shop.attribute("address").orElseThrow()).embeddable();
        ManagedAccess access = Accesses.of(addr);
        Object address = access.instantiate();
        assertThat(address).isInstanceOf(Addr.class);

        access.set(address, index(addr, "street"), "rue de Jérusalem");
        assertThat(access.get(address, index(addr, "street"))).isEqualTo("rue de Jérusalem");
    }

    @Test
    void aRecordEmbeddableIsBuiltThroughItsCanonicalConstructorAndIsReadOnly() {
        EntityModel shop = model(Shop.class);
        EmbeddableModel geo = ((EmbeddedAttribute) shop.attribute("location").orElseThrow()).embeddable();
        ManagedAccess access = Accesses.of(geo);

        Object location = access.construct(48.85, 2.35);
        assertThat(location).isEqualTo(new Geo(48.85, 2.35));
        assertThat(access.get(location, index(geo, "lon"))).isEqualTo(2.35);
        assertThatThrownBy(() -> access.set(location, 0, 1.0)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(access::instantiate).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aClassIsNotBuiltFromComponents() {
        ManagedAccess access = Accesses.of(model(Customer.class));
        assertThatThrownBy(() -> access.construct()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anUnknownAttributeIndexIsRejected() {
        EntityModel model = model(Customer.class);
        ManagedAccess access = Accesses.of(model);
        Object customer = access.instantiate();
        int outside = model.attributes().size();
        assertThatThrownBy(() -> access.get(customer, outside)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> access.set(customer, -1, null)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theWrongValueTypeFailsWithAClassCastException() {
        EntityModel model = model(Customer.class);
        ManagedAccess access = Accesses.of(model);
        Object customer = access.instantiate();
        assertThatThrownBy(() -> access.set(customer, index(model, "name"), 12)).isInstanceOf(ClassCastException.class);
        assertThatThrownBy(() -> access.get("not a customer", index(model, "name"))).isInstanceOf(ClassCastException.class);
    }

    @Test
    void everyAttributeOfEveryFixtureIsReachable() {
        for (Class<?> entity : List.of(Customer.class, Account.class, Note.class, Shop.class)) {
            EntityModel model = model(entity);
            ManagedAccess access = Accesses.of(model);
            Object instance = access.instantiate();
            for (int i = 0; i < model.attributes().size(); i++) {
                Object value = access.get(instance, i);
                access.set(instance, i, value);
            }
            assertThat(Arrays.asList(entity, instance)).doesNotContainNull();
        }
    }
}
