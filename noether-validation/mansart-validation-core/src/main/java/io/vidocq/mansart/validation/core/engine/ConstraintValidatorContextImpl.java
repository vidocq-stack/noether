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

import jakarta.validation.ClockProvider;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ValidationException;
import java.util.ArrayList;
import java.util.List;

/**
 * The context handed to {@code ConstraintValidator.isValid}. It collects the violations the validator
 * wants: the default one (unless disabled) and the custom ones built through
 * {@link #buildConstraintViolationWithTemplate(String)}.
 */
final class ConstraintValidatorContextImpl implements ConstraintValidatorContext {

    private final ClockProvider clockProvider;
    private final String defaultTemplate;
    private final PathImpl defaultPath;
    private final PathImpl basePath;
    private final ValidationRun.Slot pendingSlot;
    private boolean defaultDisabled;
    private final List<ViolationDraft> custom = new ArrayList<>();

    /**
     * @param basePath where custom nodes are added: the property path of a property constraint, the path of the
     *        bean for a class-level constraint
     * @param defaultPath where the default violation is reported: same as {@code basePath} for a property
     *        constraint, {@code basePath} plus a bean node for a class-level constraint
     */
    ConstraintValidatorContextImpl(ClockProvider clockProvider, String defaultTemplate, PathImpl basePath, PathImpl defaultPath,
            ValidationRun.Slot pendingSlot) {
        this.clockProvider = clockProvider;
        this.defaultTemplate = defaultTemplate;
        this.basePath = basePath;
        this.defaultPath = defaultPath;
        this.pendingSlot = pendingSlot;
    }

    @Override
    public void disableDefaultConstraintViolation() {
        defaultDisabled = true;
    }

    @Override
    public String getDefaultConstraintMessageTemplate() {
        return defaultTemplate;
    }

    @Override
    public ClockProvider getClockProvider() {
        return clockProvider;
    }

    @Override
    public ConstraintViolationBuilder buildConstraintViolationWithTemplate(String messageTemplate) {
        if (messageTemplate == null) {
            throw new IllegalArgumentException("The message template must not be null");
        }
        return new ViolationBuilderImpl(this, messageTemplate, basePath, pendingSlot);
    }

    /** Parameter nodes only make sense for the constraints of a method or constructor, which come with V4. */
    boolean isExecutableContext() {
        return false;
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
    }

    void add(ViolationDraft draft) {
        custom.add(draft);
    }

    /** What the validator asked for once it said "invalid": nothing at all is an error. */
    List<ViolationDraft> drafts() {
        List<ViolationDraft> all = new ArrayList<>();
        if (!defaultDisabled) {
            all.add(new ViolationDraft(defaultTemplate, defaultPath));
        }
        all.addAll(custom);
        if (all.isEmpty()) {
            throw new ValidationException("The validator reported a failure but disabled the default constraint violation "
                + "and did not add any other");
        }
        return all;
    }
}
