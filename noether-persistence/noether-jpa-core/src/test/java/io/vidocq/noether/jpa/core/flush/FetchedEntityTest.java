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
package io.vidocq.noether.jpa.core.flush;

import static org.assertj.core.api.Assertions.assertThat;

import io.vidocq.noether.jpa.core.context.EntityKey;
import io.vidocq.noether.jpa.core.flush.EntityLoader.FetchedEntity;
import org.junit.jupiter.api.Test;

/** The columns a fetch query captured for one entity are a value: two fetches of the same columns are equal. */
class FetchedEntityTest {

    private static final EntityKey KEY = new EntityKey(Object.class, 1L);

    private static FetchedEntity fetched(Object name, boolean nameIsNull) {
        return new FetchedEntity(null, 1L, new Object[] {name, 7}, new boolean[] {nameIsNull, false}, KEY);
    }

    @Test
    void twoFetchesOfTheSameColumnsAreEqual() {
        FetchedEntity first = fetched("Ada", false);
        FetchedEntity second = fetched("Ada", false);

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second).hasToString(second.toString());
        assertThat(first.toString()).contains("[Ada, 7]", "[false, false]");
    }

    @Test
    void anotherValueOrAnotherNullMakesAnotherFetch() {
        FetchedEntity ada = fetched("Ada", false);

        assertThat(ada).isNotEqualTo(fetched("Grace", false)).isNotEqualTo(fetched("Ada", true))
            .isNotEqualTo(new FetchedEntity(null, 2L, new Object[] {"Ada", 7}, new boolean[] {false, false}, KEY));
    }
}
