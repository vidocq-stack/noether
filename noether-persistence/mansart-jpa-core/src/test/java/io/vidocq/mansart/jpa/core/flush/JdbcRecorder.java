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
package io.vidocq.mansart.jpa.core.flush;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

/** Records the JDBC calls a flush makes (a test double: production code uses no dynamic proxy). */
final class JdbcRecorder {

    final List<String> calls = new ArrayList<>();

    Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            handler(connection, call -> {
                if (call.startsWith("prepareStatement")) {
                    return true;
                }
                return false;
            }));
    }

    private InvocationHandler handler(Object target, java.util.function.Predicate<String> wrapResult) {
        return (proxy, method, args) -> {
            String name = method.getName();
            if (target instanceof PreparedStatement && (name.equals("addBatch") || name.equals("executeBatch")
                    || name.equals("executeUpdate"))) {
                calls.add(name);
            }
            if (target instanceof Connection && name.equals("prepareStatement")) {
                calls.add("prepare " + args[0]);
            }
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (result instanceof PreparedStatement statement && wrapResult.test(name)) {
                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                    handler(statement, n -> false));
            }
            return result;
        };
    }

    long count(String call) {
        return calls.stream().filter(c -> c.equals(call)).count();
    }

    List<String> prepared() {
        return calls.stream().filter(c -> c.startsWith("prepare ")).map(c -> c.substring("prepare ".length())).toList();
    }
}
