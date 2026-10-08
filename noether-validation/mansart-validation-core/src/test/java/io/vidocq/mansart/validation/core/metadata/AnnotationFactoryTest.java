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

import java.lang.annotation.Annotation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Annotation instances handed to {@code ConstraintValidator.initialize} are generated classes, not JDK
 * proxies. The JDK's own instance is the oracle: same member values, same equality, same hash code.
 */
class AnnotationFactoryTest {

    private static SampleAnnotation jdk(String field) throws Exception {
        return SampleBean.class.getField(field).getAnnotation(SampleAnnotation.class);
    }

    private static SampleAnnotation fromClassFile(String field) {
        return (SampleAnnotation) ClassFileReader.fieldAnnotations(SampleBean.class, field).stream()
            .filter(a -> a instanceof SampleAnnotation).findFirst().orElseThrow();
    }

    @Test
    void defaultsAreFilledFromTheAnnotationType() throws Exception {
        SampleAnnotation read = fromClassFile("defaults");
        SampleAnnotation expected = jdk("defaults");
        assertThat(read.required()).isEqualTo("r");
        assertThat(read.aByte()).isEqualTo(expected.aByte());
        assertThat(read.aShort()).isEqualTo(expected.aShort());
        assertThat(read.anInt()).isEqualTo(expected.anInt());
        assertThat(read.aLong()).isEqualTo(expected.aLong());
        assertThat(read.aFloat()).isEqualTo(expected.aFloat());
        assertThat(read.aDouble()).isEqualTo(expected.aDouble());
        assertThat(read.aChar()).isEqualTo(expected.aChar());
        assertThat(read.aBoolean()).isEqualTo(expected.aBoolean());
        assertThat(read.aString()).isEqualTo(expected.aString());
        assertThat(read.aClass()).isEqualTo(expected.aClass());
        assertThat(read.aColor()).isEqualTo(expected.aColor());
        assertThat(read.aNested().label()).isEqualTo("nested");
        assertThat(read.ints()).containsExactly(1, 2);
        assertThat(read.strings()).isEmpty();
        assertThat(read.colors()).containsExactly(SampleAnnotation.Color.RED);
    }

    @Test
    void explicitValuesOfEveryKindAreRead() throws Exception {
        SampleAnnotation read = fromClassFile("everything");
        SampleAnnotation expected = jdk("everything");
        assertThat(read.aByte()).isEqualTo(expected.aByte());
        assertThat(read.aShort()).isEqualTo(expected.aShort());
        assertThat(read.anInt()).isEqualTo(expected.anInt());
        assertThat(read.aLong()).isEqualTo(expected.aLong());
        assertThat(read.aFloat()).isEqualTo(expected.aFloat());
        assertThat(read.aDouble()).isEqualTo(expected.aDouble());
        assertThat(read.aChar()).isEqualTo(expected.aChar());
        assertThat(read.aBoolean()).isEqualTo(expected.aBoolean());
        assertThat(read.aString()).isEqualTo(expected.aString());
        assertThat(read.aClass()).isEqualTo(String.class);
        assertThat(read.aColor()).isEqualTo(SampleAnnotation.Color.BLUE);
        assertThat(read.aNested().label()).isEqualTo("n");
        assertThat(read.ints()).containsExactly(4, 5, 6);
        assertThat(read.strings()).containsExactly("a", "b");
        assertThat(read.classes()).containsExactly(int.class, String[].class, void.class);
        assertThat(read.colors()).containsExactly(SampleAnnotation.Color.BLUE, SampleAnnotation.Color.RED);
        assertThat(read.nesteds()).hasSize(2);
        assertThat(read.nesteds()[1].label()).isEqualTo("x");
        assertThat(read.longs()).containsExactly(1L);
        assertThat(read.booleans()).containsExactly(true, false);
        assertThat(read.chars()).containsExactly('a', 'b');
    }

    @Test
    void theInstanceIsNotAJdkProxy() {
        Annotation read = fromClassFile("defaults");
        assertThat(java.lang.reflect.Proxy.isProxyClass(read.getClass())).isFalse();
        assertThat(read.annotationType()).isEqualTo(SampleAnnotation.class);
    }

    @Test
    void equalsAndHashCodeFollowTheAnnotationContract() throws Exception {
        for (String field : new String[] {"defaults", "everything"}) {
            SampleAnnotation read = fromClassFile(field);
            SampleAnnotation expected = jdk(field);
            assertThat(read).isEqualTo(expected);
            assertThat(expected).isEqualTo(read);
            assertThat(read.hashCode()).isEqualTo(expected.hashCode());
        }
        assertThat(fromClassFile("defaults")).isNotEqualTo(jdk("everything"));
    }

    @Test
    void toStringNamesTheTypeAndTheMembers() {
        assertThat(fromClassFile("defaults").toString()).startsWith("@" + SampleAnnotation.class.getCanonicalName() + "(")
            .contains("required=\"r\"");
    }

    @Test
    void arraysAreDefensivelyCopied() {
        SampleAnnotation read = fromClassFile("everything");
        read.ints()[0] = 99;
        assertThat(read.ints()).containsExactly(4, 5, 6);
    }

    @Test
    void aMemberWithoutDefaultMustBeGiven() {
        AnnotationTypeInfo info = AnnotationTypeInfo.of(SampleAnnotation.class);
        assertThatThrownBy(() -> AnnotationFactory.create(info, Map.of()))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
    }

    @Test
    void theTypeInfoListsTheMembersInDeclarationOrder() {
        AnnotationTypeInfo info = AnnotationTypeInfo.of(SampleAnnotation.class);
        assertThat(info.members().stream().map(AnnotationTypeInfo.Member::name).toList())
            .startsWith("aByte", "aShort", "anInt").endsWith("chars", "required");
        assertThat(info.member("required").hasDefault()).isFalse();
        assertThat(info.member("anInt").hasDefault()).isTrue();
        assertThat(info.member("ints").type()).isEqualTo(int[].class);
    }
}
