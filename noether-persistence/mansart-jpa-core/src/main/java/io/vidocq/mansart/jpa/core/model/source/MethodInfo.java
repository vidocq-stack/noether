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
package io.vidocq.mansart.jpa.core.model.source;

import java.lang.classfile.ClassFile;
import java.lang.constant.MethodTypeDesc;
import java.util.List;

/**
 * A method as declared in a class file.
 *
 * @param genericSignature the generic signature, or {@code null}
 */
public record MethodInfo(String name, MethodTypeDesc type, String genericSignature, int flags, List<AnnotationInfo> annotations)
        implements Annotated {

    public MethodInfo {
        annotations = List.copyOf(annotations);
    }

    public boolean isStatic() {
        return (flags & ClassFile.ACC_STATIC) != 0;
    }

    public boolean isSynthetic() {
        return (flags & (ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0;
    }

    public boolean isPrivate() {
        return (flags & ClassFile.ACC_PRIVATE) != 0;
    }
}
