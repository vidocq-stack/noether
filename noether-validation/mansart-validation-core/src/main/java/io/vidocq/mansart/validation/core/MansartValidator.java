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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.executable.ExecutableValidator;
import jakarta.validation.metadata.BeanDescriptor;
import java.util.Set;

/** The {@link Validator}. Bootstrap only: the validation engine arrives with milestone V2. */
final class MansartValidator implements Validator {

    private final Components components;

    MansartValidator(Components components) {
        this.components = components;
    }

    Components components() {
        return components;
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validate(T object, Class<?>... groups) {
        throw notYetImplemented("V2");
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateProperty(T object, String propertyName, Class<?>... groups) {
        throw notYetImplemented("V2");
    }

    @Override
    public <T> Set<ConstraintViolation<T>> validateValue(Class<T> beanType, String propertyName, Object value,
            Class<?>... groups) {
        throw notYetImplemented("V2");
    }

    @Override
    public BeanDescriptor getConstraintsForClass(Class<?> clazz) {
        throw notYetImplemented("V2");
    }

    @Override
    public ExecutableValidator forExecutables() {
        throw notYetImplemented("V4");
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
    }

    private static UnsupportedOperationException notYetImplemented(String milestone) {
        return new UnsupportedOperationException("Mansart Validation does not validate yet (milestone " + milestone + ")");
    }
}
