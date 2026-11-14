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

import static io.vidocq.noether.tests.architecture.NoetherClasses.SHIPPED;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules on the classes Noether ships: the dialect SPI and the two dialects, noether-jpa-core,
 * -pool-mansart, -processor, -jta and -cdi, test classes left out (Vidocq/vidocq#289). A failure names each class
 * and the dependency or call that breaks the rule.
 */
class ArchitectureRulesTest {

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(SHIPPED.contain("io.vidocq.noether.jpa.dialect.Dialect"), "noether-jpa-dialect-spi imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.noether.jpa.dialect.h2.H2DialectFactory"), "noether-jpa-dialect-h2 imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.noether.jpa.dialect.postgresql.PostgreSQLDialectFactory"),
                "noether-jpa-dialect-postgresql imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.noether.jpa.core.access.AccessGenerator"), "noether-jpa-core imported");
        assertTrue(
                SHIPPED.contain("io.vidocq.noether.jpa.processor.NoetherJpaProcessor"), "noether-jpa-processor imported");
        assertTrue(SHIPPED.contain("io.vidocq.noether.jpa.cdi.UnitAnnotations"), "noether-jpa-cdi imported");
        assertTrue(
                SHIPPED.stream().anyMatch(type -> type.getPackageName().startsWith("io.vidocq.noether.jpa.jta")),
                "noether-jpa-jta imported");
        assertTrue(
                SHIPPED.stream().anyMatch(type -> type.getPackageName().startsWith("io.vidocq.noether.jpa.pool.mansart")),
                "noether-jpa-pool-mansart imported");
    }

    static Stream<Named<ArchRule>> rules() {
        return Stream.of(
                named("DIALECT_SPI_DEPENDENCIES", ArchitectureRules.DIALECT_SPI_DEPENDENCIES),
                named("DIALECT_DEPENDENCIES", ArchitectureRules.DIALECT_DEPENDENCIES),
                named("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES),
                named("POOL_MANSART_DEPENDENCIES", ArchitectureRules.POOL_MANSART_DEPENDENCIES),
                named("PROCESSOR_DEPENDENCIES", ArchitectureRules.PROCESSOR_DEPENDENCIES),
                named("JTA_DEPENDENCIES", ArchitectureRules.JTA_DEPENDENCIES),
                named("CDI_DEPENDENCIES", ArchitectureRules.CDI_DEPENDENCIES),
                named("LAYERS", ArchitectureRules.LAYERS),
                named("FOUNDATIONAL_BRICKS", ArchitectureRules.FOUNDATIONAL_BRICKS),
                named("MANSART_THROUGH_ITS_ADAPTER", ArchitectureRules.MANSART_THROUGH_ITS_ADAPTER),
                named("NO_REFLECTION", ArchitectureRules.NO_REFLECTION),
                named("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS),
                named("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theShippedClassesKeepTheRule(ArchRule rule) {
        rule.check(SHIPPED);
    }
}
