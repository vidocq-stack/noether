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

import io.vidocq.mansart.validation.core.metadata.BeanMetadata;
import io.vidocq.mansart.validation.core.metadata.ConstraintDef;
import io.vidocq.mansart.validation.core.metadata.PropertyMetadata;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.MessageInterpolator;
import jakarta.validation.ValidationException;
import jakarta.validation.metadata.ConstraintDescriptor;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One call of {@code Validator.validate…}: the root bean, the violations found so far, and the beans
 * already validated for a group (a bean is validated once per group, which is what ends cycles).
 */
final class ValidationRun<T> {

    /** Where an element sits in the container being cascaded into; consumed by the first node added under it. */
    record Slot(Class<?> containerClass, Integer typeArgumentIndex, Integer index, Object key) {
    }

    /** A position in the object graph: the path so far, and the slot the next node will occupy. */
    record Cursor(PathImpl path, Slot slot) {
        Cursor add(NodeImpl node) {
            return slot == null ? new Cursor(path.add(node), null)
                : new Cursor(path.add(node.inContainer(slot.containerClass(), slot.typeArgumentIndex(), slot.index(), slot.key())), null);
        }

        Cursor into(Slot next) {
            return new Cursor(path, next);
        }
    }

    private final Components components;
    private final T rootBean;
    private final Class<T> rootBeanClass;
    private final Set<ConstraintViolation<T>> violations = new LinkedHashSet<>();
    private final Map<Object, Set<Class<?>>> processed = new IdentityHashMap<>();
    private final Map<Object, Map<String, Boolean>> reachable = new IdentityHashMap<>();

    ValidationRun(Components components, T rootBean, Class<T> rootBeanClass) {
        this.components = components;
        this.rootBean = rootBean;
        this.rootBeanClass = rootBeanClass;
    }

    Set<ConstraintViolation<T>> violations() {
        return violations;
    }

    // ---- the graph ---------------------------------------------------------------------------------

    void validateBean(Object bean, Class<?> group, Cursor at) {
        if (!processed.computeIfAbsent(bean, b -> new LinkedHashSet<>()).add(group)) {
            return;
        }
        List<BeanMetadata> hierarchy = BeanMetadata.hierarchy(bean.getClass());
        for (BeanMetadata metadata : hierarchy) {
            for (ConstraintDef constraint : metadata.classConstraints()) {
                if (appliesTo(constraint, group)) {
                    Cursor parent = at;
                    Cursor beanNode = at.add(NodeImpl.bean());
                    evaluate(constraint, bean, metadata.type(), parent.path(), beanNode.path(), bean);
                }
            }
        }
        for (BeanMetadata metadata : hierarchy) {
            for (PropertyMetadata property : metadata.properties()) {
                if (property.constraints().stream().anyMatch(c -> appliesTo(c, group)) && isReachable(bean, property, at)) {
                    Object value = property.get(bean);
                    Cursor atProperty = at.add(NodeImpl.property(property.name()));
                    for (ConstraintDef constraint : property.constraints()) {
                        if (appliesTo(constraint, group)) {
                            evaluate(constraint, value, property.valueType(), atProperty.path(), atProperty.path(), bean);
                        }
                    }
                }
            }
        }
        for (BeanMetadata metadata : hierarchy) {
            for (PropertyMetadata property : metadata.properties()) {
                if (property.cascaded() && isReachable(bean, property, at) && isCascadable(bean, property, at)) {
                    cascade(property.get(bean), property, group, at.add(NodeImpl.property(property.name())));
                }
            }
        }
    }

