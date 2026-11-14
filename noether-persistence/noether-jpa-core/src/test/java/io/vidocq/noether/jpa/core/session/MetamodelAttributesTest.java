/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Basic;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ListAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 §5.1 and §6.2: the attributes of the runtime metamodel and its canonical classes. */
class MetamodelAttributesTest {

    private EntityManagerFactory emf;

    @BeforeEach
    void open() {
        emf = new PersistenceConfiguration("metamodel-attributes")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(MaKey.class).managedClass(MaAddress.class).managedClass(MaOwner.class)
            .managedClass(MaPlot.class).managedClass(MaLicense.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:metamodel-attributes")
            .createEntityManagerFactory();
    }

    @AfterEach
    void close() {
        emf.close();
    }

    @Test
    void identifiersOfEveryKindAreIdAttributes() { // §5.1.4 isId
        EntityType<MaPlot> plot = emf.getMetamodel().entity(MaPlot.class);
        assertThat(plot.getSingularAttribute("key").isId()).isTrue();
        assertThat(plot.getSingularAttribute("surveyor").isId()).isFalse();
        EntityType<MaLicense> license = emf.getMetamodel().entity(MaLicense.class);
        assertThat(license.getSingularAttribute("owner").isId()).isTrue();
        assertThat(license.getSingularAttribute("owner").isOptional()).isFalse();
        assertThat(license.getSingularAttribute("number").isId()).isFalse();
    }

    @Test
    void attributesTellTheirPersistentKindAndOptionality() { // §5.1.3, §5.1.4
        EntityType<MaPlot> plot = emf.getMetamodel().entity(MaPlot.class);
        assertThat(plot.getAttribute("address").getPersistentAttributeType()).isEqualTo(PersistentAttributeType.EMBEDDED);
        assertThat(plot.getAttribute("tags").getPersistentAttributeType())
            .isEqualTo(PersistentAttributeType.ELEMENT_COLLECTION);
        assertThat(plot.getAttribute("owner").getPersistentAttributeType()).isEqualTo(PersistentAttributeType.MANY_TO_ONE);
        assertThat(plot.getAttribute("note").getPersistentAttributeType()).isEqualTo(PersistentAttributeType.BASIC);
        assertThat(plot.getSingularAttribute("key").isOptional()).isFalse();
        assertThat(plot.getSingularAttribute("surveyor").isOptional()).isFalse();
        assertThat(plot.getSingularAttribute("note").isOptional()).isTrue();
        assertThat(plot.getSingularAttribute("owner").isOptional()).isFalse();
        assertThat(plot.getSingularAttribute("previous").isOptional()).isTrue();
        assertThat(plot.getSingularAttribute("address").isOptional()).isTrue();
    }

    @Test
    void singularAndPluralAttributesAreApart() { // §5.1.2
        EntityType<MaPlot> plot = emf.getMetamodel().entity(MaPlot.class);
        assertThat(plot.getPluralAttributes()).extracting(Attribute::getName).containsExactly("tags");
        assertThat(plot.getDeclaredPluralAttributes()).extracting(Attribute::getName).containsExactly("tags");
        assertThat(plot.getSingularAttributes()).extracting(Attribute::getName)
            .containsExactlyInAnyOrder("key", "address", "surveyor", "note", "owner", "previous");
        assertThat(plot.getDeclaredSingularAttributes()).extracting(Attribute::getName)
            .containsExactlyInAnyOrder("key", "address", "surveyor", "note", "owner", "previous");
    }

    @Test
    void theCanonicalClassReceivesTheFieldsItsTypesAccept() { // §6.2.1.1
        assertThat(MaPlot_.class_).isSameAs(emf.getMetamodel().entity(MaPlot.class));
        assertThat(MaPlot_.surveyor).isSameAs(emf.getMetamodel().entity(MaPlot.class).getAttribute("surveyor"));
        assertThat(MaPlot_.address).isNull();
        assertThat(MaPlot_.missing).isNull();
        assertThat(MaPlot_.SURVEYOR).isEqualTo("surveyor");
    }

    @Embeddable
    public static class MaKey {
        public String zone;
        public int lot;

        @Override
        public boolean equals(Object other) {
            return other instanceof MaKey key && Objects.equals(zone, key.zone) && lot == key.lot;
        }

        @Override
        public int hashCode() {
            return Objects.hash(zone, lot);
        }
    }

    @Embeddable
    public static class MaAddress {
        public String street;
    }

    @Entity(name = "MaOwner")
    public static class MaOwner {
        @Id
        public long id;
    }

    @Entity(name = "MaPlot")
    public static class MaPlot {
        @EmbeddedId
        public MaKey key;
        @Embedded
        public MaAddress address;
        @ElementCollection
        public List<String> tags;
        @Basic(optional = false)
        public String surveyor;
        public String note;
        @ManyToOne(optional = false)
        public MaOwner owner;
        @ManyToOne
        public MaOwner previous;
    }

    @Entity(name = "MaLicense")
    public static class MaLicense {
        @Id
        @OneToOne
        public MaOwner owner;
        public String number;
    }

    /** A hand-written canonical class: only the static fields whose type accepts the attribute are populated. */
    @SuppressWarnings({"java:S101", "java:S3008"}) // §6.2.1.1 names the class X_ and its type field class_
    public static class MaPlot_ {
        public static final String SURVEYOR = "surveyor";
        public static volatile EntityType<MaPlot> class_;
        public static volatile SingularAttribute<MaPlot, String> surveyor;
        public static volatile ListAttribute<MaPlot, String> address;
        public static volatile SingularAttribute<MaPlot, String> missing;
        public String note;
    }
}
