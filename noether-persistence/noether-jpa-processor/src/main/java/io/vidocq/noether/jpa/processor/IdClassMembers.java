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
package io.vidocq.noether.jpa.processor;

import io.vidocq.noether.jpa.core.model.AccessKind;
import io.vidocq.noether.jpa.core.model.build.AccessPlanner.Member;
import io.vidocq.noether.jpa.core.model.source.ClassInfo;
import io.vidocq.noether.jpa.core.model.source.ClassInfos;
import io.vidocq.noether.jpa.core.model.source.FieldInfo;
import io.vidocq.noether.jpa.core.model.source.MethodInfo;
import java.lang.constant.ConstantDescs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The members of the access of an id class (§2.4.1), listed as the bootstrap lists the attributes of the access it
 * would build: one per identifier attribute of the entity, in their order, read by field when the id class or one of
 * its superclasses declares a non-static field of that name, else by property. The part for a relationship of a
 * derived identity has the type the id class declares for it, the parent's identifier (§2.4.1.1).
 */
final class IdClassMembers {

    private static final String RELATIONSHIP_MANY = "jakarta.persistence.ManyToOne";
    private static final String RELATIONSHIP_ONE = "jakarta.persistence.OneToOne";

    private final ClassInfos source;

    IdClassMembers(ClassInfos source) {
        this.source = source;
    }

    /**
     * The members of {@code idClass} for the identifier attributes {@code parts} of an entity, or empty when the
     * bootstrap keeps the id class to itself: a relationship without a field of its name (the id class is then the
     * parent's, the relationship the whole identifier, §2.4.1.3 example 5a), a field whose type is not the identifier
     * attribute's, a part with neither field nor getter.
     */
    Optional<List<Member>> of(ClassInfo idClass, List<Member> parts) {
        List<Member> members = new ArrayList<>();
        for (Member part : parts) {
            Optional<Member> member = relationship(part) ? relationshipMember(idClass, part) : basicMember(idClass, part);
            if (member.isEmpty()) {
                return Optional.empty();
            }
            members.add(member.get());
        }
        return Optional.of(members);
    }

    private static boolean relationship(Member part) {
        return part.element().isAnnotated(RELATIONSHIP_MANY) || part.element().isAnnotated(RELATIONSHIP_ONE);
    }

    private Optional<Member> relationshipMember(ClassInfo idClass, Member part) {
        return field(idClass, part.name()).map(declared -> new Member(part.name(), AccessKind.FIELD, declared.owner(),
            declared.field(), declared.field().type(), declared.field().genericSignature()));
    }

    private Optional<Member> basicMember(ClassInfo idClass, Member part) {
        Optional<DeclaredField> field = field(idClass, part.name());
        if (field.isPresent()) {
            // the bootstrap reads the field with the identifier attribute's type: another type is its error to report
            return field.filter(declared -> declared.field().type().equals(part.type()))
                .map(declared -> new Member(part.name(), AccessKind.FIELD, declared.owner(), declared.field(), part.type(),
                    declared.field().genericSignature()));
        }
        return getter(idClass, part).map(declared -> new Member(part.name(), AccessKind.PROPERTY, declared.owner(),
            declared.getter(), part.type(), declared.getter().genericSignature()));
    }

    /** The non-static field {@code name} of {@code type} or of the nearest superclass that declares one. */
    private Optional<DeclaredField> field(ClassInfo type, String name) {
        for (ClassInfo current : hierarchy(type)) {
            Optional<FieldInfo> field = current.field(name).filter(f -> !f.isStatic());
            if (field.isPresent()) {
                return Optional.of(new DeclaredField(current, field.get()));
            }
        }
        return Optional.empty();
    }

    /** The getter of {@code part}, by the names the bootstrap tries in turn, each looked up in the whole hierarchy. */
    private Optional<DeclaredGetter> getter(ClassInfo type, Member part) {
        String capitalized = Character.toUpperCase(part.name().charAt(0)) + part.name().substring(1);
        List<String> names = part.type().equals(ConstantDescs.CD_boolean)
            ? List.of("is" + capitalized, "is" + part.name(), "get" + capitalized, "get" + part.name())
            : List.of("get" + capitalized, "get" + part.name());
        for (String name : names) {
            for (ClassInfo current : hierarchy(type)) {
                Optional<MethodInfo> getter = current.methods().stream()
                    .filter(m -> m.name().equals(name) && !m.isStatic() && m.type().parameterCount() == 0
                        && m.type().returnType().equals(part.type()))
                    .findFirst();
                if (getter.isPresent()) {
                    return Optional.of(new DeclaredGetter(current, getter.get()));
                }
            }
        }
        return Optional.empty();
    }

    /** {@code type}, then its superclasses below {@code Object}. */
    private List<ClassInfo> hierarchy(ClassInfo type) {
        List<ClassInfo> chain = new ArrayList<>();
        Optional<ClassInfo> current = Optional.of(type);
        while (current.isPresent()) {
            chain.add(current.get());
            String superclass = current.get().superclassName();
            current = superclass == null || superclass.equals("java.lang.Object") ? Optional.empty() : source.read(superclass);
        }
        return chain;
    }

    private record DeclaredField(ClassInfo owner, FieldInfo field) {
    }

    private record DeclaredGetter(ClassInfo owner, MethodInfo getter) {
    }
}
