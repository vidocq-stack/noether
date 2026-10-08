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
package io.vidocq.mansart.jpa.core.model;

/**
 * A lifecycle callback of an entity (§3.6): a method of the entity or a superclass ({@code listener} and
 * {@code parameter} are {@code null}), or a method of an entity listener taking the entity.
 *
 * @param kind {@code PrePersist}, {@code PostPersist}, {@code PreRemove}, {@code PostRemove}, {@code PreUpdate},
 *        {@code PostUpdate} or {@code PostLoad}
 * @param owner the class that declares the method
 */
public record CallbackModel(String kind, Class<?> owner, String method, Class<?> listener, Class<?> parameter) {

    /** {@code kind:[listener#]owner.method}, as the accesses list their callbacks. */
    public String descriptor() {
        return kind + ":" + (listener == null ? "" : listener.getName() + "#") + owner.getName() + "." + method;
    }
}
