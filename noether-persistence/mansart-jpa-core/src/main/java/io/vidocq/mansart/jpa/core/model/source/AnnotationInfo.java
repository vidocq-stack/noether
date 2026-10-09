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
package io.vidocq.mansart.jpa.core.model.source;

import java.lang.constant.ClassDesc;
import java.util.List;
import java.util.Map;

/**
 * An annotation as data: its type, the members written where it is used, and the defaults of its type for the
 * others. Values are {@code String}, boxed primitives, {@link EnumValue}, {@link ClassDesc}, {@link AnnotationInfo}
 * or a {@link List} of those; no class named by the annotation is loaded.
 */
public final class AnnotationInfo {

    private final String typeName;
    private final Map<String, Object> written;
    private final Map<String, Object> defaults;

    AnnotationInfo(String typeName, Map<String, Object> written, Map<String, Object> defaults) {
        this.typeName = typeName;
        this.written = Map.copyOf(written);
        this.defaults = defaults;
    }

    /** The binary name of the annotation type. */
    /**
     * An annotation read by another source than the class files (the annotation processor reads the elements of the
     * compilation). Values are encoded as {@link ClassFileSource} encodes them: boxed primitives, {@code String},
     * {@code ClassDesc} for a class, {@link EnumValue}, {@code AnnotationInfo}, {@code List} for an array.
     *
     * @param written the members written on the annotation
     * @param defaults the defaults of the annotation type
     */
    public static AnnotationInfo of(String typeName, Map<String, Object> written, Map<String, Object> defaults) {
        return new AnnotationInfo(typeName, written, Map.copyOf(defaults));
    }

    public String typeName() {
        return typeName;
    }

    /** Whether the member has a value, written or by default. */
    public boolean has(String member) {
        return written.containsKey(member) || defaults.containsKey(member);
    }

    /** Whether the member was written where the annotation is used. */
    public boolean isWritten(String member) {
        return written.containsKey(member);
    }

    /** The value of the member, written or by default; {@code null} if it has none. */
    public Object value(String member) {
        Object value = written.get(member);
        return value != null ? value : defaults.get(member);
    }

    public String string(String member) {
        return (String) value(member);
    }

    public int integer(String member) {
        return ((Number) value(member)).intValue();
    }

    public long longValue(String member) {
        return ((Number) value(member)).longValue();
    }

    public boolean bool(String member) {
        return (Boolean) value(member);
    }

    /** The constant name of an enum member, or {@code null}. */
    public String enumConstant(String member) {
        Object value = value(member);
        return value == null ? null : ((EnumValue) value).constant();
    }

    public ClassDesc type(String member) {
        return (ClassDesc) value(member);
    }

    public AnnotationInfo annotation(String member) {
        return (AnnotationInfo) value(member);
    }

    public List<String> strings(String member) {
        return list(member, String.class);
    }

    public List<ClassDesc> types(String member) {
        return list(member, ClassDesc.class);
    }

    public List<AnnotationInfo> annotations(String member) {
        return list(member, AnnotationInfo.class);
    }

    public List<String> enumConstants(String member) {
        return list(member, EnumValue.class).stream().map(EnumValue::constant).toList();
    }

    private <T> List<T> list(String member, Class<T> type) {
        Object value = value(member);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(type::cast).toList();
        }
        return List.of(type.cast(value));
    }

    /** This annotation with {@code member} written as {@code value} (a mapping file completing or overriding it). */
    public AnnotationInfo with(String member, Object value) {
        Map<String, Object> changed = new java.util.LinkedHashMap<>(written);
        changed.put(member, value);
        return new AnnotationInfo(typeName, changed, defaults);
    }

    @Override
    public String toString() {
        return "@" + typeName + written;
    }
}
