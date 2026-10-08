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

import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import java.util.List;
import java.util.Objects;

/**
 * A node of a property path. One class implements every node interface of the API; {@link #as(Class)}
 * only agrees to the view that matches the {@link #getKind() kind} of the node.
 */
public final class NodeImpl implements Path.PropertyNode, Path.BeanNode, Path.ContainerElementNode, Path.MethodNode,
        Path.ConstructorNode, Path.ParameterNode, Path.ReturnValueNode, Path.CrossParameterNode {

    private final String name;
    private final ElementKind kind;
    private final boolean inIterable;
    private final Integer index;
    private final Object key;
    private final Class<?> containerClass;
    private final Integer typeArgumentIndex;
    private final List<Class<?>> parameterTypes;
    private final int parameterIndex;

    private NodeImpl(String name, ElementKind kind, boolean inIterable, Integer index, Object key, Class<?> containerClass,
            Integer typeArgumentIndex, List<Class<?>> parameterTypes, int parameterIndex) {
        this.name = name;
        this.kind = kind;
        this.inIterable = inIterable;
        this.index = index;
        this.key = key;
        this.containerClass = containerClass;
        this.typeArgumentIndex = typeArgumentIndex;
        this.parameterTypes = parameterTypes;
        this.parameterIndex = parameterIndex;
    }

    public static NodeImpl property(String name) {
        return new NodeImpl(name, ElementKind.PROPERTY, false, null, null, null, null, null, -1);
    }

    /** A bean node: the validated bean itself, whose name is {@code null}. */
    public static NodeImpl bean() {
        return new NodeImpl(null, ElementKind.BEAN, false, null, null, null, null, null, -1);
    }

    public static NodeImpl containerElement(String name, Class<?> containerClass, Integer typeArgumentIndex) {
        return new NodeImpl(name, ElementKind.CONTAINER_ELEMENT, false, null, null, containerClass, typeArgumentIndex, null, -1);
    }

    public static NodeImpl method(String name, List<Class<?>> parameterTypes) {
        return new NodeImpl(name, ElementKind.METHOD, false, null, null, null, null, List.copyOf(parameterTypes), -1);
    }

    public static NodeImpl constructor(String simpleName, List<Class<?>> parameterTypes) {
        return new NodeImpl(simpleName, ElementKind.CONSTRUCTOR, false, null, null, null, null, List.copyOf(parameterTypes), -1);
    }

    public static NodeImpl parameter(String name, int parameterIndex) {
        return new NodeImpl(name, ElementKind.PARAMETER, false, null, null, null, null, null, parameterIndex);
    }

    public static NodeImpl returnValue() {
        return new NodeImpl("<return value>", ElementKind.RETURN_VALUE, false, null, null, null, null, null, -1);
    }

    public static NodeImpl crossParameter() {
        return new NodeImpl("<cross-parameter>", ElementKind.CROSS_PARAMETER, false, null, null, null, null, null, -1);
    }

    private NodeImpl copy(boolean inIterable, Integer index, Object key, Class<?> containerClass, Integer typeArgumentIndex) {
        return new NodeImpl(name, kind, inIterable, index, key, containerClass, typeArgumentIndex, parameterTypes, parameterIndex);
    }

    /** The same node, marked as an element of a container: at {@code index} (list, array) or under {@code key} (map). */
    public NodeImpl inContainer(Class<?> container, Integer typeArgument, Integer elementIndex, Object elementKey) {
        return copy(true, elementIndex, elementKey, container, typeArgument);
    }

    /** The same node, marked as in an iterable, with its index and key as they are. */
    public NodeImpl inIterable() {
        return copy(true, index, key, containerClass, typeArgumentIndex);
    }

    public NodeImpl withContainer(Class<?> container, Integer typeArgument) {
        return copy(inIterable, index, key, container, typeArgument);
    }

    public NodeImpl withIndex(Integer elementIndex) {
        return copy(inIterable, elementIndex, key, containerClass, typeArgumentIndex);
    }

    public NodeImpl withKey(Object elementKey) {
        return copy(inIterable, index, elementKey, containerClass, typeArgumentIndex);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isInIterable() {
        return inIterable;
    }

    @Override
    public Integer getIndex() {
        return index;
    }

    @Override
    public Object getKey() {
        return key;
    }

    @Override
    public ElementKind getKind() {
        return kind;
    }

    @Override
    public Class<?> getContainerClass() {
        return containerClass;
    }

    @Override
    public Integer getTypeArgumentIndex() {
        return typeArgumentIndex;
    }

    @Override
    public List<Class<?>> getParameterTypes() {
        return parameterTypes;
    }

    @Override
    public int getParameterIndex() {
        return parameterIndex;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Path.Node> T as(Class<T> nodeType) {
        boolean matches = switch (kind) {
            case PROPERTY -> nodeType == Path.PropertyNode.class;
            case BEAN -> nodeType == Path.BeanNode.class;
            case CONTAINER_ELEMENT -> nodeType == Path.ContainerElementNode.class;
            case METHOD -> nodeType == Path.MethodNode.class;
            case CONSTRUCTOR -> nodeType == Path.ConstructorNode.class;
            case PARAMETER -> nodeType == Path.ParameterNode.class;
            case RETURN_VALUE -> nodeType == Path.ReturnValueNode.class;
            case CROSS_PARAMETER -> nodeType == Path.CrossParameterNode.class;
        };
        if (!matches && nodeType != Path.Node.class) {
            throw new ClassCastException("A " + kind + " node cannot be viewed as " + nodeType.getName());
        }
        return (T) this;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof NodeImpl that && Objects.equals(name, that.name) && kind == that.kind
            && inIterable == that.inIterable && Objects.equals(index, that.index) && Objects.equals(key, that.key)
            && Objects.equals(containerClass, that.containerClass) && Objects.equals(typeArgumentIndex, that.typeArgumentIndex)
            && Objects.equals(parameterTypes, that.parameterTypes) && parameterIndex == that.parameterIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, kind, inIterable, index, key, containerClass, typeArgumentIndex, parameterTypes, parameterIndex);
    }

    @Override
    public String toString() {
        return name == null ? "" : name;
    }
}
