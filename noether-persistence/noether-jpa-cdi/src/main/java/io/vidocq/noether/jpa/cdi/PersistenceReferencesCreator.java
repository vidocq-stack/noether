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
package io.vidocq.noether.jpa.cdi;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.build.compatible.spi.Parameters;
import jakarta.enterprise.inject.build.compatible.spi.SyntheticBeanCreator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.ArrayList;
import java.util.List;

/** The {@link PersistenceReferences} bean, from the references {@link NoetherPersistenceExtension} collected. */
public final class PersistenceReferencesCreator implements SyntheticBeanCreator<PersistenceReferences> {

    /** The parameters: the names, sorted, then the kind and the qualifier value of each one's bean. */
    static final String NAMES = "names";
    static final String KINDS = "kinds";
    static final String QUALIFIERS = "qualifiers";
    /** The kinds: a persistence context, bound to an {@code EntityManager}, or a unit, to its factory. */
    static final String CONTEXT = "context";
    static final String UNIT = "unit";

    @Override
    public PersistenceReferences create(Instance<Object> lookup, Parameters params) {
        String[] names = params.get(NAMES, String[].class, new String[0]);
        String[] kinds = params.get(KINDS, String[].class, new String[0]);
        String[] qualifiers = params.get(QUALIFIERS, String[].class, new String[0]);
        List<PersistenceReferences.Reference> references = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            Class<?> type = UNIT.equals(kinds[i]) ? EntityManagerFactory.class : EntityManager.class;
            references.add(new PersistenceReferences.Reference(names[i], type,
                new PersistenceContextCreator.Binding(qualifiers[i])));
        }
        return new PersistenceReferences(references);
    }
}
