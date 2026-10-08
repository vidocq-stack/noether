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
package io.vidocq.mansart.jpa.core.model.build;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.source.Annotated;
import io.vidocq.mansart.jpa.core.model.source.ClassFileSource;
import io.vidocq.mansart.jpa.core.model.source.ClassInfo;
import io.vidocq.mansart.jpa.core.model.source.ClassInfos;
import io.vidocq.mansart.jpa.core.model.source.FieldInfo;
import io.vidocq.mansart.jpa.core.model.source.MethodInfo;
import jakarta.persistence.PersistenceException;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which fields and properties of a managed class are persistent, through which access type, and in which order
 * (§2.2, §2.3). Shared by the bootstrap ({@link EntityModelBuilder}, over class files) and the annotation processor
 * (over the elements of the compilation), so that the accessors generated at build time list the attributes exactly
 * as the model does; nothing here loads a class.
 */
public final class AccessPlanner {

    private static final String JPA = "jakarta.persistence.";
    private static final String ENTITY = JPA + "Entity";
    private static final String EMBEDDABLE = JPA + "Embeddable";
    private static final String MAPPED_SUPERCLASS = JPA + "MappedSuperclass";
    private static final String ACCESS = JPA + "Access";
    private static final String ID = JPA + "Id";
    private static final String EMBEDDED_ID = JPA + "EmbeddedId";
    private static final String TRANSIENT = JPA + "Transient";
    private static final Set<String> NOT_EMBEDDED = Set.of(JPA + "OneToOne", JPA + "ManyToOne", JPA + "OneToMany",
        JPA + "ManyToMany", JPA + "ElementCollection");

    /**
     * A persistent field or property.
     *
     * @param owner the class that declares it
     * @param element the field, or the getter
     * @param type the field type, or the getter return type
     * @param signature the generic signature of the field or the getter, or {@code null}
     */
    public record Member(String name, AccessKind access, ClassInfo owner, Annotated element, ClassDesc type, String signature) {
    }

    /** The lifecycle callback annotations (§3.6.2), in the order a descriptor lists them. */
    public static final List<String> CALLBACKS = List.of("PrePersist", "PostPersist", "PreRemove", "PostRemove", "PreUpdate",
        "PostUpdate", "PostLoad");

    /**
     * A lifecycle callback (§3.6): a method of the entity or of one of its mapped or entity superclasses (no
     * parameter), or a method of an entity listener class (one parameter, the entity), annotated with {@code kind}.
     *
     * @param kind the simple name of the annotation, {@code PrePersist} … {@code PostLoad}
     * @param owner the class that declares the method
     * @param listener the listener class, or {@code null} for a method of the entity
     * @param parameter the parameter type of a listener method, or {@code null}
     */
    public record Callback(String kind, String owner, String method, String listener, ClassDesc parameter) {

        /** {@code kind:[listener#]owner.method}, as {@code ManagedAccess.callbacks()} lists it. */
        public String descriptor() {
            return kind + ":" + (listener == null ? "" : listener + "#") + owner + "." + method;
        }
    }

    private final ClassInfos source;

    public AccessPlanner(ClassInfos source) {
        this.source = source;
    }

    /** The persistent members of an entity, those of its entity and mapped superclasses first (§2.11). */
    public List<Member> entityMembers(ClassInfo entity) {
        List<ClassInfo> hierarchy = hierarchy(entity);
        AccessKind access = defaultAccess(entity, hierarchy);
        List<Member> members = new ArrayList<>();
        for (ClassInfo declaring : hierarchy) {
            members.addAll(members(declaring, classAccess(declaring, access)));
        }
        return members;
    }

    /** The persistent members of an embeddable seen by an owner of access type {@code ownerAccess} (§2.3.3). */
    public List<Member> embeddableMembers(ClassInfo embeddable, AccessKind ownerAccess) {
        return members(embeddable, embeddableAccess(embeddable, ownerAccess));
    }

