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

    ViolationBuilderImpl(ConstraintValidatorContextImpl context, String messageTemplate, PathImpl base) {
        this.context = context;
        this.messageTemplate = messageTemplate;
        this.nodes = new ArrayList<>(base.nodes());
    }

    @Override
    @SuppressWarnings("deprecation")
    public ViolationBuilderImpl addNode(String name) {
        return addPropertyNode(name);
    }

    @Override
    public ViolationBuilderImpl addPropertyNode(String name) {
        nodes.add(NodeImpl.property(name));
        return this;
    }

    @Override
    public ViolationBuilderImpl addBeanNode() {
        nodes.add(NodeImpl.bean());
        return this;
    }

    @Override
    public ViolationBuilderImpl addContainerElementNode(String name, Class<?> containerType, Integer typeArgumentIndex) {
        nodes.add(NodeImpl.containerElement(name, containerType, typeArgumentIndex));
        return this;
    }

    @Override
    public ViolationBuilderImpl addParameterNode(int index) {
        nodes.add(NodeImpl.parameter("arg" + index, index));
        return this;
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
