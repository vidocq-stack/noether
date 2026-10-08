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

import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ParameterNameProvider;
import jakarta.validation.TraversableResolver;
import jakarta.validation.ValidationException;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorContext;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.valueextraction.ValueExtractor;
import java.util.HashSet;
import java.util.Set;

/** The {@link ValidatorFactory}: immutable once built, thread-safe, closeable once and for all. */
final class MansartValidatorFactory implements ValidatorFactory {

    private final Components components;
    private volatile boolean closed;

    MansartValidatorFactory(Components components) {
        this.components = components;
    }

    @Override
    public Validator getValidator() {
        checkOpen();
        return new MansartValidator(components);
    }

    @Override
    public ValidatorContext usingContext() {
        checkOpen();
        return new Context();
    }

    @Override
    public MessageInterpolator getMessageInterpolator() {
        return components.messageInterpolator();
    }

    @Override
    public TraversableResolver getTraversableResolver() {
        return components.traversableResolver();
    }

    @Override
    public ConstraintValidatorFactory getConstraintValidatorFactory() {
        return components.constraintValidatorFactory();
    }

    @Override
    public ParameterNameProvider getParameterNameProvider() {
        return components.parameterNameProvider();
    }

    @Override
    public ClockProvider getClockProvider() {
        return components.clockProvider();
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
    }

    @Override
    public void close() {
        closed = true;
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The ValidatorFactory is closed");
        }
    }

    /** A {@link ValidatorContext} starting from the factory's components. */
    private final class Context implements ValidatorContext {

        private MessageInterpolator messageInterpolator = components.messageInterpolator();
        private TraversableResolver traversableResolver = components.traversableResolver();
        private ConstraintValidatorFactory constraintValidatorFactory = components.constraintValidatorFactory();
        private ParameterNameProvider parameterNameProvider = components.parameterNameProvider();
        private ClockProvider clockProvider = components.clockProvider();
        private final Set<ValueExtractor<?>> valueExtractors = new HashSet<>(components.valueExtractors());

        @Override
        public ValidatorContext messageInterpolator(MessageInterpolator value) {
            this.messageInterpolator = value != null ? value : components.messageInterpolator();
            return this;
        }

        @Override
        public ValidatorContext traversableResolver(TraversableResolver value) {
            this.traversableResolver = value != null ? value : components.traversableResolver();
            return this;
        }

        @Override
        public ValidatorContext constraintValidatorFactory(ConstraintValidatorFactory value) {
            this.constraintValidatorFactory = value != null ? value : components.constraintValidatorFactory();
            return this;
        }

        @Override
        public ValidatorContext parameterNameProvider(ParameterNameProvider value) {
            this.parameterNameProvider = value != null ? value : components.parameterNameProvider();
            return this;
        }

        @Override
        public ValidatorContext clockProvider(ClockProvider value) {
            this.clockProvider = value != null ? value : components.clockProvider();
            return this;
        }

        @Override
        public ValidatorContext addValueExtractor(ValueExtractor<?> extractor) {
            valueExtractors.add(extractor);
            return this;
        }

        @Override
        public Validator getValidator() {
            checkOpen();
            return new MansartValidator(new Components(messageInterpolator, traversableResolver,
                constraintValidatorFactory, parameterNameProvider, clockProvider, valueExtractors));
        }
    }
}
