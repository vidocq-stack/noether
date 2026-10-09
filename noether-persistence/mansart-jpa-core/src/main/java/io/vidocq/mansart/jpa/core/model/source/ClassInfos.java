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
package io.vidocq.mansart.jpa.core.model.source;

import java.util.List;
import java.util.Optional;

/**
 * Where the managed classes are read from, as annotation data: their class files at bootstrap
 * ({@link ClassFileSource}), or the elements of the compilation for the annotation processor.
 */
public interface ClassInfos {

    /** The class named {@code binaryName} ({@code com.acme.Outer$Inner}), if this source can read it. */
    Optional<ClassInfo> read(String binaryName);

    /** The managed classes the unit's mapping files declare, beyond the listed or discovered ones (§8.2.1.6.2). */
    default List<String> mappedClassNames() {
        return List.of();
    }

    /** The default entity listeners of the unit, in invocation order (§3.6.2, {@code persistence-unit-defaults}). */
    default List<String> defaultListeners() {
        return List.of();
    }

    /** Whether every table-, schema- and column-level identifier of the unit is delimited (§2.15, §12.2.1.3). */
    default boolean delimitedIdentifiers() {
        return false;
    }

    /**
     * The unit-level metadata of the mapping files, as the annotations they stand for: the named queries, stored
     * procedures, result set mappings and generators written under {@code entity-mappings} (§12.2.2). Each one replaces
     * an annotated one of the same name.
     */
    default List<AnnotationInfo> unitAnnotations() {
        return List.of();
    }
}
