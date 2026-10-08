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
package io.vidocq.mansart.validation.core.engine;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.util.Arrays;
import java.util.Objects;

/** A constraint violation. Two violations are equal when they report the same thing in the same place. */
public final class ConstraintViolationImpl<T> implements ConstraintViolation<T> {

    private final String message;
    private final String messageTemplate;
    private final T rootBean;
    private final Class<T> rootBeanClass;
    private final Object leafBean;
    private final Object[] executableParameters;
    private final Object executableReturnValue;
    private final Path propertyPath;
    private final Object invalidValue;
    private final ConstraintDescriptor<?> descriptor;

    public ConstraintViolationImpl(String message, String messageTemplate, T rootBean, Class<T> rootBeanClass, Object leafBean,
            Object[] executableParameters, Object executableReturnValue, Path propertyPath, Object invalidValue,
            ConstraintDescriptor<?> descriptor) {
        this.message = message;
        this.messageTemplate = messageTemplate;
        this.rootBean = rootBean;
        this.rootBeanClass = rootBeanClass;
        this.leafBean = leafBean;
        this.executableParameters = executableParameters;
        this.executableReturnValue = executableReturnValue;
        this.propertyPath = propertyPath;
        this.invalidValue = invalidValue;
        this.descriptor = descriptor;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getMessageTemplate() {
        return messageTemplate;
    }

    @Override
    public T getRootBean() {
        return rootBean;
    }

    @Override
    public Class<T> getRootBeanClass() {
        return rootBeanClass;
    }

    @Override
    public Object getLeafBean() {
        return leafBean;
    }

    @Override
    public Object[] getExecutableParameters() {
        return executableParameters == null ? null : executableParameters.clone();
    }

    @Override
    public Object getExecutableReturnValue() {
        return executableReturnValue;
    }

    @Override
    public Path getPropertyPath() {
        return propertyPath;
    }

    @Override
    public Object getInvalidValue() {
        return invalidValue;
    }

    @Override
    public ConstraintDescriptor<?> getConstraintDescriptor() {
        return descriptor;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConstraintViolationImpl<?> that && Objects.equals(message, that.message)
            && Objects.equals(messageTemplate, that.messageTemplate) && rootBean == that.rootBean
            && Objects.equals(rootBeanClass, that.rootBeanClass) && leafBean == that.leafBean
            && Arrays.equals(executableParameters, that.executableParameters)
            && Objects.equals(executableReturnValue, that.executableReturnValue)
            && Objects.equals(propertyPath, that.propertyPath) && Objects.equals(invalidValue, that.invalidValue)
            && Objects.equals(descriptor, that.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(message, messageTemplate, System.identityHashCode(rootBean), rootBeanClass,
            System.identityHashCode(leafBean), propertyPath, descriptor);
    }

    @Override
    public String toString() {
        return "ConstraintViolationImpl{rootBeanClass=" + (rootBeanClass == null ? null : rootBeanClass.getName())
            + ", propertyPath=" + propertyPath + ", message='" + message + "', messageTemplate='" + messageTemplate + "'}";
    }
}
