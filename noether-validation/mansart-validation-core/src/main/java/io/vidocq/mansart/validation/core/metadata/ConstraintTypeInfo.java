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

import jakarta.validation.ConstraintDefinitionException;
import jakarta.validation.ConstraintValidator;
import java.lang.annotation.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.AnnotationValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the class file of a constraint annotation type says about it: its validators, its composing
 * constraints, whether it reports a single violation. An annotation type is a constraint if it is
 * annotated with {@code @Constraint}; a malformed one is a {@link ConstraintDefinitionException}.
 */
public final class ConstraintTypeInfo {

    private static final String CONSTRAINT = "Ljakarta/validation/Constraint;";
    private static final String REPORT_AS_SINGLE_VIOLATION = "Ljakarta/validation/ReportAsSingleViolation;";
    /** Meta-annotations that are never composing constraints. */
    private static final List<String> NOT_COMPOSING = List.of(CONSTRAINT, REPORT_AS_SINGLE_VIOLATION,
        "Ljakarta/validation/OverridesAttribute;", "Ljakarta/validation/OverridesAttribute$List;",
        "Ljava/lang/annotation/");

    private static final ClassValue<Optional<ConstraintTypeInfo>> CACHE = new ClassValue<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected Optional<ConstraintTypeInfo> computeValue(Class<?> type) {
            return ConstraintTypeInfo.read((Class<? extends Annotation>) type);
        }
    };

    private final Class<? extends Annotation> type;
    private final List<Class<? extends ConstraintValidator<?, ?>>> validatedBy;
    private final List<java.lang.classfile.Annotation> rawComposing;
    private volatile List<ConstraintDef> composing;
    private final boolean reportAsSingleViolation;

    private ConstraintTypeInfo(Class<? extends Annotation> type, List<Class<? extends ConstraintValidator<?, ?>>> validatedBy,
            List<java.lang.classfile.Annotation> rawComposing, boolean reportAsSingleViolation) {
        this.type = type;
        this.validatedBy = validatedBy;
        this.rawComposing = rawComposing;
        this.reportAsSingleViolation = reportAsSingleViolation;
    }

    /** Empty if {@code type} is not a constraint annotation. */
    public static Optional<ConstraintTypeInfo> of(Class<? extends Annotation> type) {
        return CACHE.get(type);
    }

    public Class<? extends Annotation> type() {
        return type;
    }

    /** The validators declared by {@code @Constraint(validatedBy)}; the built-in ones are not in this list. */
    public List<Class<? extends ConstraintValidator<?, ?>>> validatedBy() {
        return validatedBy;
    }

    /** The composing constraints, as written on the annotation type; read once, when first needed. */
    public List<ConstraintDef> composingConstraints() {
        List<ConstraintDef> result = composing;
        if (result == null) {
            result = List.copyOf(ConstraintDef.fromRaw(rawComposing, type.getClassLoader()));
            composing = result;
        }
        return result;
    }

    public boolean reportAsSingleViolation() {
        return reportAsSingleViolation;
    }

    @SuppressWarnings("unchecked")
    private static Optional<ConstraintTypeInfo> read(Class<? extends Annotation> type) {
        List<java.lang.classfile.Annotation> raw = ClassFiles.parse(type).findAttribute(Attributes.runtimeVisibleAnnotations())
            .map(a -> a.annotations()).orElse(List.of());
        java.lang.classfile.Annotation constraint = raw.stream()
            .filter(a -> a.className().stringValue().equals(CONSTRAINT)).findFirst().orElse(null);
        if (constraint == null) {
            return Optional.empty();
        }
        ClassLoader loader = type.getClassLoader();
        checkDefinition(type);

        List<Class<? extends ConstraintValidator<?, ?>>> validators = new ArrayList<>();
        for (var element : constraint.elements()) {
            if (element.name().stringValue().equals("validatedBy") && element.value() instanceof AnnotationValue.OfArray array) {
                for (AnnotationValue value : array.values()) {
                    if (value instanceof AnnotationValue.OfClass c) {
                        validators.add((Class<? extends ConstraintValidator<?, ?>>) ClassFiles.load(c.classSymbol(), loader));
                    }
                }
            }
        }
        boolean single = raw.stream().anyMatch(a -> a.className().stringValue().equals(REPORT_AS_SINGLE_VIOLATION));
        List<java.lang.classfile.Annotation> composing = new ArrayList<>();
        for (java.lang.classfile.Annotation a : raw) {
            String descriptor = a.className().stringValue();
            if (NOT_COMPOSING.stream().noneMatch(descriptor::startsWith)) {
                composing.add(a);
            }
        }
        return Optional.of(new ConstraintTypeInfo(type, List.copyOf(validators), List.copyOf(composing), single));
    }

    /** Spec 2.1: message, groups and payload are mandatory; member names starting with "valid" are reserved. */
    private static void checkDefinition(Class<? extends Annotation> type) {
        AnnotationTypeInfo info = AnnotationTypeInfo.of(type);
        requireMember(type, info, "message", String.class, false);
        requireMember(type, info, "groups", Class[].class, true);
        requireMember(type, info, "payload", Class[].class, true);
        for (AnnotationTypeInfo.Member member : info.members()) {
            if (member.name().startsWith("valid") && !member.name().equals("validationAppliesTo")) {
                throw new ConstraintDefinitionException("The constraint " + type.getName() + " declares the attribute "
                    + member.name() + ": names starting with \"valid\" are reserved");
            }
        }
    }

    private static void requireMember(Class<? extends Annotation> type, AnnotationTypeInfo info, String name, Class<?> expected,
            boolean emptyDefault) {
        if (!info.hasMember(name) || info.member(name).type() != expected) {
            throw new ConstraintDefinitionException("The constraint " + type.getName() + " must declare a "
                + name + " attribute of type " + expected.getSimpleName());
        }
        AnnotationTypeInfo.Member member = info.member(name);
        if (emptyDefault && (!member.hasDefault() || ((Object[]) member.defaultValue()).length != 0)) {
            throw new ConstraintDefinitionException("The attribute " + name + " of the constraint " + type.getName()
                + " must default to an empty array");
        }
        if (!emptyDefault && !member.hasDefault()) {
            throw new ConstraintDefinitionException("The attribute " + name + " of the constraint " + type.getName()
                + " must have a default value");
        }
    }
}
