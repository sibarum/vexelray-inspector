package dev.sibarum.inspector.history;

import dev.sibarum.inspector.clock.Escapement;

/**
 * The runs being inspected, measured and kept by build (see {@link Recorder}): a listener of the escapement, told
 * when a run of a build begins and when it ends.
 */
public interface Runs extends Escapement.Listener, AutoCloseable {

    /** How many builds the window lists. */
    int LIMIT = 5;

    /** A run of {@code build} begins; the one before, if any, ends and is kept. {@code null} for an unknown build. */
    void begin(Build build);

    /** The run ended: keep it, if anything was measured. On the offload lane. */
    void finish();

    /** Show {@code build}'s application's history, read on the offload lane. {@code null} shows none. */
    void look(Build build);

    /** The Inspector is closing: keep the run being measured now, on this thread, before the lanes stop. */
    @Override
    void close();
}
