/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.AttributeNode;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedSubgraph;
import jakarta.persistence.PersistenceConfiguration;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Subgraph;
import org.junit.jupiter.api.Test;

/** §10.3.1: the entity graphs {@code @NamedEntityGraph} declares, read when the factory is created. */
class NamedEntityGraphsTest {

    private static EntityManagerFactory factory(String name, Class<?>... classes) {
        PersistenceConfiguration configuration = new PersistenceConfiguration(name)
            .provider("io.vidocq.noether.jpa.core.NoetherPersistenceProvider")
            .property(PersistenceConfiguration.JDBC_URL, "jdbc:h2:mem:" + name);
        for (Class<?> managed : classes) {
            configuration.managedClass(managed);
        }
        return configuration.createEntityManagerFactory();
    }

    @Test
    void aDeclaredGraphHoldsItsNodesSubgraphsAndSubclassSubgraphs() {
        try (var factory = factory("named-graphs", NgTeam.class, NgSquad.class, NgPlayer.class);
                var em = factory.createEntityManager()) {
            EntityGraph<?> members = em.getEntityGraph("NgTeam.members");
            assertThat(members.getAttributeNodes()).extracting(AttributeNode::getAttributeName)
                .containsExactlyInAnyOrder("id", "name", "lead");
            AttributeNode<?> lead = members.getAttributeNode("lead");
            Subgraph<?> leadGraph = lead.getSubgraphs().get(NgPlayer.class);
            assertThat(leadGraph.getAttributeNodes()).extracting(AttributeNode::getAttributeName)
                .containsExactly("name", "coach");
            assertThat(EntityGraphs.plan(members).attributes()).containsKeys("id", "name", "lead", "motto");
        }
    }

    @Test
    void aGraphWithoutANameIsNamedAfterItsEntity() { // §10.3.1: name defaults to the entity name
        try (var factory = factory("default-graph", NgTeam.class, NgSquad.class, NgPlayer.class);
                var em = factory.createEntityManager()) {
            assertThat(em.getEntityGraph("NgTeam").getAttributeNodes()).extracting(AttributeNode::getAttributeName)
                .containsExactly("name");
            assertThat(em.getEntityGraph("NgPlayer.coach").getAttributeNodes()).extracting(AttributeNode::getAttributeName)
                .containsExactly("coach");
        }
    }

    @Test
    void aSubgraphThatContainsItselfIsRefused() {
        assertThatThrownBy(() -> factory("cyclic-graph", NgLink.class))
            .isInstanceOf(PersistenceException.class)
            .hasStackTraceContaining("Invalid named subgraph loop");
    }

    @Test
    void anUndeclaredSubgraphIsRefused() {
        assertThatThrownBy(() -> factory("dangling-graph", NgDangling.class))
            .isInstanceOf(PersistenceException.class)
            .hasStackTraceContaining("Invalid named subgraph missing");
    }

    @Test
    void twoGraphsOfTheSameNameAreRefused() {
        assertThatThrownBy(() -> factory("duplicate-graph", NgFirst.class, NgSecond.class))
            .isInstanceOf(PersistenceException.class)
            .hasStackTraceContaining("Duplicate named entity graph same");
    }

    @Test
    @SuppressWarnings("deprecation") // addSubclassSubgraph is the deprecated form of addTreatedSubgraph
    void treatedSubgraphsAreOfEntitySubtypes() { // §3.7.4: a subgraph of a subclass
        try (var factory = factory("treated-graph", NgTeam.class, NgSquad.class, NgPlayer.class);
                var em = factory.createEntityManager()) {
            EntityGraph<NgTeam> graph = em.createEntityGraph(NgTeam.class);
            graph.addTreatedSubgraph(NgSquad.class).addAttributeNodes("motto");
            assertThat(graph.addSubclassSubgraph(NgSquad.class).getClassType()).isEqualTo(NgSquad.class);
            assertThat(EntityGraphs.plan(graph).attributes()).containsOnlyKeys("motto");
            EntityGraph<NgSquad> squad = em.createEntityGraph(NgSquad.class);
            assertThatThrownBy(() -> squad.addSubclassSubgraph(NgPlayer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not an entity subtype");
        }
    }

    @Entity(name = "NgTeam")
    @NamedEntityGraph(name = "NgTeam.members", includeAllAttributes = true,
        attributeNodes = @NamedAttributeNode(value = "lead", subgraph = "lead"),
        subgraphs = @NamedSubgraph(name = "lead", attributeNodes = {@NamedAttributeNode("name"), @NamedAttributeNode("coach")}),
        subclassSubgraphs = @NamedSubgraph(name = "squad", type = NgSquad.class, attributeNodes = @NamedAttributeNode("motto")))
    @NamedEntityGraph(attributeNodes = @NamedAttributeNode("name"))
    public static class NgTeam {
        @Id
        public long id;
        public String name;
        @ManyToOne
        public NgPlayer lead;
    }

    @Entity(name = "NgSquad")
    public static class NgSquad extends NgTeam {
        public String motto;
    }

    @Entity(name = "NgPlayer")
    @NamedEntityGraph(name = "NgPlayer.coach", attributeNodes = @NamedAttributeNode("coach"))
    public static class NgPlayer {
        @Id
        public long id;
        public String name;
        @ManyToOne
        public NgPlayer coach;
    }

    @Entity(name = "NgLink")
    @NamedEntityGraph(name = "chain", attributeNodes = @NamedAttributeNode(value = "next", subgraph = "loop"),
        subgraphs = @NamedSubgraph(name = "loop", attributeNodes = @NamedAttributeNode(value = "next", subgraph = "loop")))
    public static class NgLink {
        @Id
        public long id;
        @ManyToOne
        public NgLink next;
    }

    @Entity(name = "NgDangling")
    @NamedEntityGraph(name = "dangling", attributeNodes = @NamedAttributeNode(value = "next", subgraph = "missing"))
    public static class NgDangling {
        @Id
        public long id;
        @ManyToOne
        public NgDangling next;
    }

    @Entity(name = "NgFirst")
    @NamedEntityGraph(name = "same", attributeNodes = @NamedAttributeNode("id"))
    public static class NgFirst {
        @Id
        public long id;
    }

    @Entity(name = "NgSecond")
    @NamedEntityGraph(name = "same", attributeNodes = @NamedAttributeNode("id"))
    public static class NgSecond {
        @Id
        public long id;
    }
}
