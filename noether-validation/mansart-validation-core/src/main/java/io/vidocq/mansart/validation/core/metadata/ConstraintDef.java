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
package io.vidocq.mansart.validation.core.metadata;

import jakarta.validation.ConstraintTarget;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.Payload;
import jakarta.validation.ValidationException;
import jakarta.validation.groups.Default;
import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ValidateUnwrappedValue;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.lang.classfile.AnnotationValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A constraint as declared on an element: the {@link ConstraintDescriptor} the API exposes, and what the engine reads. */
public final class ConstraintDef implements ConstraintDescriptor<Annotation> {

    private final Annotation annotation;
    private final AnnotationTypeInfo info;
    private final ConstraintTypeInfo typeInfo;
    private final Map<String, Object> attributes;
    private final Set<Class<?>> groups;
    private final Set<Class<? extends Payload>> payload;
    private final String messageTemplate;
    private final Set<ConstraintDescriptor<?>> composing;

    private ConstraintDef(Annotation annotation, ConstraintTypeInfo typeInfo) {
        this.annotation = annotation;
        this.typeInfo = typeInfo;
        this.info = AnnotationTypeInfo.of(annotation.annotationType());
        Map<String, Object> all = new LinkedHashMap<>(info.valuesOf(annotation));
        this.attributes = Collections.unmodifiableMap(all);
        this.messageTemplate = (String) all.get("message");
        Class<?>[] declared = (Class<?>[]) all.get("groups");
        Set<Class<?>> groupSet = new LinkedHashSet<>(List.of(declared));
        if (groupSet.isEmpty()) {
            groupSet.add(Default.class);
        }
        this.groups = Collections.unmodifiableSet(groupSet);
        @SuppressWarnings("unchecked")
        Class<? extends Payload>[] payloads = (Class<? extends Payload>[]) all.get("payload");
        this.payload = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(payloads)));
        this.composing = Collections.unmodifiableSet(new LinkedHashSet<>(typeInfo.composingConstraints()));
    }

    /**
     * The constraints among the raw annotations of a class file: constraint annotations themselves, and the
     * constraints a multi-valued container ({@code value()} returning constraint annotations) holds. A container
     * is read from the class file, never instantiated, so it may well be non-public. An annotation whose class
     * cannot be loaded (an optional dependency of the application) cannot be a constraint and is skipped; a
     * malformed constraint is not.
     */
    @SuppressWarnings("unchecked")
    public static List<ConstraintDef> fromRaw(List<java.lang.classfile.Annotation> raw, ClassLoader loader) {
        List<ConstraintDef> defs = new ArrayList<>();
        for (java.lang.classfile.Annotation annotation : raw) {
            Class<?> type;
            try {
                type = ClassFiles.load(annotation.classSymbol(), loader);
            } catch (ValidationException | LinkageError absent) {
                continue;
            }
            if (!type.isAnnotation()) {
                continue;
            }
            Class<? extends Annotation> annotationType = (Class<? extends Annotation>) type;
            Optional<ConstraintTypeInfo> typeInfo = ConstraintTypeInfo.of(annotationType);
            if (typeInfo.isPresent()) {
                defs.add(new ConstraintDef(AnnotationFactory.of(annotation, loader), typeInfo.get()));
            } else if (isContainer(annotationType)) {
                for (java.lang.classfile.AnnotationElement element : annotation.elements()) {
                    if (element.name().stringValue().equals("value") && element.value() instanceof AnnotationValue.OfArray array) {
                        List<java.lang.classfile.Annotation> contained = new ArrayList<>();
                        for (AnnotationValue value : array.values()) {
                            if (value instanceof AnnotationValue.OfAnnotation nested) {
                                contained.add(nested.annotation());
                            }
                        }
                        defs.addAll(fromRaw(contained, loader));
                    }
                }
            }
        }
        return defs;
    }

    /** A multi-valued container: {@code value()} returns an array of constraint annotations. */
    @SuppressWarnings("unchecked")
    private static boolean isContainer(Class<? extends Annotation> type) {
        AnnotationTypeInfo info = AnnotationTypeInfo.of(type);
        if (!info.hasMember("value")) {
            return false;
        }
        Class<?> value = info.member("value").type();
        return value.isArray() && value.getComponentType().isAnnotation()
            && ConstraintTypeInfo.of((Class<? extends Annotation>) value.getComponentType()).isPresent();
    }

    public Class<? extends Annotation> annotationType() {
        return annotation.annotationType();
    }

    @Override
    public Annotation getAnnotation() {
        return annotation;
    }

    @Override
    public String getMessageTemplate() {
        return messageTemplate;
    }

    @Override
    public Set<Class<?>> getGroups() {
        return groups;
    }

    @Override
    public Set<Class<? extends Payload>> getPayload() {
        return payload;
    }

    @Override
    public ConstraintTarget getValidationAppliesTo() {
        return attributes.get("validationAppliesTo") instanceof ConstraintTarget target ? target : null;
    }

    /** The validators declared by the annotation type (the built-in ones are added by the registry, see the engine). */
    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<Class<? extends ConstraintValidator<Annotation, ?>>> getConstraintValidatorClasses() {
        return (List) typeInfo.validatedBy();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Set<ConstraintDescriptor<?>> getComposingConstraints() {
        return composing;
    }

    @Override
    public boolean isReportAsSingleViolation() {
        return typeInfo.reportAsSingleViolation();
    }

    @Override
    public ValidateUnwrappedValue getValueUnwrapping() {
        return ValidateUnwrappedValue.DEFAULT;
    }

    @Override
    public <U> U unwrap(Class<U> type) {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new ValidationException("Type " + type.getName() + " is not supported by " + getClass().getName());
    }

    @Override
    public String toString() {
        return "ConstraintDef[" + annotation + "]";
    }
}
