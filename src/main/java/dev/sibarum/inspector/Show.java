package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.BeadPool;
import dev.sibarum.inspector.clock.BusRows;
import dev.sibarum.inspector.clock.Combo;
import dev.sibarum.inspector.clock.Pending;
import dev.sibarum.inspector.clock.ShowClock;
import dev.sibarum.inspector.rows.Row;
import dev.sibarum.inspector.rows.RowSink;
import dev.vexelray.gui.krono.KronoGui;
import sibarum.kronometer.Dur;
import sibarum.kronometer.anim.Ease;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where the rows go: measured as they arrive, and shown on the {@link ShowClock}.
 *
 * <p>Each row goes two ways:
 * <ul>
 *   <li><b>At once, to {@code measure}</b>, on the source's thread: the escapement whose beats the history keeps. A
 *       run is measured as it happened, never as it was shown; and each publish is counted into the
 *       {@link Combo}, which is real time by definition.</li>
 *   <li><b>Into {@link Pending}</b>, released on the timeline as show time reaches it: frame rows to {@code view},
 *       the escapement the window shows, so the balance wheel and the beads share one clock and never disagree
 *       about which frame something happened in; bus rows to the {@link BusView}, as beads.</li>
 * </ul>
 *
 * <h2>Real time</h2>
 *
 * The target's now is the latest row's time plus the wall time since, read as the largest {@code row time - wall
 * time} any row has arrived with: the least-delayed row is the best guess at the offset between the two clocks, and
 * the guess only ever improves. A replay sped up is followed the same way.
 *
 * <h2>Threads</h2>
 *
 * {@link #begin}, {@link #row} and {@link #end} on the source's thread. The rest on the timeline, inside the frame:
 * while there is anything to show, a ramp calls {@link #step} every frame, and when there is nothing left it stops,
 * so an idle target leaves the Inspector idle too. The step takes no lock and allocates nothing of its own.
 */
final class Show implements RowSink {

    /** How long one arming of the per-frame step lasts before it looks whether it is still needed. */
    private static final Dur SPAN = Dur.ms(250);

    /** The longest a frame may count as, so a frame after a long idle is not a leap. */
    private static final long LONGEST_FRAME = 100_000_000L;

    private final KronoGui krono;
    private final RowSink measure;
    private final RowSink view;
    private final BusView bus;
    private final Pending pending = new Pending(1 << 16);
    private final ShowClock clock = ShowClock.standard();
    private final BeadPool pool = new BeadPool(BusView.BEADS, BusView.TRAVEL, BusView.LANES, BusView.GAP);
    private final Combo combo = Combo.standard();
    private final AtomicBoolean armed = new AtomicBoolean();

    // The source's own, read by the timeline.
    private volatile long offset = Long.MIN_VALUE;

    // The source's own.
    private long frames;
    private long busRows;
    private boolean hinted;

    // The timeline's own.
    private long last;

    Show(KronoGui krono, RowSink measure, RowSink view, BusView bus) {
        this.krono = krono;
        this.measure = measure;
        this.view = view;
        this.bus = bus;
    }

    // ------------------------------------------------------------------ the source's thread

    @Override
    public void begin(String origin) {
        measure.begin(origin);
        offset = Long.MIN_VALUE;
        combo.reset();
        frames = 0;
        busRows = 0;
        hinted = false;
        bus.hint("waiting for the bus");
        pending.marker(new Pending.Marker(true, origin));
        arm();
    }

    @Override
    public void row(Row row) {
        measure.row(row);
        long o = row.monoNanos() - System.nanoTime();
        if (o > offset) {
            offset = o;
        }
        if (row.lane().equals(BusRows.LANE)) {
            busRows++;
            if (BusRows.publish(row)) {
                combo.event(row.monoNanos());
            }
        } else if (row.kind().equals(Row.FRAME_PRESENT) && ++frames == 120 && busRows == 0 && !hinted) {
            hinted = true;
            bus.hint("no bus rows in 120 frames: the target needs -Dprobe=all -Dprobe.format=csv");
        }
        pending.row(row);
        arm();
    }

    @Override
    public void end(String why) {
        measure.end(why);
        pending.marker(new Pending.Marker(false, why));
        arm();
    }

    private void arm() {
        if (armed.compareAndSet(false, true)) {
            krono.onTimeline(this::run);
        }
    }

    // ------------------------------------------------------------------ the timeline

    private void run() {
        last = System.nanoTime();
        krono.ramp(SPAN, Ease.LINEAR, p -> step(), this::again);
    }

    /** At the end of an arming: go on if there is still something to show, else rest until a row arrives. */
    private void again() {
        if (step()) {
            run();
            return;
        }
        armed.set(false);
        if (!pending.isEmpty()) {
            arm();
        }
    }

    /** One frame: move show time on, release what it reached, move the beads. Whether anything is left to show. */
    private boolean step() {
        long now = System.nanoTime();
        long dt = Math.min(LONGEST_FRAME, Math.max(0, now - last));
        last = now;
        long o = offset;
        long real = o == Long.MIN_VALUE ? Long.MIN_VALUE : now + o;
        long show = real == Long.MIN_VALUE ? Long.MIN_VALUE : clock.advance(dt, real, pending);
        Object item;
        while ((item = pending.poll(show)) != null) {
            if (item instanceof Row row) {
                if (row.lane().equals(BusRows.LANE)) {
                    bus.apply(row, pool, now);
                }
                view.row(row);
            } else if (item instanceof Pending.Marker m) {
                if (m.begin()) {
                    clock.reset();
                    bus.reset(pool);
                    view.begin(m.text());
                } else {
                    view.end(m.text());
                }
            }
        }
        boolean moving = bus.frame(now, dt, clock, real == Long.MIN_VALUE ? 0 : real, pool, combo);
        return moving || !pending.isEmpty() || (real != Long.MIN_VALUE && clock.lag(real) > 0);
    }
}
