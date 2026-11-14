package io.vidocq.noether.jpa.cdi;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitInjection;
import jakarta.enterprise.inject.spi.DeploymentException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The beans of each unit are built when this extension runs, at build in a container that freezes it: the start fails
 * when the units differ from those (Vidocq/vidocq#551, decision 3).
 */
class UnitsStartCheckTest {

    private static final UnitInjection LEDGER = new UnitInjection("ledger", List.of(), null, true);
    private static final UnitInjection ARCHIVE = new UnitInjection("archive", List.of("org.acme.Archived"), null, true);

    @Test
    void theSameUnitsPass() {
        assertThat(UnitsStartCheck.problems(built(LEDGER, ARCHIVE), List.of(ARCHIVE, LEDGER))).isEmpty();
    }

    @Test
    void aUnitDeclaredOnlyAtStartIsAProblem() {
        assertThat(UnitsStartCheck.problems(built(LEDGER), List.of(LEDGER, ARCHIVE)))
            .containsExactly("persistence unit archive is declared at start, not when the beans were built");
    }

    @Test
    void aUnitGoneAtStartIsAProblem() {
        assertThat(UnitsStartCheck.problems(built(LEDGER, ARCHIVE), List.of(LEDGER)))
            .containsExactly("persistence unit archive was declared when the beans were built, not at start");
    }

    @Test
    void aUnitWhoseBeansWouldDifferIsAProblem() {
        UnitInjection requestScoped = new UnitInjection("ledger", List.of("org.acme.Ledger"),
            "jakarta.enterprise.context.RequestScoped", false);

        assertThat(UnitsStartCheck.problems(built(LEDGER), List.of(requestScoped))).containsExactly(
            "persistence unit ledger was JTA, @Default, transaction scoped when the beans were built, and is"
                + " RESOURCE_LOCAL, @org.acme.Ledger, @jakarta.enterprise.context.RequestScoped at start");
    }

    @Test
    void theFailureListsEveryProblemAndAsksForARebuild() {
        DeploymentException failure = UnitsStartCheck.failure(List.of("first", "second"));

        assertThat(failure).hasMessageStartingWith("The persistence units differ from those the beans were built from:"
                + " first; second.")
            .hasMessageContaining("rebuild the application");
    }

    private static String[] built(UnitInjection... units) {
        return List.of(units).stream().map(UnitsStartCheck::describe).toArray(String[]::new);
    }
}
