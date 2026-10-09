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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.vidocq.mansart.jpa.core.model.AccessKind;
import io.vidocq.mansart.jpa.core.model.AssociationAttribute;
import io.vidocq.mansart.jpa.core.model.AttributeModel;
import io.vidocq.mansart.jpa.core.model.BasicAttribute;
import io.vidocq.mansart.jpa.core.model.ElementCollectionAttribute;
import io.vidocq.mansart.jpa.core.model.EmbeddableModel;
import io.vidocq.mansart.jpa.core.model.EmbeddedAttribute;
import io.vidocq.mansart.jpa.core.model.ConverterModel;
import io.vidocq.mansart.jpa.core.model.EntityModel;
import io.vidocq.mansart.jpa.core.model.IdModel;
import io.vidocq.mansart.jpa.core.model.JoinColumnModel;
import io.vidocq.mansart.jpa.core.model.PersistenceUnitModel;
import io.vidocq.mansart.jpa.core.model.ValueConversion;
import io.vidocq.mansart.jpa.core.model.build.fixtures.*;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Dependent;
import io.vidocq.mansart.jpa.core.model.build.fixtures.DependentKey;
import io.vidocq.mansart.jpa.core.model.build.fixtures.NamedDependent;
import io.vidocq.mansart.jpa.core.model.build.fixtures.Parent;
import io.vidocq.mansart.jpa.core.model.build.fixtures.XmlMapped;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Emp;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Meeting;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Room;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.GenerationType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TemporalType;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2, chapter 2 (entities) and chapter 11 (metadata annotations): the entity model. */
class EntityModelBuilderTest {

    private static PersistenceUnitModel build(Class<?>... classes) {
        return EntityModelBuilder.build(List.of(classes).stream().map(Class::getName).toList(),
            EntityModelBuilderTest.class.getClassLoader());
    }

    private static EntityModel entity(Class<?> type, Class<?>... others) {
        Class<?>[] all = new Class<?>[others.length + 1];
        all[0] = type;
        System.arraycopy(others, 0, all, 1, others.length);
        return build(all).entity(type).orElseThrow();
    }

    private static BasicAttribute basic(EntityModel entity, String name) {
        return (BasicAttribute) entity.attribute(name).orElseThrow();
    }

    private static List<String> names(List<? extends AttributeModel> attributes) {
        return attributes.stream().map(AttributeModel::name).toList();
    }

    // ---- entity, table, access --------------------------------------------------------------------------

    @Test
    void theEntityNameAndTableDefaultToTheUnqualifiedClassName() { // §2.1, §11.1.50
        EntityModel customer = entity(Customer.class);
        assertThat(customer.entityName()).isEqualTo("Customer");
        assertThat(customer.table().name()).isEqualTo("Customer");
        assertThat(customer.table().schema()).isNull();
    }

    @Test
    void theEntityNameAndTableCanBeGiven() {
        EntityModel account = entity(Account.class);
        assertThat(account.entityName()).isEqualTo("Acct");
        assertThat(account.table().name()).isEqualTo("ACCOUNTS");
        assertThat(account.table().schema()).isEqualTo("BANK");
    }

    @Test
    void theAccessTypeFollowsThePlacementOfTheId() { // §2.3.1
        assertThat(entity(Customer.class).access()).isEqualTo(AccessKind.FIELD);
        assertThat(entity(Account.class).access()).isEqualTo(AccessKind.PROPERTY);
    }

    @Test
    void fieldAccessPersistsEveryFieldButStaticTransientAndTransientAnnotatedOnes() { // §2.2
        assertThat(names(entity(Customer.class).attributes())).containsExactly("id", "name", "email", "visits", "balance",
            "credit", "since", "legacy", "birthDate", "token", "level", "preferredLevel", "grade", "notes", "picture",
            "version", "biography");
    }

    @Test
    void propertyAccessPersistsGetterSetterPairsOnly() { // §2.2
        EntityModel account = entity(Account.class);
        assertThat(names(account.attributes())).containsExactlyInAnyOrder("number", "owner", "active");
        assertThat(account.attributes()).allMatch(a -> a.access() == AccessKind.PROPERTY);
    }

