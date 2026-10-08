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
package io.vidocq.mansart.validation.core.descriptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.validation.core.MansartValidationProvider;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.BeanDescriptor;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import jakarta.validation.metadata.Scope;
import java.lang.annotation.ElementType;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapter 7: the metadata API for beans and properties. */
class BeanDescriptorTest {

    public interface Strict {
    }

    public interface Stricter extends Strict {
    }

    @NotNull
    public static class Unconstrained {
        public String free;
    }

    public static class Plain {
        public String free;
        private int hidden;
    }

    public static class User {
        @Size(max = 50)
        public String getName() {
            return null;
        }

        @NotNull
        protected String email;

        @Valid
        protected Object friend;

        @Size(min = 2, groups = Strict.class)
        protected String nick;
    }

    public static class Customer extends User {
        @NotNull
        @Override
        public String getName() {
            return null;
        }

        @Size(min = 1, groups = Stricter.class)
        protected String code;
    }

    private ValidatorFactory factory;
    private Validator validator;

    @BeforeEach
    void open() {
        factory = Validation.byProvider(MansartValidationProvider.class).configure().buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterEach
    void close() {
        factory.close();
    }

    private static Set<String> names(Set<PropertyDescriptor> properties) {
        return properties.stream().map(PropertyDescriptor::getPropertyName).collect(Collectors.toSet());
    }

    private static Set<Class<?>> annotationTypes(Set<ConstraintDescriptor<?>> descriptors) {
        return descriptors.stream().map(d -> (Class<?>) d.getAnnotation().annotationType()).collect(Collectors.toSet());
    }

    @Test
    void theDescriptorDescribesTheClass() {
        BeanDescriptor bean = validator.getConstraintsForClass(Customer.class);
        assertThat(bean.getElementClass()).isEqualTo(Customer.class);
    }

    @Test
    void aNullClassIsRejected() {
        assertThatThrownBy(() -> validator.getConstraintsForClass(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBeanWithoutAnyDeclarationIsNotConstrained() {
        BeanDescriptor bean = validator.getConstraintsForClass(Plain.class);
        assertThat(bean.isBeanConstrained()).isFalse();
        assertThat(bean.hasConstraints()).isFalse();
        assertThat(bean.getConstraintDescriptors()).isEmpty();
        assertThat(bean.getConstrainedProperties()).isEmpty();
    }

    @Test
    void aClassLevelConstraintMakesTheBeanConstrained() {
        BeanDescriptor bean = validator.getConstraintsForClass(Unconstrained.class);
        assertThat(bean.isBeanConstrained()).isTrue();
        assertThat(bean.hasConstraints()).isTrue();
        assertThat(annotationTypes(bean.getConstraintDescriptors())).containsExactly(NotNull.class);
        assertThat(bean.getConstrainedProperties()).isEmpty();
    }

    @Test
    void aPropertyConstraintMakesTheBeanConstrainedButNotTheBeanElement() {
        BeanDescriptor bean = validator.getConstraintsForClass(User.class);
        assertThat(bean.isBeanConstrained()).isTrue();
        assertThat(bean.hasConstraints()).isFalse();
    }

    @Test
    void constrainedPropertiesIncludeInheritedAndCascadedOnes() {
        assertThat(names(validator.getConstraintsForClass(Customer.class).getConstrainedProperties()))
            .containsExactlyInAnyOrder("name", "email", "friend", "nick", "code");
    }

    @Test
    void anUnknownOrUnconstrainedPropertyHasNoDescriptor() {
        BeanDescriptor bean = validator.getConstraintsForClass(Plain.class);
        assertThat(bean.getConstraintsForProperty("free")).isNull();
        assertThat(bean.getConstraintsForProperty("nope")).isNull();
        assertThatThrownBy(() -> bean.getConstraintsForProperty(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPropertyDescriptorDescribesTheProperty() {
        PropertyDescriptor email = validator.getConstraintsForClass(Customer.class).getConstraintsForProperty("email");
        assertThat(email.getPropertyName()).isEqualTo("email");
        assertThat(email.getElementClass()).isEqualTo(String.class);
        assertThat(email.hasConstraints()).isTrue();
        assertThat(email.isCascaded()).isFalse();
        assertThat(email.getGroupConversions()).isEmpty();
        assertThat(email.getConstrainedContainerElementTypes()).isEmpty();
        assertThat(annotationTypes(email.getConstraintDescriptors())).containsExactly(NotNull.class);
    }

    @Test
    void aCascadedPropertyIsDescribedEvenWithoutConstraint() {
        PropertyDescriptor friend = validator.getConstraintsForClass(Customer.class).getConstraintsForProperty("friend");
        assertThat(friend.isCascaded()).isTrue();
        assertThat(friend.hasConstraints()).isFalse();
        assertThat(friend.getConstraintDescriptors()).isEmpty();
    }

    @Test
    void constraintsOfTheWholeHierarchyAreReturnedByDefault() {
        PropertyDescriptor name = validator.getConstraintsForClass(Customer.class).getConstraintsForProperty("name");
        assertThat(annotationTypes(name.getConstraintDescriptors())).containsExactlyInAnyOrder(NotNull.class, Size.class);
        assertThat(name.getConstraintDescriptors()).hasSize(2);
    }

    @Test
    void theFinderRestrictsTheScope() {
        PropertyDescriptor name = validator.getConstraintsForClass(Customer.class).getConstraintsForProperty("name");
        assertThat(annotationTypes(name.findConstraints().lookingAt(Scope.LOCAL_ELEMENT).getConstraintDescriptors()))
            .containsExactly(NotNull.class);
        assertThat(name.findConstraints().lookingAt(Scope.HIERARCHY).getConstraintDescriptors()).hasSize(2);
    }

    @Test
    void theFinderRestrictsWhereTheConstraintIsHosted() {
        PropertyDescriptor name = validator.getConstraintsForClass(Customer.class).getConstraintsForProperty("name");
        assertThat(name.findConstraints().declaredOn(ElementType.METHOD).getConstraintDescriptors()).hasSize(2);
        assertThat(name.findConstraints().declaredOn(ElementType.FIELD).getConstraintDescriptors()).isEmpty();
        assertThat(name.findConstraints().declaredOn(ElementType.METHOD).lookingAt(Scope.LOCAL_ELEMENT)
            .unorderedAndMatchingGroups(Default.class).getConstraintDescriptors()).hasSize(1);
    }

    @Test
    void theFinderRestrictsGroupsWithInheritance() {
        BeanDescriptor customer = validator.getConstraintsForClass(Customer.class);
        PropertyDescriptor code = customer.getConstraintsForProperty("code");
        assertThat(code.findConstraints().unorderedAndMatchingGroups(Stricter.class).getConstraintDescriptors()).hasSize(1);
        assertThat(code.findConstraints().unorderedAndMatchingGroups(Strict.class).getConstraintDescriptors()).isEmpty();
        PropertyDescriptor nick = customer.getConstraintsForProperty("nick");
        assertThat(nick.findConstraints().unorderedAndMatchingGroups(Stricter.class).getConstraintDescriptors()).hasSize(1);
        assertThat(nick.findConstraints().unorderedAndMatchingGroups(Default.class).getConstraintDescriptors()).isEmpty();
        assertThat(nick.findConstraints().unorderedAndMatchingGroups(Default.class).hasConstraints()).isFalse();
        assertThat(nick.findConstraints().hasConstraints()).isTrue();
    }

    @Test
    void theBeanFinderSeesTheClassLevelConstraints() {
        BeanDescriptor bean = validator.getConstraintsForClass(Unconstrained.class);
        assertThat(bean.findConstraints().declaredOn(ElementType.TYPE).getConstraintDescriptors()).hasSize(1);
        assertThat(bean.findConstraints().declaredOn(ElementType.FIELD).getConstraintDescriptors()).isEmpty();
    }

    @Test
    void theDescriptorsAreImmutable() {
        PropertyDescriptor name = validator.getConstraintsForClass(Customer.class).getConstraintsForProperty("name");
        assertThatThrownBy(() -> name.getConstraintDescriptors().clear()).isInstanceOf(UnsupportedOperationException.class);
        BeanDescriptor customer = validator.getConstraintsForClass(Customer.class);
        assertThatThrownBy(() -> customer.getConstrainedProperties().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void methodsAndConstructorsAreNotDescribedYet() {
        BeanDescriptor customer = validator.getConstraintsForClass(Customer.class);
        assertThat(customer.getConstraintsForMethod("getName")).isNull();
        assertThat(customer.getConstrainedMethods(jakarta.validation.metadata.MethodType.GETTER)).isEmpty();
        assertThat(customer.getConstrainedConstructors()).isEmpty();
    }
}
