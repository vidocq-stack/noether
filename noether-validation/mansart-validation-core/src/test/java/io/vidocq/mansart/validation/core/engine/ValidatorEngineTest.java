
package io.vidocq.mansart.validation.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.validation.core.MansartValidationProvider;
import io.vidocq.mansart.validation.core.engine.EngineFixtures.*;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.UnexpectedTypeException;
import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, chapters 3 and 6: the validation routine on beans, properties and values. */
class ValidatorEngineTest {

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

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static Set<String> typesOf(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
            .collect(Collectors.toSet());
    }

    // ---- basics --------------------------------------------------------------------------------------

    @Test
    void aValidBeanHasNoViolation() {
        assertThat(validator.validate(new Person("Alice"))).isNotEmpty(); // getComputed() is null
        assertThat(paths(validator.validate(new Person("Alice")))).containsExactly("computed");
    }

    @Test
    void fieldAndGetterConstraintsAreChecked() {
        Person person = new Person(null);
        person.nickname("ab");
        assertThat(paths(validator.validate(person))).containsExactlyInAnyOrder("first", "nickname", "computed");
    }

    @Test
    void theViolationDescribesWhatWentWrong() {
        Person person = new Person("Alice");
        person.title("Dr X");
        ConstraintViolation<Person> violation = validator.validate(person).stream()
            .filter(v -> v.getPropertyPath().toString().equals("title")).findFirst().orElseThrow();
        assertThat(violation.getRootBean()).isSameAs(person);
        assertThat(violation.getRootBeanClass()).isEqualTo(Person.class);
        assertThat(violation.getLeafBean()).isSameAs(person);
        assertThat(violation.getInvalidValue()).isEqualTo("Dr X");
        assertThat(violation.getMessageTemplate()).isEqualTo("must start with {value}");
        assertThat(violation.getConstraintDescriptor().getAnnotation()).isInstanceOf(StartsWith.class);
        assertThat(violation.getExecutableParameters()).isNull();
        assertThat(violation.getExecutableReturnValue()).isNull();
        Path.Node node = violation.getPropertyPath().iterator().next();
        assertThat(node.getKind()).isEqualTo(ElementKind.PROPERTY);
        assertThat(node.getName()).isEqualTo("title");
        assertThat(node.isInIterable()).isFalse();
    }

    @Test
    void primitivesAreBoxedAndChecked() {
        Person person = new Person("Alice");
        person.age = 10;
        assertThat(paths(validator.validate(person))).contains("age");
    }

    @Test
    void aClassLevelViolationHasABeanNodeAndCustomNodesReplaceIt() {
        Person person = new Person("A");
        Set<ConstraintViolation<Person>> violations = validator.validate(person);
        assertThat(paths(violations)).contains("first");
        ConstraintViolation<Person> custom = violations.stream().filter(v -> v.getMessage().equals("too short")).findFirst().orElseThrow();
        assertThat(custom.getPropertyPath().toString()).isEqualTo("first");
    }

    @Test
    void theDefaultClassLevelViolationHasAnUnnamedBeanNode() {
        Set<ConstraintViolation<WithClassConstraint>> violations = validator.validate(new WithClassConstraint());
        Path.Node node = violations.iterator().next().getPropertyPath().iterator().next();
        assertThat(node.getKind()).isEqualTo(ElementKind.BEAN);
        assertThat(node.getName()).isNull();
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEmpty();
    }

    @StartsWith("x")
    public static class WithClassConstraint implements CharSequence {
        @Override
        public int length() {
            return 1;
        }

        @Override
        public char charAt(int index) {
            return 'a';
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return "a";
        }

        @Override
        public String toString() {
            return "a";
        }
    }

    // ---- inheritance ---------------------------------------------------------------------------------

    @Test
    void constraintsOfSuperclassesAndInterfacesApply() {
        Set<ConstraintViolation<Employee>> violations = validator.validate(new Employee());
        assertThat(paths(violations)).containsExactlyInAnyOrder("code", "name", "computed");
        assertThat(violations.stream().filter(v -> v.getPropertyPath().toString().equals("name")).findFirst().orElseThrow()
            .getConstraintDescriptor().getAnnotation().annotationType()).isEqualTo(NotNull.class);
    }

    // ---- cascading -----------------------------------------------------------------------------------

    @Test
    void aCascadedBeanIsValidatedUnderItsPropertyPath() {
        Person person = new Person("Alice");
        person.home = new Address(null, "12345");
        assertThat(paths(validator.validate(person))).contains("home.street");
    }

    @Test
    void aListElementHasAnIndex() {
        Person person = new Person("Alice");
        person.others.add(new Address("ok", "12345"));
        person.others.add(new Address(null, "12345"));
        ConstraintViolation<Person> violation = validator.validate(person).stream()
            .filter(v -> v.getPropertyPath().toString().contains("street")).findFirst().orElseThrow();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("others[1].street");
        List<Path.Node> nodes = nodesOf(violation);
        assertThat(nodes.get(0).getName()).isEqualTo("others");
        assertThat(nodes.get(0).isInIterable()).isFalse();
        assertThat(nodes.get(1).getName()).isEqualTo("street");
        assertThat(nodes.get(1).isInIterable()).isTrue();
        assertThat(nodes.get(1).getIndex()).isEqualTo(1);
        assertThat(nodes.get(1).getKey()).isNull();
        assertThat(nodes.get(1).as(Path.PropertyNode.class).getContainerClass()).isEqualTo(List.class);
        assertThat(nodes.get(1).as(Path.PropertyNode.class).getTypeArgumentIndex()).isEqualTo(0);
    }

