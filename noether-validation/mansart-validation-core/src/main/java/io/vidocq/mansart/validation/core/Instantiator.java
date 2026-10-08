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
package io.vidocq.mansart.validation.core;

import jakarta.validation.ValidationException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Creates components from their class, through method handles rather than {@code Constructor.newInstance}.
 * A public class in a package exported to this module needs nothing; any other class must be reachable through
 * {@code privateLookupIn}, i.e. its package must be opened to this module (always true on the class path).
 */
final class Instantiator {

    private Instantiator() {
    }

    /** Loads {@code className} with the context class loader, then this module's class loader. */
    static <T> Class<? extends T> load(String className, Class<T> type) {
        Class<?> loaded;
        try {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            loaded = context != null ? Class.forName(className, false, context)
                : Class.forName(className, false, Instantiator.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            try {
                loaded = Class.forName(className, false, Instantiator.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError second) {
                throw new ValidationException("Unable to load class " + className, e);
            }
        }
        if (!type.isAssignableFrom(loaded)) {
            throw new ValidationException(className + " is not a " + type.getName());
        }
        return loaded.asSubclass(type);
    }

    static <T> T create(String className, Class<T> type) {
        return create(load(className, type));
    }

    /** Calls the no-arg constructor; every failure, including a runtime exception in it, is a {@link ValidationException}. */
    static <T> T create(Class<? extends T> clazz) {
        MethodHandle constructor = constructor(clazz);
        try {
            return clazz.cast(constructor.invoke());
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new ValidationException("Unable to instantiate " + clazz.getName(), e);
        }
    }

    private static MethodHandle constructor(Class<?> clazz) {
        MethodType noArg = MethodType.methodType(void.class);
        try {
            return MethodHandles.lookup().findConstructor(clazz, noArg);
        } catch (NoSuchMethodException e) {
            throw new ValidationException(clazz.getName() + " has no no-arg constructor", e);
        } catch (IllegalAccessException notPublic) {
            try {
                return MethodHandles.privateLookupIn(clazz, MethodHandles.lookup()).findConstructor(clazz, noArg);
            } catch (NoSuchMethodException e) {
                throw new ValidationException(clazz.getName() + " has no no-arg constructor", e);
            } catch (IllegalAccessException e) {
                throw new ValidationException(clazz.getName() + " is not accessible: its package must be opened to "
                    + Instantiator.class.getModule().getName(), e);
            }
        }
    }
}
