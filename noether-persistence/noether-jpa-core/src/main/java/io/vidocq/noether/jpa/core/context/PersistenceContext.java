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
package io.vidocq.noether.jpa.core.context;

import io.vidocq.noether.jpa.core.mapping.LazyCollection;
import io.vidocq.noether.jpa.core.mapping.MappedEntity;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.LockModeType;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A persistence context (§3.3, §7.2): at most one managed instance per identity, and the state of each instance in
 * its life cycle. It knows nothing of the database: the entity operations (P4) and the flush engine drive it. Not
 * thread-safe, as an entity manager is not (§7.2): no lock, no {@code ThreadLocal}.
 */
public final class PersistenceContext {

    private final Map<EntityKey, ManagedEntity> byKey = new HashMap<>();
    /**
     * By instance, compared by identity: entities may define equals and hashCode, a persistence context compares
     * identities. The table holds the instances themselves, without a key or an entry object for each, and starts
     * small: most contexts hold a few instances.
     */
    private final IdentityHashMap<Object, ManagedEntity> byInstance = new IdentityHashMap<>(2);
    /** The entries in the order the context met them, linked through themselves: the flush follows it without sorting. */
    private ManagedEntity first;
    private ManagedEntity last;
    /** What {@link #entries()} last returned, as long as no instance joined or left the context since. */
    private List<ManagedEntity> entries;
    private long sequence;
    private LazyCollection.Loader lazyLoader;
    private java.util.function.Consumer<Object> referenceLoader;
    /** Nesting depth of the loads in progress, and the PostLoad callbacks they deferred (BUG-20261016-01). */
    private int loads;
    private List<Runnable> postLoads = new ArrayList<>();

    public java.util.function.Consumer<Object> referenceLoader() { return referenceLoader; }
    public void referenceLoader(java.util.function.Consumer<Object> loader) { referenceLoader = loader; }

    public ManagedEntity reference(Object instance, MappedEntity type, Object id) {
        return register(new ManagedEntity(instance, type, sequence++, new EntityKey(type.root(), id),
            ManagedEntity.Status.MANAGED, true, null));
    }

    /**
     * The loader of the LAZY collections of the instances of this context (§2.10); {@code null}: the collections are
     * loaded with their owner.
     */
    public LazyCollection.Loader lazyLoader() {
        return lazyLoader;
    }

    public void lazyLoader(LazyCollection.Loader loader) {
        this.lazyLoader = loader;
    }

    /**
     * Makes {@code instance} managed (§3.2.2): a new instance awaits its insert, a removed one becomes managed again, a
     * managed one is left as it is. Another instance with the same identity is an {@link EntityExistsException}.
     */
    public ManagedEntity persist(Object instance, MappedEntity type) {
        ManagedEntity known = byInstance.get(instance);
        if (known != null) {
            known.status(ManagedEntity.Status.MANAGED);
            return known;
        }
        EntityKey key = key(type, type.id(instance));
        if (key != null && byKey.containsKey(key)) {
            throw new EntityExistsException("Another instance of " + type.model().entityName() + " with the identifier "
                + key.id() + " is already managed by this persistence context");
        }
        return register(new ManagedEntity(instance, type, sequence++, key, ManagedEntity.Status.MANAGED, false, null));
    }

    /**
     * Makes a row read from the database managed, with the snapshot of its state; if the context already manages its
     * identity, the managed instance stays and is returned (§3.2.8: one managed instance per identity).
     */
    public ManagedEntity loaded(Object instance, MappedEntity type, Object[] snapshot) {
        return loaded(instance, type, type.id(instance), snapshot);
    }

    /**
     * Like {@link #loaded(Object, MappedEntity, Object[])}, with the identity {@code id} the row was read by: a derived
     * identity (§2.4.1) is known before the parent it derives from is loaded.
     */
    public ManagedEntity loaded(Object instance, MappedEntity type, Object id, Object[] snapshot) {
        EntityKey key = key(type, id);
        if (key == null) {
            throw new IllegalArgumentException("A loaded instance of " + type.model().entityName() + " has no identifier");
        }
        return loadedAs(key, instance, type, snapshot);
    }

