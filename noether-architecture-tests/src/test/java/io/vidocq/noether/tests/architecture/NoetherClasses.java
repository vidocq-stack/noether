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

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;

/** The classes the architecture rules are applied to, imported once per test run. */
final class NoetherClasses {

    /**
     * The root package of the shipped modules: noether-jpa-dialect-spi, -h2 and -postgresql, noether-jpa-core,
     * -pool-mansart, -processor, -jta and -cdi. The test, bench, TCK and integration-test modules are not on the
     * class path of this module, and noether-validation, a test stopgap, ships nothing.
     */
    private static final String SHIPPED_PACKAGE = "io.vidocq.noether.jpa";

    private static final ImportOption NO_TESTS = new ImportOption.DoNotIncludeTests();

    /** The classes Noether ships, test classes and fixtures left out. */
    static final JavaClasses SHIPPED = new ClassFileImporter()
            .withImportOption(NO_TESTS)
            .withImportOption(location -> !isAFixture(location))
            .importPackages(SHIPPED_PACKAGE);

    /**
     * The shipped classes plus the fixtures of this module: those of an {@code archfixture} package and those
     * named {@code ArchFixture*} (for a module whose package has no sub-package to hold them), each of which sits
     * in a shipped package and breaks one rule there.
     */
    static final JavaClasses SHIPPED_AND_FIXTURES = new ClassFileImporter()
            .withImportOption(location -> NO_TESTS.includes(location) || isAFixture(location))
            .importPackages(SHIPPED_PACKAGE);

    private NoetherClasses() {
        // Constants only.
    }

    private static boolean isAFixture(Location location) {
        return location.contains("/archfixture/") || location.contains("/ArchFixture");
    }
}
