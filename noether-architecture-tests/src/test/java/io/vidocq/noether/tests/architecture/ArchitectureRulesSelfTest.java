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
package io.vidocq.noether.tests.architecture;

import static io.vidocq.archrules.RuleAssertions.assertKept;
import static io.vidocq.noether.tests.architecture.NoetherClasses.SHIPPED;
import static io.vidocq.noether.tests.architecture.NoetherClasses.SHIPPED_AND_FIXTURES;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vidocq.archrules.ReflectionExceptions;
import io.vidocq.archrules.VidocqArchRules;
import io.vidocq.noether.jpa.cdi.archfixture.CdiReachingIntoMansart;
import io.vidocq.noether.jpa.core.archfixture.CoreReachingIntoJta;
import io.vidocq.noether.jpa.dialect.ArchFixtureDialectSpiReachingIntoCore;
import io.vidocq.noether.jpa.dialect.h2.archfixture.H2DialectReachingIntoCore;
import io.vidocq.noether.jpa.jta.archfixture.JtaReachingIntoCdi;
import io.vidocq.noether.jpa.pool.mansart.archfixture.PoolReachingIntoJta;
import io.vidocq.noether.jpa.processor.archfixture.ProcessorReachingIntoCdi;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Each architecture rule catches what it forbids: a rule that passes on the shipped classes proves nothing unless
 * it fails on a class that breaks it. A violation must name the fixture, so that a rule failing for another
 * reason (an empty layer, a class the rule does not select) does not count.
 */
class ArchitectureRulesSelfTest {

    /** The module and layer rules, each with a fixture of this module that breaks it. */
    static Stream<Arguments> moduleRuleBreaches() {
        return Stream.of(
                ruleWith(
                        "DIALECT_SPI_DEPENDENCIES",
                        ArchitectureRules.DIALECT_SPI_DEPENDENCIES,
                        ArchFixtureDialectSpiReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ArchFixtureDialectSpiReachingIntoCore.class),
                ruleWith("DIALECT_DEPENDENCIES", ArchitectureRules.DIALECT_DEPENDENCIES, H2DialectReachingIntoCore.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, H2DialectReachingIntoCore.class),
                ruleWith("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES, CoreReachingIntoJta.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, CoreReachingIntoJta.class),
                ruleWith("POOL_MANSART_DEPENDENCIES", ArchitectureRules.POOL_MANSART_DEPENDENCIES, PoolReachingIntoJta.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, PoolReachingIntoJta.class),
                ruleWith("PROCESSOR_DEPENDENCIES", ArchitectureRules.PROCESSOR_DEPENDENCIES, ProcessorReachingIntoCdi.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, ProcessorReachingIntoCdi.class),
                ruleWith("JTA_DEPENDENCIES", ArchitectureRules.JTA_DEPENDENCIES, JtaReachingIntoCdi.class),
                ruleWith("LAYERS", ArchitectureRules.LAYERS, JtaReachingIntoCdi.class),
                ruleWith("CDI_DEPENDENCIES", ArchitectureRules.CDI_DEPENDENCIES, CdiReachingIntoMansart.class),
                ruleWith(
                        "MANSART_THROUGH_ITS_ADAPTER",
                        ArchitectureRules.MANSART_THROUGH_ITS_ADAPTER,
                        CdiReachingIntoMansart.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("moduleRuleBreaches")
    void eachModuleRuleCatchesAFixtureAmongTheShippedClasses(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, SHIPPED_AND_FIXTURES, fixture);
    }

    @Test
    void theFoundationalBricksRuleCatchesADependencyOnAForbiddenPackage() {
        // None of Chappe, Champollion or Vauban is on the class path of this module (adding one would draw an edge
        // between bricks in the dependency graph), so the same rule is set to a package a fixture does reach.
        ArchRule rule = VidocqArchRules.neverDependOn(
                new String[] {"io.vidocq.noether.."}, new String[] {"io.vidocq.noether.jpa.jta.."}, "self-test");
        assertNamesTheFixture(rule, SHIPPED_AND_FIXTURES, CoreReachingIntoJta.class);
        String real = ArchitectureRules.FOUNDATIONAL_BRICKS.getDescription();
        assertTrue(
                real.contains("io.vidocq.chappe..")
                        && real.contains("io.vidocq.champollion..")
                        && real.contains("io.vidocq.vauban.."),
                "the real rule names Chappe, Champollion and Vauban");
    }

    /** The code rules, each with a fixture below that breaks it. */
    static Stream<Arguments> codeRuleBreaches() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpAClassByName.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAMethodReflectively.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsAnAnnotationReflectively.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, CopiesAnArrayReflectively.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesAFixedThreadPool.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, SchedulesOnAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, BuildsAThreadPoolExecutor.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, StartsAPlatformThread.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, CreatesAThread.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, KeepsAThreadLocal.class));
    }