    @Test
    void aMapValueHasAKey() {
        Person person = new Person("Alice");
        person.byName.put("home", new Address(null, "12345"));
        ConstraintViolation<Person> violation = validator.validate(person).stream()
            .filter(v -> v.getPropertyPath().toString().contains("street")).findFirst().orElseThrow();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("byName[home].street");
        Path.Node leaf = nodesOf(violation).get(1);
        assertThat(leaf.getKey()).isEqualTo("home");
        assertThat(leaf.getIndex()).isNull();
        assertThat(leaf.as(Path.PropertyNode.class).getContainerClass()).isEqualTo(java.util.Map.class);
        assertThat(leaf.as(Path.PropertyNode.class).getTypeArgumentIndex()).isEqualTo(1);
    }

    @Test
    void aSetElementIsInAnIterableWithoutIndex() {
        Person person = new Person("Alice");
        person.unordered.add(new Address(null, "12345"));
        ConstraintViolation<Person> violation = validator.validate(person).stream()
            .filter(v -> v.getPropertyPath().toString().contains("street")).findFirst().orElseThrow();
        Path.Node leaf = nodesOf(violation).get(1);
        assertThat(leaf.isInIterable()).isTrue();
        assertThat(leaf.getIndex()).isNull();
        assertThat(leaf.getKey()).isNull();
    }

    @Test
    void anArrayElementHasAnIndex() {
        Person person = new Person("Alice");
        person.array = new Address[] {new Address("ok", "12345"), null, new Address(null, "12345")};
        ConstraintViolation<Person> violation = validator.validate(person).stream()
            .filter(v -> v.getPropertyPath().toString().contains("street")).findFirst().orElseThrow();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("array[2].street");
        assertThat(nodesOf(violation).get(1).getIndex()).isEqualTo(2);
    }

    @Test
    void aNullCascadedValueIsSkipped() {
        Person person = new Person("Alice");
        person.home = null;
        assertThat(paths(validator.validate(person))).doesNotContain("home");
    }

    @Test
    void aCycleEndsAndEachBeanIsReportedOnce() {
        Node a = new Node(null);
        Node b = new Node(null);
        a.next = b;
        b.next = a;
        a.children.add(a);
        Set<ConstraintViolation<Node>> violations = validator.validate(a);
        assertThat(violations).hasSize(2);
        assertThat(paths(violations)).containsExactlyInAnyOrder("label", "next.label");
    }

    @Test
    void theSameBeanReachedTwiceIsValidatedOnce() {
        Node shared = new Node(null);
        Node root = new Node("root");
        root.next = shared;
        root.children.add(shared);
        assertThat(validator.validate(root)).hasSize(1);
    }

    // ---- groups --------------------------------------------------------------------------------------

    @Test
    void theDefaultGroupIsUsedWhenNoneIsGiven() {
        assertThat(paths(validator.validate(new Grouped()))).containsExactly("byDefault");
        assertThat(paths(validator.validate(new Grouped(), Default.class))).containsExactly("byDefault");
    }

    @Test
    void validatingAGroupAlsoValidatesItsSuperGroups() {
        assertThat(paths(validator.validate(new Grouped(), Stricter.class))).containsExactlyInAnyOrder("strict", "stricter");
        assertThat(paths(validator.validate(new Grouped(), Strict.class))).containsExactly("strict");
    }

    @Test
    void severalGroupsAreAllValidated() {
        assertThat(paths(validator.validate(new Grouped(), Other.class, Strict.class))).containsExactlyInAnyOrder("other", "strict");
    }

    @Test
    void groupsOfCascadedBeansFollowTheRequestedGroup() {
        Person person = new Person("Alice");
        person.home = new Address("street", "1");
        assertThat(paths(validator.validate(person, Strict.class))).contains("home.zip");
        assertThat(paths(validator.validate(person))).doesNotContain("home.zip");
    }

    // ---- validateProperty, validateValue -------------------------------------------------------------

    @Test
    void validatePropertyChecksOnlyThatProperty() {
        Person person = new Person(null);
        assertThat(paths(validator.validateProperty(person, "first"))).containsExactly("first");
        assertThat(validator.validateProperty(person, "age")).isEmpty();
    }

    @Test
    void validatePropertyOnAGetterAndAnInheritedProperty() {
        assertThat(paths(validator.validateProperty(new Employee(), "name"))).containsExactly("name");
        assertThat(paths(validator.validateProperty(new Employee(), "computed"))).containsExactly("computed");
    }

    @Test
    void validatePropertyDoesNotCascade() {
        Person person = new Person("Alice");
        person.home = new Address(null, "12345");
        assertThat(validator.validateProperty(person, "home")).isEmpty();
    }