    /**
     * Like {@link #loaded(Object, MappedEntity, Object, Object[])}, with the identity as the {@code key} the caller
     * already looked the context up with: the root of {@code type} and the identifier of the row.
     */
    public ManagedEntity loadedAs(EntityKey key, Object instance, MappedEntity type, Object[] snapshot) {
        ManagedEntity known = byKey.get(key);
        if (known != null) {
            return known;
        }
        return register(new ManagedEntity(instance, type, sequence++, key, ManagedEntity.Status.MANAGED, true, snapshot));
    }

    /**
     * Records the insert of {@code entry}: its identity, generated by the insert or not, and its state as snapshot.
     * Like {@link #loaded} and {@link #updated}, the context keeps the array it is given: callers hand over a
     * {@link io.vidocq.noether.jpa.core.mapping.StatePolicy#snapshot(Object[]) snapshot} they no longer write.
     */
    public void inserted(ManagedEntity entry, Object id, Object[] state) {
        identify(entry, id);
        entry.inserted(true);
        entry.snapshot(state);
    }

    /** Registers an identity assigned after persist, including a cascaded parent's generated key. */
    public void identify(ManagedEntity entry, Object id) {
        if (id == null) return;
        if (entry.key() == null) {
            EntityKey key = key(entry.type(), id);
            if (byKey.putIfAbsent(key, entry) != null) {
                throw new EntityExistsException("The database generated the identifier " + id + " of "
                    + entry.type().model().entityName() + " for two instances");
            }
            entry.key(key);
        }
    }

    /** Records an update: the state written becomes the snapshot. */
    public void updated(ManagedEntity entry, Object[] state) {
        entry.snapshot(state);
    }

    /** Forgets an instance whose row was deleted, or that was removed before it was inserted. */
    public void deleted(ManagedEntity entry) {
        forget(entry);
    }

    /**
     * Makes a managed instance removed (§3.2.3); a removed one stays removed. {@code false} if the context does not hold
     * the instance: it is new or detached, which the entity operations tell apart.
     */
    public boolean remove(Object instance) {
        ManagedEntity known = byInstance.get(instance);
        if (known == null) {
            return false;
        }
        known.status(ManagedEntity.Status.REMOVED);
        return true;
    }

    /** Records the lock mode {@code entry} holds in the current transaction (§3.5). */
    public void lock(ManagedEntity entry, LockModeType mode) {
        entry.lockMode(mode);
    }

    /** The locks end with the transaction: every instance is back to {@link LockModeType#NONE}. */
    public void releaseLocks() {
        for (ManagedEntity entry = first; entry != null; entry = entry.next) {
            entry.lockMode(LockModeType.NONE);
        }
    }

    /** §3.2.8: whether {@code instance} is managed; a removed instance is not. */
    public boolean contains(Object instance) {
        ManagedEntity known = byInstance.get(instance);
        return known != null && known.status() == ManagedEntity.Status.MANAGED;
    }

    /** The entry of {@code instance}, managed or removed. */
    public Optional<ManagedEntity> entry(Object instance) {
        return Optional.ofNullable(byInstance.get(instance));
    }

