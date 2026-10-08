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

import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Customer;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Invoice;
import io.vidocq.mansart.jpa.core.model.build.fixtures.MoneyConverter;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Shop;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappedUnitTest {

    private static MappedUnit unit(Class<?>... classes) {
        return MappedUnit.of(List.of(classes).stream().map(Class::getName).toList(), MappedUnitTest.class.getClassLoader());
    }

    @Test
    void everyEntityHasItsAccess() {
        MappedUnit unit = unit(Customer.class, Shop.class);
        for (EntityModel entity : unit.model().entities()) {
            assertThat(unit.access(entity).type()).isEqualTo(entity.javaType());
        }
    }

    @Test
    void everyEmbeddableOfEveryEntityHasItsAccessNestedOnesIncluded() {
        MappedUnit unit = unit(Shop.class);
        EntityModel shop = unit.model().entity(Shop.class).orElseThrow();
        EmbeddableModel address = ((EmbeddedAttribute) shop.attribute("address").orElseThrow()).embeddable();
        EmbeddableModel geo = ((EmbeddedAttribute) address.attribute("geo").orElseThrow()).embeddable();
        assertThat(unit.access(address).type()).isEqualTo(address.javaType());
        assertThat(unit.access(geo).type()).isEqualTo(geo.javaType());
    }

    @Test
    void everyBasicAttributeHasItsBinderEmbeddedOnesIncluded() {
        MappedUnit unit = unit(Customer.class, Shop.class, Invoice.class, MoneyConverter.class);
        for (EntityModel entity : unit.model().entities()) {
            assertBinders(unit, entity.attributes());
        }
    }

    private static void assertBinders(MappedUnit unit, List<AttributeModel> attributes) {
        for (AttributeModel attribute : attributes) {
            switch (attribute) {
                case BasicAttribute basic -> assertThat(unit.binder(basic)).as(basic.name()).isNotNull();
                case EmbeddedAttribute embedded -> assertBinders(unit, embedded.embeddable().attributes());
                default -> {
                }
            }
        }
    }
}
