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
package io.vidocq.mansart.validation.core.metadata;

import io.vidocq.mansart.validation.core.metadata.ConstraintFixtures.Required;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A small bean hierarchy: an interface, a base class, a subclass. */
public final class BeanFixtures {

    private BeanFixtures() {
    }

    @Deprecated
    @interface NotAConstraintEither {
    }

    public interface Named {
        @NotNull
        String getName();
    }

    @Required(message = "class level")
    public static class Base implements Named {
        @NotNull
        private String id = "id-1";
        @Valid
        protected Object child = "child";
        public String untouched = "untouched";
        @Size(min = 1)
        public static String staticField = "ignored";

        @Override
        public String getName() {
            return "base-name";
        }

        @NotNull
        public boolean isActive() {
            return true;
        }

        @NotNull
        public boolean hasLabel() {
            return true;
        }

        /** Not a getter: has a parameter. */
        @NotNull
        public String getWithParameter(int ignored) {
            return "x";
        }

        /** Not a getter: void. */
        @NotNull
        public void getNothing() {
        }

        @NotNull
        public static String getStatic() {
            return "ignored";
        }

        /** Not a getter: wrong prefix. */
        @NotNull
        public String computeSomething() {
            return "x";
        }
    }

    public static class Derived extends Base {
        @Size(min = 2)
        @NotAConstraintEither
        public String label = "label";

        @Required
        @Valid
        private int[] numbers = {1};
    }
}
