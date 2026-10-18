package dev.sibarum.inspector.history;

import dev.sibarum.inspector.clock.Beat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Measures the run being inspected and keeps it in the {@link History} when it ends: a listener of the escapement
 * beside the window, so what it keeps is what the wheel showed.
 *
 * <p>A run is measured only when the build is known ({@link Build#known()}): a live application advertising its
 * build identity. A replay, or an application built without the identity plugin, is shown and not kept, because
 * there would be nothing to compare its runs by.
 *
 * <h2>Threads</h2>
 *
 * Beats arrive on the source's thread; {@link #begin} and {@link #finish} come from wherever the source changes or
 * ends. Both are serialised here. Reading and writing the history happen on {@code disk}, the offload lane, and
 * {@code shown} is called there with what was read, so the window can show it. {@link #close} writes on the calling
 * thread, because the lanes stop when the Inspector closes.
 */
public final class Recorder implements Runs {

    private static final sibarum.probe.Log LOG = sibarum.probe.Log.of("vexelray-inspector.history");

    private final History history;
    private final Executor disk;
    private final BiConsumer<Build, List<History.BuildStats>> shown;
    private Session session;

    /**
     * @param shown told, on {@code disk}, of a build's application's history each time it is read: after a run is
     *              kept, and when {@link #look} asks
     */
    public Recorder(History history, Executor disk, BiConsumer<Build, List<History.BuildStats>> shown) {
        this.history = history;
        this.disk = disk;
        this.shown = shown;
    }

    @Override
    public synchronized void begin(Build build) {
        finish();
        session = build != null && build.known() ? new Session(build, Instant.now()) : null;
    }

    @Override
    public synchronized void finish() {
        Summary summary = take();
        if (summary != null) {
            disk.execute(() -> {
                keep(summary);
                read(summary.build());
            });
        }
    }

    @Override
    public synchronized void close() {
        Summary summary = take();
        if (summary != null) {
            keep(summary);
        }
    }

    @Override
    public void look(Build build) {
        disk.execute(() -> read(build));
    }

    /** The run being measured, ended: its summary, or {@code null} when there was nothing to keep. */
    private Summary take() {
        Session s = session;
        session = null;
        return s == null || !s.measured() ? null : s.summary();
    }

    private void keep(Summary summary) {
        try {
            history.add(summary);
        } catch (IOException e) {
            LOG.warn("could not keep the run of " + summary.build().shortSource() + ": " + e.getMessage());
        }
    }

    private void read(Build build) {
        if (build == null || !build.known()) {
            shown.accept(build, List.of());
            return;
        }
        try {
            shown.accept(build, history.builds(build, LIMIT));
        } catch (IOException e) {
            LOG.warn("could not read the history of " + build.artifact() + ": " + e.getMessage());
        }
    }

    /** The run being measured so far, or {@code null}. */
    public synchronized Summary current() {
        return session == null ? null : session.summary();
    }

    @Override
    public synchronized void tick(Beat beat) {
        if (session != null) {
            session.tick(beat);
        }
    }

    @Override
    public synchronized void rest(Beat beat) {
        if (session != null) {
            session.rest(beat);
        }
    }

    @Override
    public synchronized void wake(Beat beat) {
        if (session != null) {
            session.other(beat);
        }
    }

    @Override
    public synchronized void lost(Beat beat) {
        if (session != null) {
            session.other(beat);
        }
    }

    @Override
    public void source(String state) {
        // A run's beginning and end come from Sources, which knows the build; the escapement's words are for people.
    }
}