    /** {@code value} is the value of {@code property}; {@code at} is the path of the property. */
    private void cascade(Object value, PropertyMetadata property, Class<?> group, Cursor at) {
        if (value == null) {
            return;
        }
        Class<?> declared = property.valueType();
        switch (value) {
            case Map<?, ?> map -> {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() != null) {
                        validateElement(entry.getValue(), group, at.into(new Slot(declared, 1, null, entry.getKey())));
                    }
                }
            }
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) != null) {
                        validateElement(list.get(i), group, at.into(new Slot(declared, 0, i, null)));
                    }
                }
            }
            case Iterable<?> iterable -> {
                for (Object element : iterable) {
                    if (element != null) {
                        validateElement(element, group, at.into(new Slot(declared, 0, null, null)));
                    }
                }
            }
            case Object[] array -> {
                for (int i = 0; i < array.length; i++) {
                    if (array[i] != null) {
                        validateElement(array[i], group, at.into(new Slot(null, null, i, null)));
                    }
                }
            }
            default -> validateElement(value, group, at);
        }
    }

    private void validateElement(Object element, Class<?> group, Cursor at) {
        String name = element.getClass().getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")) {
            return;
        }
        validateBean(element, group, at);
    }

    static boolean appliesTo(ConstraintDef constraint, Class<?> group) {
        for (Class<?> declared : constraint.getGroups()) {
            if (declared.isAssignableFrom(group)) {
                return true;
            }
        }
        return false;
    }

    private boolean isReachable(Object bean, PropertyMetadata property, Cursor at) {
        String key = property.kind() + ":" + property.memberName();
        return reachable.computeIfAbsent(bean, b -> new java.util.HashMap<>()).computeIfAbsent(key, k ->
            components.traversableResolver().isReachable(bean, NodeImpl.property(property.name()), rootBeanClass, at.path(),
                elementType(property)));
    }

    private boolean isCascadable(Object bean, PropertyMetadata property, Cursor at) {
        return components.traversableResolver().isCascadable(bean, NodeImpl.property(property.name()), rootBeanClass, at.path(),
            elementType(property));
    }

    private static ElementType elementType(PropertyMetadata property) {
        return property.kind() == PropertyMetadata.Kind.FIELD ? ElementType.FIELD : ElementType.METHOD;
    }

    // ---- one constraint ----------------------------------------------------------------------------

    /**
     * Validates {@code value} against {@code constraint}.
     *
     * @param basePath where custom nodes are added
     * @param defaultPath where the default violation is reported
     */
    boolean evaluate(ConstraintDef constraint, Object value, Class<?> declaredType, PathImpl basePath, PathImpl defaultPath, Object leafBean) {
        List<ViolationDraft> mine = new ArrayList<>();
        List<Violation> fromComposing = new ArrayList<>();
        boolean composingValid = true;
        for (ConstraintDescriptor<?> composing : constraint.getComposingConstraints()) {
            List<Violation> sub = new ArrayList<>();
            composingValid &= evaluateInto(sub, (ConstraintDef) composing, value, declaredType, basePath, defaultPath);
            fromComposing.addAll(sub);
        }
        boolean mainValid = true;
        Class<? extends ConstraintValidator<?, ?>> validatorClass = ValidatorChoice.choose(constraint, declaredType);
        if (validatorClass == null && constraint.getComposingConstraints().isEmpty()) {
            throw new jakarta.validation.UnexpectedTypeException("No validator could be found for constraint '"
                + constraint.annotationType().getName() + "' validating type '" + declaredType.getName() + "'");
        }
        if (validatorClass != null) {
            ConstraintValidatorContextImpl context = new ConstraintValidatorContextImpl(components.clockProvider(),
                constraint.getMessageTemplate(), basePath, defaultPath);
            mainValid = runValidator(validatorClass, constraint, value, context);
            if (!mainValid) {
                mine.addAll(context.drafts());
            }
        }
        boolean valid = composingValid && mainValid;
        if (!valid) {
            if (constraint.isReportAsSingleViolation()) {
                report(constraint, value, leafBean, new ViolationDraft(constraint.getMessageTemplate(), defaultPath));
            } else {
                for (Violation v : fromComposing) {
                    report(v.constraint(), value, leafBean, v.draft());
                }
                for (ViolationDraft draft : mine) {
                    report(constraint, value, leafBean, draft);
                }
            }
        }
        return valid;
    }

    private record Violation(ConstraintDef constraint, ViolationDraft draft) {
    }

    /** Evaluates a composing constraint, but collects its violations instead of reporting them. */
    private boolean evaluateInto(List<Violation> into, ConstraintDef constraint, Object value, Class<?> declaredType,
            PathImpl basePath, PathImpl defaultPath) {
        ValidationRun<T> probe = new ValidationRun<>(components, rootBean, rootBeanClass);
        boolean valid = probe.evaluate(constraint, value, declaredType, basePath, defaultPath, null);
        for (ConstraintViolation<T> v : probe.violations) {
            into.add(new Violation((ConstraintDef) v.getConstraintDescriptor(), new ViolationDraft(v.getMessageTemplate(),
                (PathImpl) v.getPropertyPath())));
        }
        return valid;
    }

    private boolean runValidator(Class<? extends ConstraintValidator<?, ?>> validatorClass, ConstraintDef constraint, Object value,
            ConstraintValidatorContextImpl context) {
        ConstraintValidator<Annotation, Object> validator = instantiate(validatorClass);
        try {
            try {
                validator.initialize(constraint.getAnnotation());
            } catch (ValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ValidationException("Unable to initialize " + validatorClass.getName(), e);
            }
            try {
                return validator.isValid(value, context);
            } catch (ValidationException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new ValidationException("Unexpected exception during isValid call of " + validatorClass.getName(), e);
            }
        } finally {
            try {
                components.constraintValidatorFactory().releaseInstance(validator);
            } catch (RuntimeException ignored) {
                // releasing is best effort
            }
        }
    }

    @SuppressWarnings("unchecked")
    private ConstraintValidator<Annotation, Object> instantiate(Class<? extends ConstraintValidator<?, ?>> validatorClass) {
        ConstraintValidator<?, ?> instance;
        try {
            instance = components.constraintValidatorFactory().getInstance(validatorClass);
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ValidationException("Unable to instantiate " + validatorClass.getName(), e);
        }
        if (instance == null) {
            throw new ValidationException("The ConstraintValidatorFactory returned null for " + validatorClass.getName());
        }
        return (ConstraintValidator<Annotation, Object>) instance;
    }

    private void report(ConstraintDef constraint, Object value, Object leafBean, ViolationDraft draft) {
        String message;
        try {
            message = components.messageInterpolator().interpolate(draft.messageTemplate(), new MessageContext(constraint, value));
        } catch (ValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ValidationException("Unable to interpolate the message " + draft.messageTemplate(), e);
        }
        violations.add(new ConstraintViolationImpl<>(message, draft.messageTemplate(), rootBean, rootBeanClass, leafBean, null, null,
            draft.path(), value, constraint));
    }

    /** What a message interpolator learns about the violation being reported. */
    private record MessageContext(ConstraintDescriptor<?> descriptor, Object value) implements MessageInterpolator.Context {
        @Override
        public ConstraintDescriptor<?> getConstraintDescriptor() {
            return descriptor;
        }

        @Override
        public Object getValidatedValue() {
            return value;
        }

        @Override
        public <U> U unwrap(Class<U> type) {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
        }
    }

    static List<Class<?>> groupsOrDefault(Class<?>[] groups) {
        if (groups == null) {
            throw new IllegalArgumentException("The groups must not be null");
        }
        if (groups.length == 0) {
            return List.of(jakarta.validation.groups.Default.class);
        }
        List<Class<?>> list = new ArrayList<>(groups.length);
        for (Class<?> group : groups) {
            if (group == null) {
                throw new IllegalArgumentException("A group must not be null");
            }
            list.add(group);
        }
        return Collections.unmodifiableList(list);
    }
}