    @ParameterizedTest(name = "{0} catches {1}")
    @MethodSource("codeRuleBreaches")
    void eachCodeRuleCatchesAFixture(ArchRule rule, Class<?> fixture) {
        assertNamesTheFixture(rule, new ClassFileImporter().importClasses(fixture), fixture);
    }

    /** What the code rules let through: look-alikes of the breaches that keep the rule. */
    static Stream<Arguments> codeRuleLookAlikes() {
        return Stream.of(
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, LooksUpACharsetByName.class),
                ruleWith("NO_REFLECTION", ArchitectureRules.NO_REFLECTION, ReadsTheModifiersOfAClass.class),
                ruleWith("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS, UsesVirtualThreads.class),
                ruleWith("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL, UsesVirtualThreads.class));
    }

    @ParameterizedTest(name = "{0} keeps {1}")
    @MethodSource("codeRuleLookAlikes")
    void eachCodeRuleKeepsALookAlike(ArchRule rule, Class<?> fixture) {
        assertKept(rule, fixture);
    }

    @Test
    void theReflectiveFallbacksAreShippedClassesThatStillReflect() {
        // A stale entry would exempt a class that no longer exists or no longer reflects, or let a new class take
        // its name: the same rule, with no exemption, must report each listed class.
        ArchRule unexempted = VidocqArchRules.noReflection(ReflectionExceptions.none(), "self-test");
        String report = String.join("\n", unexempted.evaluate(SHIPPED).getFailureReport().getDetails());
        for (String allowed : ArchitectureRules.REFLECTIVE_FALLBACKS.keySet()) {
            assertTrue(SHIPPED.contain(allowed), () -> allowed + " is not a shipped class");
            assertTrue(report.contains(allowed), () -> allowed + " no longer uses reflection: drop its entry");
        }
    }

    private static Arguments ruleWith(String name, ArchRule rule, Class<?> fixture) {
        return Arguments.of(named(name, rule), fixture);
    }

    private static void assertNamesTheFixture(ArchRule rule, JavaClasses classes, Class<?> fixture) {
        List<String> details = rule.evaluate(classes).getFailureReport().getDetails();
        assertTrue(
                details.stream().anyMatch(detail -> detail.contains(fixture.getName())),
                () -> "expected a violation by " + fixture.getName() + " of: " + rule.getDescription() + ", got "
                        + details);
    }

    static final class LooksUpAClassByName {
        Class<?> find(String name) throws ClassNotFoundException {
            return Class.forName(name);
        }
    }

    static final class ReadsAMethodReflectively {
        Object method() throws NoSuchMethodException {
            return String.class.getDeclaredMethod("length");
        }
    }

    static final class ReadsAnAnnotationReflectively {
        boolean deprecated(Class<?> type) {
            return type.isAnnotationPresent(Deprecated.class);
        }
    }

    static final class CopiesAnArrayReflectively {
        Object copy(Object array) {
            return java.lang.reflect.Array.newInstance(array.getClass().getComponentType(), 1);
        }
    }

    static final class LooksUpACharsetByName {
        Charset charset(String name) {
            return Charset.forName(name);
        }
    }

    static final class ReadsTheModifiersOfAClass {
        boolean isFinal(Class<?> type) {
            return java.lang.reflect.Modifier.isFinal(type.getModifiers());
        }
    }

    static final class UsesAFixedThreadPool {
        ExecutorService pool() {
            return Executors.newFixedThreadPool(4);
        }
    }

    static final class SchedulesOnAPlatformThread {
        ScheduledExecutorService reaper() {
            return Executors.newSingleThreadScheduledExecutor();
        }
    }

    static final class BuildsAThreadPoolExecutor {
        ExecutorService pool() {
            return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }
    }

    static final class StartsAPlatformThread {
        Thread start(Runnable task) {
            return Thread.ofPlatform().start(task);
        }
    }

    static final class CreatesAThread {
        Thread create(Runnable task) {
            return new Thread(task);
        }
    }

    static final class UsesVirtualThreads {
        ExecutorService executor() {
            return Executors.newVirtualThreadPerTaskExecutor();
        }
    }

    static final class KeepsAThreadLocal {
        private static final ThreadLocal<StringBuilder> BUFFER = ThreadLocal.withInitial(StringBuilder::new);

        StringBuilder buffer() {
            return BUFFER.get();
        }
    }
}
