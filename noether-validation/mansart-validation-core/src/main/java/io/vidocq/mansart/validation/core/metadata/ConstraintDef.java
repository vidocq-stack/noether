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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        this.composing = Collections.unmodifiableSet(new LinkedHashSet<>(from(typeInfo.composing())));
    }

    /**
     * The constraints among {@code annotations}: constraint annotations themselves, and the constraints
     * a multi-valued container ({@code value()} returning constraint annotations) holds. Anything else is skipped.
     */
    public static List<ConstraintDef> from(List<? extends Annotation> annotations) {
        List<ConstraintDef> defs = new ArrayList<>();
        for (Annotation annotation : annotations) {
            ConstraintTypeInfo typeInfo = ConstraintTypeInfo.of(annotation.annotationType()).orElse(null);
            if (typeInfo != null) {
                defs.add(new ConstraintDef(annotation, typeInfo));
            } else {
                defs.addAll(unwrapContainer(annotation));
            }
        }
        return defs;
    }

    @SuppressWarnings("unchecked")
    private static List<ConstraintDef> unwrapContainer(Annotation container) {
        AnnotationTypeInfo info = AnnotationTypeInfo.of(container.annotationType());
        if (!info.hasMember("value")) {
            return List.of();
        }
        Class<?> valueType = info.member("value").type();
        if (!valueType.isArray() || !valueType.getComponentType().isAnnotation()
                || ConstraintTypeInfo.of((Class<? extends Annotation>) valueType.getComponentType()).isEmpty()) {
            return List.of();
        }
        Annotation[] contained = (Annotation[]) info.read(info.members().indexOf(info.member("value")), container);
        return from(List.of(contained));
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
        Object target = attributes.get("validationAppliesTo");
        return target instanceof ConstraintTarget t ? t : ConstraintTarget.IMPLICIT;
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
