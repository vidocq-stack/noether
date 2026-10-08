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
package io.vidocq.mansart.jpa.dialect;

/**
 * Creates the {@link Dialect} of a database. Found with {@link java.util.ServiceLoader}: a dialect module declares
 * {@code provides io.vidocq.mansart.jpa.dialect.DialectFactory with …}.
 */
public interface DialectFactory {

    /** The name of the dialects it creates. */
    String name();

    /** Whether it serves the database whose JDBC metadata reports {@code productName}. */
    boolean supports(String productName);

    /** The dialect of the version of the database that the JDBC metadata reports. */
    Dialect create(int majorVersion, int minorVersion);
}
