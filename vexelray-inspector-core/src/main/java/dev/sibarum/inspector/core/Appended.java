package dev.sibarum.inspector.core;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.RandomAccess;

/**
 * An immutable list that a batch adds to without copying what it already holds: the lanes' events and the tape's
 * rows, which a fold adds a batch at a time to a list a minute long.
 *
 * <p>Its elements are a run of a store shared with the lists it was made from, then a short tail of its own. A list
 * {@linkplain #plus adds} to the store in place when it ends where the store does, so the list it was made from, and
 * anything already published, still sees only its own run; a list that does not (an older checkpoint folded again, an
 * island's kept events beside the live ones) copies its run first. What a list holds never changes after it is made,
 * so it is safe to read from any thread once published; making one from another belongs to one thread, the fold's.
 *
 * <p>Only the tail is ever {@linkplain #with replaced}, which is where a fold changes an event it already holds: a
 * pass, as the rows after it say whether it drew. Replacing an element before the tail copies the whole list.
 *
 * @param <E> what it holds
 */
public final class Appended<E> extends AbstractList<E> implements RandomAccess {

    /** How many of the newest elements a list keeps as its own, where {@link #with} is cheap. */
    static final int TAIL = 32;

    private static final Object[] NONE = {};

    /** The shared elements: written once each, in order, by whichever list ends where it does. */
    private static final class Store {
        Object[] items;
        int size;

        Store(Object[] items, int size) {
            this.items = items;
            this.size = size;
        }
    }

    private final Store store;
    /** The store's array as it was when this list was made: the elements before {@link #to} never change in it. */
    private final Object[] items;
    private final int from;
    private final int to;
    private final Object[] tail;

    private Appended(Store store, int from, int to, Object[] tail) {
        this.store = store;
        this.items = store.items;
        this.from = from;
        this.to = to;
        this.tail = tail;
    }

    /** Nothing. */
    public static <E> Appended<E> of() {
        return new Appended<>(new Store(NONE, 0), 0, 0, NONE);
    }

    /** {@code list}'s elements: the same list if it is one of these, or a copy. */
    @SuppressWarnings("unchecked")
    public static <E> Appended<E> of(List<? extends E> list) {
        return list instanceof Appended<?> a ? (Appended<E>) a : Appended.<E>of().plus(list);
    }

    @Override
    @SuppressWarnings("unchecked")
    public E get(int index) {
        int stored = to - from;
        if (index < 0 || index >= stored + tail.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return (E) (index < stored ? items[from + index] : tail[index - stored]);
    }

    @Override
    public int size() {
        return to - from + tail.length;
    }

    /** This list with {@code more} after it. */
    public Appended<E> plus(List<? extends E> more) {
        if (more.isEmpty()) {
            return this;
        }
        Object[] longer = Arrays.copyOf(tail, tail.length + more.size());
        for (int i = 0; i < more.size(); i++) {
            longer[tail.length + i] = more.get(i);
        }
        if (longer.length <= 2 * TAIL) {
            return new Appended<>(store, from, to, longer);
        }
        // The tail grew long: all but its newest go into the store, which this list must end where it does.
        int moved = longer.length - TAIL;
        Store s = store;
        int start = from;
        if (s.size != to) {
            s = new Store(Arrays.copyOfRange(items, from, Math.max(to, from + 16) + moved), to - from);
            start = 0;
        }
        if (s.size + moved > s.items.length) {
            s.items = Arrays.copyOf(s.items, Math.max(s.size + moved, s.items.length * 2));
        }
        System.arraycopy(longer, 0, s.items, s.size, moved);
        s.size += moved;
        return new Appended<>(s, start, s.size, Arrays.copyOfRange(longer, moved, longer.length));
    }

    /** This list with its element {@code index} replaced by {@code e}; cheap among the newest {@link #TAIL}. */
    public Appended<E> with(int index, E e) {
        int stored = to - from;
        if (index >= stored && index < stored + tail.length) {
            Object[] t = tail.clone();
            t[index - stored] = e;
            return new Appended<>(store, from, to, t);
        }
        @SuppressWarnings("unchecked")
        E[] all = (E[]) toArray();
        all[index] = e;
        return Appended.<E>of().plus(Arrays.asList(all));
    }

    /** This list without its first {@code n} elements. */
    public Appended<E> dropFirst(int n) {
        if (n <= 0) {
            return this;
        }
        int stored = to - from;
        if (n >= stored) {
            return new Appended<>(new Store(NONE, 0), 0, 0, Arrays.copyOfRange(tail, Math.min(n - stored, tail.length),
                    tail.length));
        }
        int start = from + n;
        // Let go of the store once most of it is behind every list that ends where it does.
        if (start > to - start && start > 1024) {
            Store s = new Store(Arrays.copyOfRange(items, start, to + Math.max(16, (to - start) / 2)), to - start);
            return new Appended<>(s, 0, s.size, tail);
        }
        return new Appended<>(store, start, to, tail);
    }
}
