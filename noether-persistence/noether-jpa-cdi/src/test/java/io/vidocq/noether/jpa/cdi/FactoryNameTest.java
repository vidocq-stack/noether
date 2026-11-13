package io.vidocq.noether.jpa.cdi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The name of a unit's factory bean (BUG-20261113-01): it depends on the unit alone, never on the other units of the
 * deployment, and two units never share it.
 */
class FactoryNameTest {

    @Test
    void unitsThatOnlyDifferInTheirPunctuationKeepDistinctNames() {
        List<String> names = List.of("a.b", "a_b", "a-b", "a b", "ab", "a_2e_b").stream()
            .map(NoetherPersistenceExtension::factoryName).toList();

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void aNameHasNoDot() {
        // A dotted name would clash with any bean named after its first segment.
        assertThat(NoetherPersistenceExtension.factoryName("com.acme.orders")).doesNotContain(".");
    }

    @Test
    void aNameKeepsTheLettersAndDigitsOfItsUnit() {
        assertThat(NoetherPersistenceExtension.factoryName("orders2")).endsWith("orders2");
    }
}