    @Test
    void anAttributeCanOverrideTheAccessOfItsClass() { // §2.3.2
        EntityModel mixed = entity(Mixed.class);
        assertThat(mixed.access()).isEqualTo(AccessKind.PROPERTY);
        assertThat(mixed.attribute("id").orElseThrow().access()).isEqualTo(AccessKind.PROPERTY);
        assertThat(mixed.attribute("secret").orElseThrow().access()).isEqualTo(AccessKind.FIELD);
        assertThat(basic(mixed, "secret").column().name()).isEqualTo("SECRET");
        assertThat(mixed.attribute("helper")).isEmpty();
    }

    // ---- basic attributes ---------------------------------------------------------------------------------

    @Test
    void aColumnDefaultsToTheAttributeName() { // §11.1.9
        BasicAttribute name = basic(entity(Customer.class), "name");
        assertThat(name.column().name()).isEqualTo("name");
        assertThat(name.column().nullable()).isTrue();
        assertThat(name.column().insertable()).isTrue();
        assertThat(name.column().updatable()).isTrue();
        assertThat(name.column().unique()).isFalse();
        assertThat(name.column().length()).isEqualTo(255);
        assertThat(name.javaType()).isEqualTo(String.class);
    }

    @Test
    void theColumnAnnotationIsRead() {
        EntityModel customer = entity(Customer.class);
        BasicAttribute email = basic(customer, "email");
        assertThat(email.column().name()).isEqualTo("E_MAIL");
        assertThat(email.column().nullable()).isFalse();
        assertThat(email.column().unique()).isTrue();
        assertThat(email.column().length()).isEqualTo(120);
        assertThat(basic(customer, "credit").column().precision()).isEqualTo(12);
        assertThat(basic(customer, "credit").column().scale()).isEqualTo(2);
        assertThat(basic(entity(Account.class), "owner").column().name()).isEqualTo("OWNER_NAME");
    }

    @Test
    void primitivesAreNotOptionalAndBasicCanSayOtherwise() { // §11.1.6
        EntityModel customer = entity(Customer.class);
        assertThat(basic(customer, "visits").optional()).isFalse();
        assertThat(basic(customer, "name").optional()).isTrue();
        assertThat(basic(customer, "biography").optional()).isFalse();
        assertThat(basic(customer, "biography").fetch()).isEqualTo(FetchType.LAZY);
        assertThat(basic(customer, "name").fetch()).isEqualTo(FetchType.EAGER);
        assertThat(basic(customer, "visits").javaType()).isEqualTo(int.class);
    }

    @Test
    void enumsAreStoredByOrdinalUnlessToldOtherwise() { // §11.1.18, @EnumeratedValue (3.2)
        EntityModel customer = entity(Customer.class);
        assertThat(basic(customer, "level").conversion()).isEqualTo(new ValueConversion.EnumOrdinal());
        assertThat(basic(customer, "preferredLevel").conversion()).isEqualTo(new ValueConversion.EnumString());
        assertThat(basic(customer, "grade").conversion()).isEqualTo(new ValueConversion.EnumByValue("code", String.class));
    }

    @Test
    void legacyDatesAreTimestampsUnlessTemporalSaysOtherwise() { // §11.1.53
        EntityModel customer = entity(Customer.class);
        assertThat(basic(customer, "legacy").conversion()).isEqualTo(new ValueConversion.Temporal(TemporalType.TIMESTAMP));
        assertThat(basic(customer, "birthDate").conversion()).isEqualTo(new ValueConversion.Temporal(TemporalType.DATE));
        assertThat(basic(customer, "since").conversion()).isEqualTo(new ValueConversion.None());
    }

    @Test
    void lobsAndVersionsAreRecognised() { // §11.1.28, §11.1.57
        EntityModel customer = entity(Customer.class);
        assertThat(basic(customer, "notes").lob()).isTrue();
        assertThat(basic(customer, "picture").lob()).isFalse();
        assertThat(customer.version()).hasValueSatisfying(v -> assertThat(v.name()).isEqualTo("version"));
        assertThat(basic(customer, "version").version()).isTrue();
        assertThat(entity(Account.class).version()).isEmpty();
    }

    // ---- identifiers --------------------------------------------------------------------------------------

