/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model.xml.fixtures;

import jakarta.persistence.AssociationOverride;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.CascadeType;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.JoinTable;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.OneToOne;

public class EmbeddedLinks {
    @Entity(name = "EmbeddedTarget")
    public static class Target {
        private long id;
        private String label;
        private List<Holder> owners = new ArrayList<>();
        @Id
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        @OneToMany(mappedBy = "value.target")
        public List<Holder> getOwners() { return owners; }
        public void setOwners(List<Holder> owners) { this.owners = owners; }
    }

    @Embeddable
    public static class Value {
        @ManyToOne(cascade = CascadeType.ALL)
        public Target target;
        @ManyToMany(cascade = CascadeType.PERSIST)
        @OrderColumn(name = "Position")
        public List<Target> targets = new ArrayList<>();
    }

    @Embeddable
    public static class Tags {
        @ElementCollection
        @CollectionTable(name = "EmbeddedTags", joinColumns = @JoinColumn(name = "HolderId"))
        @Column(name = "Tag")
        public List<String> tags = new ArrayList<>();
    }

    @Embeddable
    public static class Orphan {
        @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
        public Target target;
    }

    @Embeddable
    public record RecordValue(int count, @ManyToOne(cascade = CascadeType.ALL) Target target) {
    }

    @Entity
    public static class RecordHolder {
        @Id
        public long id;
        @Embedded
        @AssociationOverride(name = "target", joinColumns = @JoinColumn(name = "RecordFk"))
        public RecordValue value;
    }

    @Embeddable
    public static class ForeignKeyChildren {
        @OneToMany
        @JoinColumn(name = "OwnerFk")
        public List<Target> children = new ArrayList<>();
    }

    @Entity
    public static class UnsupportedHolder {
        @Id
        public long id;
        @Embedded
        public ForeignKeyChildren links;
    }

    @Entity
    public static class UnsupportedElementHolder {
        @Id
        public long id;
        @ElementCollection
        public List<RecordValue> values = new ArrayList<>();
    }

    @Embeddable
    public static class Nested {
        @Embedded
        public Value links;
    }

    @Entity
    @AssociationOverride(name = "nested.links.target", joinColumns = @JoinColumn(name = "NestedFk"))
    public static class Holder {
        @Id
        public long id;
        @Embedded
        @AssociationOverride(name = "target", joinColumns = @JoinColumn(name = "EmbeddedFk",
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT)))
        @AssociationOverride(name = "targets", joinTable = @JoinTable(name = "EmbeddedTargets",
            joinColumns = @JoinColumn(name = "HolderId"), inverseJoinColumns = @JoinColumn(name = "TargetId")))
        public Value value;
        @Embedded
        @AssociationOverride(name = "links.target", joinColumns = @JoinColumn(name = "MemberNestedFk"))
        @AssociationOverride(name = "links.targets", joinTable = @JoinTable(name = "NestedTargets",
            joinColumns = @JoinColumn(name = "HolderId"), inverseJoinColumns = @JoinColumn(name = "TargetId")))
        public Nested nested;
        @Embedded
        public Tags labels;
        @Embedded
        @AssociationOverride(name = "target", joinColumns = @JoinColumn(name = "OrphanFk"))
        public Orphan orphan;
    }
}
