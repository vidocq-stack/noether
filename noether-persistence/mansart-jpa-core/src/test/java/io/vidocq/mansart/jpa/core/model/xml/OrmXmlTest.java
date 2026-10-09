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
package io.vidocq.mansart.jpa.core.model.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.NamedQueryModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.build.EntityModelBuilder;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Address;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Annotated;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Complete;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Events;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.EmbeddedLinks;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Excluded;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Item;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.LinkEntity;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Owner;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Plain;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.QuotedGenerators;
import io.vidocq.mansart.jpa.core.model.xml.fixtures.Watched;
import jakarta.persistence.CascadeType;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 chapter 12: the XML object/relational mapping descriptors. */
class OrmXmlTest {

    private static final ClassLoader LOADER = OrmXmlTest.class.getClassLoader();

    private static OrmOverlay overlay(String... resources) {
        return OrmOverlay.of(java.util.Arrays.stream(resources).map(r -> new MappingFile(r, LOADER.getResource(r))).toList(), LOADER);
    }

    private static PersistenceUnitModel model(String... resources) {
        return EntityModelBuilder.build(List.of(), overlay(resources), LOADER);
    }

    // ---- reading (§12.2, §8.2.1.6.2) -----------------------------------------------------------------------

    @Test
    void aDoctypeIsRefusedSoNoExternalEntityIsEverResolved() {
        assertThatThrownBy(() -> model("orm/dtd.xml")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("orm/dtd.xml").hasMessageContaining("DTD");
    }

    @Test
    void theRootMustBeEntityMappingsOfAKnownNamespaceAndVersion() {
        assertThatThrownBy(() -> model("orm/wrong-root.xml")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("orm/wrong-root.xml").hasMessageContaining("entity-mappings");
        assertThatThrownBy(() -> model("orm/wrong-version.xml")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("orm/wrong-version.xml").hasMessageContaining("2.2");
    }

    @Test
    void aDocumentTheSchemaRefusesFailsWithItsResourceAndLine() {
        assertThatThrownBy(() -> model("orm/invalid.xml")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("orm/invalid.xml").hasMessageContaining("line 6").hasMessageContaining("colour");
    }

    @Test
    void anUnknownDottedAssociationOverrideIsAnErrorNotSilentlyIgnored() {
        assertThatThrownBy(() -> model("orm/unsupported.xml")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("AssociationOverride").hasMessageContaining("embedded.owner").hasMessageContaining("unknown");
    }

    @Test
    void anAttributeTheClassDoesNotDeclareIsAnError() {
        assertThatThrownBy(() -> model("orm/missing-member.xml")).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("orm/missing-member.xml").hasMessageContaining("line 6").hasMessageContaining("colour");
    }

    @Test
    void anOlderNamespaceAndVersionIsRead() { // §12.2: the orm 2.2 schema is still a valid mapping file
        EntityModel legacy = model("orm/legacy.xml").entity(Excluded.class).orElseThrow();
        assertThat(legacy.table().name()).isEqualTo("LEGACY_T");
    }

    // ---- entities only the mapping file declares (§12.2.3) ---------------------------------------------------

    @Test
    void aClassWithoutAnyAnnotationIsAnEntityByItsMappingFile() {
        PersistenceUnitModel unit = model("orm/units.xml");
        EntityModel plain = unit.entity(Plain.class).orElseThrow();
        assertThat(plain.entityName()).isEqualTo("Plain"); // §12.2.3.1: the unqualified class name by default
        assertThat(plain.table().name()).isEqualTo("PLAIN_T");
        assertThat(plain.access()).isEqualTo(AccessKind.FIELD); // <access> of the mapping file (§12.2.2.5)
        assertThat(plain.attributes()).extracting(a -> a.name()).containsExactly("id", "label", "price", "address", "tags");
        BasicAttribute label = (BasicAttribute) plain.attribute("label").orElseThrow();
        assertThat(label.column().name()).isEqualTo("P_LABEL");
        assertThat(label.column().length()).isEqualTo(40);
        assertThat(label.column().nullable()).isFalse();
        assertThat(((BasicAttribute) plain.attribute("price").orElseThrow()).column().name()).isEqualTo("price");
        EmbeddedAttribute address = (EmbeddedAttribute) plain.attribute("address").orElseThrow();
        assertThat(address.embeddable().javaType()).isEqualTo(Address.class);
        assertThat(address.columns().get("city").name()).isEqualTo("P_CITY");
        assertThat(address.columns().get("zip").name()).isEqualTo("ZIP_CODE");
        ElementCollectionAttribute tags = (ElementCollectionAttribute) plain.attribute("tags").orElseThrow();
        assertThat(tags.table().name()).isEqualTo("PLAIN_TAGS");
        assertThat(tags.table().joinColumns().getFirst().name()).isEqualTo("PLAIN_ID");
        assertThat(((BasicAttribute) tags.element()).column().name()).isEqualTo("TAG");
    }

    @Test
    void relationshipsOfMappingFilesMapLikeTheirAnnotations() {
        PersistenceUnitModel unit = model("orm/units.xml");
        AssociationAttribute items = (AssociationAttribute) unit.entity(Owner.class).orElseThrow().attribute("items").orElseThrow();
        assertThat(items.targetEntity()).isEqualTo(Item.class);
        assertThat(items.mappedBy()).isEqualTo("owner");
        assertThat(items.orderBy()).isEqualTo("title");
        assertThat(items.cascade()).containsExactly(CascadeType.PERSIST);
        AssociationAttribute owner = (AssociationAttribute) unit.entity(Item.class).orElseThrow().attribute("owner").orElseThrow();
        assertThat(owner.joinColumns().getFirst().name()).isEqualTo("OWNER_FK");
    }

    // ---- overriding annotations (§12.1, §12.2.3) ---------------------------------------------------------------

    @Test
    void theMappingFileOverridesTheAnnotationsItRestates() {
        EntityModel annotated = model("orm/units.xml").entity(Annotated.class).orElseThrow();
        assertThat(annotated.entityName()).isEqualTo("Renamed");
        assertThat(annotated.table().name()).isEqualTo("REN_T");
        assertThat(((BasicAttribute) annotated.attribute("label").orElseThrow()).column().name()).isEqualTo("X_LABEL");
        assertThat(((BasicAttribute) annotated.attribute("kept").orElseThrow()).column().name()).isEqualTo("KEPT_COLUMN");
        assertThat(annotated.attribute("note")).isEmpty(); // <transient> overrides the default mapping
    }

    @Test
    void annotationAssociationOverridesRetainTheInheritedRelationshipContract() {
        EntityModel entity = EntityModelBuilder.build(List.of(LinkEntity.class.getName()), LOADER)
            .entity(LinkEntity.class).orElseThrow();
        AssociationAttribute target = (AssociationAttribute) entity.attribute("target").orElseThrow();
        assertThat(target.joinColumns().getFirst().name()).isEqualTo("ANNOTATED_FK");
        assertThat(target.optional()).isFalse();
        assertThat(target.cascade()).containsExactly(CascadeType.PERSIST);
        assertThat(((AssociationAttribute) entity.attribute("targets").orElseThrow()).joinTable().name())
            .isEqualTo("ANNOTATED_LINKS");
    }

    @Test
    void embeddedRelationshipOverridesExecuteThroughNestedGeneratedAccess() {
        try (EntityManagerFactory emf = embeddedFactory("embedded-annotations", false)) {
            exerciseEmbeddedAssociations(emf, "EmbeddedFk", "NestedFk", "EmbeddedTargets");
        }
    }

    @Test
    void xmlEmbeddedOverridesReplaceAnnotationJoinsWithoutMutatingTheReusableEmbeddable() {
        try (EntityManagerFactory emf = embeddedFactory("embedded-xml", true)) {
            exerciseEmbeddedAssociations(emf, "XmlEmbeddedFk", "XmlNestedFk", "XmlEmbeddedTargets");
        }
    }

    @Test
    void embeddedAssociationsCascadeMergeRefreshDetachAndRemove() {
        try (EntityManagerFactory emf = embeddedFactory("embedded-cascades", false)) {
            emf.runInTransaction(em -> {
                EmbeddedLinks.Holder holder = new EmbeddedLinks.Holder();
                holder.id = 200;
                holder.value = new EmbeddedLinks.Value();
                holder.value.target = new EmbeddedLinks.Target();
                holder.value.target.setId(201);
                holder.value.target.setLabel("before");
                holder.orphan = new EmbeddedLinks.Orphan();
                holder.orphan.target = new EmbeddedLinks.Target();
                holder.orphan.target.setId(202);
                em.persist(holder);
            });
            EmbeddedLinks.Holder detached = emf.callInTransaction(em -> em.find(EmbeddedLinks.Holder.class, 200L));
            detached.value.target.setLabel("merged");
            emf.runInTransaction(em -> {
                EmbeddedLinks.Holder managed = em.merge(detached);
                assertThat(managed).isNotSameAs(detached);
                assertThat(managed.value).isNotSameAs(detached.value);
                assertThat(managed.value.target).isNotSameAs(detached.value.target);
                assertThat(em.contains(managed.value.target)).isTrue();
            });
            emf.runInTransaction(em -> {
                EmbeddedLinks.Holder holder = em.find(EmbeddedLinks.Holder.class, 200L);
                assertThat(holder.value.target.getLabel()).isEqualTo("merged");
                holder.orphan.target = new EmbeddedLinks.Target();
                holder.orphan.target.setId(203);
                em.flush();
                assertThat(em.find(EmbeddedLinks.Target.class, 202L)).isNull();
                assertThat(em.find(EmbeddedLinks.Target.class, 203L)).isSameAs(holder.orphan.target);
                holder.value.target.setLabel("unflushed");
                em.refresh(holder);
                assertThat(holder.value.target.getLabel()).isEqualTo("merged");
                EmbeddedLinks.Target target = holder.value.target;
                em.detach(holder);
                assertThat(em.contains(target)).isFalse();
            });
            emf.runInTransaction(em -> em.remove(em.find(EmbeddedLinks.Holder.class, 200L)));
            emf.runInTransaction(em -> {
                assertThat(em.find(EmbeddedLinks.Holder.class, 200L)).isNull();
                assertThat(em.find(EmbeddedLinks.Target.class, 201L)).isNull();
                assertThat(em.find(EmbeddedLinks.Target.class, 203L)).isNull();
            });
        }

    }

    @Test
    void recordEmbeddablesRetainBasicComponentsWhenRelationshipsAreHydratedAndMerged() {
        try (EntityManagerFactory emf = new PersistenceConfiguration("embedded-record")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(EmbeddedLinks.RecordHolder.class).managedClass(EmbeddedLinks.Holder.class)
                .managedClass(EmbeddedLinks.Target.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:embedded-record")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create").createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                EmbeddedLinks.RecordHolder holder = new EmbeddedLinks.RecordHolder();
                holder.id = 300;
                EmbeddedLinks.Target target = new EmbeddedLinks.Target();
                target.setId(301);
                holder.value = new EmbeddedLinks.RecordValue(7, target);
                em.persist(holder);
            });
            EmbeddedLinks.RecordHolder detached = emf.callInTransaction(em -> em.find(EmbeddedLinks.RecordHolder.class, 300L));
            assertThat(detached.value.count()).isEqualTo(7);
            assertThat(detached.value.target().getId()).isEqualTo(301);
            detached.value = new EmbeddedLinks.RecordValue(8, null);
            emf.runInTransaction(em -> em.merge(detached));
            emf.runInTransaction(em -> {
                EmbeddedLinks.RecordHolder holder = em.find(EmbeddedLinks.RecordHolder.class, 300L);
                assertThat(holder.value.count()).isEqualTo(8);
                assertThat(holder.value.target()).isNull();
            });
        }
    }

    @Test
    void unsupportedEmbeddedCollectionShapesFailAtBootstrapRatherThanOmittingTheirState() {
        assertThatThrownBy(() -> new PersistenceConfiguration("unsupported-embedded-children")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(EmbeddedLinks.UnsupportedHolder.class).managedClass(EmbeddedLinks.Holder.class)
            .managedClass(EmbeddedLinks.Target.class).createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("links.children").hasMessageContaining("foreign key");
        assertThatThrownBy(() -> new PersistenceConfiguration("unsupported-embedded-elements")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(EmbeddedLinks.UnsupportedElementHolder.class).managedClass(EmbeddedLinks.Holder.class)
            .managedClass(EmbeddedLinks.Target.class).createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("element collection").hasMessageContaining("relationship");
    }

    private static EntityManagerFactory embeddedFactory(String name, boolean xml) {
        PersistenceConfiguration configuration = new PersistenceConfiguration(name)
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(EmbeddedLinks.Holder.class).managedClass(EmbeddedLinks.Target.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        if (xml) configuration.mappingFile("orm/embedded-associations.xml");
        return configuration.createEntityManagerFactory();
    }

    private static void exerciseEmbeddedAssociations(EntityManagerFactory emf, String direct, String nested, String table) {
        emf.runInTransaction(em -> {
            EmbeddedLinks.Holder holder = new EmbeddedLinks.Holder();
            holder.id = 100;
            holder.value = new EmbeddedLinks.Value();
            holder.value.target = new EmbeddedLinks.Target();
            holder.value.target.setId(101);
            holder.value.targets.add(holder.value.target);
            holder.labels = new EmbeddedLinks.Tags();
            holder.labels.tags.add("original");
            holder.nested = new EmbeddedLinks.Nested();
            holder.nested.links = new EmbeddedLinks.Value();
            holder.nested.links.target = new EmbeddedLinks.Target();
            holder.nested.links.target.setId(102);
            holder.nested.links.targets.add(holder.nested.links.target);
            em.persist(holder);
        });
        emf.runInTransaction(em -> {
            EmbeddedLinks.Holder holder = em.find(EmbeddedLinks.Holder.class, 100L);
            assertThat(holder.value.target.getId()).isEqualTo(101);
            assertThat(holder.value.target.getOwners()).containsExactly(holder);
            assertThat(holder.nested.links.target.getId()).isEqualTo(102);
            assertThat(holder.value.targets).extracting(EmbeddedLinks.Target::getId).containsExactly(101L);
            assertThat(holder.nested.links.targets).extracting(EmbeddedLinks.Target::getId).containsExactly(102L);
            assertThat(holder.labels.tags).containsExactly("original");
            assertThat(em.createNativeQuery("SELECT " + direct + ", " + nested + " FROM Holder WHERE id = 100")
                .getSingleResult()).isEqualTo(new Object[]{101L, 102L});
            assertThat(em.createNativeQuery("SELECT TargetId FROM " + table + " WHERE HolderId = 100")
                .getSingleResult()).isEqualTo(101L);
            assertThat(em.createNativeQuery("SELECT Position FROM " + table + " WHERE HolderId = 100")
                .getSingleResult()).isEqualTo(0);
            assertThat(em.createNativeQuery("UPDATE Holder SET " + direct + " = 999 WHERE id = 100")
                .executeUpdate()).isEqualTo(1);
            em.createNativeQuery("UPDATE Holder SET " + direct + " = 101 WHERE id = 100").executeUpdate();
            assertThat(em.createQuery("select h.value.target from Holder h where h.nested.links.target.id = 102",
                EmbeddedLinks.Target.class).getSingleResult()).isSameAs(holder.value.target);
            var builder = em.getCriteriaBuilder();
            var query = builder.createQuery(EmbeddedLinks.Target.class);
            var root = query.from(EmbeddedLinks.Holder.class);
            query.select(root.get("nested").get("links").get("target"));
            assertThat(em.createQuery(query).getSingleResult()).isSameAs(holder.nested.links.target);
            assertThat(em.createQuery("select t from Holder h join h.value.targets t", EmbeddedLinks.Target.class)
                .getResultList()).containsExactly(holder.value.target);
            assertThat(em.createQuery("select h from Holder h where h.value.targets is not empty", EmbeddedLinks.Holder.class)
                .getResultList()).containsExactly(holder);
            assertThat(emf.getMetamodel().entity(EmbeddedLinks.Holder.class).getAttributes())
                .extracting(jakarta.persistence.metamodel.Attribute::getName).containsExactlyInAnyOrder("id", "value", "nested", "labels", "orphan");
            holder.value.target = holder.nested.links.target;
            holder.value.targets.clear();
            holder.labels.tags.set(0, "changed");
        });
        emf.runInTransaction(em -> {
            EmbeddedLinks.Holder holder = em.find(EmbeddedLinks.Holder.class, 100L);
            assertThat(holder.value.target.getId()).isEqualTo(102);
            assertThat(holder.value.targets).isEmpty();
            assertThat(holder.labels.tags).containsExactly("changed");
            holder.value.target = null;
            holder.nested.links.target = null;
            holder.nested.links.targets.clear();
        });
        emf.runInTransaction(em -> {
            EmbeddedLinks.Holder holder = em.find(EmbeddedLinks.Holder.class, 100L);
            assertThat(holder.value.target).isNull();
            em.remove(holder);
        });
        emf.runInTransaction(em -> assertThat(em.find(EmbeddedLinks.Holder.class, 100L)).isNull());
    }

    @Test
    void joinColumnsCannotSilentlyOverrideADefaultJoinTable() {
        assertThatThrownBy(() -> EntityModelBuilder.build(List.of(LinkEntity.InvalidOverride.class.getName()), LOADER))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("AssociationOverride")
            .hasMessageContaining("join-table");
    }

    @Test
    void xmlAssociationOverridesReplaceAnnotationsWithoutReplacingRelationshipDefaults() {
        EntityModel entity = model("orm/association-overrides.xml").entity(LinkEntity.class).orElseThrow();
        AssociationAttribute target = (AssociationAttribute) entity.attribute("target").orElseThrow();
        assertThat(target.joinColumns().getFirst().name()).isEqualTo("XML_FK");
        assertThat(target.joinColumns().getFirst().nullable()).isFalse();
        assertThat(target.optional()).isFalse();
        assertThat(target.cascade()).containsExactly(CascadeType.PERSIST);
        AssociationAttribute targets = (AssociationAttribute) entity.attribute("targets").orElseThrow();
        assertThat(targets.joinTable().name()).isEqualTo("XmlLinks");
        assertThat(targets.joinTable().joinColumns().getFirst().name()).isEqualTo("OwnerId");
        assertThat(targets.joinTable().inverseJoinColumns().getFirst().name()).isEqualTo("TargetId");
    }

    @Test
    void overriddenInheritedJoinsStoreAndLoadWithTheUnitIdentifierPolicy() {
        try (EntityManagerFactory emf = new PersistenceConfiguration("orm-overridden-joins")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .mappingFile("orm/units.xml").mappingFile("orm/association-overrides.xml").mappingFile("orm/delimited.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-overridden-joins;DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                LinkEntity link = new LinkEntity();
                link.id = 51;
                link.target = new Excluded();
                link.target.setId(52);
                link.targets.add(link.target);
                em.persist(link);
            });
            emf.runInTransaction(em -> {
                LinkEntity found = em.find(LinkEntity.class, 51L);
                assertThat(found.target.getId()).isEqualTo(52);
                assertThat(found.targets).extracting(Excluded::getId).containsExactly(52L);
                assertThat(em.createNativeQuery("SELECT \"TargetId\" FROM \"XmlLinks\" WHERE \"OwnerId\" = 51")
                    .getSingleResult()).isEqualTo(52L);
                assertThat(em.createNativeQuery("SELECT \"XML_FK\" FROM \"LinkEntity\" WHERE \"id\" = 51")
                    .getSingleResult()).isEqualTo(52L);
                assertThat(em.createNativeQuery("UPDATE \"LinkEntity\" SET \"XML_FK\" = 999 WHERE \"id\" = 51")
                    .executeUpdate()).isEqualTo(1); // override's NO_CONSTRAINT, not the superclass's implicit FK
                assertThat(em.createNativeQuery("UPDATE \"XmlLinks\" SET \"TargetId\" = 999 WHERE \"OwnerId\" = 51")
                    .executeUpdate()).isEqualTo(1);
            });
        }
    }

    @Test
    void aMetadataCompleteMappingIgnoresEveryAnnotationOfItsClass() { // §12.2.3.1
        EntityModel complete = model("orm/units.xml").entity(Complete.class).orElseThrow();
        assertThat(complete.entityName()).isEqualTo("Complete");
        assertThat(complete.table().name()).isEqualTo("Complete");
        assertThat(((BasicAttribute) complete.attribute("id").orElseThrow()).column().name()).isEqualTo("id");
        assertThat(complete.attribute("kept")).isPresent();
        assertThat(complete.attribute("dropped")).isEmpty();
        assertThat(complete.callbacks()).noneMatch(c -> c.method().equals("ignoredCallback"));
    }

    @Test
    void namedQueriesOfTheMappingFileOverrideAnnotatedOnesOfTheSameName() { // §12.2.2.7, §10.4.1
        PersistenceUnitModel unit = model("orm/units.xml");
        assertThat(unit.namedQueries()).filteredOn(q -> q.name().equals("Annotated.byLabel")).singleElement()
            .extracting(NamedQueryModel::query).isEqualTo("SELECT a FROM Renamed a WHERE a.label = :label");
        assertThat(unit.namedQueries()).extracting(NamedQueryModel::name)
            .contains("Annotated.all", "Plain.byLabel", "Plain.labels");
        assertThat(unit.sqlResultSetMappings()).extracting(m -> m.name()).contains("Plain.labelColumn");
    }

    // ---- defaults (§12.2.1.1, §12.2.2) ------------------------------------------------------------------------

    @Test
    void theUnitSchemaAppliesEverywhereUnlessAMappingFileNamesItsOwn() { // §12.2.1.1.1, §12.2.2.2
        PersistenceUnitModel unit = model("orm/schema-unit.xml", "orm/schema-file.xml");
        assertThat(unit.entity(Excluded.class).orElseThrow().table().schema()).isEqualTo("UNIT_S");
        assertThat(unit.entity(Watched.class).orElseThrow().table().schema()).isEqualTo("FILE_S");
    }

    @Test
    void twoMappingFilesCannotDisagreeOnAUnitDefault() { // §12.2.1.1: one set of defaults per unit
        assertThatThrownBy(() -> model("orm/schema-unit.xml", "orm/schema-conflict.xml"))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("orm/schema-conflict.xml")
            .hasMessageContaining("line 6").hasMessageContaining("UNIT_S").hasMessageContaining("OTHER_S");
    }

    // ---- callbacks and listeners (§3.6, §12.2.2.x) ----------------------------------------------------------

    @Test
    void defaultListenersComeFirstThenEntityListenersThenTheMethodTheFileNames() { // §3.6.4
        EntityModel watched = model("orm/units.xml").entity(Watched.class).orElseThrow();
        assertThat(watched.callbacks()).extracting(c -> c.kind() + ":" + c.method())
            .containsExactly("PrePersist:stamp", "PrePersist:before", "PrePersist:onPersist");
        EntityModel excluded = model("orm/units.xml").entity(Excluded.class).orElseThrow();
        assertThat(excluded.callbacks()).isEmpty(); // <exclude-default-listeners/>
    }

    // ---- end to end, on H2 ------------------------------------------------------------------------------------

    @Test
    void delimitedJoinedKeysAndDiscriminatorsRetainCaseDuringPolymorphicQueries() {
        try (EntityManagerFactory emf = new PersistenceConfiguration("orm-delimited-inheritance")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(QuotedGenerators.Root.class).managedClass(QuotedGenerators.Child.class)
                .mappingFile("orm/delimited.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-delimited-inheritance;DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                var child = new QuotedGenerators.Child();
                child.id = 81;
                child.detail = "joined";
                em.persist(child);
            });
            emf.runInTransaction(em -> {
                assertThat(em.find(QuotedGenerators.Root.class, 81L)).isInstanceOf(QuotedGenerators.Child.class);
                assertThat(em.createQuery("SELECT r FROM Root r WHERE TYPE(r) = Child", QuotedGenerators.Root.class)
                    .getResultList()).hasSize(1);
                assertThat(em.createNativeQuery("SELECT \"detail\" FROM \"MixedChild\" WHERE \"ChildId\" = 81")
                    .getSingleResult()).isEqualTo("joined");
            });
        }
    }

    @Test
    void aDelimitedPrimaryKeyReferenceCannotMatchADifferentlyCasedColumn() {
        assertThatThrownBy(() -> {
            try (var ignored = new PersistenceConfiguration("orm-delimited-wrong-case")
                    .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                    .managedClass(QuotedGenerators.Root.class).managedClass(QuotedGenerators.WrongCase.class)
                    .mappingFile("orm/delimited.xml")
                    .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-delimited-wrong-case")
                    .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                    .createEntityManagerFactory()) {
            }
        }).isInstanceOf(PersistenceException.class).hasMessageContaining("primary key join column");
    }

    @Test
    void explicitDelimitedNamesAndDefaultForeignKeysWorkWithoutUnitDefaults() {
        try (EntityManagerFactory emf = new PersistenceConfiguration("orm-explicit-quotes")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(QuotedGenerators.Explicit.class).managedClass(QuotedGenerators.Reference.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-explicit-quotes;DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                var target = new QuotedGenerators.Explicit();
                target.label = "escaped";
                em.persist(target);
                var reference = new QuotedGenerators.Reference();
                reference.id = 91;
                reference.target = target;
                em.persist(reference);
            });
            emf.runInTransaction(em -> {
                assertThat(em.find(QuotedGenerators.Reference.class, 91L).target.label).isEqualTo("escaped");
                assertThat(em.createNativeQuery("SELECT \"Odd, \"\"Name\"\"\" FROM \"ExplicitOrders\"")
                    .getSingleResult()).isEqualTo("escaped");
                assertThat(em.createNativeQuery("SELECT target_KeyId FROM ExplicitRef").getSingleResult()).isEqualTo(1L);
                assertThat(em.createNativeQuery("SELECT * FROM \"ExplicitOrders\"", QuotedGenerators.Explicit.class)
                    .getSingleResult()).isInstanceOf(QuotedGenerators.Explicit.class);
            });
        }
    }

    @Test
    void delimitedMixedCaseGeneratorsIndexesAndGeneratedKeysMatchTheirSchema() {
        try (EntityManagerFactory emf = new PersistenceConfiguration("orm-delimited-generators")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(QuotedGenerators.Sequence.class).managedClass(QuotedGenerators.Identity.class)
                .managedClass(QuotedGenerators.ByTable.class).mappingFile("orm/delimited.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-delimited-generators;DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                var sequence = new QuotedGenerators.Sequence();
                sequence.label = "first";
                em.persist(sequence);
                assertThat(sequence.id).isPositive();
                var identity = new QuotedGenerators.Identity();
                em.persist(identity);
                em.flush();
                assertThat(identity.id).isPositive();
                var table = new QuotedGenerators.ByTable();
                em.persist(table);
                assertThat(table.id).isPositive();
            });
            emf.runInTransaction(em -> {
                assertThat(em.createNativeQuery("SELECT \"Label\" FROM \"MixedOrders\"").getSingleResult()).isEqualTo("first");
                assertThat(em.createNativeQuery("SELECT \"Id\" FROM \"MixedIdentity\"").getSingleResult()).isEqualTo(1L);
                assertThat(em.createNativeQuery("SELECT \"id\" FROM \"MixedTable\"").getSingleResult()).isEqualTo(1L);
            });
        }
    }

    @Test
    void delimitedIdentifiersApplyToDefaultsAnnotationsAndOtherMappingFiles() { // §2.15, §12.2.1.3
        try (EntityManagerFactory emf = new PersistenceConfiguration("orm-delimited")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .mappingFile("orm/delimited.xml").mappingFile("orm/units.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-delimited;DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                Plain plain = new Plain();
                plain.setId(31);
                plain.setLabel("quoted");
                plain.setPrice(12);
                Address address = new Address();
                address.setCity("Lyon");
                plain.setAddress(address);
                plain.getTags().add("red");
                em.persist(plain);
                Annotated annotated = new Annotated();
                annotated.setId(31);
                annotated.setLabel("annotation");
                annotated.setKept("kept");
                em.persist(annotated);
            });
            emf.runInTransaction(em -> {
                assertThat(em.createNativeQuery("SELECT \"price\" FROM \"PLAIN_T\" WHERE \"id\" = 31")
                    .getSingleResult()).isEqualTo(12);
                Plain found = em.createNamedQuery("Plain.byLabel", Plain.class)
                    .setParameter("label", "quoted").getSingleResult();
                assertThat(found.getTags()).containsExactly("red");
                assertThat(found.getAddress().getCity()).isEqualTo("Lyon");
                found.setPrice(13);
                assertThat(em.createNativeQuery("SELECT \"KEPT_COLUMN\" FROM \"REN_T\"").getSingleResult())
                    .isEqualTo("kept");
            });
            emf.runInTransaction(em -> {
                assertThat(em.find(Plain.class, 31L).getPrice()).isEqualTo(13);
                assertThat(em.createQuery("UPDATE Plain p SET p.price = 14 WHERE p.id = 31").executeUpdate()).isEqualTo(1);
            });
            emf.runInTransaction(em -> em.remove(em.find(Plain.class, 31L)));
        }
    }

    @Test
    void aUnitMappedByItsMappingFileStoresAndQueries() {
        Events.LOG.clear();
        try (EntityManagerFactory emf = new PersistenceConfiguration("orm-xml")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .mappingFile("orm/units.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-xml;DB_CLOSE_DELAY=-1")
                .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
                .createEntityManagerFactory()) {
            emf.runInTransaction(em -> {
                Plain plain = new Plain();
                plain.setId(1);
                plain.setLabel("first");
                plain.setNote("not stored");
                plain.getTags().add("red");
                Address address = new Address();
                address.setCity("Lyon");
                plain.setAddress(address);
                em.persist(plain);
                Watched watched = new Watched();
                watched.setId(1);
                em.persist(watched);
                Excluded excluded = new Excluded();
                excluded.setId(1);
                em.persist(excluded);
                Annotated annotated = new Annotated();
                annotated.setId(1);
                annotated.setLabel("found");
                em.persist(annotated);
            });
            assertThat(Events.LOG).containsExactly("default:Plain", "default:Watched", "listener:Watched", "method:Watched",
                "default:Annotated");
            emf.runInTransaction(em -> {
                Plain found = em.createNamedQuery("Plain.byLabel", Plain.class).setParameter("label", "first").getSingleResult();
                assertThat(found.getNote()).isNull();
                assertThat(found.getAddress().getCity()).isEqualTo("Lyon");
                assertThat(found.getTags()).containsExactly("red");
                assertThat(em.createNamedQuery("Plain.labels").getResultList()).containsExactly("first");
                assertThat(em.createNativeQuery("SELECT P_CITY FROM PLAIN_T").getSingleResult()).isEqualTo("Lyon");
                assertThat(em.createNamedQuery("Annotated.byLabel", Annotated.class).setParameter("label", "found")
                    .getResultList()).hasSize(1);
                assertThat(em.createNativeQuery("SELECT X_LABEL FROM REN_T").getSingleResult()).isEqualTo("found");
            });
        }
    }

    @Test
    void theMappingFilesOfAUnitAreFoundOnItsClassLoaderWithoutARoot() {
        assertThatThrownBy(() -> new PersistenceConfiguration("orm-missing")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .mappingFile("orm/none.xml")
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:orm-missing")
                .createEntityManagerFactory())
            .isInstanceOf(PersistenceException.class).hasMessageContaining("orm/none.xml");
        URL unused = LOADER.getResource("orm/units.xml");
        assertThat(unused).isNotNull();
    }
}
