package io.vidocq.noether.jpa.cdi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitInjection;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.inject.Named;
import jakarta.inject.Qualifier;
import jakarta.transaction.TransactionScoped;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Jakarta Persistence 3.2, §8.2.1.5 and §8.2.1.6: {@code <qualifier>} names a CDI qualifier and {@code <scope>} a CDI
 * scope. The extension loads both before it adds the unit's beans, and refuses a name that is neither, naming the unit.
 */
class UnitAnnotationsTest {

    @Qualifier
    @Retention(RetentionPolicy.RUNTIME)
    @interface Tagged {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Plain {
    }

    private static final ClassLoader LOADER = UnitAnnotationsTest.class.getClassLoader();

    private static UnitInjection unit(List<String> qualifiers, String scope) {
        return new UnitInjection("ledger", qualifiers, scope, true);
    }

    @Test
    void theQualifiersOfAUnitAreTheAnnotationsItsDescriptorNames() {
        var unit = unit(List.of(Tagged.class.getName(), Named.class.getName()), null);

        assertThat(UnitAnnotations.qualifiers(unit, LOADER)).containsExactly(Tagged.class, Named.class);
    }

    @Test
    void aUnitWithoutQualifierHasNone() {
        assertThat(UnitAnnotations.qualifiers(unit(List.of(), null), LOADER)).isEmpty();
    }

    @Test
    void theScopeOfAUnitIsTheOneItsDescriptorNamesOrTransactionScoped() {
        assertThat(UnitAnnotations.scope(unit(List.of(), RequestScoped.class.getName()), LOADER)).isEqualTo(RequestScoped.class);
        assertThat(UnitAnnotations.scope(unit(List.of(), Dependent.class.getName()), LOADER)).isEqualTo(Dependent.class);
        assertThat(UnitAnnotations.scope(unit(List.of(), null), LOADER)).isEqualTo(TransactionScoped.class);
    }

    @Test
    void aQualifierTheDeploymentCannotLoadFailsNamingTheUnit() {
        var missing = unit(List.of("com.acme.Missing"), null);

        assertThatThrownBy(() -> UnitAnnotations.qualifiers(missing, LOADER))
            .isInstanceOf(DefinitionException.class)
            .hasMessageContaining("'ledger'")
            .hasMessageContaining("com.acme.Missing")
            .hasMessageContaining("cannot load");
    }

    @Test
    void aClassThatIsNotAQualifierIsRefused() {
        var notAnAnnotation = unit(List.of(String.class.getName()), null);
        var notAQualifier = unit(List.of(Plain.class.getName()), null);

        assertThatThrownBy(() -> UnitAnnotations.qualifiers(notAnAnnotation, LOADER))
            .isInstanceOf(DefinitionException.class)
            .hasMessageContaining("'ledger'")
            .hasMessageContaining("java.lang.String")
            .hasMessageContaining("not an annotation");
        assertThatThrownBy(() -> UnitAnnotations.qualifiers(notAQualifier, LOADER))
            .isInstanceOf(DefinitionException.class)
            .hasMessageContaining(Plain.class.getName())
            .hasMessageContaining("@jakarta.inject.Qualifier");
    }

    @Test
    void anAnnotationThatIsNotAScopeIsRefused() {
        var notAScope = unit(List.of(), Tagged.class.getName());
        var missing = unit(List.of(), "com.acme.Missing");

        assertThatThrownBy(() -> UnitAnnotations.scope(notAScope, LOADER))
            .isInstanceOf(DefinitionException.class)
            .hasMessageContaining("'ledger'")
            .hasMessageContaining(Tagged.class.getName())
            .hasMessageContaining("@jakarta.inject.Scope")
            .hasMessageContaining("@jakarta.enterprise.context.NormalScope");
        assertThatThrownBy(() -> UnitAnnotations.scope(missing, LOADER))
            .isInstanceOf(DefinitionException.class)
            .hasMessageContaining("com.acme.Missing")
            .hasMessageContaining("cannot load");
    }

    /**
     * A unit keeps one factory: the one its injection points already share, the one an unnamed injection point reaches
     * when the deployment has a single unit, otherwise its own.
     */
    @Test
    void aDeclaredUnitSharesTheFactoryOfItsInjectionPoints() {
        assertThat(NoetherPersistenceExtension.factoryKey("ledger", Set.of("ledger", ""), 1)).isEqualTo("ledger");
        assertThat(NoetherPersistenceExtension.factoryKey("ledger", Set.of(""), 1)).isEmpty();
        assertThat(NoetherPersistenceExtension.factoryKey("ledger", Set.of(""), 2)).isEqualTo("ledger");
        assertThat(NoetherPersistenceExtension.factoryKey("ledger", Set.of(), 1)).isEqualTo("ledger");
    }
}
