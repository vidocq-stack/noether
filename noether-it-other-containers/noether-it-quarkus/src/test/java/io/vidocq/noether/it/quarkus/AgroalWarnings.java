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
package io.vidocq.noether.it.quarkus;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** The warnings Agroal's pool logs while a test listens. Quarkus' log manager backs {@code java.util.logging}. */
final class AgroalWarnings extends Handler {
    /** Agroal's pool logs under this category. */
    private final Logger agroal = Logger.getLogger("io.agroal.pool");
    private final SimpleFormatter formatter = new SimpleFormatter();
    final List<String> messages = new CopyOnWriteArrayList<>();

    void listen() {
        agroal.addHandler(this);
    }

    void stop() {
        agroal.removeHandler(this);
    }

    @Override
    public void publish(LogRecord logRecord) {
        if (logRecord.getLevel().intValue() >= Level.WARNING.intValue()) {
            messages.add(formatter.formatMessage(logRecord));
        }
    }

    @Override
    public void flush() {
        // the messages are kept in memory
    }

    @Override
    public void close() {
        // nothing to release
    }
}
