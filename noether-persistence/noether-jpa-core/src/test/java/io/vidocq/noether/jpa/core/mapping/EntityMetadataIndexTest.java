/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Bootstrap indexes retain the relationship and inverse-index mapping contracts (§2.10, §11.1.33, §11.1.42). */
class EntityMetadataIndexTest {
    private static final MappedUnit UNIT = MappedUnit.of(
        List.of(Owner.class.getName(), Target.class.getName()), EntityMetadataIndexTest.class.getClassLoader());

    @Test
    void referenceSlotsMatchTheOriginalMappingIncludingInheritedAndEmbeddedRelationships() {
        MappedEntity type = UNIT.entity(Owner.class).orElseThrow();
        EntityStatements statements = type.statements();
        assertThat(statements.references()).hasSize(3);
        for (int slot = 0; slot < type.model().attributes().size(); slot++) {
            int attribute = slot;
            assertThat(statements.reference(slot))
                .isEqualTo(statements.references().stream().filter(r -> r.attribute() == attribute).findFirst());
        }
        for (EntityStatements.Reference reference : statements.references()) {
            assertThat(statements.reference(reference.attribute()).orElseThrow()).isSameAs(reference);
        }
        assertThat(statements.references()).anyMatch(EntityStatements.Reference::businessKey);
    }

    @Test
    void absentAndOutOfRangeReferenceSlotsRemainEmpty() {
        EntityStatements statements = UNIT.entity(Owner.class).orElseThrow().statements();
        assertThat(statements.reference(-1)).isEmpty();
        assertThat(statements.reference(Integer.MIN_VALUE)).isEmpty();
        assertThat(statements.reference(Integer.MAX_VALUE)).isEmpty();
        assertThat(statements.reference(UNIT.entity(Owner.class).orElseThrow().model().attributes().size())).isEmpty();
        EntityStatements basic = MappedUnit.of(List.of(Basic.class.getName()), getClass().getClassLoader())
            .entity(Basic.class).orElseThrow().statements();
        assertThat(basic.reference(0)).isEmpty();
        assertThat(basic.reference(1)).isEmpty();
    }

    @Test
    void inverseIndexesRetainOnlyWritableMappedByIndexesInMappingOrder() {
        EntityStatements statements = UNIT.entity(Owner.class).orElseThrow().statements();
        assertThat(statements.inverseIndexes()).hasSize(2);
        assertThat(statements.inverseIndexes()).extracting(c -> c.association().name())
            .containsExactlyElementsOf(statements.collections().stream()
                .filter(c -> c instanceof CollectionMapping.MappedBy inverse && inverse.indexUpdate() != null)
                .map(c -> c.association().name()).toList());
        for (CollectionMapping.MappedBy inverse : statements.inverseIndexes()) {
            assertThat(inverse.indexUpdate()).isNotNull();
            assertThat(statements.collection(inverse.attribute()).orElseThrow()).isSameAs(inverse);
        }
        assertThatThrownBy(() -> statements.inverseIndexes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void basicAndToOneOnlyMappingsHaveNoInverseIndexes() {
        assertThat(UNIT.entity(Target.class).orElseThrow().statements().inverseIndexes()).isEmpty();
        EntityStatements basic = MappedUnit.of(List.of(Basic.class.getName()), getClass().getClassLoader())
            .entity(Basic.class).orElseThrow().statements();
        assertThat(basic.inverseIndexes()).isEmpty();
    }

    @Test
    void indexesBelongToTheirMappedUnit() {
        MappedUnit other = MappedUnit.of(List.of(Owner.class.getName(), Target.class.getName()), getClass().getClassLoader());
        EntityStatements first = UNIT.entity(Owner.class).orElseThrow().statements();
        EntityStatements second = other.entity(Owner.class).orElseThrow().statements();
        for (EntityStatements.Reference reference : first.references()) {
            assertThat(second.reference(reference.attribute()).orElseThrow()).isNotSameAs(reference);
        }
        assertThat(second.inverseIndexes()).isNotSameAs(first.inverseIndexes());
    }

    @MappedSuperclass
    public static class Parent {
        @Id public long id;
        @ManyToOne public Target inherited;
    }

    @Entity(name = "IndexedOwner")
    public static class Owner extends Parent {
        public String label;
        @Embedded public Details details;
        @ManyToOne @JoinColumn(name = "business_code", referencedColumnName = "code") public Target business;
        @OneToMany(mappedBy = "owner") @OrderColumn(name = "list_position") public List<Target> ordered;
        @OneToMany(mappedBy = "owner") @MapKeyColumn(name = "map_key") public Map<String, Target> keyed;
        @OneToMany(mappedBy = "owner") public List<Target> unordered;
        @OneToMany(mappedBy = "owner") @MapKey(name = "code") public Map<String, Target> derived;
        @OneToMany @JoinTable(name = "indexed_owned") @OrderColumn(name = "position") public List<Target> owned;
        @ElementCollection @OrderColumn(name = "position") public List<String> tags;
    }

    @Embeddable
    public static class Details {
        @ManyToOne public Target target;
    }

    @Entity(name = "IndexedTarget")
    public static class Target {
        @Id public long id;
        @Column(unique = true, nullable = false, updatable = false) public String code;
        @ManyToOne public Owner owner;
    }

    @Entity(name = "IndexedBasic")
    public static class Basic {
        @Id public long id;
        public String label;
    }
}
