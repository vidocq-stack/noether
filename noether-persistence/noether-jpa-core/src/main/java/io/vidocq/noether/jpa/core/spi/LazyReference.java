/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.noether.jpa.core.spi;

import jakarta.persistence.PersistenceException;

/** Contract implemented only by the generated, getter-interceptable entity subclasses. */
public interface LazyReference {
    State $noetherState();
    void $noetherState(State state);

    static boolean unloaded(Object instance) {
        return instance instanceof LazyReference reference && reference.$noetherState() != null
            && !reference.$noetherState().loaded;
    }

    static void initialize(Object instance) {
        if (instance instanceof LazyReference reference) State.initialize(reference.$noetherState());
    }

    /** Per-instance state; never shared between persistence contexts. */
    final class State {
        private Runnable loader;
        private boolean loaded;
        private boolean loading;

        public State(Runnable loader) {
            this.loader = loader;
        }

        /** Called by generated methods; construction and hydration have no pending load. */
        public static void initialize(State state) {
            if (state == null || state.loaded || state.loading) return;
            if (state.loader == null) {
                throw new PersistenceException("The lazy to-one reference cannot be initialized: it is detached or its entity manager is closed");
            }
            state.loading = true;
            try {
                state.loader.run();
                state.loaded = true;
                state.loader = null;
            } finally {
                state.loading = false;
            }
        }

        /**
         * Initializes the reference with {@code hydration}, state the caller already read (a fetch join row), instead of
         * its loader; nothing if it is loaded or loading.
         */
        public void hydrate(Runnable hydration) {
            if (loaded || loading) return;
            loading = true;
            try {
                hydration.run();
                loaded = true;
                loader = null;
            } finally {
                loading = false;
            }
        }

        public void complete() {
            loaded = true;
            loader = null;
        }

        public void disconnect() {
            loader = null;
        }
    }
}
