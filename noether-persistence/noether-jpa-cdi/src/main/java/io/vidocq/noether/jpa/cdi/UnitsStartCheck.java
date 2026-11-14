package io.vidocq.noether.jpa.cdi;

import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors;
import io.vidocq.noether.jpa.core.spi.PersistenceDescriptors.UnitInjection;
import jakarta.enterprise.event.Startup;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticObserver;
import jakarta.enterprise.inject.spi.DeploymentException;
import jakarta.enterprise.inject.spi.EventContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fails the start when the persistence units differ from those the beans were built from (Vidocq/vidocq#551). The
 * extension builds the beans of each unit when it runs: at build, in a container that freezes it ({@code vidocq:generate}).
 * A unit source that declares another unit, qualifier, scope or transaction type at start would leave those beans
 * behind, so the start compares the units it finds with those the extension saw, handed over as parameters.
 *
 * <p>Registered only when the extension saw a unit: Vauban's annotation processor runs it with no resource of the
 * application, hence no {@code persistence.xml}, and the units of the start then have nothing to be compared with.
 */
public final class UnitsStartCheck implements SyntheticObserver<Startup> {

    /** The parameter that lists the units the beans were built from, as {@link #describe} writes them. */
    static final String UNITS = "units";

    /** Early, before the application's observers; not 0, which a Vauban without BUG-20261113-11 reads as none. */
    static final int PRIORITY = 1;

    private static final String SEPARATOR = "|";

    @Override
    public void observe(EventContext<Startup> event, Parameters params) {
        List<String> problems = problems(params.get(UNITS, String[].class, new String[0]),
            PersistenceDescriptors.injections(PersistenceFactoryCreator.loader()));
        if (!problems.isEmpty()) {
            throw failure(problems);
        }
    }

    /** A unit as its beans depend on it: its name, transaction type, qualifiers and scope. */
    static String describe(UnitInjection unit) {
        return String.join(SEPARATOR, unit.name(), unit.jta() ? "JTA" : "RESOURCE_LOCAL",
            String.join(",", unit.qualifierNames()), unit.scopeName() == null ? "" : unit.scopeName());
    }

    /** What differs between the units the beans were built from and those of the start, one sentence each. */
    static List<String> problems(String[] built, List<UnitInjection> atStart) {
        Map<String, String> before = byName(List.of(built));
        Map<String, String> after = byName(atStart.stream().map(UnitsStartCheck::describe).toList());
        Set<String> names = new LinkedHashSet<>(before.keySet());
        names.addAll(after.keySet());
        List<String> problems = new ArrayList<>();
        for (String name : names) {
            String then = before.get(name);
            String now = after.get(name);
            if (then == null) {
                problems.add("persistence unit " + name + " is declared at start, not when the beans were built");
            } else if (now == null) {
                problems.add("persistence unit " + name + " was declared when the beans were built, not at start");
            } else if (!then.equals(now)) {
                problems.add("persistence unit " + name + " was " + readable(then) + " when the beans were built, and is "
                    + readable(now) + " at start");
            }
        }
        return problems;
    }

    static DeploymentException failure(List<String> problems) {
        return new DeploymentException("The persistence units differ from those the beans were built from: "
            + String.join("; ", problems) + ". The beans of each unit are built when the CDI extension of Noether runs,"
            + " at build when the container freezes it (vidocq:generate): rebuild the application, or set what changed"
            + " where the build reads it");
    }

    private static Map<String, String> byName(List<String> units) {
        Map<String, String> byName = new LinkedHashMap<>();
        for (String unit : units) {
            byName.put(unit.substring(0, unit.indexOf(SEPARATOR)), unit);
        }
        return byName;
    }

    /** {@code JTA, @Default, transaction scoped} for {@code ledger|JTA||}. */
    private static String readable(String unit) {
        String[] parts = unit.split("\\|", -1);
        String qualifiers = parts[2].isEmpty() ? "@Default" : "@" + parts[2].replace(",", ", @");
        String scope = parts[3].isEmpty() ? "transaction scoped" : "@" + parts[3];
        return parts[1] + ", " + qualifiers + ", " + scope;
    }
}
