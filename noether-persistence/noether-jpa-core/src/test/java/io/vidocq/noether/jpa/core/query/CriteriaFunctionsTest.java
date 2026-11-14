/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 §6.5: string functions, treated joins, orders and subqueries of the Criteria API. */
class CriteriaFunctionsTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private EntityManagerFactory emf;
    private EntityManager em;
    private CriteriaBuilder cb;

    @BeforeEach
    void open() {
        emf = new PersistenceConfiguration("criteria-functions")
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .managedClass(CqShelf.class).managedClass(CqBook.class).managedClass(CqRare.class)
            .property(PersistenceConfiguration.JDBC_URL,
                "jdbc:h2:mem:criteria-functions" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1")
            .property(PersistenceConfiguration.JDBC_USER, "sa")
            .property(PersistenceConfiguration.SCHEMAGEN_DATABASE_ACTION, "drop-and-create")
            .createEntityManagerFactory();
        em = emf.createEntityManager();
        cb = em.getCriteriaBuilder();
        CqShelf main = new CqShelf(1, "Main");
        em.getTransaction().begin();
        em.persist(main);
        em.persist(new CqShelf(2, "Empty"));
        em.persist(new CqBook(1, "Dune", null, main));
        em.persist(new CqRare(2, "Emma", "Classic", main, 3));
        em.getTransaction().commit();
        em.clear();
    }

    @AfterEach
    void close() {
        em.close();
        emf.close();
    }

    @Test
    void everyOverloadOfTheStringFunctionsBuildsItsFunction() { // §6.5.9 string functions
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<CqBook> book = query.from(CqBook.class);
        Path<String> title = book.get("title");
        Path<String> subtitle = book.get("subtitle");
        query.multiselect(
            cb.concat(List.of(title, cb.literal("!"))), cb.concat(title, cb.literal("-")), cb.concat(title, "+"),
            cb.concat("<", title),
            cb.substring(title, cb.literal(2)), cb.substring(title, 2),
            cb.substring(title, cb.literal(1), cb.literal(2)), cb.substring(title, 1, 2),
            cb.locate(title, cb.literal("u")), cb.locate(title, "u"),
            cb.locate(title, cb.literal("e"), cb.literal(2)), cb.locate(title, "e", 2),
            cb.replace(title, cb.literal("u"), cb.literal("U")), cb.replace(title, "u", cb.literal("U")),
            cb.replace(title, cb.literal("u"), "U"), cb.replace(title, "u", "U"),
            cb.coalesce(subtitle, cb.literal("none")), cb.coalesce(subtitle, "none"))
            .where(cb.equal(book.get("id"), 1L));
        assertThat(em.createQuery(query).getSingleResult()).containsExactly(
            "Dune!", "Dune-", "Dune+", "<Dune", "une", "une", "Du", "Du", 2, 2, 4, 4,
            "DUne", "DUne", "DUne", "DUne", "none", "none");
    }

    @Test
    void aTreatedJoinReachesTheAttributesOfTheSubtype() { // §6.5.7 TREAT of a join
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<CqShelf> shelf = query.from(CqShelf.class);
        Join<CqShelf, CqBook> books = shelf.join("books");
        Join<CqShelf, CqRare> rare = cb.treat(books, CqRare.class);
        query.select(rare.get("title")).where(cb.gt(rare.<Integer>get("copies"), 1));
        assertThat(em.createQuery(query).getResultList()).containsExactly("Emma");
        assertThat(rare.getJavaType()).isEqualTo(CqRare.class);
        assertThat(books.getJavaType()).isEqualTo(CqBook.class);
    }

    @Test
    void aReversedOrderSwapsDirectionAndNullPrecedence() { // §6.5.12
        Root<CqBook> book = cb.createQuery(CqBook.class).from(CqBook.class);
        Order first = cb.asc(book.get("subtitle"), Nulls.FIRST);
        assertThat(first.reverse().isAscending()).isFalse();
        assertThat(first.reverse().getNullPrecedence()).isEqualTo(Nulls.LAST);
        assertThat(cb.desc(book.get("subtitle"), Nulls.LAST).reverse().getNullPrecedence()).isEqualTo(Nulls.FIRST);
        assertThat(cb.asc(book.get("subtitle")).reverse().getNullPrecedence()).isEqualTo(Nulls.NONE);
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<CqBook> ordered = query.from(CqBook.class);
        query.select(ordered.get("title")).orderBy(cb.asc(ordered.get("subtitle"), Nulls.LAST).reverse());
        assertThat(em.createQuery(query).getResultList()).containsExactly("Dune", "Emma");
    }

    @Test
    void aNegatedConjunctionAndACollectionValuedInList() { // §6.5.10, §4.6.9
        CriteriaQuery<String> negated = cb.createQuery(String.class);
        Root<CqBook> book = negated.from(CqBook.class);
        negated.select(book.get("title"))
            .where(cb.and(cb.equal(book.get("id"), 1L), cb.equal(book.get("title"), "Dune")).not());
        assertThat(em.createQuery(negated).getResultList()).containsExactly("Emma");

        CriteriaQuery<String> listed = cb.createQuery(String.class);
        Root<CqBook> each = listed.from(CqBook.class);
        Expression<Collection<?>> titles = collectionValued(cb.parameter(Collection.class, "titles"));
        listed.select(each.get("title")).where(each.get("title").in(titles));
        assertThat(em.createQuery(listed).setParameter("titles", List.of("Dune")).getResultList()).containsExactly("Dune");
    }

    @SuppressWarnings("unchecked") // a parameter of class Collection holds any collection
    private static Expression<Collection<?>> collectionValued(Expression<?> parameter) {
        return (Expression<Collection<?>>) parameter;
    }

    @Test
    void aCollectionAttributeSelectsItsElements() { // §4.8, §6.5.5
        var books = em.getMetamodel().entity(CqShelf.class).getCollection("books", CqBook.class);
        CriteriaQuery<Object> query = cb.createQuery(Object.class);
        Root<CqShelf> shelf = query.from(CqShelf.class);
        query.select(shelf.get(books)).where(cb.equal(shelf.get("id"), 1L));
        assertThat(em.createQuery(query).getResultList()).extracting(b -> ((CqBook) b).title)
            .containsExactlyInAnyOrder("Dune", "Emma");
    }

    @Test
    void aQueryWithoutSelectionSelectsItsOnlyRootOrIsRefused() { // §6.5.11
        CriteriaQuery<CqShelf> query = cb.createQuery(CqShelf.class);
        query.from(CqShelf.class);
        assertThat(em.createQuery(query).getResultList()).extracting(s -> s.label)
            .containsExactlyInAnyOrder("Main", "Empty");
        CriteriaQuery<String> unselected = cb.createQuery(String.class);
        unselected.from(CqShelf.class);
        assertThatThrownBy(() -> em.createQuery(unselected))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Query needs a selection");
    }

    @Test
    void anExistsSubqueryWithoutSelection() { // §6.5.10 EXISTS
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<CqShelf> shelf = query.from(CqShelf.class);
        Subquery<CqBook> sub = query.subquery(CqBook.class);
        Root<CqBook> book = sub.from(CqBook.class);
        sub.where(cb.equal(book.get("shelf"), shelf));
        query.select(shelf.get("label")).where(cb.exists(sub));
        assertThat(em.createQuery(query).getResultList()).containsExactly("Main");
    }

    @Test
    void correlatedRootsAndJoinsRangeOverTheirJoins() { // §6.5.8 correlated subqueries
        CriteriaQuery<String> query = cb.createQuery(String.class);
        Root<CqShelf> shelf = query.from(CqShelf.class);
        Subquery<Long> count = query.subquery(Long.class);
        Join<CqShelf, CqBook> owned = count.correlate(shelf).join("books");
        count.select(cb.count(owned));
        query.select(shelf.get("label")).where(cb.gt(count, 1L));
        assertThat(em.createQuery(query).getResultList()).containsExactly("Main");

        CriteriaQuery<String> titles = cb.createQuery(String.class);
        Root<CqShelf> outer = titles.from(CqShelf.class);
        Join<CqShelf, CqBook> book = outer.join("books");
        Subquery<String> label = titles.subquery(String.class);
        Join<CqBook, CqShelf> back = label.correlate(book).join("shelf");
        label.select(back.get("label"));
        titles.select(book.get("title")).where(cb.equal(label, "Main"));
        assertThat(em.createQuery(titles).getResultList()).containsExactlyInAnyOrder("Dune", "Emma");
    }

    @Entity(name = "CqShelf")
    public static class CqShelf {
        @Id
        public long id;
        public String label;
        @OneToMany(mappedBy = "shelf")
        public Collection<CqBook> books = new ArrayList<>();

        protected CqShelf() {
        }

        CqShelf(long id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    @Entity(name = "CqBook")
    public static class CqBook {
        @Id
        public long id;
        public String title;
        public String subtitle;
        @ManyToOne
        public CqShelf shelf;

        protected CqBook() {
        }

        CqBook(long id, String title, String subtitle, CqShelf shelf) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.shelf = shelf;
        }
    }

    @Entity(name = "CqRare")
    public static class CqRare extends CqBook {
        public Integer copies;

        protected CqRare() {
        }

        CqRare(long id, String title, String subtitle, CqShelf shelf, Integer copies) {
            super(id, title, subtitle, shelf);
            this.copies = copies;
        }
    }
}
