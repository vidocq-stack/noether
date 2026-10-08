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
package io.vidocq.mansart.validation.core.engine;

import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * The fluent node builder of a custom violation. One class implements every step interface of the API;
 * each method returns this builder, which is a subtype of whatever step the API promises next.
 */
final class ViolationBuilderImpl implements ConstraintViolationBuilder,
        ConstraintViolationBuilder.NodeBuilderDefinedContext,
        ConstraintViolationBuilder.NodeBuilderCustomizableContext,
        ConstraintViolationBuilder.NodeContextBuilder,
        ConstraintViolationBuilder.LeafNodeBuilderCustomizableContext,
        ConstraintViolationBuilder.LeafNodeContextBuilder,
        ConstraintViolationBuilder.LeafNodeBuilderDefinedContext,
        ConstraintViolationBuilder.ContainerElementNodeBuilderCustomizableContext,
        ConstraintViolationBuilder.ContainerElementNodeContextBuilder,
        ConstraintViolationBuilder.ContainerElementNodeBuilderDefinedContext {

    private final ConstraintValidatorContextImpl context;
    private final String messageTemplate;
    private final List<NodeImpl> nodes;
    private ValidationRun.Slot pendingSlot;

    ViolationBuilderImpl(ConstraintValidatorContextImpl context, String messageTemplate, PathImpl base, ValidationRun.Slot pendingSlot) {
        this.context = context;
        this.messageTemplate = messageTemplate;
        this.nodes = new ArrayList<>(base.nodes());
        this.pendingSlot = pendingSlot;
    }

    /** The first node added takes the position of the bean in its container, if it has one. */
    private ViolationBuilderImpl append(NodeImpl node) {
        NodeImpl added = node;
        if (pendingSlot != null) {
            added = node.inContainer(pendingSlot.containerClass(), pendingSlot.typeArgumentIndex(), pendingSlot.index(), pendingSlot.key());
            pendingSlot = null;
        }
        nodes.add(added);
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public ViolationBuilderImpl addNode(String name) {
        return addPropertyNode(name);
    }

    @Override
    public ViolationBuilderImpl addPropertyNode(String name) {
        return append(NodeImpl.property(name));
    }

    @Override
    public ViolationBuilderImpl addBeanNode() {
        return append(NodeImpl.bean());
    }

    @Override
    public ViolationBuilderImpl addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
        return append(NodeImpl.containerElement(name, containerType, typeArgumentIndex));
    }

    @Override
    public ViolationBuilderImpl addParameterNode(int index) {
        if (!context.isExecutableContext()) {
            throw new IllegalStateException("A parameter node can only be added to the violation of a method or constructor constraint");
        }
        return append(NodeImpl.parameter("arg" + index, index));
    }

    @Override
    public ViolationBuilderImpl inIterable() {
        replaceLast(last().inIterable());
        return this;
    }

    @Override
    public ViolationBuilderImpl inContainer(Class<?> containerType, Integer typeArgumentIndex) {
        replaceLast(last().withContainer(containerType, typeArgumentIndex));
        return this;
    }

    @Override
    public ViolationBuilderImpl atKey(Object key) {
        replaceLast(last().withKey(key));
        return this;
    }

    @Override
    public ViolationBuilderImpl atIndex(Integer index) {
        replaceLast(last().withIndex(index));
        return this;
    }

    @Override
    public ConstraintValidatorContext addConstraintViolation() {
        context.add(new ViolationDraft(messageTemplate, PathImpl.of(nodes)));
        return context;
    }

    private NodeImpl last() {
        if (nodes.isEmpty()) {
            throw new IllegalStateException("There is no node to customize");
        }
        return nodes.get(nodes.size() - 1);
    }

    private void replaceLast(NodeImpl node) {
        nodes.set(nodes.size() - 1, node);
    }
}