    @Test
    void validatePropertyRejectsAnUnknownPropertyButNotAnUnconstrainedOne() {
        assertThatThrownBy(() -> validator.validateProperty(new Person("A"), "nope")).isInstanceOf(IllegalArgumentException.class);
        assertThat(validator.validateProperty(new Address("a", "b"), "street")).isEmpty();
    }

    @Test
    void validateValueUsesTheGivenValue() {
        Set<ConstraintViolation<Person>> violations = validator.validateValue(Person.class, "first", null);
        assertThat(violations).hasSize(1);
        ConstraintViolation<Person> violation = violations.iterator().next();
        assertThat(violation.getRootBean()).isNull();
        assertThat(violation.getRootBeanClass()).isEqualTo(Person.class);
        assertThat(violation.getLeafBean()).isNull();
        assertThat(violation.getInvalidValue()).isNull();
        assertThat(validator.validateValue(Person.class, "first", "Alice")).isEmpty();
        assertThat(validator.validateValue(Person.class, "age", 3)).hasSize(1);
    }

    @Test
    void theArgumentsAreChecked() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(new Person("A"), (Class<?>[]) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validate(new Person("A"), (Class<?>) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateProperty(null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateProperty(new Person("A"), "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> validator.validateValue(null, "x", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anExceptionFromAGetterIsWrapped() {
        assertThatThrownBy(() -> validator.validateProperty(new BadGetter(), "value")).isInstanceOf(ValidationException.class);
    }

    // ---- validators ----------------------------------------------------------------------------------

    @Test
    void theValidatorIsInitializedWithTheAnnotation() {
        StartsWithValidator.initialized = 0;
        Person person = new Person("Alice");
        validator.validate(person);
        assertThat(StartsWithValidator.initialized).isPositive();
        person.title("Mr Y");
        assertThat(paths(validator.validate(person))).doesNotContain("title");
    }

    @Test
    void aRuntimeExceptionInIsValidIsWrapped() {
        assertThatThrownBy(() -> validator.validateProperty(new Composition(), "broken"))
            .isInstanceOf(ValidationException.class).hasRootCauseMessage("kaboom");
    }

    @Test
    void disablingTheDefaultViolationWithoutAddingOneIsAnError() {
        assertThatThrownBy(() -> validator.validateProperty(new Composition(), "silent")).isInstanceOf(ValidationException.class);
    }

    @Test
    void anAmbiguousValidatorResolutionIsAnError() {
        assertThatThrownBy(() -> validator.validateProperty(new Composition(), "ambiguous")).isInstanceOf(UnexpectedTypeException.class);
    }

    @Test
    void aConstraintWithoutValidatorForTheDeclaredTypeIsAnError() {
        assertThatThrownBy(() -> validator.validateProperty(new Composition(), "noValidatorForObject"))
            .isInstanceOf(UnexpectedTypeException.class);
    }

    @Test
    void everyConstraintOfAnElementIsReported() {
        Set<ConstraintViolation<Composition>> violations = validator.validateProperty(new Composition(), "twoConstraints");
        assertThat(typesOf(violations)).containsExactlyInAnyOrder("Size", "StartsWith");
    }

    @Test
    void aCustomConstraintValidatorFactoryIsUsed() {
        try (ValidatorFactory custom = Validation.byProvider(MansartValidationProvider.class).configure()
                .constraintValidatorFactory(new jakarta.validation.ConstraintValidatorFactory() {
                    @Override
                    public <T extends jakarta.validation.ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        return null;
                    }

                    @Override
                    public void releaseInstance(jakarta.validation.ConstraintValidator<?, ?> instance) {
                    }
                }).buildValidatorFactory()) {
            assertThatThrownBy(() -> custom.getValidator().validate(new Address(null, null))).isInstanceOf(ValidationException.class);
        }
    }

    // ---- traversable resolver ------------------------------------------------------------------------

    @Test
    void anUnreachablePropertyIsNotValidated() {
        try (ValidatorFactory custom = Validation.byProvider(MansartValidationProvider.class).configure()
                .traversableResolver(new jakarta.validation.TraversableResolver() {
                    @Override
                    public boolean isReachable(Object o, Path.Node n, Class<?> r, Path p, java.lang.annotation.ElementType t) {
                        return !n.getName().equals("first");
                    }

                    @Override
                    public boolean isCascadable(Object o, Path.Node n, Class<?> r, Path p, java.lang.annotation.ElementType t) {
                        return false;
                    }
                }).buildValidatorFactory()) {
            Person person = new Person(null);
            person.home = new Address(null, "12345");
            assertThat(paths(custom.getValidator().validate(person))).doesNotContain("first", "home.street");
        }
    }

    private static List<Path.Node> nodesOf(ConstraintViolation<?> violation) {
        List<Path.Node> nodes = new java.util.ArrayList<>();
        violation.getPropertyPath().forEach(nodes::add);
        return nodes;
    }

    @SuppressWarnings("unused")
    private static void unused(Size size, Min min) {
    }
}
