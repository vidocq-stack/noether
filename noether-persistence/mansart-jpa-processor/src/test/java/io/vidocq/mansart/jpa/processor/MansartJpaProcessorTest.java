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
package io.vidocq.mansart.jpa.processor;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.mansart.jpa.core.spi.ManagedAccess;
import io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The accesses generated at build time, checked against the model the bootstrap builds from the compiled classes. */
class MansartJpaProcessorTest {

    @TempDir
    static Path out;

    static Compilation shop;
    static URLClassLoader loader;
    static Map<Class<?>, ManagedAccess> accesses;
    static PersistenceUnitModel model;

    @BeforeAll
    static void compileTheShop() throws Throwable {
        shop = Compilation.onClassPath(out, Compilation.FIXTURES.resolve("shop"));
        assertThat(shop.success()).as("%s", shop.diagnostics()).isTrue();
        loader = new URLClassLoader(new java.net.URL[] {shop.classes().toUri().toURL()}, MansartJpaProcessorTest.class.getClassLoader());
        Class<?> providerClass = Class.forName("shop._MansartJpaAccess", true, loader);
        ManagedAccessProvider provider = (ManagedAccessProvider) MethodHandles.publicLookup()
            .findConstructor(providerClass, MethodType.methodType(void.class)).invoke();
        accesses = provider.accesses().stream().collect(Collectors.toMap(ManagedAccess::type, Function.identity(), (a, b) -> {
            throw new AssertionError("two accesses for " + a.type());
        }));
        model = EntityModelBuilder.build(List.of("shop.Item", "shop.Purchase"), loader);
    }

    @AfterAll
    static void close() throws Exception {
        loader.close();
    }

    private static Class<?> type(String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static List<String> descriptor(List<AttributeModel> attributes) {
        return attributes.stream().map(a -> a.name() + ":" + a.access()).toList();
    }

    private static EntityModel entity(String name) throws ClassNotFoundException {
        return model.entity(type(name)).orElseThrow();
    }

    @Test
    void theShopCompilesWithoutWarnings() {
        assertThat(shop.messages(Diagnostic.Kind.WARNING)).isEmpty();
        assertThat(shop.messages(Diagnostic.Kind.MANDATORY_WARNING)).isEmpty();
    }

    @Test
    void oneProviderPerPackageOfEntitiesIsRegisteredForTheClassPath() throws Exception {
        assertThat(Files.readAllLines(shop.classes().resolve("META-INF/services/io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider")))
            .containsExactly("shop._MansartJpaAccess");
        assertThat(shop.generated().resolve("shop/base/_MansartJpaAccess.java")).doesNotExist();
    }

    @Test
    void everyAccessListsTheAttributesOfTheModelBuiltAtBootstrap() throws Exception { // AccessPlanner, both sides
        EntityModel item = entity("shop.Item");
        EntityModel purchase = entity("shop.Purchase");
        assertThat(accesses.get(type("shop.Item")).attributes()).isEqualTo(descriptor(item.attributes()))
            .containsExactly("createdAt:FIELD", "revision:FIELD", "id:FIELD", "name:FIELD", "price:FIELD", "stock:FIELD",
                "tags:FIELD");
        assertThat(accesses.get(type("shop.Purchase")).attributes()).isEqualTo(descriptor(purchase.attributes()));
        EmbeddedAttribute delivery = (EmbeddedAttribute) purchase.attribute("delivery").orElseThrow();
        assertThat(accesses.get(type("shop.Address")).attributes()).isEqualTo(descriptor(delivery.embeddable().attributes()));
        EmbeddedAttribute pickup = (EmbeddedAttribute) purchase.attribute("pickup").orElseThrow();
        assertThat(accesses.get(type("shop.Geo")).attributes()).isEqualTo(descriptor(pickup.embeddable().attributes()));
    }

    @Test
    void fieldsAreReadAndWrittenWhateverTheirVisibility() throws Exception {
        ManagedAccess access = accesses.get(type("shop.Item"));
        Object item = access.instantiate();
        Instant now = Instant.now();
        Object[] values = {now, 3, 7L, "lamp", new BigDecimal("9.90"), 12, List.of("light")};
        for (int i = 0; i < values.length; i++) {
            access.set(item, i, values[i]);
        }
        for (int i = 0; i < values.length; i++) {
            assertThat(access.get(item, i)).as(access.attributes().get(i)).isEqualTo(values[i]);
        }
    }

    @Test
    void accessibleMembersAreReachedDirectlyTheOthersThroughHandles() {
        String item = shop.source("shop/Item$$MansartAccess.java");
        assertThat(item).contains("entity.price").contains("entity.stock").contains("new shop.Item()")
            .doesNotContain("entity.name").doesNotContain("entity.revision");
        String purchase = shop.source("shop/Purchase$$MansartAccess.java");
        assertThat(purchase).contains("entity.getdescription()").contains("entity.isPaid()").doesNotContain("MethodHandle");
    }

    @Test
    void theWholeStateIsReadAndWrittenInOneCall() throws Exception {
        ManagedAccess access = accesses.get(type("shop.Item"));
        Object item = access.instantiate();
        Object[] values = {Instant.now(), 3, 7L, "lamp", new BigDecimal("9.90"), 12, List.of("light")};
        access.write(item, values);
        Object[] state = new Object[values.length];
        access.read(item, state);
        assertThat(state).isEqualTo(values);
        assertThat(shop.source("shop/Item$$MansartAccess.java")).contains("public void read(").contains("public void write(");
        assertThat(shop.source("shop/Geo$$MansartAccess.java")).contains("public void read(").doesNotContain("public void write(");
    }

    @Test
    void propertiesGoThroughTheirAccessors() throws Exception {
        ManagedAccess access = accesses.get(type("shop.Purchase"));
        Object purchase = access.instantiate();
        List<String> attributes = access.attributes();
        access.set(purchase, attributes.indexOf("description:PROPERTY"), "gift");
        access.set(purchase, attributes.indexOf("paid:PROPERTY"), true);
        assertThat(access.get(purchase, attributes.indexOf("description:PROPERTY"))).isEqualTo("gift");
        assertThat(access.get(purchase, attributes.indexOf("paid:PROPERTY"))).isEqualTo(true);
    }

    @Test
    void embeddablesAreBuiltRecordsThroughTheirCanonicalConstructor() throws Exception {
        ManagedAccess address = accesses.get(type("shop.Address"));
        Object delivery = address.instantiate();
        address.set(delivery, 0, "rue de Jérusalem");
        assertThat(address.get(delivery, 0)).isEqualTo("rue de Jérusalem");

        ManagedAccess geo = accesses.get(type("shop.Geo"));
        Object pickup = geo.construct(48.85, 2.35);
        assertThat(geo.get(pickup, 1)).isEqualTo(2.35);
    }

    @Test
    void anEntityThatCannotBePlannedIsLeftToTheBootstrapWithAWarning(@TempDir Path elsewhere) {
        Compilation broken = Compilation.onClassPath(elsewhere, Compilation.FIXTURES.resolve("broken"));
        assertThat(broken.success()).isTrue();
        assertThat(broken.messages(Diagnostic.Kind.WARNING)).anySatisfy(m -> assertThat(m).contains("broken.NoId")
            .contains("no @Id"));
        assertThat(broken.generated().resolve("broken/NoId$$MansartAccess.java")).doesNotExist();
    }
}
