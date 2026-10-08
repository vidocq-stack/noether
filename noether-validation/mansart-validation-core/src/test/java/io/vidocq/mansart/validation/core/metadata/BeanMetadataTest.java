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
package io.vidocq.mansart.validation.core.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.validation.core.metadata.BeanFixtures.Base;
import io.vidocq.mansart.validation.core.metadata.BeanFixtures.Derived;
import io.vidocq.mansart.validation.core.metadata.BeanFixtures.Named;
import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.Required;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 3: constraints on fields and getters, on classes and interfaces, and @Valid. */
class BeanMetadataTest {

    private static PropertyMetadata property(BeanMetadata metadata, String name, PropertyMetadata.Kind kind) {
        return metadata.properties().stream().filter(p -> p.name().equals(name) && p.kind() == kind).findFirst().orElseThrow();
    }

    @Test
    void aClassKeepsOnlyItsOwnDeclaredConstrainedMembers() {
        BeanMetadata derived = BeanMetadata.of(Derived.class);
        assertThat(derived.properties().stream().map(PropertyMetadata::name)).containsExactlyInAnyOrder("label", "numbers");
    }

    @Test
    void fieldConstraintsAreRead() {
        PropertyMetadata label = property(BeanMetadata.of(Derived.class), "label", PropertyMetadata.Kind.FIELD);
        assertThat(label.constraints().stream().map(ConstraintDef::annotationType)).containsExactly(Size.class);
        assertThat(label.valueType()).isEqualTo(String.class);
        assertThat(label.cascaded()).isFalse();
    }

    @Test
    void anAnnotationThatIsNotAConstraintIsIgnored() {
        PropertyMetadata label = property(BeanMetadata.of(Derived.class), "label", PropertyMetadata.Kind.FIELD);
        assertThat(label.constraints()).hasSize(1);
    }

    @Test
    void validMarksAFieldAsCascaded() {
        BeanMetadata base = BeanMetadata.of(Base.class);
        assertThat(property(base, "child", PropertyMetadata.Kind.FIELD).cascaded()).isTrue();
        assertThat(property(base, "child", PropertyMetadata.Kind.FIELD).constraints()).isEmpty();
    }

    @Test
    void constraintsAndValidCanBeCombinedOnAnArrayField() {
        PropertyMetadata numbers = property(BeanMetadata.of(Derived.class), "numbers", PropertyMetadata.Kind.FIELD);
        assertThat(numbers.cascaded()).isTrue();
        assertThat(numbers.constraints()).hasSize(1);
        assertThat(numbers.valueType()).isEqualTo(int[].class);
    }

    @Test
    void anUnconstrainedFieldIsNotListed() {
        assertThat(BeanMetadata.of(Base.class).properties().stream().map(PropertyMetadata::name)).doesNotContain("untouched");
    }

    @Test
    void staticMembersAreIgnored() {
        assertThat(BeanMetadata.of(Base.class).properties().stream().map(PropertyMetadata::name))
            .doesNotContain("staticField", "static");
    }

    @Test
    void gettersFollowTheJavaBeansConventionPlusHas() {
        BeanMetadata base = BeanMetadata.of(Base.class);
        assertThat(base.properties().stream().filter(p -> p.kind() == PropertyMetadata.Kind.GETTER).map(PropertyMetadata::name))
            .containsExactlyInAnyOrder("active", "label");
        assertThat(property(base, "active", PropertyMetadata.Kind.GETTER).valueType()).isEqualTo(boolean.class);
    }

    @Test
    void anInterfaceGetterIsReadFromTheInterface() {
        BeanMetadata named = BeanMetadata.of(Named.class);
        PropertyMetadata name = property(named, "name", PropertyMetadata.Kind.GETTER);
        assertThat(name.constraints().stream().map(ConstraintDef::annotationType)).containsExactly(NotNull.class);
        assertThat(name.declaringClass()).isEqualTo(Named.class);
    }

    @Test
    void classLevelConstraintsAreRead() {
        assertThat(BeanMetadata.of(Base.class).classConstraints().stream().map(ConstraintDef::annotationType))
            .containsExactly(Required.class);
        assertThat(BeanMetadata.of(Derived.class).classConstraints()).isEmpty();
    }

    @Test
    void theHierarchyListsTheClassThenItsAncestors() {
        List<BeanMetadata> hierarchy = BeanMetadata.hierarchy(Derived.class);
        assertThat(hierarchy.stream().map(BeanMetadata::type).toList()).containsExactly(Derived.class, Base.class, Named.class);
    }

    @Test
    void thereIsNoMetadataForObject() {
        assertThat(BeanMetadata.hierarchy(Object.class)).isEmpty();
        assertThat(BeanMetadata.hierarchy(Derived.class).stream().map(BeanMetadata::type)).doesNotContain(Object.class);
    }

    @Test
    void accessorsReadPrivateFieldsPublicFieldsAndGetters() {
        Derived bean = new Derived();
        assertThat(property(BeanMetadata.of(Base.class), "id", PropertyMetadata.Kind.FIELD).get(bean)).isEqualTo("id-1");
        assertThat(property(BeanMetadata.of(Derived.class), "label", PropertyMetadata.Kind.FIELD).get(bean)).isEqualTo("label");
        assertThat(property(BeanMetadata.of(Base.class), "active", PropertyMetadata.Kind.GETTER).get(bean)).isEqualTo(true);
        assertThat(property(BeanMetadata.of(Named.class), "name", PropertyMetadata.Kind.GETTER).get(bean)).isEqualTo("base-name");
        assertThat((int[]) property(BeanMetadata.of(Derived.class), "numbers", PropertyMetadata.Kind.FIELD).get(bean)).containsExactly(1);
    }
}