    /** The entry with this identity, managed or removed. */
    public Optional<ManagedEntity> find(EntityKey key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** §3.2.7: detaches one instance; changes not flushed are lost. */
    public void detach(Object instance) {
        ManagedEntity known = byInstance.get(instance);
        if (known != null) {
            forget(known);
        }
    }

    /**
     * Runs {@code load}, a read of entity state from the database, as a load of this context: the PostLoad callbacks
     * {@link #postLoad deferred} while it, or any load it nests, runs are invoked once the outermost one has returned,
     * in the order they were deferred (§3.6.3). The LAZY collections the nested loads were initializing are then
     * complete, so that a callback reading one, even the one whose loading read its entity, sees all its elements. A
     * callback that loads again opens its own outermost load. A failed load discards the callbacks of its entities.
     */
    public <T> T loading(java.util.function.Supplier<T> load) {
        T result;
        loads++;
        try {
            result = load.get();
        } catch (RuntimeException | Error failure) {
            if (--loads == 0) postLoads = new ArrayList<>();
            throw failure;
        }
        if (--loads == 0) {
            while (!postLoads.isEmpty()) {
                List<Runnable> ready = postLoads;
                postLoads = new ArrayList<>();
                ready.forEach(Runnable::run);
            }
        }
        return result;
    }

    /** Invokes the PostLoad {@code callback} once the outermost {@link #loading load} returns; now outside of one. */
    public void postLoad(Runnable callback) {
        if (loads == 0) {
            callback.run();
        } else {
            postLoads.add(callback);
        }
    }

    /** §3.2.7: detaches every instance. */
    public void clear() {
        disconnectAll();
        byKey.clear();
        byInstance.clear();
        ManagedEntity entry = first;
        while (entry != null) { // an entry a caller still holds keeps no other alive
            ManagedEntity next = entry.next;
            entry.previous = null;
            entry.next = null;
            entry = next;
        }
        first = null;
        last = null;
        entries = null;
    }

    /**
     * Disconnects the unloaded LAZY collections of every instance from this context, which keeps them: they fail on
     * their next access instead of loading (§3.2.7, a closed entity manager), and do not retain it.
     */
    public void disconnectAll() {
        for (ManagedEntity entry = first; entry != null; entry = entry.next) {
            disconnect(entry);
        }
    }

    /**
     * The entries, in the order the context met their instances, as they are now: an instance that joins or leaves
     * the context afterwards does not change the list returned, which cannot be modified. A flush asks for them
     * several times without changing the context in between: the same list answers until an instance joins or leaves.
     */
    public List<ManagedEntity> entries() {
        List<ManagedEntity> current = entries;
        if (current == null) {
            int size = byInstance.size();
            if (size == 0) {
                current = List.of();
            } else {
                ManagedEntity[] array = new ManagedEntity[size];
                int i = 0;
                for (ManagedEntity entry = first; entry != null; entry = entry.next) {
                    array[i++] = entry;
                }
                current = new Entries(array);
            }
            entries = current;
        }
        return current;
    }

    /** How many instances the context holds. */
    public int size() {
        return byInstance.size();
    }

    private ManagedEntity register(ManagedEntity entry) {
        byInstance.put(entry.instance(), entry);
        entry.previous = last;
        if (last == null) {
            first = entry;
        } else {
            last.next = entry;
        }
        last = entry;
        entries = null;
        if (entry.key() != null) {
            byKey.put(entry.key(), entry);
        }
        return entry;
    }

    private void forget(ManagedEntity entry) {
        disconnect(entry);
        ManagedEntity held = byInstance.remove(entry.instance());
        if (held != null) {
            if (held.previous == null) {
                first = held.next;
            } else {
                held.previous.next = held.next;
            }
            if (held.next == null) {
                last = held.previous;
            } else {
                held.next.previous = held.previous;
            }
            held.previous = null;
            held.next = null;
            entries = null;
        }
        if (entry.key() != null) {
            byKey.remove(entry.key(), entry);
        }
    }

    /** An unloaded collection stays in the snapshot of its owner until it is loaded or the attribute replaced. */
    private static void disconnect(ManagedEntity entry) {
        if (entry.instance() instanceof io.vidocq.noether.jpa.core.spi.LazyReference reference
                && reference.$noetherState() != null) reference.$noetherState().disconnect();
        Object[] snapshot = entry.snapshot();
        if (snapshot == null) return;
        for (Object value : snapshot) {
            if (value instanceof LazyCollection lazy && lazy.owner() == entry.instance()) {
                lazy.disconnect();
            }
        }
    }

    /** The entries of a context at one point in time: a fixed array behind a list that cannot be modified. */
    private static final class Entries extends AbstractList<ManagedEntity> implements java.util.RandomAccess {
        private final ManagedEntity[] array;

        Entries(ManagedEntity[] array) {
            this.array = array;
        }

        @Override
        public ManagedEntity get(int index) {
            return array[index];
        }

        @Override
        public int size() {
            return array.length;
        }
    }

    private static EntityKey key(MappedEntity type, Object id) {
        return id == null ? null : new EntityKey(type.root(), id);
    }
}
