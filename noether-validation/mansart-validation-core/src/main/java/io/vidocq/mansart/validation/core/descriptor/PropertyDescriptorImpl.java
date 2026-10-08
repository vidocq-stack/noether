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

import jakarta.validation.metadata.ContainerElementTypeDescriptor;
import jakarta.validation.metadata.GroupConversionDescriptor;
import jakarta.validation.metadata.PropertyDescriptor;
import java.util.List;
import java.util.Set;

/** A constrained or cascaded property: the field and the getter of the same name, in the whole class hierarchy. */
final class PropertyDescriptorImpl extends ElementDescriptorImpl implements PropertyDescriptor {

    private final String name;
    private final boolean cascaded;

    PropertyDescriptorImpl(String name, Class<?> valueType, Class<?> localType, boolean cascaded, List<HostedConstraint> hosted) {
        super(valueType, localType, hosted);
        this.name = name;
        this.cascaded = cascaded;
    }

    @Override
    public String getPropertyName() {
        return name;
    }

    @Override
    public boolean isCascaded() {
        return cascaded;
    }

    /** Group conversions come with milestone V3. */
    @Override
    public Set<GroupConversionDescriptor> getGroupConversions() {
        return Set.of();
    }

    /** Container element constraints come with milestone V3. */
    @Override
    public Set<ContainerElementTypeDescriptor> getConstrainedContainerElementTypes() {
        return Set.of();
    }
}
