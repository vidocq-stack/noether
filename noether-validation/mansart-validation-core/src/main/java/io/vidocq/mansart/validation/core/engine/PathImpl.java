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

import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** An immutable property path. Its string form is {@code addresses[0].street}, {@code animals[Jumbo].weight}, ... */
public final class PathImpl implements Path {

    private static final PathImpl ROOT = new PathImpl(List.of());

    private final List<NodeImpl> nodes;

    private PathImpl(List<NodeImpl> nodes) {
        this.nodes = nodes;
    }

    /** The empty path of the bean being validated. */
    public static PathImpl root() {
        return ROOT;
    }

    public static PathImpl of(List<NodeImpl> nodes) {
        return new PathImpl(List.copyOf(nodes));
    }

    public PathImpl add(NodeImpl node) {
        List<NodeImpl> copy = new ArrayList<>(nodes.size() + 1);
        copy.addAll(nodes);
        copy.add(node);
        return new PathImpl(List.copyOf(copy));
    }

    /** The same path with its last node replaced, e.g. marked as an element of a container. */
    public PathImpl replaceLast(NodeImpl node) {
        List<NodeImpl> copy = new ArrayList<>(nodes);
        copy.set(copy.size() - 1, node);
        return new PathImpl(List.copyOf(copy));
    }

    public List<NodeImpl> nodes() {
        return nodes;
    }

    public NodeImpl last() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);
    }

    @Override
    public Iterator<Path.Node> iterator() {
        return new ArrayList<Path.Node>(nodes).iterator();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PathImpl that && nodes.equals(that.nodes);
    }

    @Override
    public int hashCode() {
        return nodes.hashCode();
    }

    /** A node in an iterable prints its index or key in brackets, right after the node that holds the container. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        for (NodeImpl node : nodes) {
            if (node.isInIterable() && out.length() > 0) {
                Object at = node.getIndex() != null ? node.getIndex() : node.getKey();
                out.append('[').append(at == null ? "" : at).append(']');
            }
            if (node.getName() != null) {
                if (out.length() > 0) {
                    out.append('.');
                }
                out.append(node.getName());
            }
        }
        return out.toString();
    }
}
