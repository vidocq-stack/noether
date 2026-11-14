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
package io.vidocq.noether.jpa.core.spi;

import java.util.List;

/**
 * A source of persistence units besides the {@code META-INF/persistence.xml} files: a runtime declares units from its
 * own configuration, the Vidocq runtime from its {@code vidocq.persistence."<unit>".*} keys for instance. Noether knows
 * no key of any runtime: it merges what the sources return with the units of the files, wherever it looks for units
 * (the provider, {@link PersistenceDescriptors}).
 *
 * <p>Found with {@link java.util.ServiceLoader}, in the layer of Noether's module and then through the class loader of
 * the application ({@code provides io.vidocq.noether.jpa.core.spi.PersistenceUnitSource with ...}, or a
 * {@code META-INF/services} entry on the class path). A source is asked each time the units are looked for: at build,
 * when a container freezes its beans, and at start, when the provider creates a factory. What it returns may differ
 * between the two; keeping them consistent is the runtime's business.
 */
public interface PersistenceUnitSource {

    /**
     * The units this source declares or overrides for the application of {@code loader}.
     *
     * @param loader the class loader of the application, which the units' classes and roots belong to
     */
    List<PersistenceUnitDeclaration> units(ClassLoader loader);
}
