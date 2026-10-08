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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.Composed;
import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.Group;
import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.Holder;
import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.Required;
import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.RequiredValidator;
import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintTarget;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 2 (constraint definition) and 7.2 (ConstraintDescriptor). */
class ConstraintDefTest {

    private static List<ConstraintDef> on(String field) {
        return ClassFileReader.fieldConstraints(Holder.class, field);
    }

    @Test
    void aConstraintAnnotationIsDescribed() {
        ConstraintDef def = on("plain").get(0);
        assertThat(def.annotationType()).isEqualTo(Required.class);
        assertThat(def.getMessageTemplate()).isEqualTo("required!");
        assertThat(def.getGroups()).containsExactly(Default.class);
        assertThat(def.getPayload()).isEmpty();
        assertThat(def.isReportAsSingleViolation()).isFalse();
        assertThat(def.getComposingConstraints()).isEmpty();
        assertThat(def.getValidationAppliesTo()).isNull();
        assertThat(def.getConstraintValidatorClasses()).containsExactly((Class) RequiredValidator.class);
    }

    @Test
    void explicitAttributesAreKept() {
        ConstraintDef def = on("custom").get(0);
        assertThat(def.getMessageTemplate()).isEqualTo("custom");
        assertThat(def.getGroups()).containsExactly(Group.class);
        assertThat(def.getAttributes()).containsEntry("level", 5).containsEntry("message", "custom");
        assertThat(def.getAttributes()).containsKeys("groups", "payload");
        assertThat(((Required) def.getAnnotation()).level()).isEqualTo(5);
    }

    @Test
    void theAttributesMapIsReadOnly() {
        ConstraintDef def = on("plain").get(0);
        assertThatThrownBy(() -> def.getAttributes().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aRepeatedConstraintIsUnwrappedFromItsContainer() {
        List<ConstraintDef> defs = on("repeated");
        assertThat(defs).hasSize(2);
        assertThat(defs.stream().map(d -> d.getAttributes().get("level")).toList()).containsExactly(2, 3);
    }

    @Test
    void aNonPublicContainerIsUnwrappedWithoutInstantiatingIt() {
        List<ConstraintDef> defs = on("hiddenContainer");
        assertThat(defs).hasSize(2);
        assertThat(defs.stream().map(d -> d.getAttributes().get("level")).toList()).containsExactly(7, 8);
    }

    @Test
    void aComposedConstraintListsItsComposingConstraints() {
        ConstraintDef def = on("composed").get(0);
        assertThat(def.isReportAsSingleViolation()).isTrue();
        assertThat(def.getComposingConstraints()).hasSize(2);
        assertThat(def.getComposingConstraints().stream().map(ConstraintDescriptor::getAnnotation)
            .map(Annotation::annotationType).toList()).containsExactlyInAnyOrder(NotNull.class, Size.class);
    }

    @Test
    void anAnnotationWithoutConstraintMetaAnnotationIsNotAConstraint() {
        assertThat(on("notAConstraint")).isEmpty();
    }

    @Test
    void severalConstraintsOnOneElementAreAllKept() {
        assertThat(on("builtIns").stream().map(ConstraintDef::annotationType).toList())
            .containsExactly(NotNull.class, Size.class);
    }

    @Test
    void aConstraintWithoutMessageIsInvalid() {
        assertThatThrownBy(() -> on("noMessage")).isInstanceOf(ConstraintDefinitionException.class);
    }

    @Test
    void aMemberNameStartingWithValidIsReserved() {
        assertThatThrownBy(() -> on("reserved")).isInstanceOf(ConstraintDefinitionException.class);
    }
}
