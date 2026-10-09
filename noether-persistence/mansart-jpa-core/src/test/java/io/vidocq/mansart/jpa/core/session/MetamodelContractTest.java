/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.mansart.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Dept;
import io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Emp;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.LocalDateTimeField;
import jakarta.persistence.metamodel.Bindable;
import jakarta.persistence.metamodel.Type;
import org.junit.jupiter.api.Test;

class MetamodelContractTest {
    @Test
    void criteria32NodesPreserveTypesParametersAndNamedQuerySnapshots() { // §6.3; §3.11.1
        try (var factory = factory("extended"); var em = factory.createEntityManager()) {
            var builder = factory.getCriteriaBuilder();
            assertThat(builder.extract(LocalDateTimeField.SECOND, builder.localDateTime()).getJavaType()).isEqualTo(Double.class);
            assertThat(builder.extract(LocalDateTimeField.DATE, builder.localDateTime()).getJavaType()).isEqualTo(java.time.LocalDate.class);
            assertThat(builder.<String>coalesce().value("value").getJavaType()).isEqualTo(String.class);
            assertThat(builder.<String>selectCase().when(builder.conjunction(), "yes").otherwise("no").getJavaType()).isEqualTo(String.class);
            var first = builder.createQuery(String.class);
            var emp = first.from(Emp.class);
            var parameter = builder.parameter(String.class, "name");
            first.select(emp.get("name")).where(builder.equal(emp.get("name"), parameter));
            var typed = em.createQuery(first);
            var returned = typed.getParameter("name", String.class);
            assertThat(returned).isSameAs(parameter);
            typed.setParameter(returned, "original");
            assertThat(typed.getParameterValue(parameter)).isEqualTo("original");
            var second = builder.createQuery(String.class);
            var dept = second.from(Dept.class);
            var anonymous = builder.parameter(String.class);
            second.select(dept.get("name")).where(builder.equal(dept.get("name"), anonymous));
            var union = em.createQuery(builder.union(first, second));
            union.setParameter(parameter, "one").setParameter(anonymous, "two");
            assertThat(union.getParameters()).containsExactlyInAnyOrder(parameter, anonymous);
            typed.setMaxResults(3).setFlushMode(FlushModeType.COMMIT);
            factory.addNamedQuery("criteria-name", typed);
            first.where(builder.disjunction());
            var named = em.createNamedQuery("criteria-name", String.class);
            assertThat(named.getMaxResults()).isEqualTo(3);
            assertThat(named.getFlushMode()).isEqualTo(FlushModeType.COMMIT);
            named.setParameter("name", "snapshot");
        }
    }

    @Test
    void entityJoinsLowerThroughTheSharedTranslator() { // §6.3.3: 3.2 entity joins
        try (var factory = factory("entity-join"); var em = factory.createEntityManager()) {
            var builder = factory.getCriteriaBuilder();
            var query = builder.createQuery(String.class);
            var root = query.from(Emp.class);
            var department = root.join(Dept.class, JoinType.LEFT);
            department.on(builder.equal(root.get("dept").get("id"), department.get("id")));
            query.select(department.get("name"));
            assertThat(em.createQuery(query)).isNotNull();
        }
    }

    private static jakarta.persistence.EntityManagerFactory factory(String name) {
        return new PersistenceConfiguration(name)
            .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
            .managedClass(Dept.class).managedClass(Emp.class)
            .managedClass(io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk.class)
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + name).createEntityManagerFactory();
    }
    @Test
    void namedGraphsAreDeepImmutableCopiesAndValidateTypes() { // §3.8: named graphs are immutable, copies mutable
        try (var factory = new PersistenceConfiguration("graphs")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(Dept.class).managedClass(Emp.class)
                .managedClass(io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:graphs")
                .createEntityManagerFactory(); var em = factory.createEntityManager()) {
            var graph = em.createEntityGraph(Emp.class);
            var department = graph.addSubgraph("dept", Dept.class);
            department.addAttributeNodes("name");
            factory.addNamedEntityGraph("employee", graph);
            department.addAttributeNodes("id");
            var named = em.getEntityGraph("employee");
            assertThat(named.getAttributeNode("dept").getSubgraphs().get(Dept.class).getAttributeNodes()).hasSize(1);
            assertThatThrownBy(() -> named.addAttributeNodes("name")).isInstanceOf(IllegalStateException.class);
            var copy = em.createEntityGraph("employee");
            copy.addAttributeNodes("name");
            assertThat(named.hasAttributeNode("name")).isFalse();
            assertThatThrownBy(() -> graph.addSubgraph("name")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> graph.addSubgraph("dept", Emp.class)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void criteriaSharesQueryExecutionAndValidatesPaths() { // §6.3: queries are built from typed model paths
        try (var factory = new PersistenceConfiguration("criteria")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(Dept.class).managedClass(Emp.class)
                .managedClass(io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:criteria")
                .createEntityManagerFactory()) {
            var builder = factory.getCriteriaBuilder();
            var query = builder.createQuery(Emp.class);
            var employee = query.from(Emp.class);
            var name = builder.parameter(String.class, "name");
            query.select(employee).where(builder.equal(employee.get("name"), name));
            assertThat(query.getRoots()).containsExactly(employee);
            assertThat(query.getParameters()).containsExactly(name);
            assertThatThrownBy(() -> employee.get("missing")).isInstanceOf(IllegalArgumentException.class);
            try (var em = factory.createEntityManager()) {
                assertThat(em.createQuery(query).getParameter("name").getParameterType()).isEqualTo(String.class);
            }
        }
    }
    @Test
    void modelExposesTypedAttributesAndRejectsWrongTypes() { // §5.1: runtime metamodel
        try (var factory = new PersistenceConfiguration("metamodel")
                .provider("io.vidocq.mansart.jpa.core.MansartPersistenceProvider")
                .managedClass(Dept.class).managedClass(Emp.class)
                .managedClass(io.vidocq.mansart.jpa.core.model.build.fixtures.rel.Desk.class)
                .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:metamodel")
                .createEntityManagerFactory()) {
            var model = factory.getMetamodel();
            var employee = model.entity(Emp.class);
            assertThat(model.entity("Emp")).isSameAs(employee);
            assertThat(employee.getPersistenceType()).isEqualTo(Type.PersistenceType.ENTITY);
            assertThat(employee.getBindableType()).isEqualTo(Bindable.BindableType.ENTITY_TYPE);
            assertThat(employee.getSingularAttribute("dept", Dept.class).isAssociation()).isTrue();
            assertThat(employee.getId(long.class).isId()).isTrue();
            assertThat(employee.getVersion(int.class).isVersion()).isTrue();
            assertThat(employee.getAttribute("name").getJavaMember().getName()).isEqualTo("name");
            assertThatThrownBy(() -> employee.getSingularAttribute("name", Long.class))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> model.entity(String.class)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
