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
package io.vidocq.noether.jpa.core.query;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 §2.14.3: concrete tables, polymorphic queries and shared identity. */
class TablePerClassTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private EntityManagerFactory factory(Class<?>... types) {
        var configuration = new PersistenceConfiguration("table-per-class")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:tpc" + DATABASES.incrementAndGet())
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create");
        for (Class<?> type : types) configuration.managedClass(type);
        return configuration.createEntityManagerFactory();
    }

    @Test
    void concreteTablesIncludeInheritedStateWithoutAbstractTablesOrDiscriminators() {
        try (var emf = factory(Asset.class, Book.class, Film.class, Shelf.class);
                var em = emf.createEntityManager()) {
            Shelf shelf = new Shelf(); shelf.id = 1;
            Book book = new Book(); book.title = "book"; book.pages = 200; book.notes = "notes"; book.shelf = shelf;
            book.category = "paper"; book.tags.add("read");
            Film film = new Film(); film.title = "film"; film.duration = 90; film.shelf = shelf;
            em.getTransaction().begin(); em.persist(shelf); em.persist(book); em.persist(film); em.getTransaction().commit();
            assertThat(book.id).isNotEqualTo(film.id);
            assertThat(em.createNativeQuery("select count(*) from INFORMATION_SCHEMA.TABLES where TABLE_NAME = 'ASSET'")
                .getSingleResult()).isEqualTo(0L);
            assertThat(em.createNativeQuery("select count(*) from INFORMATION_SCHEMA.COLUMNS where COLUMN_NAME = 'DTYPE'")
                .getSingleResult()).isEqualTo(0L);
            em.clear();
            Asset loaded = em.find(Asset.class, book.id);
            assertThat(loaded).isInstanceOf(Book.class).isSameAs(em.find(Book.class, book.id));
            assertThat(em.find(Film.class, book.id)).isNull();
            assertThat(((Book) loaded).notes).isEqualTo("notes");
            assertThat(loaded.category).isEqualTo("paper");
            assertThat(loaded.tags).containsExactly("read");
            assertThat(loaded.loaded).isTrue();
            assertThat(((Book) loaded).shelf.assets).hasSize(2);
            assertThat(em.createQuery("select a from Asset a order by a.title", Asset.class).getResultList())
                .extracting(a -> a.title).containsExactly("book", "film");
            assertThat(em.createQuery("select a from Asset a order by a.title", Asset.class)
                .setFirstResult(1).setMaxResults(1).getSingleResult()).isInstanceOf(Film.class);
            assertThat(em.createQuery("select count(a) from Asset a where a.title like '%i%'", Long.class)
                .getSingleResult()).isEqualTo(1L);
            assertThat(em.createQuery("select TYPE(a) from Asset a order by a.title").getResultList())
                .containsExactly(Book.class, Film.class);
            assertThat(em.createQuery("select a from Asset a where TYPE(a) = :type", Asset.class)
                .setParameter("type", Book.class).getResultList()).containsExactly(loaded);
            assertThat(em.createQuery("select a from Asset a where TREAT(a AS Book).pages > 100", Asset.class)
                .getResultList()).containsExactly(loaded);
            var cb = emf.getCriteriaBuilder();
            var query = cb.createQuery(Asset.class); var root = query.from(Asset.class);
            query.select(root).where(cb.equal(root.get("title"), "film"));
            assertThat(em.createQuery(query).getSingleResult()).isInstanceOf(Film.class);
            em.getTransaction().begin(); loaded.title = "new"; ((Book) loaded).notes = "changed";
            em.getTransaction().commit(); em.clear();
            assertThat(em.find(Book.class, book.id).title).isEqualTo("new");
            assertThat(em.find(Book.class, book.id).version).isEqualTo(1);
            assertThat(em.find(Book.class, book.id).notes).isEqualTo("changed");
            em.getTransaction().begin(); em.remove(em.find(Asset.class, film.id)); em.getTransaction().commit();
            em.clear(); assertThat(em.find(Asset.class, film.id)).isNull();
        }
    }

    @Test
    void bulkMutationsApplySqlPredicatesAndCapturedValuesAcrossConcreteTables() {
        try (var emf = factory(Asset.class, Book.class, Film.class, Shelf.class);
                var em = emf.createEntityManager()) {
            Book book = new Book(); book.title = "old"; book.pages = 12; book.notes = "keep"; book.tags.add("bulk");
            Shelf borrower = new Shelf(); borrower.id = 4; book.borrowers.add(borrower);
            Film film = new Film(); film.title = "old"; film.duration = 30;
            em.getTransaction().begin(); em.persist(borrower); em.persist(book); em.persist(film); em.getTransaction().commit(); em.clear();
            em.getTransaction().begin();
            assertThat(em.createQuery("update Asset a set a.title = concat(a.title, '-new') where a.title = 'old'")
                .executeUpdate()).isEqualTo(2);
            em.getTransaction().commit(); em.clear();
            assertThat(em.find(Book.class, book.id).title).isEqualTo("old-new");
            assertThat(em.find(Film.class, film.id).title).isEqualTo("old-new");
            em.getTransaction().begin();
            assertThat(em.createQuery("update Asset a set a.category = a.title where a.id > 0").executeUpdate()).isEqualTo(2);
            em.getTransaction().commit(); em.clear();
            assertThat(em.find(Book.class, book.id).category).isEqualTo("old-new");
            assertThat(em.find(Film.class, film.id).category).isEqualTo("old-new");
            em.getTransaction().begin();
            assertThat(em.createQuery("delete from Asset a where TYPE(a) = Book").executeUpdate()).isEqualTo(1);
            em.getTransaction().commit(); em.clear();
            assertThat(em.find(Asset.class, book.id)).isNull();
            assertThat(em.find(Asset.class, film.id)).isInstanceOf(Film.class);
            assertThat(em.createNativeQuery("select count(*) from Asset_tags").getSingleResult()).isEqualTo(0L);
            assertThat(em.find(Shelf.class, 4L)).isNotNull();
            assertThat(em.createNativeQuery("select borrowed_book from Shelf where id = 4").getSingleResult()).isNull();
        }
    }

    @Test
    void identityGenerationIsRejectedRatherThanAllocatingCollidingHierarchyKeys() {
        assertThatThrownBy(() -> factory(IdentityRoot.class, IdentityLeaf.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("IDENTITY").hasMessageContaining("TABLE_PER_CLASS");
    }

    @Test
    void concreteRootCompositeKeysAndPolymorphicJoinTableTargets() {
        try (var emf = factory(Record.class, DetailedRecord.class, Holder.class);
                var em = emf.createEntityManager()) {
            Record root = new Record(); root.left = 1; root.right = 2; root.label = "root";
            DetailedRecord leaf = new DetailedRecord(); leaf.left = 3; leaf.right = 4; leaf.label = "leaf"; leaf.detail = 7;
            Holder holder = new Holder(); holder.id = 1; holder.favorite = leaf; holder.records.add(root); holder.records.add(leaf);
            em.getTransaction().begin(); em.persist(root); em.persist(leaf); em.persist(holder); em.getTransaction().commit(); em.clear();
            assertThat(em.find(Record.class, new RecordKey(3, 4))).isInstanceOf(DetailedRecord.class)
                .isSameAs(em.find(DetailedRecord.class, new RecordKey(3, 4)));
            Holder loaded = em.find(Holder.class, 1L);
            assertThat(loaded.favorite).isInstanceOf(DetailedRecord.class);
            assertThat(loaded.records).hasSize(2);
            assertThat(em.createQuery("select r from Holder h join h.records r order by r.label", Record.class).getResultList())
                .extracting(r -> r.label).containsExactly("leaf", "root");
            assertThat(em.createQuery("select h.favorite from Holder h", Record.class).getSingleResult()).isSameAs(loaded.favorite);
            assertThat(em.createQuery("select sum(TREAT(r AS DetailedRecord).detail) from Record r", Long.class)
                .getSingleResult()).isEqualTo(7L);
            em.getTransaction().begin();
            assertThat(em.createQuery("update Record r set r.label = 'changed' where r.left > 0").executeUpdate()).isEqualTo(2);
            em.getTransaction().commit(); em.clear();
            assertThat(em.find(Record.class, new RecordKey(1, 2)).label).isEqualTo("changed");
            assertThat(em.find(DetailedRecord.class, new RecordKey(3, 4)).label).isEqualTo("changed");
        }
    }

    @Test
    void polymorphicTargetForeignKeysAreWrittenAndQueriedInEachConcreteTable() {
        try (var emf = factory(Asset.class, Book.class, Film.class, Shelf.class);
                var em = emf.createEntityManager()) {
            Shelf shelf = new Shelf(); shelf.id = 1;
            Book book = new Book(); book.title = "book"; book.pages = 10;
            Film film = new Film(); film.title = "film"; film.duration = 20;
            shelf.selected.add(book); shelf.selected.add(film);
            em.getTransaction().begin(); em.persist(book); em.persist(film); em.persist(shelf); em.getTransaction().commit(); em.clear();
            Shelf loaded = em.find(Shelf.class, 1L);
            assertThat(loaded.selected).extracting(a -> a.title).containsExactly("book", "film");
            assertThat(em.createQuery("select a from Shelf s join s.selected a order by a.title", Asset.class).getResultList())
                .extracting(a -> a.title).containsExactly("book", "film");
            em.getTransaction().begin(); loaded.selected.removeFirst(); em.getTransaction().commit(); em.clear();
            assertThat(em.find(Shelf.class, 1L).selected).extracting(a -> a.title).containsExactly("film");
            em.getTransaction().begin();
            assertThat(em.createQuery("delete from Shelf s where s.id = 1").executeUpdate()).isEqualTo(1);
            em.getTransaction().commit(); em.clear();
            assertThat(em.find(Film.class, film.id)).isNotNull();
            assertThat(em.createNativeQuery("select selected_shelf from Film where id = " + film.id).getSingleResult()).isNull();
        }
    }

    @Test
    void siblingColumnsWithDifferentTypesStayDistinctThroughAbstractIntermediateEntities() {
        try (var emf = factory(Record.class, IntermediateRecord.class, NumberRecord.class, TextRecord.class);
                var em = emf.createEntityManager()) {
            NumberRecord number = new NumberRecord(); number.left = 1; number.right = 2; number.payload = 19;
            TextRecord text = new TextRecord(); text.left = 3; text.right = 4; text.payload = "text";
            em.getTransaction().begin(); em.persist(number); em.persist(text); em.getTransaction().commit(); em.clear();
            assertThat(em.find(Record.class, new RecordKey(1, 2))).isInstanceOf(NumberRecord.class)
                .isSameAs(em.find(IntermediateRecord.class, new RecordKey(1, 2)));
            assertThat(em.createQuery("select sum(TREAT(r AS NumberRecord).payload) from Record r", Long.class)
                .getSingleResult()).isEqualTo(19L);
            assertThat(em.createQuery("select r from Record r where TREAT(r AS TextRecord).payload = 'text'", Record.class)
                .getResultList()).hasSize(1).first().isInstanceOf(TextRecord.class);
            assertThat(em.createQuery("select count(r) from IntermediateRecord r", Long.class).getSingleResult()).isEqualTo(1L);
        }
    }

    @Test
    void polymorphicEntityQueriesLockConcreteRowsAfterSqlPaging() {
        try (var emf = factory(Record.class, DetailedRecord.class);
                var em = emf.createEntityManager()) {
            Record root = new Record(); root.left = 1; root.right = 2;
            DetailedRecord leaf = new DetailedRecord(); leaf.left = 3; leaf.right = 4;
            em.getTransaction().begin(); em.persist(root); em.persist(leaf); em.getTransaction().commit(); em.clear();
            em.getTransaction().begin();
            Record locked = em.createQuery("select r from Record r order by r.left", Record.class)
                .setFirstResult(1).setMaxResults(1).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
            assertThat(locked).isInstanceOf(DetailedRecord.class);
            assertThat(em.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
            try (var other = emf.createEntityManager()) {
                other.getTransaction().begin();
                assertThatThrownBy(() -> other.find(DetailedRecord.class, new RecordKey(3, 4),
                    LockModeType.PESSIMISTIC_WRITE, java.util.Map.of("jakarta.persistence.lock.timeout", 0)))
                    .isInstanceOfAny(LockTimeoutException.class, PessimisticLockException.class);
                other.getTransaction().rollback();
            }
            assertThatThrownBy(() -> em.createQuery("select r.label from Record r").setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()).isInstanceOf(PersistenceException.class).hasMessageContaining("entity");
            em.getTransaction().rollback();
        }
    }

    @Entity(name = "IntermediateRecord")
    public abstract static class IntermediateRecord extends Record { protected IntermediateRecord() {} }
    @Entity(name = "NumberRecord")
    public static class NumberRecord extends IntermediateRecord { public int payload; public NumberRecord() {} }
    @Entity(name = "TextRecord")
    public static class TextRecord extends Record { public String payload; public TextRecord() {} }

    public record RecordKey(long left, long right) implements java.io.Serializable {}
    @Entity(name = "Record") @Table(name = "BaseRecord")
    @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS) @IdClass(RecordKey.class)
    public static class Record {
        @Id @Column(name = "left_id") public long left;
        @Id @Column(name = "right_id") public long right;
        public String label;
        public Record() {}
    }
    @Entity(name = "DetailedRecord")
    public static class DetailedRecord extends Record { public int detail; public DetailedRecord() {} }
    @Entity(name = "Holder")
    public static class Holder {
        @Id public long id;
        @ManyToOne @JoinTable(name = "Favorite") public Record favorite;
        @OneToMany @JoinTable(name = "HeldRecords") public List<Record> records = new ArrayList<>();
        public Holder() {}
    }

    @Entity(name = "Asset") @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    @SecondaryTable(name = "AssetMeta")
    public abstract static class Asset {
        @Id @GeneratedValue(strategy = GenerationType.SEQUENCE)
        @SequenceGenerator(name = "assets", sequenceName = "asset_ids", allocationSize = 1)
        public long id;
        public String title;
        @Column(table = "AssetMeta") public String category;
        @Version public int version;
        @ManyToOne public Shelf shelf;
        @ElementCollection public List<String> tags = new ArrayList<>();
        @Transient public boolean loaded;
        @PostLoad protected void loaded() { loaded = true; }
        protected Asset() {}
    }
    @Entity(name = "Book") @SecondaryTable(name = "BookNotes")
    public static class Book extends Asset {
        public int pages;
        @Column(table = "BookNotes") public String notes;
        @OneToMany @JoinColumn(name = "borrowed_book") public List<Shelf> borrowers = new ArrayList<>();
        public Book() {}
    }
    @Entity(name = "Film")
    public static class Film extends Asset {
        public int duration;
        public Film() {}
    }
    @Entity(name = "Shelf")
    public static class Shelf {
        @Id public long id;
        @OneToMany(mappedBy = "shelf") public List<Asset> assets = new ArrayList<>();
        @OneToMany @JoinColumn(name = "selected_shelf") @OrderColumn(name = "selected_position")
        public List<Asset> selected = new ArrayList<>();
        public Shelf() {}
    }
    @Entity @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public abstract static class IdentityRoot {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        protected IdentityRoot() {}
    }
    @Entity public static class IdentityLeaf extends IdentityRoot { public IdentityLeaf() {} }
}