    /** The entity and its entity or mapped superclasses, the root first (§2.11). */
    public List<ClassInfo> hierarchy(ClassInfo entity) {
        List<ClassInfo> chain = new ArrayList<>();
        ClassInfo current = entity;
        while (current != null) {
            if (current == entity || current.isAnnotated(ENTITY) || current.isAnnotated(MAPPED_SUPERCLASS)) {
                chain.addFirst(current);
            }
            String superclass = current.superclassName();
            current = superclass == null || superclass.startsWith("java.") ? null : source.read(superclass).orElse(null);
        }
        return chain;
    }

    /**
     * §2.3.1: the default access type of the hierarchy is given by the placement of the identifier in the classes that
     * do not declare their access type; an explicit {@code @Access} applies to its own class only (§2.3.2), and is the
     * default only when every class of the hierarchy declares one.
     */
    public AccessKind defaultAccess(ClassInfo entity, List<ClassInfo> hierarchy) {
        for (ClassInfo info : hierarchy) {
            if (explicitAccess(info).isPresent()) {
                continue;
            }
            if (info.fields().stream().anyMatch(f -> f.isAnnotated(ID) || f.isAnnotated(EMBEDDED_ID))) {
                return AccessKind.FIELD;
            }
            if (info.methods().stream().anyMatch(m -> m.isAnnotated(ID) || m.isAnnotated(EMBEDDED_ID))) {
                return AccessKind.PROPERTY;
            }
        }
        return explicitAccess(entity)
            .or(() -> hierarchy.stream().map(AccessPlanner::explicitAccess).flatMap(Optional::stream).findFirst())
            .orElseThrow(() -> new PersistenceException("The entity " + entity.name()
                + " has no identifier: no @Id or @EmbeddedId (§2.4)"));
    }

    /** The access type of a class: its own {@code @Access}, else the one it inherits. */
    public static AccessKind classAccess(ClassInfo info, AccessKind inherited) {
        return explicitAccess(info).orElse(inherited);
    }

    /** §2.3.3: an embeddable takes the access type of its owner, unless it says otherwise; a record is read by field. */
    public static AccessKind embeddableAccess(ClassInfo embeddable, AccessKind ownerAccess) {
        return embeddable.isRecord() ? AccessKind.FIELD : classAccess(embeddable, ownerAccess);
    }

    static Optional<AccessKind> explicitAccess(Annotated element) {
        return element.annotation(ACCESS).map(a -> AccessKind.valueOf(a.enumConstant("value")));
    }

    /** The persistent fields or properties a class declares, in declaration order. */
    public List<Member> members(ClassInfo info, AccessKind access) {
        List<Member> members = new ArrayList<>();
        for (FieldInfo field : info.fields()) {
            if (field.isStatic() || field.isSynthetic() || field.isTransientModifier() || field.isAnnotated(TRANSIENT)) {
                continue;
            }
            AccessKind fieldAccess = explicitAccess(field).orElse(null);
            if (access == AccessKind.FIELD || fieldAccess == AccessKind.FIELD) {
                members.add(new Member(field.name(), AccessKind.FIELD, info, field, field.type(), field.genericSignature()));
            }
        }
        for (MethodInfo method : info.methods()) {
            String property = propertyName(method);
            if (property == null || method.isAnnotated(TRANSIENT)) {
                continue;
            }
            AccessKind methodAccess = explicitAccess(method).orElse(null);
            boolean persistent = access == AccessKind.PROPERTY ? methodAccess != AccessKind.FIELD : methodAccess == AccessKind.PROPERTY;
            if (persistent && hasSetter(info, method, property)) {
                members.add(new Member(property, AccessKind.PROPERTY, info, method, method.type().returnType(), method.genericSignature()));
            }
        }
        return members;
    }

    /**
     * The embeddable a member embeds: an {@code @EmbeddedId}, an {@code @Embedded}, or an attribute whose type is an
     * {@code @Embeddable} and that is not a relationship or an element collection.
     */
    public Optional<ClassInfo> embedded(Member member) {
        Annotated element = member.element();
        boolean explicit = element.isAnnotated(EMBEDDED_ID) || element.isAnnotated(JPA + "Embedded");
        if (!explicit && NOT_EMBEDDED.stream().anyMatch(element::isAnnotated)) {
            return Optional.empty();
        }
        ClassDesc type = member.type();
        if (!type.isClassOrInterface()) {
            return Optional.empty();
        }
        String name = ClassFileSource.binaryName(type);
        if (!explicit && name.startsWith("java.")) {
            return Optional.empty();
        }
        Optional<ClassInfo> info = source.read(name);
        return explicit ? info : info.filter(i -> i.isAnnotated(EMBEDDABLE));
    }

