package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitInjection;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.inject.Qualifier;
import jakarta.inject.Scope;
import jakarta.transaction.TransactionScoped;
import java.lang.annotation.Annotation;
import java.util.List;

/**
 * The qualifiers and the scope a unit of {@code persistence.xml} names (Jakarta Persistence 3.2, §8.2.1.5 and
 * §8.2.1.6), loaded before the unit's beans exist. A name the deployment cannot load, or that is not a qualifier or a
 * scope, fails the deployment and names the unit, rather than giving a bean nobody can inject.
 */
final class UnitAnnotations {

    private UnitAnnotations() {
    }

    /** The qualifiers of the unit's beans, in the order of its {@code <qualifier>} elements; empty means {@code @Default}. */
    static List<Class<? extends Annotation>> qualifiers(UnitInjection unit, ClassLoader loader) {
        return unit.qualifierNames().stream().<Class<? extends Annotation>>map(name -> {
            Class<? extends Annotation> qualifier = annotation(unit, "qualifier", name, loader);
            if (!qualifier.isAnnotationPresent(Qualifier.class)) {
                throw refused(unit, "qualifier", name, "is not annotated @" + Qualifier.class.getName());
            }
            return qualifier;
        }).toList();
    }

    /** The scope of the unit's {@code EntityManager} bean: its {@code <scope>}, or {@code @TransactionScoped} (Platform §13.12.1). */
    static Class<? extends Annotation> scope(UnitInjection unit, ClassLoader loader) {
        if (unit.scopeName() == null) return TransactionScoped.class;
        Class<? extends Annotation> scope = annotation(unit, "scope", unit.scopeName(), loader);
        if (!scope.isAnnotationPresent(Scope.class) && !scope.isAnnotationPresent(NormalScope.class)) {
            throw refused(unit, "scope", unit.scopeName(),
                "is annotated neither @" + Scope.class.getName() + " nor @" + NormalScope.class.getName());
        }
        return scope;
    }

    private static Class<? extends Annotation> annotation(UnitInjection unit, String element, String name, ClassLoader loader) {
        Class<?> type;
        try {
            type = Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError failure) {
            throw refused(unit, element, name, "which the deployment cannot load", failure);
        }
        if (!type.isAnnotation()) throw refused(unit, element, name, "is not an annotation");
        return type.asSubclass(Annotation.class);
    }

    private static DefinitionException refused(UnitInjection unit, String element, String name, String reason) {
        return new DefinitionException(message(unit, element, name, "which " + reason));
    }

    private static DefinitionException refused(UnitInjection unit, String element, String name, String reason, Throwable cause) {
        return new DefinitionException(message(unit, element, name, reason), cause);
    }

    private static String message(UnitInjection unit, String element, String name, String reason) {
        return "Persistence unit '" + unit.name() + "' names the " + element + " " + name + " in persistence.xml, " + reason;
    }
}
