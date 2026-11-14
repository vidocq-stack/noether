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
package io.vidocq.noether.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.noether.jpa.core.spi.ResultConstructorAccess;
import jakarta.persistence.PersistenceException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The constructors of a {@code SELECT NEW} (§4.8.2) or {@code @ConstructorResult} (§3.11.15) class: read once per class,
 * not once per row, and called through method handles rather than {@code Constructor.newInstance}.
 */
class ResultConstructorsTest {

    @Test
    void aResultClassIsReadOnce() {
        assertThat(ResultConstructors.of(Summary.class)).isSameAs(ResultConstructors.of(Summary.class));
        assertThat(ResultConstructors.of(Summary.class).candidates())
            .isSameAs(ResultConstructors.of(Summary.class).candidates());
    }

    @Test
    void theCandidatesAreThePublicConstructorsOnly() {
        assertThat(ResultConstructors.of(Pair.class).candidates())
            .extracting(candidate -> List.of(candidate.parameters()))
            .containsExactlyInAnyOrder(List.of(String.class), List.of(String.class, int.class));
    }

    @Test
    void aCandidateBuildsTheResultWithItsArguments() {
        ResultConstructors.Candidate canonical = ResultConstructors.of(Summary.class).candidates().getFirst();

        assertThat(canonical.newInstance(new Object[] {"Vidocq", 1775L})).isEqualTo(new Summary("Vidocq", 1775L));
        assertThat(canonical.newInstance(new Object[] {"Lacenaire", 1800L})).isEqualTo(new Summary("Lacenaire", 1800L));
    }

    @Test
    void aPrimitiveParameterTakesItsBoxedArgument() {
        ResultConstructors.Candidate twoArguments = ResultConstructors.of(Pair.class).candidates().stream()
            .filter(candidate -> candidate.parameters().length == 2).findFirst().orElseThrow();

        assertThat(twoArguments.newInstance(new Object[] {"seats", 12})).isEqualTo(new Pair("seats", 12));
    }

    @Test
    void theExceptionOfTheConstructorIsTheCauseOfThePersistenceException() {
        ResultConstructors.Candidate failing = ResultConstructors.of(Failing.class).candidates().getFirst();

        assertThatThrownBy(() -> failing.newInstance(new Object[] {"no witness"}))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining(Failing.class.getName())
            .cause().isInstanceOf(IllegalStateException.class).hasMessage("no witness");
    }

    @Test
    void theGeneratedConstructorsAreTheCandidatesInTheirOrder() {
        ResultConstructors generated = ResultConstructors.of(new PairConstructors());

        assertThat(generated.candidates()).extracting(candidate -> List.of(candidate.parameters()))
            .containsExactly(List.of(String.class), List.of(String.class, int.class));
        assertThat(generated.candidates().get(1).newInstance(new Object[] {"seats", 12})).isEqualTo(new Pair("seats", 12));
    }

    @Test
    void theExceptionOfAGeneratedConstructorIsTheCauseOfThePersistenceException() {
        ResultConstructors.Candidate failing = ResultConstructors.of(new FailingConstructors()).candidates().getFirst();
        Object[] arguments = {"no witness"};

        assertThatThrownBy(() -> failing.newInstance(arguments))
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining(Failing.class.getName())
            .cause().isInstanceOf(IllegalStateException.class).hasMessage("no witness");
    }

    /** What noether-jpa-processor generates for {@link Pair}: its public constructors, in declaration order. */
    private static final class PairConstructors extends ResultConstructorAccess {
        PairConstructors() {
            super(Pair.class, List.of(List.of(String.class), List.of(String.class, int.class)));
        }

        @Override
        public Object construct(int constructor, Object[] arguments) {
            return switch (constructor) {
                case 0 -> new Pair((String) arguments[0]);
                case 1 -> new Pair((String) arguments[0], (Integer) arguments[1]);
                default -> throw new IndexOutOfBoundsException(constructor);
            };
        }
    }

    /** What noether-jpa-processor generates for {@link Failing}. */
    private static final class FailingConstructors extends ResultConstructorAccess {
        FailingConstructors() {
            super(Failing.class, List.of(List.of(String.class)));
        }

        @Override
        public Object construct(int constructor, Object[] arguments) {
            return new Failing((String) arguments[0]);
        }
    }

    /** Two public constructors and a private one, which a query never calls. */
    public static final class Pair {
        private final String name;
        private final int count;

        public Pair(String name) {
            this(name, 0);
        }

        public Pair(String name, int count) {
            this.name = name;
            this.count = count;
        }

        @SuppressWarnings("unused")
        private Pair(int count) {
            this("", count);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Pair pair && pair.name.equals(name) && pair.count == count;
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + count;
        }

        @Override
        public String toString() {
            return name + "=" + count;
        }
    }

    /** A constructor that refuses its arguments. */
    public static final class Failing {
        public Failing(String reason) {
            throw new IllegalStateException(reason);
        }
    }
}