    @Test
    void aSimpleIdWithoutGeneration() { // §2.4
        IdModel id = entity(Customer.class).id();
        assertThat(id).isInstanceOf(IdModel.Single.class);
        assertThat(((IdModel.Single) id).attribute().name()).isEqualTo("id");
        assertThat(((IdModel.Single) id).generation()).isEmpty();
    }

    @Test
    void aGeneratedIdWithASequenceGenerator() { // §11.1.20, §11.1.51
        IdModel.Single id = (IdModel.Single) entity(Account.class).id();
        assertThat(id.generation()).hasValueSatisfying(g -> {
            assertThat(g.strategy()).isEqualTo(GenerationType.SEQUENCE);
            assertThat(g.sequence()).hasValueSatisfying(s -> {
                assertThat(s.name()).isEqualTo("acct_seq");
                assertThat(s.sequenceName()).isEqualTo("ACCT_SEQ");
                assertThat(s.allocationSize()).isEqualTo(20);
                assertThat(s.initialValue()).isEqualTo(1);
            });
        });
        assertThat(((IdModel.Single) entity(Shop.class, Addr.class, Geo.class).id()).generation())
            .hasValueSatisfying(g -> assertThat(g.strategy()).isEqualTo(GenerationType.AUTO));
    }

    @Test
    void aCompositeIdThroughAnIdClass() { // §2.4, §11.1.22
        IdModel.ByIdClass id = (IdModel.ByIdClass) entity(OrderLine.class).id();
        assertThat(id.idClass()).isEqualTo(OrderLineKey.class);
        assertThat(names(id.attributes())).containsExactly("orderId", "line");
    }

    @Test
    void aCompositeIdThroughAnEmbeddedId() { // §11.1.17
        EntityModel ticket = entity(Ticket.class, TicketKey.class);
        IdModel.Embedded id = (IdModel.Embedded) ticket.id();
        assertThat(id.attribute().name()).isEqualTo("key");
        assertThat(names(id.attribute().embeddable().attributes())).containsExactly("office", "number");
    }

    @Test
    void anEntityWithoutIdIsRejected() { // §2.4
        assertThatThrownBy(() -> build(NoId.class)).isInstanceOf(PersistenceException.class).hasMessageContaining("NoId");
    }

    // ---- embeddables --------------------------------------------------------------------------------------

    @Test
    void anEmbeddedAttributeMapsTheColumnsOfItsEmbeddable() { // §2.6, §11.1.15
        EntityModel shop = entity(Shop.class, Addr.class, Geo.class);
        EmbeddedAttribute address = (EmbeddedAttribute) shop.attribute("address").orElseThrow();
        Function<String, String> column = path -> address.column(path).orElseThrow().name();
        assertThat(column.apply("street")).isEqualTo("street");
        assertThat(column.apply("city")).isEqualTo("TOWN");
        assertThat(column.apply("geo.lat")).isEqualTo("lat");
    }

    @Test
    void attributeOverridesRenameColumnsOfAnEmbeddedAttribute() { // §11.1.4, dotted names for nested embeddables
        EntityModel shop = entity(Shop.class, Addr.class, Geo.class);
        EmbeddedAttribute billing = (EmbeddedAttribute) shop.attribute("billing").orElseThrow();
        assertThat(billing.column("street").orElseThrow().name()).isEqualTo("BILL_STREET");
        assertThat(billing.column("geo.lat").orElseThrow().name()).isEqualTo("BILL_LAT");
        assertThat(billing.column("city").orElseThrow().name()).isEqualTo("TOWN");
        // the other use of the same embeddable is not affected
        EmbeddedAttribute address = (EmbeddedAttribute) shop.attribute("address").orElseThrow();
        assertThat(address.column("street").orElseThrow().name()).isEqualTo("street");
    }

    @Test
    void anAttributeOfAnEmbeddableTypeIsEmbeddedWithoutAnnotation() { // §2.6
        EntityModel shop = entity(Shop.class, Addr.class, Geo.class);
        assertThat(shop.attribute("location").orElseThrow()).isInstanceOf(EmbeddedAttribute.class);
    }

