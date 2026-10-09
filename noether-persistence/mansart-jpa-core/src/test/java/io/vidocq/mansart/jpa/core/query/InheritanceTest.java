/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Jakarta Persistence 3.2 §2.13, §2.14, §4.4.8 and §4.6.17.5. */
class InheritanceTest {
    private static final AtomicInteger DATABASES = new AtomicInteger();

    private EntityManagerFactory factory(String ddl, Class<?>... types) throws Exception {
        String url = "jdbc:h2:mem:inheritance" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            for (String sql : ddl.split(";")) {
                statement.execute(sql);
            }
        }
        var configuration = new PersistenceConfiguration("inheritance")
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, url).property(PersistenceConfiguration.JDBC_USER, "sa");
        for (Class<?> type : types) {
            configuration.managedClass(type);
        }
        return configuration.createEntityManagerFactory();
    }

    @Test
    void defaultSingleTableIsPolymorphicAndSharesIdentity() throws Exception { // §2.14.1, §3.3, §4.4.8
        try (var emf = factory("create table Animal (id bigint primary key, name varchar(50), version int, "
                + "DTYPE varchar(31), lives int, bark varchar(20))", Animal.class, Cat.class, Dog.class);
                var em = emf.createEntityManager()) {
            Cat cat = new Cat(1, "cat", 9);
            Dog dog = new Dog(2, "dog");
            em.getTransaction().begin();
            em.persist(cat);
            em.persist(dog);
            em.getTransaction().commit();
            em.clear();
            Animal loaded = em.find(Animal.class, 1L);
            assertThat(loaded).isInstanceOf(Cat.class).isSameAs(em.find(Cat.class, 1L));
            assertThat(em.find(Dog.class, 1L)).isNull();
            assertThat(((Cat) loaded).lives).isEqualTo(9);
            assertThat(em.createQuery("select a from Animal a order by a.id", Animal.class).getResultList())
                .extracting(Object::getClass).containsExactly(Cat.class, Dog.class);
            assertThat(em.createQuery("select c from Cat c", Cat.class).getResultList()).hasSize(1);
            em.getTransaction().begin();
            loaded.name = "updated";
            ((Cat) loaded).lives = 8;
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Cat.class, 1L).name).isEqualTo("updated");
            assertThat(em.find(Cat.class, 1L).lives).isEqualTo(8);
            em.getTransaction().begin();
            em.remove(em.find(Animal.class, 1L));
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Animal.class, 1L)).isNull();
        }
    }

    @Test
    void typeSelectEqualityParametersAndInUseEntityClasses() throws Exception { // §4.6.17.5
        try (var emf = factory("create table Animal (id bigint primary key, name varchar(50), version int, "
                + "DTYPE varchar(31), lives int, bark varchar(20))", Animal.class, Cat.class, Dog.class);
                var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Cat(1, "cat", 9));
            em.persist(new Dog(2, "dog"));
            em.getTransaction().commit();
            em.clear();
            assertThat(em.createQuery("select TYPE(a) from Animal a order by a.id").getResultList())
                .containsExactly(Cat.class, Dog.class);
            assertThat(em.createQuery("select a from Animal a where TYPE(a) = Cat", Animal.class).getResultList())
                .hasSize(1).first().isInstanceOf(Cat.class);
            assertThat(em.createQuery("select a from Animal a where TYPE(a) = :type", Animal.class)
                .setParameter("type", Dog.class).getResultList()).hasSize(1).first().isInstanceOf(Dog.class);
            assertThat(em.createQuery("select a from Animal a where TYPE(a) in (Cat, Dog)", Animal.class).getResultList())
                .hasSize(2);
            assertThat(em.createQuery("select a from Animal a where TYPE(a) in :types", Animal.class)
                .setParameter("types", List.of(Cat.class)).getResultList()).hasSize(1);
            // §4.4.9: a treated path narrows the inherited range to the specified entity subtype.
            assertThat(em.createQuery("select a from Animal a where TREAT(a AS Cat).lives = 9", Animal.class)
                .getResultList()).hasSize(1).first().isInstanceOf(Cat.class);
        }
    }

    @Test
    void criteriaTreatRootsRetainTheirNewJoins() throws Exception { // §6.5.7 TREAT navigation
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50));"
                + "create table Department (id bigint primary key, boss_id bigint)",
                Person.class, Worker.class, Manager.class, Department.class); var em = emf.createEntityManager()) {
            Department department = new Department(); department.id = 1;
            Manager manager = new Manager(); manager.id = 1; manager.name = "chief"; manager.department = department;
            em.getTransaction().begin(); em.persist(department); em.persist(manager); em.getTransaction().commit();
            var builder = emf.getCriteriaBuilder();
            var criteria = builder.createQuery(Long.class);
            var root = criteria.from(Person.class);
            var treated = builder.treat(root, Manager.class);
            criteria.select(treated.join("department").get("id"));
            assertThat(em.createQuery(criteria).getSingleResult()).isEqualTo(1L);
            assertThat(root.getJavaType()).isEqualTo(Person.class);
            assertThat(treated.getJavaType()).isEqualTo(Manager.class);
        }
    }

    @Test
    void joinedTablesLoadConcreteLeavesAndUpdateInheritedState() throws Exception { // §2.14.2
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50))",
                Person.class, Worker.class, Manager.class, Department.class); var em = emf.createEntityManager()) {
            Manager manager = new Manager();
            manager.id = 1;
            manager.name = "chief";
            manager.salary = 100;
            manager.office = "north";
            em.getTransaction().begin();
            em.persist(manager);
            em.getTransaction().commit();
            em.clear();
            Person loaded = em.find(Person.class, 1L);
            assertThat(loaded).isInstanceOf(Manager.class).isSameAs(em.find(Worker.class, 1L))
                .isSameAs(em.find(Manager.class, 1L));
            assertThat(((Manager) loaded).salary).isEqualTo(100);
            assertThat(((Manager) loaded).office).isEqualTo("north");
            assertThat(em.createQuery("select p from Person p", Person.class).getResultList()).containsExactly(loaded);
            assertThat(em.createQuery("select w.salary from Worker w", Integer.class).getResultList()).containsExactly(100);
            assertThat(em.createQuery("select m.office from Manager m", String.class).getResultList()).containsExactly("north");
            assertThat(em.createQuery("select TYPE(p) from Person p").getResultList()).containsExactly(Manager.class);
            em.getTransaction().begin();
            loaded.name = "new";
            ((Manager) loaded).salary = 200;
            ((Manager) loaded).office = "south";
            em.getTransaction().commit();
            em.refresh(loaded);
            assertThat(loaded.name).isEqualTo("new");
            assertThat(((Manager) loaded).salary).isEqualTo(200);
            em.getTransaction().begin();
            em.remove(loaded);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Person.class, 1L)).isNull();
        }
    }

    @Test
    void integerDiscriminatorAndInheritedCallbacks() throws Exception { // §11.1.12, §11.1.13, §3.6.4
        try (var emf = factory("create table Vehicle (id bigint primary key, KIND int, wheels int)",
                Vehicle.class, Bicycle.class); var em = emf.createEntityManager()) {
            Bicycle bicycle = new Bicycle();
            bicycle.id = 1;
            bicycle.wheels = 2;
            em.getTransaction().begin();
            em.persist(bicycle);
            em.getTransaction().commit();
            em.clear();
            Bicycle loaded = (Bicycle) em.find(Vehicle.class, 1L);
            assertThat(loaded.wheels).isEqualTo(2);
            assertThat(loaded.loaded).isTrue();
            assertThat(em.createQuery("select TYPE(v) from Vehicle v").getResultList()).containsExactly(Bicycle.class);
        }
    }

    @Test
    void optionalTablePerClassIsExplicitlyRejected() throws Exception { // §2.14: optional strategy, decision D5 open
        assertThatThrownBy(() -> factory("create table Unsupported (id bigint)", Unsupported.class))
            .isInstanceOf(PersistenceException.class).hasMessageContaining("TABLE_PER_CLASS");
    }

    @Test
    void mappedSuperclassNonentityGapAndIdentityGeneration() throws Exception { // §2.13.2, §2.13.3, §11.1.21
        try (var emf = factory("create table GeneratedBase (id bigint generated by default as identity primary key, "
                + "label varchar(50), DTYPE varchar(31), detail varchar(50))",
                GeneratedBase.class, GeneratedLeaf.class); var em = emf.createEntityManager()) {
            GeneratedLeaf leaf = new GeneratedLeaf();
            leaf.label = "base";
            leaf.detail = "leaf";
            em.getTransaction().begin();
            em.persist(leaf);
            em.getTransaction().commit();
            assertThat(leaf.id).isPositive();
            long id = leaf.id;
            em.clear();
            assertThat(em.find(GeneratedBase.class, id)).isInstanceOf(GeneratedLeaf.class)
                .isSameAs(em.find(GeneratedLeaf.class, id));
            GeneratedLeaf loaded = em.find(GeneratedLeaf.class, id);
            assertThat(loaded.label).isEqualTo("base");
            assertThat(loaded.detail).isEqualTo("leaf");
            assertThat(loaded.nonpersistent).isEqualTo("not a persistent field");
        }
    }

    @Test
    void relationshipsToPolymorphicTargetsAndJoinedDeclaredForeignKeys() throws Exception { // §2.10, §2.14, §4.4.5
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50));"
                + "create table Department (id bigint primary key, boss_id bigint references Person(id))",
                Person.class, Worker.class, Manager.class, Department.class); var em = emf.createEntityManager()) {
            Manager manager = new Manager();
            manager.id = 1;
            manager.name = "chief";
            Department department = new Department();
            department.id = 10;
            department.boss = manager;
            manager.department = department;
            em.getTransaction().begin();
            em.persist(manager);
            em.persist(department);
            em.getTransaction().commit();
            em.clear();
            Department loaded = em.find(Department.class, 10L);
            assertThat(loaded.boss).isInstanceOf(Manager.class).isSameAs(em.find(Person.class, 1L));
            assertThat(((Worker) loaded.boss).department).isSameAs(loaded);
            assertThat(loaded.workers).containsExactly((Worker) loaded.boss);
            assertThat(em.createQuery("select w.department from Worker w").getResultList()).containsExactly(loaded);
            assertThat(em.createQuery("select w from Worker w join w.department d where d.id = 10", Worker.class)
                .getResultList()).containsExactly((Worker) loaded.boss);
            assertThat(em.createQuery("select d.boss from Department d", Person.class).getResultList()).containsExactly(loaded.boss);
            assertThat(em.createQuery("select w from Department d join d.workers w", Worker.class).getResultList())
                .containsExactly((Worker) loaded.boss);
        }
    }

    @Test
    void joinedExplicitCharDiscriminatorAndSubtypeRestriction() throws Exception { // §2.14.2, §11.1.12
        try (var emf = factory("create table CharBase (id bigint primary key, tag char(1));"
                + "create table CharLeaf (id bigint primary key references CharBase(id), detail varchar(50))",
                CharBase.class, CharLeaf.class); var em = emf.createEntityManager()) {
            CharBase base = new CharBase();
            base.id = 1;
            CharLeaf leaf = new CharLeaf();
            leaf.id = 2;
            leaf.detail = "leaf";
            em.getTransaction().begin();
            em.persist(base);
            em.persist(leaf);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(CharBase.class, 2L)).isInstanceOf(CharLeaf.class);
            assertThat(em.find(CharLeaf.class, 1L)).isNull();
            assertThat(em.createQuery("select b from CharBase b order by b.id").getResultList())
                .extracting(Object::getClass).containsExactly(CharBase.class, CharLeaf.class);
            assertThat(em.createQuery("select c.detail from CharLeaf c").getResultList()).containsExactly("leaf");
            assertThat(em.createQuery("select b from CharBase b where TYPE(b) = CharLeaf").getResultList()).hasSize(1);
        }
    }

    @Test
    void bulkSingleTableStatementsRespectSubtypeAndLeaveOtherTypesAlone() throws Exception { // §4.10, §4.4.8
        try (var emf = factory("create table Animal (id bigint primary key, name varchar(50), version int, "
                + "DTYPE varchar(31), lives int, bark varchar(20))", Animal.class, Cat.class, Dog.class);
                var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            em.persist(new Cat(1, "cat", 9));
            em.persist(new Dog(2, "dog"));
            em.getTransaction().commit();
            em.clear();
            em.getTransaction().begin();
            assertThat(em.createQuery("update Cat c set c.name = 'changed'").executeUpdate()).isEqualTo(1);
            em.getTransaction().commit();
            assertThat(em.find(Dog.class, 2L).name).isEqualTo("dog");
            assertThat(em.find(Cat.class, 1L).name).isEqualTo("changed");
            em.getTransaction().begin();
            assertThat(em.createQuery("delete from Cat c").executeUpdate()).isEqualTo(1);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.createQuery("select a from Animal a").getResultList()).singleElement().isInstanceOf(Dog.class);
        }

    }

    @Test
    void compositeJoinedKeysMatchReferencedColumnsNotAnnotationOrder() throws Exception { // §11.1.45, §2.4
        try (var emf = factory("create table PairBase (leftId bigint, rightId bigint, primary key(leftId, rightId));"
                + "create table PairLeaf (k1 bigint, k2 bigint, valueText varchar(50), primary key(k1, k2), "
                + "foreign key(k1, k2) references PairBase(leftId, rightId))", PairBase.class, PairLeaf.class);
                var em = emf.createEntityManager()) {
            PairLeaf leaf = new PairLeaf();
            leaf.leftId = 1;
            leaf.rightId = 2;
            leaf.valueText = "pair";
            em.getTransaction().begin();
            em.persist(leaf);
            em.getTransaction().commit();
            em.clear();
            PairBase loaded = em.find(PairBase.class, new PairId(1, 2));
            assertThat(loaded).isInstanceOf(PairLeaf.class).isSameAs(em.find(PairLeaf.class, new PairId(1, 2)));
            assertThat(((PairLeaf) loaded).valueText).isEqualTo("pair");
            assertThat(em.createQuery("select l.valueText from PairLeaf l").getResultList()).containsExactly("pair");
        }
    }

    @Test
    void joinedBulkUpdatesCaptureValuesBeforeChangesAndDeletesArePolymorphic() throws Exception { // §4.10, §4.4.8
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50));"
                + "create table Department (id bigint primary key, boss_id bigint references Person(id))",
                Person.class, Worker.class, Manager.class, Department.class); var em = emf.createEntityManager()) {
            Manager manager = new Manager();
            manager.id = 1;
            manager.name = "chief";
            manager.salary = 100;
            manager.office = "north";
            Worker worker = new Worker();
            worker.id = 2;
            worker.name = "worker";
            worker.salary = 50;
            em.getTransaction().begin();
            em.persist(manager);
            em.persist(worker);
            em.getTransaction().commit();
            em.clear();
            em.getTransaction().begin();
            assertThat(em.createQuery("update Worker w set w.name = :name, w.salary = w.salary * 2 where w.salary >= 50")
                .setParameter("name", "changed").executeUpdate()).isEqualTo(2);
            em.getTransaction().commit();
            assertThat(em.find(Manager.class, 1L).salary).isEqualTo(200);
            assertThat(em.find(Worker.class, 2L).salary).isEqualTo(100);
            assertThat(em.find(Person.class, 1L).name).isEqualTo("changed");
            em.clear();
            em.getTransaction().begin();
            assertThat(em.createQuery("delete from Manager m where m.office = 'north'").executeUpdate()).isEqualTo(1);
            em.getTransaction().commit();
            assertThat(em.find(Person.class, 1L)).isNull();
            assertThat(em.find(Worker.class, 2L)).isNotNull();
            em.clear();
            em.getTransaction().begin();
            assertThat(em.createQuery("delete from Person p").executeUpdate()).isEqualTo(1);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Person.class, 2L)).isNull();
        }
    }

    @Test
    void joinedLeafOnlyChangesVersionCallbacksMergeAndOptimisticLock() throws Exception { // §3.3.7, §3.5.2, §3.6.4
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50));"
                + "create table Department (id bigint primary key, boss_id bigint references Person(id))",
                Person.class, Worker.class, Manager.class, Department.class); var em = emf.createEntityManager()) {
            Manager manager = new Manager();
            manager.id = 1;
            manager.office = "north";
            em.getTransaction().begin();
            em.persist(manager);
            em.getTransaction().commit();
            int version = manager.version;
            em.getTransaction().begin();
            manager.office = "south";
            em.getTransaction().commit();
            assertThat(manager.version).isGreaterThan(version);
            assertThat(manager.updates).isEqualTo(1);
            em.detach(manager);
            manager.office = "west";
            em.getTransaction().begin();
            Manager merged = em.merge(manager);
            em.getTransaction().commit();
            assertThat(merged).isNotSameAs(manager).isSameAs(em.find(Person.class, 1L));
            em.clear();
            Person loaded = em.find(Person.class, 1L);
            assertThat(((Manager) loaded).office).isEqualTo("west");
            try (var other = emf.createEntityManager()) {
                other.getTransaction().begin();
                Worker concurrent = other.find(Worker.class, 1L);
                concurrent.salary = 10;
                other.getTransaction().commit();
            }
            em.getTransaction().begin();
            ((Manager) loaded).office = "stale";
            assertThatThrownBy(() -> em.getTransaction().commit()).isInstanceOf(RollbackException.class)
                .hasRootCauseInstanceOf(OptimisticLockException.class);
        }
    }

    @Test
    void joinedEmptyLeafTableKeepsDefaultRenamedSuperclassKey() throws Exception { // §2.14.2, §11.1.45
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50));"
                + "create table EmptyManager (manager_id bigint primary key references Manager(manager_id));"
                + "create table Department (id bigint primary key, boss_id bigint references Person(id))",
                Person.class, Worker.class, Manager.class, EmptyManager.class, Department.class);
                var em = emf.createEntityManager()) {
            EmptyManager manager = new EmptyManager();
            manager.id = 1;
            manager.name = "empty leaf";
            em.getTransaction().begin();
            em.persist(manager);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(Person.class, 1L)).isInstanceOf(EmptyManager.class)
                .isSameAs(em.find(EmptyManager.class, 1L));
            assertThat(em.createQuery("select TYPE(p) from Person p").getResultList()).containsExactly(EmptyManager.class);
            assertThat(em.createQuery("select e from EmptyManager e").getResultList()).hasSize(1);
        }
    }

    @Test
    void providerDefaultsForIntegerAndCharDiscriminatorsAreDistinct() throws Exception { // §11.1.13
        try (var emf = factory("create table NumericBase (id bigint primary key, kind int)",
                NumericBase.class, NumericLeaf.class, NumericExplicit.class); var em = emf.createEntityManager()) {
            NumericBase base = new NumericBase();
            base.id = 1;
            NumericLeaf leaf = new NumericLeaf();
            leaf.id = 2;
            NumericExplicit explicit = new NumericExplicit();
            explicit.id = 3;
            em.getTransaction().begin();
            em.persist(base);
            em.persist(leaf);
            em.persist(explicit);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.createQuery("select TYPE(n) from NumericBase n order by n.id").getResultList())
                .containsExactly(NumericBase.class, NumericLeaf.class, NumericExplicit.class);
        }
        try (var emf = factory("create table LetterBase (id bigint primary key, kind char(1))",
                LetterBase.class, LetterLeaf.class); var em = emf.createEntityManager()) {
            LetterBase base = new LetterBase();
            base.id = 1;
            LetterLeaf leaf = new LetterLeaf();
            leaf.id = 2;
            em.getTransaction().begin();
            em.persist(base);
            em.persist(leaf);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.createQuery("select TYPE(n) from LetterBase n order by n.id").getResultList())
                .containsExactly(LetterBase.class, LetterLeaf.class);
        }
    }

    @Test
    void joinedSecondaryTablesUseTheirDeclaringPrimaryKeyDefaults() throws Exception { // §11.1.46, §2.14.2
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table SecondaryWorker (employee_id bigint primary key references Person(id));"
                + "create table EmployeeDetails (employee_id bigint primary key references SecondaryWorker(employee_id), "
                + "detail varchar(50))", Person.class, SecondaryWorker.class); var em = emf.createEntityManager()) {
            SecondaryWorker worker = new SecondaryWorker();
            worker.id = 1;
            worker.detail = "secondary";
            em.getTransaction().begin();
            em.persist(worker);
            em.getTransaction().commit();
            em.clear();
            assertThat(((SecondaryWorker) em.find(Person.class, 1L)).detail).isEqualTo("secondary");
            assertThat(em.createQuery("select w.detail from SecondaryWorker w").getResultList()).containsExactly("secondary");
        }
    }

    @Test
    void inheritedJoinAndElementCollectionTablesKeepDeclaringEntityDefaults() throws Exception { // §2.10, §11.1.8, §2.14.1
        try (var emf = factory("create table HobbyOwner (id bigint primary key, DTYPE varchar(31), detail int);"
                + "create table Hobby (id bigint primary key);"
                + "create table HobbyOwner_Hobby (HobbyOwner_id bigint references HobbyOwner(id), hobbies_id bigint references Hobby(id));"
                + "create table HobbyOwner_tags (HobbyOwner_id bigint references HobbyOwner(id), tags varchar(50))",
                HobbyOwner.class, HobbyChild.class, Hobby.class); var em = emf.createEntityManager()) {
            HobbyChild child = new HobbyChild();
            child.id = 1;
            Hobby hobby = new Hobby();
            hobby.id = 2;
            child.hobbies.add(hobby);
            child.tags.add("inherited");
            em.getTransaction().begin();
            em.persist(child);
            em.getTransaction().commit();
            em.clear();
            HobbyChild loaded = (HobbyChild) em.find(HobbyOwner.class, 1L);
            assertThat(loaded.hobbies).singleElement().isSameAs(em.find(Hobby.class, 2L));
            assertThat(loaded.tags).containsExactly("inherited");
            assertThat(em.createQuery("select t from HobbyChild c join c.tags t").getResultList()).containsExactly("inherited");
            assertThat(em.createQuery("select h from HobbyChild c join c.hobbies h").getResultList()).hasSize(1);
        }
    }

    @Test
    void relationshipToJoinedSubtypeUsesItsPrimaryKeyColumnDefaults() throws Exception { // §11.1.25, §2.14.2
        try (var emf = factory("create table Person (id bigint primary key, name varchar(50), version int);"
                + "create table Worker (worker_id bigint primary key references Person(id), salary int, department_id bigint);"
                + "create table Manager (manager_id bigint primary key references Worker(worker_id), office varchar(50));"
                + "create table Department (id bigint primary key, boss_id bigint references Person(id));"
                + "create table WorkerReference (id bigint primary key, worker_worker_id bigint references Worker(worker_id))",
                Person.class, Worker.class, Manager.class, Department.class, WorkerReference.class);
                var em = emf.createEntityManager()) {
            Manager manager = new Manager();
            manager.id = 1;
            WorkerReference reference = new WorkerReference();
            reference.id = 2;
            reference.worker = manager;
            em.getTransaction().begin();
            em.persist(manager);
            em.persist(reference);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(WorkerReference.class, 2L).worker).isInstanceOf(Manager.class)
                .isSameAs(em.find(Person.class, 1L));
            assertThat(em.createQuery("select r.worker from WorkerReference r").getResultList()).hasSize(1);
        }
    }

    @Test
    void orderedInverseSubtypeCollectionsWriteTheDeclaringTableAndFilterSiblings() throws Exception { // §11.1.42, §4.4.8
        try (var emf = factory("create table OrderedOwner (id bigint primary key);"
                + "create table OrderedBase (id bigint primary key, DTYPE varchar(31), owner_id bigint references OrderedOwner(id), "
                + "position int, detail int)", OrderedOwner.class, OrderedBase.class, OrderedLeaf.class);
                var em = emf.createEntityManager()) {
            OrderedOwner owner = new OrderedOwner();
            owner.id = 1;
            OrderedLeaf leaf = new OrderedLeaf();
            leaf.id = 2;
            leaf.owner = owner;
            owner.leaves.add(leaf);
            OrderedBase base = new OrderedBase();
            base.id = 3;
            base.owner = owner;
            em.getTransaction().begin();
            em.persist(owner);
            em.persist(leaf);
            em.persist(base);
            em.getTransaction().commit();
            em.clear();
            assertThat(em.find(OrderedOwner.class, 1L).leaves).singleElement()
                .isSameAs(em.find(OrderedLeaf.class, 2L));
        }
    }

    @Entity(name = "OrderedOwner")
    public static class OrderedOwner {
        @Id public long id;
        @OneToMany(mappedBy = "owner") @OrderColumn(name = "position")
        public List<OrderedLeaf> leaves = new java.util.ArrayList<>();
        public OrderedOwner() {}
    }
    @Entity(name = "OrderedBase")
    public static class OrderedBase {
        @Id public long id;
        @ManyToOne public OrderedOwner owner;
        public OrderedBase() {}
    }
    @Entity(name = "OrderedLeaf")
    public static class OrderedLeaf extends OrderedBase {
        public int detail;
        public OrderedLeaf() {}
    }

    @Entity(name = "Hobby")
    public static class Hobby {
        @Id public long id;
        public Hobby() {}
    }
    @Entity(name = "HobbyOwner")
    public static class HobbyOwner {
        @Id public long id;
        @ManyToMany(cascade = CascadeType.ALL) public List<Hobby> hobbies = new java.util.ArrayList<>();
        @ElementCollection public List<String> tags = new java.util.ArrayList<>();
        public HobbyOwner() {}
    }
    @Entity(name = "HobbyChild")
    public static class HobbyChild extends HobbyOwner {
        public int detail;
        public HobbyChild() {}
    }
    @Entity(name = "WorkerReference")
    public static class WorkerReference {
        @Id public long id;
        @ManyToOne public Worker worker;
        public WorkerReference() {}
    }

    @Entity(name = "SecondaryWorker") @PrimaryKeyJoinColumn(name = "employee_id")
    @SecondaryTable(name = "EmployeeDetails")
    public static class SecondaryWorker extends Person {
        @Column(table = "EmployeeDetails") public String detail;
        public SecondaryWorker() {}
    }

    @Entity(name = "NumericBase") @Inheritance
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.INTEGER)
    public static class NumericBase {
        @Id public long id;
        public NumericBase() {}
    }
    @Entity(name = "NumericLeaf")
    public static class NumericLeaf extends NumericBase { public NumericLeaf() {} }
    @Entity(name = "NumericExplicit") @DiscriminatorValue("1")
    public static class NumericExplicit extends NumericBase { public NumericExplicit() {} }
    @Entity(name = "LetterBase") @Inheritance
    @DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.CHAR)
    public static class LetterBase {
        @Id public long id;
        public LetterBase() {}
    }
    @Entity(name = "LetterLeaf")
    public static class LetterLeaf extends LetterBase { public LetterLeaf() {} }

    @Entity(name = "Animal")
    public abstract static class Animal {
        @Id public long id;
        public String name;
        @Version public int version;
        protected Animal() {}
    }
    @Entity(name = "Cat")
    public static class Cat extends Animal {
        public int lives;
        public Cat() {}
        Cat(long id, String name, int lives) { this.id = id; this.name = name; this.lives = lives; }
    }
    @Entity(name = "Dog")
    public static class Dog extends Animal {
        public String bark;
        public Dog() {}
        Dog(long id, String name) { this.id = id; this.name = name; bark = "woof"; }
    }
    @Entity(name = "Person") @Inheritance(strategy = InheritanceType.JOINED)
    public abstract static class Person {
        @Id public long id;
        public String name;
        @Version public int version;
        @Transient public int updates;
        @PreUpdate protected void beforeUpdate() { updates++; }
        protected Person() {}
    }
    @Entity(name = "Worker") @PrimaryKeyJoinColumn(name = "worker_id")
    public static class Worker extends Person {
        public int salary;
        @ManyToOne public Department department;
        public Worker() {}
    }
    @Entity(name = "Manager") @PrimaryKeyJoinColumn(name = "manager_id", referencedColumnName = "worker_id")
    public static class Manager extends Worker {
        public String office;
        public Manager() {}
    }
    @Entity(name = "EmptyManager")
    public static class EmptyManager extends Manager {
        public EmptyManager() {}
    }
    @Entity(name = "Vehicle") @Inheritance
    @DiscriminatorColumn(name = "KIND", discriminatorType = DiscriminatorType.INTEGER)
    public abstract static class Vehicle {
        @Id public long id;
        @Transient public boolean loaded;
        @PostLoad protected void postLoad() { loaded = true; }
        protected Vehicle() {}
    }
    @Entity(name = "Bicycle") @DiscriminatorValue("7")
    public static class Bicycle extends Vehicle {
        public int wheels;
        public Bicycle() {}
    }
    @Entity(name = "Unsupported") @Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
    public static class Unsupported {
        @Id public long id;
        public Unsupported() {}
    }
    @MappedSuperclass
    public abstract static class GeneratedId {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public long id;
        protected GeneratedId() {}
    }
    @Entity(name = "GeneratedBase")
    public abstract static class GeneratedBase extends GeneratedId {
        public String label;
        protected GeneratedBase() {}
    }
    public abstract static class NonentityGap extends GeneratedBase {
        public String nonpersistent = "not a persistent field";
        protected NonentityGap() {}
    }
    @Entity(name = "GeneratedLeaf")
    public static class GeneratedLeaf extends NonentityGap {
        public String detail;
        public GeneratedLeaf() {}
    }
    @Entity(name = "Department")
    public static class Department {
        @Id public long id;
        @ManyToOne public Person boss;
        @OneToMany(mappedBy = "department") public List<Worker> workers = new java.util.ArrayList<>();
        public Department() {}
    }
    @Entity(name = "CharBase") @Inheritance(strategy = InheritanceType.JOINED)
    @DiscriminatorColumn(name = "tag", discriminatorType = DiscriminatorType.CHAR) @DiscriminatorValue("B")
    public static class CharBase {
        @Id public long id;
        public CharBase() {}
    }
    @Entity(name = "CharLeaf") @DiscriminatorValue("L")
    public static class CharLeaf extends CharBase {
        public String detail;
        public CharLeaf() {}
    }
    public record PairId(long leftId, long rightId) implements java.io.Serializable {}
    @Entity(name = "PairBase") @Inheritance(strategy = InheritanceType.JOINED) @IdClass(PairId.class)
    public abstract static class PairBase {
        @Id public long leftId;
        @Id public long rightId;
        protected PairBase() {}
    }
    @Entity(name = "PairLeaf")
    @PrimaryKeyJoinColumns({@PrimaryKeyJoinColumn(name = "k2", referencedColumnName = "rightId"),
        @PrimaryKeyJoinColumn(name = "k1", referencedColumnName = "leftId")})
    public static class PairLeaf extends PairBase {
        public String valueText;
        public PairLeaf() {}
    }
}
