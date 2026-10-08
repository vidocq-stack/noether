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

import jakarta.validation.ValidationException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * A constrained or cascaded property as declared by one class: a field, or a getter. The value of an
 * instance is read through a method handle, never through {@code java.lang.reflect}.
 */
public final class PropertyMetadata {

    /** Where the property is declared. */
    public enum Kind { FIELD, GETTER }

    private final Class<?> declaringClass;
    private final String name;
    private final Kind kind;
    private final String memberName;
    private final Class<?> valueType;
    private final boolean cascaded;
    private final List<ConstraintDef> constraints;
    private volatile MethodHandle accessor;

    PropertyMetadata(Class<?> declaringClass, String name, Kind kind, String memberName, Class<?> valueType, boolean cascaded,
            List<ConstraintDef> constraints) {
        this.declaringClass = declaringClass;
        this.name = name;
        this.kind = kind;
        this.memberName = memberName;
        this.valueType = valueType;
        this.cascaded = cascaded;
        this.constraints = List.copyOf(constraints);
    }

    /** The JavaBeans property name: the field name, or the decapitalized getter name without its prefix. */
    public String name() {
        return name;
    }

    public Kind kind() {
        return kind;
    }

    /** The field or method name. */
    public String memberName() {
        return memberName;
    }

    /** The declared type of the field, or the return type of the getter. */
    public Class<?> valueType() {
        return valueType;
    }

    public Class<?> declaringClass() {
        return declaringClass;
    }

    /** Whether the property carries {@code @Valid}. */
    public boolean cascaded() {
        return cascaded;
    }

    public List<ConstraintDef> constraints() {
        return constraints;
    }

    /** The value of this property on {@code bean}, an instance of the declaring class. */
    public Object get(Object bean) {
        try {
            return handle().invoke(bean);
        } catch (Error e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new ValidationException("Unable to read " + declaringClass.getName() + "." + memberName, e);
        }
    }

    private MethodHandle handle() {
        MethodHandle handle = accessor;
        if (handle == null) {
            handle = resolve();
            accessor = handle;
        }
        return handle;
    }

    /**
     * Our own lookup reaches public members of public classes in packages exported to this module;
     * everything else needs {@code privateLookupIn}, i.e. the package opened to this module.
     */
    private MethodHandle resolve() {
        Modules.canRead(declaringClass);
        MethodHandles.Lookup own = MethodHandles.lookup();
        try {
            try {
                return find(own).asType(MethodType.methodType(Object.class, Object.class));
            } catch (IllegalAccessException notAccessible) {
                return find(MethodHandles.privateLookupIn(declaringClass, own)).asType(MethodType.methodType(Object.class, Object.class));
            }
        } catch (ReflectiveOperationException e) {
            throw new ValidationException("Unable to access " + declaringClass.getName() + "." + memberName
                + ": its package must be opened to " + PropertyMetadata.class.getModule().getName(), e);
        }
    }

    private MethodHandle find(MethodHandles.Lookup lookup) throws ReflectiveOperationException {
        return kind == Kind.FIELD
            ? lookup.findGetter(declaringClass, memberName, valueType)
            : lookup.findVirtual(declaringClass, memberName, MethodType.methodType(valueType));
    }

    @Override
    public String toString() {
        return "PropertyMetadata[" + declaringClass.getName() + "#" + name + " " + kind + "]";
    }
}