    @Test
    void aRecordIsAnEmbeddable() { // 3.2: records as embeddables
        EmbeddedAttribute location = (EmbeddedAttribute) entity(Shop.class, Addr.class, Geo.class).attribute("location").orElseThrow();
        assertThat(location.embeddable().isRecord()).isTrue();
        assertThat(names(location.embeddable().attributes())).containsExactly("lat", "lon");
    }

    // ---- inheritance from mapped superclasses ---------------------------------------------------------------

    @Test
    void theAttributesOfAMappedSuperclassBelongToTheEntity() { // §2.11.2
        EntityModel note = entity(Note.class);
        assertThat(names(note.attributes())).containsExactly("id", "createdAt", "text");
        assertThat(note.attribute("id").orElseThrow().declaringClass()).isEqualTo(Audited.class);
        assertThat(((IdModel.Single) note.id()).generation()).hasValueSatisfying(g ->
            assertThat(g.strategy()).isEqualTo(GenerationType.IDENTITY));
        assertThat(basic(note, "createdAt").column().name()).isEqualTo("CREATED");
    }

    // ---- converters ---------------------------------------------------------------------------------------

    @Test
    void convertersApplyAutomaticallyExplicitlyOrNotAtAll() { // §3.9, §11.1.10
        EntityModel invoice = build(Invoice.class, MoneyConverter.class, UpperCaseConverter.class).entity(Invoice.class).orElseThrow();
        assertThat(basic(invoice, "total").conversion()).isEqualTo(new ValueConversion.Converted(MoneyConverter.class, Long.class));
        assertThat(basic(invoice, "code").conversion()).isEqualTo(new ValueConversion.Converted(UpperCaseConverter.class, String.class));
        assertThat(basic(invoice, "free").conversion()).isEqualTo(new ValueConversion.None());
        assertThat(basic(invoice, "raw").conversion()).isEqualTo(new ValueConversion.None());
    }

    @Test
    void aConvertNamesTheAttributeItConvertsOnTheEntityOrOnAnEmbedded() { // §11.1.10
        EntityModel letter = entity(Letter.class, Postal.class, Geo.class);
        assertThat(basic(letter, "signature").conversion()) // inherited from a mapped superclass
            .isEqualTo(new ValueConversion.Converted(UpperCaseConverter.class, String.class));
        EmbeddableModel address = ((EmbeddedAttribute) letter.attribute("address").orElseThrow()).embeddable();
        assertThat(((BasicAttribute) address.attribute("street").orElseThrow()).conversion())
            .isEqualTo(new ValueConversion.Converted(UpperCaseConverter.class, String.class));
        assertThat(((BasicAttribute) address.attribute("city").orElseThrow()).conversion()).isEqualTo(new ValueConversion.None());
        EmbeddableModel geo = ((EmbeddedAttribute) address.attribute("geo").orElseThrow()).embeddable();
        assertThat(((BasicAttribute) geo.attribute("lat").orElseThrow()).conversion())
            .isEqualTo(new ValueConversion.Converted(HalfConverter.class, Double.class));
        assertThat(((BasicAttribute) geo.attribute("lon").orElseThrow()).conversion()).isEqualTo(new ValueConversion.None());
    }

    @Test
    void aConverterOfAPrimitiveArrayAppliesAutomatically() { // its signature reads AttributeConverter<[C, String>
        EntityModel letter = entity(Letter.class, Postal.class, Geo.class, CharsConverter.class);
        assertThat(basic(letter, "initials").conversion()).isEqualTo(new ValueConversion.Converted(CharsConverter.class, String.class));
    }

    @Test
    void theConvertersOfTheUnitAreListed() {
        PersistenceUnitModel unit = build(Invoice.class, MoneyConverter.class, UpperCaseConverter.class);
        assertThat(unit.converters().stream().collect(Collectors.<ConverterModel, Class<?>, Boolean>toMap(ConverterModel::converterClass, ConverterModel::autoApply)))
            .containsEntry(MoneyConverter.class, true).containsEntry(UpperCaseConverter.class, false);
    }

