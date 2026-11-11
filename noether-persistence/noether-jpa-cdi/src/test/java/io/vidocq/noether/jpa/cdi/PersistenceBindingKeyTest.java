package io.vidocq.noether.jpa.cdi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The qualifier value that binds a persistence-context injection point to its synthetic bean (BUG-20261018-03): two
 * different configurations must never share it, or the second one is silently dropped.
 */
class PersistenceBindingKeyTest {

    @Test
    void propertyMapsThatPrintAlikeKeepDistinctBindings() {
        String commaInValue = NoetherPersistenceExtension.contextBinding("orders", "TRANSACTION", "SYNCHRONIZED",
            Map.of("x", "a, y=b"));
        String twoProperties = NoetherPersistenceExtension.contextBinding("orders", "TRANSACTION", "SYNCHRONIZED",
            Map.of("x", "a", "y", "b"));

        assertThat(commaInValue).isNotEqualTo(twoProperties);
    }

    @Test
    void separatorsInsideAUnitNameOrAValueKeepDistinctBindings() {
        String separatorsInUnit = NoetherPersistenceExtension.contextBinding("u:TRANSACTION:SYNCHRONIZED:{k=v}",
            "TRANSACTION", "SYNCHRONIZED", Map.of());
        String separatorsInValue = NoetherPersistenceExtension.contextBinding("u", "TRANSACTION", "SYNCHRONIZED",
            Map.of("k", "v}:TRANSACTION:SYNCHRONIZED:{"));

        assertThat(separatorsInUnit).isNotEqualTo(separatorsInValue);
    }

    @Test
    void theSameConfigurationAlwaysHasTheSameBinding() {
        assertThat(NoetherPersistenceExtension.contextBinding("orders", "EXTENDED", "UNSYNCHRONIZED",
                Map.of("b", "2", "a", "1")))
            .isEqualTo(NoetherPersistenceExtension.contextBinding("orders", "EXTENDED", "UNSYNCHRONIZED",
                Map.of("a", "1", "b", "2")));
    }
}
