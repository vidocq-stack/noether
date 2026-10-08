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
package io.vidocq.mansart.validation.core.descriptor;

import jakarta.validation.metadata.ConstraintDescriptor;
import jakarta.validation.metadata.ElementDescriptor;
import jakarta.validation.metadata.Scope;
import java.lang.annotation.ElementType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** An element that hosts constraints: what the class hierarchy declares about it, and the finder over it. */
abstract class ElementDescriptorImpl implements ElementDescriptor {

    private final Class<?> elementClass;
    private final Class<?> localType;
    private final List<HostedConstraint> hosted;

    /**
     * @param localType the class the descriptor was requested for: the constraints hosted on it are the
     *        {@link Scope#LOCAL_ELEMENT} ones
     */
    ElementDescriptorImpl(Class<?> elementClass, Class<?> localType, List<HostedConstraint> hosted) {
        this.elementClass = elementClass;
        this.localType = localType;
        this.hosted = List.copyOf(hosted);
    }

    @Override
    public Class<?> getElementClass() {
        return elementClass;
    }

    @Override
    public boolean hasConstraints() {
        return !hosted.isEmpty();
    }

    @Override
    public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
        return findConstraints().getConstraintDescriptors();
    }

    @Override
    public ConstraintFinder findConstraints() {
        return new Finder();
    }

    /** The fluent finder: every call returns a new, more restricted finder. */
    private final class Finder implements ConstraintFinder {

        private final Set<Class<?>> groups;
        private final Scope scope;
        private final Set<ElementType> declaredOn;

        Finder() {
            this(null, Scope.HIERARCHY, EnumSet.allOf(ElementType.class));
        }

        private Finder(Set<Class<?>> groups, Scope scope, Set<ElementType> declaredOn) {
            this.groups = groups;
            this.scope = scope;
            this.declaredOn = declaredOn;
        }

        @Override
        public ConstraintFinder unorderedAndMatchingGroups(Class<?>... requested) {
            if (requested == null) {
                throw new IllegalArgumentException("The groups must not be null");
            }
            Set<Class<?>> set = new LinkedHashSet<>(List.of(requested));
            if (set.isEmpty()) {
                set.add(jakarta.validation.groups.Default.class);
            }
            return new Finder(set, scope, declaredOn);
        }

        @Override
        public ConstraintFinder lookingAt(Scope newScope) {
            return new Finder(groups, newScope, declaredOn);
        }

        @Override
        public ConstraintFinder declaredOn(ElementType... types) {
            Set<ElementType> set = EnumSet.noneOf(ElementType.class);
            set.addAll(List.of(types));
            return new Finder(groups, scope, set);
        }

        @Override
        public Set<ConstraintDescriptor<?>> getConstraintDescriptors() {
            Set<ConstraintDescriptor<?>> found = new LinkedHashSet<>();
            for (HostedConstraint h : hosted) {
                if ((scope == Scope.HIERARCHY || h.hostClass() == localType) && declaredOn.contains(h.hostedOn()) && matches(h)) {
                    found.add(h.constraint());
                }
            }
            return Collections.unmodifiableSet(found);
        }

        @Override
        public boolean hasConstraints() {
            return !getConstraintDescriptors().isEmpty();
        }

        private boolean matches(HostedConstraint h) {
            if (groups == null) {
                return true;
            }
            for (Class<?> declared : h.constraint().getGroups()) {
                for (Class<?> requested : groups) {
                    if (declared.isAssignableFrom(requested)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