    @Test
    void anAccessAnnotationOnTheEntityDoesNotChangeTheAccessOfItsSuperclasses() { // §2.3.1, §2.3.2
        EntityModel contractor = build(Contractor.class).entity(Contractor.class).orElseThrow();
        assertThat(contractor.access()).isEqualTo(AccessKind.PROPERTY);
        assertThat(contractor.attribute("id").orElseThrow().access()).isEqualTo(AccessKind.FIELD);
        assertThat(contractor.attribute("name").orElseThrow().access()).isEqualTo(AccessKind.FIELD);
        assertThat(contractor.attribute("rate").orElseThrow().access()).isEqualTo(AccessKind.PROPERTY);
        assertThat(contractor.id()).isInstanceOfSatisfying(IdModel.Single.class, id ->
            assertThat(id.attribute().name()).isEqualTo("id"));
    }

    @Test
    void aPropertyIsNamedAfterItsAccessorEvenUncapitalised() { // JavaBeans decapitalisation: getdescription
        EntityModel lowercase = build(Lowercase.class).entity(Lowercase.class).orElseThrow();
        assertThat(lowercase.attributes()).extracting(AttributeModel::name).containsExactly("id", "description");
    }

    @Test
    void aRelationshipCanBeTheIdentifier() { // §2.4.1: derived identities
        EntityModel dependent = build(Parent.class, Dependent.class).entity(Dependent.class).orElseThrow();
        assertThat(dependent.id()).isInstanceOfSatisfying(IdModel.Derived.class, id -> {
            assertThat(id.relationship().name()).isEqualTo("parent");
            assertThat(id.relationship().kind()).isEqualTo(AssociationAttribute.Kind.ONE_TO_ONE);
        });
    }

    @Test
    void anIdClassMayGatherBasicAndRelationshipIdentifiers() { // §2.4.1.1
        EntityModel dependent = build(Parent.class, NamedDependent.class).entity(NamedDependent.class).orElseThrow();
        assertThat(dependent.id()).isInstanceOfSatisfying(IdModel.ByIdClass.class, id -> {
            assertThat(id.idClass()).isEqualTo(DependentKey.class);
            assertThat(id.attributes()).extracting(AttributeModel::name).containsExactly("name", "parent");
            assertThat(id.attributes().get(1)).isInstanceOf(AssociationAttribute.class);
        });
    }

    @Test
    void anAttributeTheAnnotationsCannotMapIsAnErrorEvenWhenMappingFilesMayExist() { // ch. 12: the mapping files are read
        assertThatThrownBy(() -> build(Parent.class, XmlMapped.class)).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("parents");
    }

    // ---- relationships (§2.10, §11.1) and invalid mappings ---------------------------------------------------

    private static AssociationAttribute association(EntityModel entity, String name) {
        return (AssociationAttribute) entity.attribute(name).orElseThrow();
    }

    @Test
    void aRelationshipKnowsItsTargetFromItsTypeItsElementsOrItsTargetEntity() { // §2.10
        PersistenceUnitModel unit = build(Author.class, Book.class);
        AssociationAttribute books = association(unit.entity(Author.class).orElseThrow(), "books");
        assertThat(books.kind()).isEqualTo(AssociationAttribute.Kind.ONE_TO_MANY);
        assertThat(books.targetEntity()).isEqualTo(Book.class); // List<Book>
        assertThat(books.fetch()).isEqualTo(FetchType.LAZY);
        assertThat(unit.entity(Author.class).orElseThrow().attribute("aliases").orElseThrow())
            .isInstanceOfSatisfying(ElementCollectionAttribute.class, aliases -> { // §2.7: its element, a basic value
                assertThat(aliases.element()).isInstanceOfSatisfying(BasicAttribute.class, element -> {
                    assertThat(element.javaType()).isEqualTo(String.class);
                    assertThat(element.column().name()).isEqualTo("aliases"); // §11.1.9: the attribute name by default
                });
                assertThat(aliases.table().name()).isNull(); // the default, <entity>_<attribute>, applied at mapping
            });
        AssociationAttribute author = association(unit.entity(Book.class).orElseThrow(), "author");
        assertThat(author.kind()).isEqualTo(AssociationAttribute.Kind.MANY_TO_ONE);
        assertThat(author.targetEntity()).isEqualTo(Author.class);
        assertThat(author.fetch()).isEqualTo(FetchType.EAGER);
    }

