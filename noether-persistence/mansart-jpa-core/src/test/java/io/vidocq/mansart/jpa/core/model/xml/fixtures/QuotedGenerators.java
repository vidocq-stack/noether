/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.model.xml.fixtures;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.TableGenerator;
import jakarta.persistence.UniqueConstraint;

public class QuotedGenerators {
    @Entity
    @Table(name = "MixedRoot")
    @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "Kind")
    public static class Root {
        @Id
        @Column(name = "Id")
        public long id;
    }

    @Entity
    @Table(name = "MixedChild")
    @PrimaryKeyJoinColumn(name = "ChildId", referencedColumnName = "Id")
    public static class Child extends Root {
        public String detail;
    }

    @Entity
    @PrimaryKeyJoinColumn(name = "ChildId", referencedColumnName = "id")
    public static class WrongCase extends Root {
    }

    @Entity
    @Table(name = "\"ExplicitOrders\"", indexes = @Index(columnList = "\"Odd, \"\"Name\"\"\" DESC"))
    @SequenceGenerator(name = "explicit", sequenceName = "\"ExplicitSequence\"", allocationSize = 1)
    public static class Explicit {
        @Id
        @Column(name = "\"KeyId\"")
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "explicit")
        public long id;
        @Column(name = "\"Odd, \"\"Name\"\"\"")
        public String label;
    }

    @Entity
    @Table(name = "ExplicitRef")
    public static class Reference {
        @Id
        public long id;
        @ManyToOne
        public Explicit target;
    }

    @Entity
    @Table(name = "MixedOrders", indexes = @Index(columnList = "id DESC"),
        uniqueConstraints = @UniqueConstraint(columnNames = "Label"))
    @SequenceGenerator(name = "mixed", sequenceName = "MixedSequence", allocationSize = 1)
    public static class Sequence {
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mixed")
        public long id;
        @Column(name = "Label")
        public String label;
    }

    @Entity
    @Table(name = "MixedIdentity")
    public static class Identity {
        @Id
        @Column(name = "Id")
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        public long id;
    }

    @Entity
    @Table(name = "MixedTable")
    @TableGenerator(name = "mixedTable", table = "MixedGenerator", pkColumnName = "GeneratorKey",
        valueColumnName = "GeneratorValue", initialValue = 1, allocationSize = 1)
    public static class ByTable {
        @Id
        @GeneratedValue(strategy = GenerationType.TABLE, generator = "mixedTable")
        public long id;
    }
}
