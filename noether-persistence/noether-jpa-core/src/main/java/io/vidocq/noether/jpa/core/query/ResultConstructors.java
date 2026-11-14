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
package io.vidocq.noether.jpa.core.query;

import io.vidocq.noether.jpa.core.mapping.MappedUnit;
import io.vidocq.noether.jpa.core.spi.ResultConstructorAccess;
import jakarta.persistence.PersistenceException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The public constructors of a class that a query builds without it being an entity: {@code SELECT NEW} (§4.8.2) and
 * {@code @ConstructorResult} (§3.11.15). Those noether-jpa-processor generated, for a class a named query or a
 * result-set mapping of the sources names, come first: they call the constructors from the class's own package
 * (BUG-20261114-04). Otherwise the constructors are found reflectively — once per class, kept as long as the class (a
 * {@link ClassValue}), and called through method handles for every row: on the module path, the class's package must
 * be exported to this module, as for any provider. The generated constructors are in declaration order, the reflective
 * ones in the order {@link Class#getConstructors()} gives, in practice the same.
 */
final class ResultConstructors {

    private static final ClassValue<ResultConstructors> OF = new ClassValue<>() {
        @Override
        protected ResultConstructors computeValue(Class<?> type) {
            return new ResultConstructors(type);
        }
    };

    private final List<Candidate> candidates;

    private ResultConstructors(Class<?> type) {
        ResultConstructors.class.getModule().addReads(type.getModule());
        List<Candidate> found = new ArrayList<>();
        for (Constructor<?> constructor : type.getConstructors()) {
            found.add(Candidate.of(type, constructor));
        }
        this.candidates = List.copyOf(found);
    }

    private ResultConstructors(ResultConstructorAccess generated) {
        List<Candidate> found = new ArrayList<>();
        for (int i = 0; i < generated.constructors().size(); i++) {
            found.add(new Candidate(generated.type(), generated.constructors().get(i).toArray(Class<?>[]::new), generated, i));
        }
        this.candidates = List.copyOf(found);
    }

    /** The constructors of {@code type}: those generated for it, else those read reflectively on first use. */
    static ResultConstructors of(Class<?> type, MappedUnit unit) {
        return unit.resultConstructors(type).map(ResultConstructors::of).orElseGet(() -> of(type));
    }

    /** The constructors of {@code type}, read reflectively on first use. */
    static ResultConstructors of(Class<?> type) {
        return OF.get(type);
    }

    /** The constructors noether-jpa-processor generated. */
    static ResultConstructors of(ResultConstructorAccess generated) {
        return new ResultConstructors(generated);
    }

    /** The public constructors, in declaration order, or in the order {@link Class#getConstructors()} lists them. */
    List<Candidate> candidates() {
        return candidates;
    }

    /**
     * One public constructor: its parameter types (shared, not to be modified) and a handle typed
     * {@code (Object[])Object}, or the reason it cannot be called, reported when a query picks it.
     */
    static final class Candidate {
        private final Class<?> type;
        private final Class<?>[] parameters;
        private final MethodHandle handle;
        private final ReflectiveOperationException denied;
        private final ResultConstructorAccess generated;
        private final int index;

        private Candidate(Class<?> type, Class<?>[] parameters, MethodHandle handle, ReflectiveOperationException denied) {
            this.type = type;
            this.parameters = parameters;
            this.handle = handle;
            this.denied = denied;
            this.generated = null;
            this.index = -1;
        }

        private Candidate(Class<?> type, Class<?>[] parameters, ResultConstructorAccess generated, int index) {
            this.type = type;
            this.parameters = parameters;
            this.handle = null;
            this.denied = null;
            this.generated = generated;
            this.index = index;
        }

        private static Candidate of(Class<?> type, Constructor<?> constructor) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (Modifier.isAbstract(type.getModifiers())) {
                return new Candidate(type, parameters, null, new InstantiationException(type.getName() + " is abstract"));
            }
            try {
                MethodHandle handle = MethodHandles.lookup().unreflectConstructor(constructor)
                    .asFixedArity()
                    .asSpreader(Object[].class, parameters.length)
                    .asType(MethodType.methodType(Object.class, Object[].class));
                return new Candidate(type, parameters, handle, null);
            } catch (IllegalAccessException e) {
                return new Candidate(type, parameters, null, e);
            }
        }

        /** The parameter types of the constructor; the array is shared. */
        Class<?>[] parameters() {
            return parameters;
        }

        /**
         * A new instance built from {@code arguments}, already converted to the parameter types (a primitive parameter
         * takes its boxed value, never {@code null}).
         */
        Object newInstance(Object[] arguments) {
            if (generated != null) {
                try {
                    return generated.construct(index, arguments);
                } catch (RuntimeException e) {
                    throw new PersistenceException("The constructor of " + type.getName() + " failed: " + e, e);
                }
            }
            if (handle == null) {
                throw new PersistenceException("The constructor of " + type.getName() + " cannot be called: "
                    + denied.getMessage(), denied);
            }
            try {
                // without a cast, a signature-polymorphic call returns Object (JLS 15.12.3): the type of the handle
                return handle.invokeExact(arguments);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                throw new PersistenceException("The constructor of " + type.getName() + " failed: " + e, e);
            }
        }
    }
}
