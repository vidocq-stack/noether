/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.validation.core.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import jakarta.validation.ElementKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Jakarta Validation 3.1, 6.2 (Path.Node): the kind of each node and its position in a container. */
class NodeImplTest {

    @Test
    void eachFactoryMakesANodeOfItsKindOutsideAnyContainer() {
        assertThat(List.of(NodeImpl.property("p"), NodeImpl.bean(), NodeImpl.method("m", List.of(int.class)),
                NodeImpl.constructor("C", List.of()), NodeImpl.parameter("arg0", 0), NodeImpl.returnValue(),
                NodeImpl.crossParameter()))
            .extracting(NodeImpl::getKind, NodeImpl::isInIterable, NodeImpl::getIndex, NodeImpl::getKey,
                NodeImpl::getContainerClass)
            .containsExactly(
                tuple(ElementKind.PROPERTY, false, null, null, null),
                tuple(ElementKind.BEAN, false, null, null, null),
                tuple(ElementKind.METHOD, false, null, null, null),
                tuple(ElementKind.CONSTRUCTOR, false, null, null, null),
                tuple(ElementKind.PARAMETER, false, null, null, null),
                tuple(ElementKind.RETURN_VALUE, false, null, null, null),
                tuple(ElementKind.CROSS_PARAMETER, false, null, null, null));
    }

    @Test
    void aContainerElementNodeKnowsItsContainerAndAnElementItsIndexOrKey() {
        assertThat(List.of(NodeImpl.containerElement("<list element>", List.class, 0),
                NodeImpl.property("p").inContainer(List.class, 0, 3, null),
                NodeImpl.property("p").inContainer(java.util.Map.class, 1, null, "k")))
            .extracting(NodeImpl::getKind, NodeImpl::isInIterable, NodeImpl::getIndex, NodeImpl::getKey,
                NodeImpl::getContainerClass, NodeImpl::getTypeArgumentIndex)
            .containsExactly(
                tuple(ElementKind.CONTAINER_ELEMENT, false, null, null, List.class, 0),
                tuple(ElementKind.PROPERTY, true, 3, null, List.class, 0),
                tuple(ElementKind.PROPERTY, true, null, "k", java.util.Map.class, 1));
    }
}
