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
package io.vidocq.mansart.jpa.core.model.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.mansart.jpa.core.model.fixtures.Address;
import io.vidocq.mansart.jpa.core.model.fixtures.Purchase;
import io.vidocq.mansart.jpa.core.model.fixtures.PurchaseKey;
import java.lang.constant.ClassDesc;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reading a class as data from its class file: annotations, fields and methods, without loading the classes the
 * annotations name. Annotation members not written take the default of the annotation type, read from its own class file.
 */
class ClassFileSourceTest {

    private final ClassFileSource source = new ClassFileSource(getClass().getClassLoader());

    private ClassInfo purchase() {
        return source.read(Purchase.class.getName()).orElseThrow();
    }

    @Test
    void aClassIsReadByName() {
        ClassInfo info = purchase();
        assertThat(info.name()).isEqualTo(Purchase.class.getName());
        assertThat(info.superclassName()).isEqualTo("java.lang.Object");
        assertThat(source.read("com.acme.DoesNotExist")).isEmpty();
    }

    @Test
    void classAnnotationsCarryTheirWrittenValues() {
        ClassInfo info = purchase();
        assertThat(info.annotation("jakarta.persistence.Entity")).hasValueSatisfying(a ->
            assertThat(a.string("name")).isEqualTo("Purchase"));
        AnnotationInfo table = info.annotation("jakarta.persistence.Table").orElseThrow();
        assertThat(table.string("name")).isEqualTo("PURCHASES");
        List<AnnotationInfo> constraints = table.annotations("uniqueConstraints");
        assertThat(constraints).hasSize(1);
        assertThat(constraints.get(0).strings("columnNames")).containsExactly("CODE", "LABEL");
        assertThat(info.annotation("jakarta.persistence.IdClass").orElseThrow().type("value"))
            .isEqualTo(ClassDesc.of(PurchaseKey.class.getName()));
    }

    @Test
    void unwrittenMembersTakeTheDefaultOfTheAnnotationType() { // e.g. @Column(length) defaults to 255
        AnnotationInfo plain = purchase().field("plain").orElseThrow().annotation("jakarta.persistence.Column").orElseThrow();
        assertThat(plain.isWritten("length")).isFalse();
        assertThat(plain.integer("length")).isEqualTo(255);
        assertThat(plain.bool("nullable")).isTrue();
        assertThat(plain.string("name")).isEmpty();
        AnnotationInfo label = purchase().field("label").orElseThrow().annotation("jakarta.persistence.Column").orElseThrow();
        assertThat(label.isWritten("length")).isTrue();
        assertThat(label.integer("length")).isEqualTo(40);
        assertThat(label.bool("nullable")).isFalse();
        assertThat(purchase().annotation("jakarta.persistence.Entity").orElseThrow().string("name")).isEqualTo("Purchase");
    }

    @Test
    void enumValuesAreNamedWithoutLoadingTheEnum() {
        AnnotationInfo enumerated = purchase().field("status").orElseThrow().annotation("jakarta.persistence.Enumerated").orElseThrow();
        assertThat(enumerated.enumConstant("value")).isEqualTo("STRING");
        AnnotationInfo defaulted = purchase().field("plain").orElseThrow().annotation("jakarta.persistence.Column").orElseThrow();
        assertThat(defaulted.has("value")).isFalse();
    }

    @Test
    void nestedAnnotationsAreRead() {
        AnnotationInfo override = purchase().field("shipping").orElseThrow().annotation("jakarta.persistence.AttributeOverride").orElseThrow();
        assertThat(override.string("name")).isEqualTo("street");
        assertThat(override.annotation("column").string("name")).isEqualTo("SHIP_STREET");
        assertThat(override.annotation("column").integer("length")).isEqualTo(255);
    }

    @Test
    void fieldsKeepTheirDeclaredAndGenericTypesAndFlags() {
        ClassInfo info = purchase();
        assertThat(info.fields().stream().map(FieldInfo::name))
            .containsExactly("id", "code", "label", "status", "plain", "shipping", "tags", "ignored", "constant");
        FieldInfo tags = info.field("tags").orElseThrow();
        assertThat(tags.type()).isEqualTo(ClassDesc.of("java.util.List"));
        assertThat(tags.genericSignature()).isEqualTo("Ljava/util/List<Ljava/lang/String;>;");
        assertThat(info.field("id").orElseThrow().type()).isEqualTo(java.lang.constant.ConstantDescs.CD_long);
        assertThat(info.field("constant").orElseThrow().isStatic()).isTrue();
        assertThat(info.field("ignored").orElseThrow().isStatic()).isFalse();
        assertThat(info.field("ignored").orElseThrow().annotation("jakarta.persistence.Transient")).isPresent();
    }

    @Test
    void methodsAreListedWithTheirDescriptors() {
        MethodInfo getter = purchase().methods().stream().filter(m -> m.name().equals("getLabel")).findFirst().orElseThrow();
        assertThat(getter.type().returnType()).isEqualTo(java.lang.constant.ConstantDescs.CD_String);
        assertThat(getter.type().parameterCount()).isZero();
        assertThat(getter.isStatic()).isFalse();
    }

    @Test
    void anEmbeddableIsRecognisedByItsAnnotation() {
        assertThat(source.read(Address.class.getName()).orElseThrow().annotation("jakarta.persistence.Embeddable")).isPresent();
        assertThat(source.read(PurchaseKey.class.getName()).orElseThrow().annotations()).isEmpty();
    }
}
