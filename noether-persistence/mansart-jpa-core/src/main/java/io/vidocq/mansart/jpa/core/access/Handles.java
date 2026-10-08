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
package io.vidocq.mansart.jpa.core.access;

import jakarta.persistence.PersistenceException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Method handles on the classes of a persistence unit (entities, embeddables, converters, enums), obtained through a
 * private lookup: the package of each class must be opened to this module, which is the only requirement Mansart puts
 * on application modules.
 */
public final class Handles {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private Handles() {
    }

    /** A private lookup in {@code type}, or a {@link PersistenceException} telling how to open its package. */
    static MethodHandles.Lookup lookupIn(Class<?> type) {
        Module module = Handles.class.getModule();
        module.addReads(type.getModule());
        try {
            return MethodHandles.privateLookupIn(type, LOOKUP);
        } catch (IllegalAccessException e) {
            throw new PersistenceException("Mansart cannot access the class " + type.getName() + ": compile it with "
                + "mansart-jpa-processor and declare the access it generates ('provides "
                + "io.vidocq.mansart.jpa.core.spi.ManagedAccessProvider with " + type.getPackageName() + "._MansartJpaAccess;'), "
                + "or open its package to " + module.getName() + " ('opens " + type.getPackageName() + " to " + module.getName()
                + ";' in its module-info.java)", e);
        }
    }

    /** The lookup of this package, which defines the generated hidden classes. */
    static MethodHandles.Lookup own() {
        return LOOKUP;
    }

    /** A new instance of {@code type} through its no-arg constructor (a converter, §3.9). */
    public static <T> T newInstance(Class<T> type) {
        try {
            return type.cast(lookupIn(type).findConstructor(type, MethodType.methodType(void.class)).invoke());
        } catch (PersistenceException | Error e) { // an Error is not an instantiation failure
            throw e;
        } catch (Throwable e) {
            throw new PersistenceException("Mansart cannot instantiate " + type.getName() + " through its no-arg constructor", e);
        }
    }

    /** A getter of the field {@code name} of {@code owner}, typed {@code (Object)Object}. */
    public static MethodHandle getter(Class<?> owner, String name, Class<?> type) {
        try {
            return lookupIn(owner).findGetter(owner, name, type).asType(MethodType.methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new PersistenceException("Mansart cannot read the field " + name + " of " + owner.getName(), e);
        }
    }
}