    /**
     * The lifecycle callbacks of an entity, in the order §3.6.4 invokes those of a same event: the methods of its entity
     * listeners, the listeners of its superclasses first (unless a class excludes them with
     * {@code @ExcludeSuperclassListeners}), then its own lifecycle methods, superclasses first; an overridden method
     * keeps the place of the superclass method and is called once, the override (virtual dispatch). Default listeners
     * come from mapping files (P10).
     */
    public List<Callback> callbacks(ClassInfo entity) {
        List<ClassInfo> hierarchy = hierarchy(entity);
        List<String> listeners = new ArrayList<>();
        for (ClassInfo info : hierarchy) {
            if (info.isAnnotated(JPA + "ExcludeSuperclassListeners")) {
                listeners.clear();
            }
            info.annotation(JPA + "EntityListeners").ifPresent(a -> a.types("value")
                .forEach(type -> listeners.add(ClassFileSource.binaryName(type))));
        }
        List<Callback> callbacks = new ArrayList<>();
        for (String listener : listeners) {
            List<ClassInfo> chain = new ArrayList<>();
            for (Optional<ClassInfo> current = source.read(listener); current.isPresent(); ) {
                chain.addFirst(current.get());
                String superclass = current.get().superclassName();
                current = superclass == null || superclass.startsWith("java.") ? Optional.empty() : source.read(superclass);
            }
            Set<String> seen = new HashSet<>();
            for (ClassInfo info : chain) {
                for (MethodInfo method : info.methods()) {
                    String kind = kind(method);
                    if (kind != null && !method.isStatic() && method.type().parameterCount() == 1 && seen.add(kind + method.name())) {
                        callbacks.add(new Callback(kind, info.name(), method.name(), listener, method.type().parameterType(0)));
                    }
                }
            }
        }
        Set<String> seen = new HashSet<>();
        for (ClassInfo info : hierarchy) {
            for (MethodInfo method : info.methods()) {
                String kind = kind(method);
                if (kind != null && !method.isStatic() && method.type().parameterCount() == 0 && seen.add(kind + method.name())) {
                    callbacks.add(new Callback(kind, info.name(), method.name(), null, null));
                }
            }
        }
        return callbacks;
    }

    /** The callback annotation of {@code method}, if it has one. */
    private static String kind(MethodInfo method) {
        for (String kind : CALLBACKS) {
            if (method.isAnnotated(JPA + kind)) {
                return kind;
            }
        }
        return null;
    }

    /** JavaBeans: {@code getX()}, or {@code isX()} for a {@code boolean}; {@code null} if not a getter. */
    static String propertyName(MethodInfo method) {
        if (method.isStatic() || method.isSynthetic() || method.type().parameterCount() != 0) {
            return null;
        }
        ClassDesc returned = method.type().returnType();
        String name = method.name();
        String suffix;
        if (name.startsWith("get") && name.length() > 3 && !returned.equals(ConstantDescs.CD_void)) {
            suffix = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2 && returned.equals(ConstantDescs.CD_boolean)) {
            suffix = name.substring(2);
        } else {
            return null;
        }
        if (suffix.length() > 1 && Character.isUpperCase(suffix.charAt(0)) && Character.isUpperCase(suffix.charAt(1))) {
            return suffix;
        }
        return Character.toLowerCase(suffix.charAt(0)) + suffix.substring(1);
    }

    private static boolean hasSetter(ClassInfo info, MethodInfo getter, String property) {
        String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        String alternative = "set" + property;
        return info.methods().stream().anyMatch(m -> (m.name().equals(setter) || m.name().equals(alternative)) && !m.isStatic()
            && m.type().parameterCount() == 1 && m.type().parameterType(0).equals(getter.type().returnType()));
    }
}