    @Test
    void theJoinColumnsFetchAndOptionalityAreReadAsWritten() { // §11.1.25, §11.1.26, §11.1.40
        EntityModel emp = entity(Emp.class, Dept.class, Desk.class);
        AssociationAttribute dept = association(emp, "dept");
        assertThat(dept.joinColumns()).isEmpty(); // the defaults depend on Dept, applied when the unit is mapped
        assertThat(dept.owning()).isTrue();
        assertThat(dept.optional()).isTrue();
        AssociationAttribute manager = association(emp, "manager");
        assertThat(manager.fetch()).isEqualTo(FetchType.LAZY);
        assertThat(manager.joinColumns()).extracting(JoinColumnModel::name).containsExactly("MGR");
        AssociationAttribute desk = association(emp, "desk");
        assertThat(desk.orphanRemoval()).isTrue();
        assertThat(desk.cascades(CascadeType.PERSIST)).isTrue();
        assertThat(association(entity(Desk.class, Emp.class, Dept.class), "emp").mappedBy()).isEqualTo("desk");
        AssociationAttribute room = association(entity(Meeting.class, Room.class), "room");
        assertThat(room.optional()).isFalse();
        assertThat(room.joinColumns()).extracting(JoinColumnModel::name, JoinColumnModel::referencedColumnName)
            .containsExactly(tuple("BLDG", "building"), tuple("NUM", "number"));
    }

    @Test
    void invalidEntityClassesAreRejected() { // §2.1
        assertThatThrownBy(() -> build(FinalEntity.class)).isInstanceOf(PersistenceException.class).hasMessageContaining("final");
        assertThatThrownBy(() -> build(NoDefaultConstructor.class)).isInstanceOf(PersistenceException.class)
            .hasMessageContaining("constructor");
        assertThatThrownBy(() -> build(Unmappable.class)).isInstanceOf(PersistenceException.class).hasMessageContaining("thread");
    }

    @Test
    void entitiesAreFoundByClassAndByName() {
        PersistenceUnitModel unit = build(Customer.class, Account.class);
        assertThat(unit.entity("Acct")).hasValueSatisfying(e -> assertThat(e.javaType()).isEqualTo(Account.class));
        assertThat(unit.entity(Object.class)).isEmpty();
        assertThat(unit.entities()).hasSize(2);
    }

    // ---- callbacks (§3.6) ---------------------------------------------------------------------------------------

    private static List<String> callbacks(Class<?> entity) {
        var source = new io.vidocq.mansart.jpa.core.model.source.ClassFileSource(entity.getClassLoader());
        var planner = new AccessPlanner(source);
        return planner.callbacks(source.read(entity.getName()).orElseThrow()).stream().map(AccessPlanner.Callback::descriptor).toList();
    }

    @Test
    void listenersComeFirstSuperclassesFirstThenLifecycleMethods() { // §3.6.4
        String fixtures = "io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.";
        assertThat(callbacks(io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.Record.class)).containsExactly(
            "PrePersist:" + fixtures + "AuditListener#" + fixtures + "AuditListener.prePersist",
            "PostLoad:" + fixtures + "AuditListener#" + fixtures + "AuditListener.postLoad",
            "PrePersist:" + fixtures + "StampListener#" + fixtures + "StampListener.prePersist",
            "PreUpdate:" + fixtures + "StampListener#" + fixtures + "StampListener.preUpdate",
            "PrePersist:" + fixtures + "Tracked.onPersist", // overridden: called once, through the override
            "PostPersist:" + fixtures + "Tracked.persisted",
            "PreRemove:" + fixtures + "Record.removing",
            "PostRemove:" + fixtures + "Record.removed",
            "PostUpdate:" + fixtures + "Record.updated",
            "PostLoad:" + fixtures + "Record.loaded");
    }

    @Test
    void excludedSuperclassListenersAreNotCalledButLifecycleMethodsAre() { // §3.6.4 @ExcludeSuperclassListeners
        String fixtures = "io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.";
        assertThat(callbacks(io.vidocq.mansart.jpa.core.model.build.fixtures.callbacks.Quiet.class)).containsExactly(
            "PrePersist:" + fixtures + "Tracked.onPersist", "PostPersist:" + fixtures + "Tracked.persisted");
    }
}
