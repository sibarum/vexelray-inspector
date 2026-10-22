package dev.sibarum.inspector;

import dev.sibarum.inspector.clock.BusRows;
import dev.sibarum.inspector.clock.Pending;
import dev.sibarum.inspector.clock.ShowClock;
import dev.sibarum.inspector.rows.Row;
import dev.sibarum.inspector.rows.RowSink;
import dev.sibarum.inspector.tape.Tape;
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
 *       run is measured as it happened, never as it was shown.</li>
 *   <li><b>At once, to the {@link Tape}</b>, as its header, so the tape holds what happened whatever has been shown;
 *       the {@link TapeView} draws it up to show time.</li>
 *   <li><b>Into {@link Pending}</b>, released on the timeline as show time reaches it: frame rows to {@code view},
 *       the escapement the window shows, so the balance wheel and the graph share one clock and never disagree
 *       about which frame something happened in; bus rows to the {@link GraphView}, as messages between parts.</li>
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
    private final Ui ui;
    private final GraphView bus;
    private final Tape tape;
    private final TapeView strip;
    private final Pending pending = new Pending(1 << 16);
    private final ShowClock clock = ShowClock.standard();
    private final AtomicBoolean armed = new AtomicBoolean();

    // The source's own, read by the timeline.
    private volatile long offset = Long.MIN_VALUE;

    // The source's own.
    private long frames;
    private long busRows;
    private boolean hinted;

    /** Paused, as asked for from any thread; the timeline takes it up at its next turn ({@link #flip}). */
    private volatile boolean pauseAsked;

    // The timeline's own.
    private long last;
    private boolean paused;
    private long shown = Long.MIN_VALUE;   // the show time last drawn, which a pause holds

    Show(KronoGui krono, RowSink measure, RowSink view, Ui ui) {
        this.krono = krono;
        this.measure = measure;
        this.view = view;
        this.ui = ui;
        this.bus = ui.graph();
        this.strip = ui.tape();
        this.tape = strip.tape();
        strip.onToggle(this::toggle);
    }

    // ------------------------------------------------------------------ pausing

    /**
     * Pause, or go on: any thread. While paused, show time is held for everything drawn (the movement, its readouts,
     * the graph and the tape), and the tape is frozen at that moment to be looked through. The release goes on behind
     * it, so nothing piles up: rows still reach the escapement and the graph's counts, and going on shows them as
     * they are now.
     */
    void toggle() {
        pauseAsked = !pauseAsked;
        krono.onTimeline(this::flip);
    }

    private void flip() {
        boolean want = pauseAsked;
        if (want == paused || (want && shown == Long.MIN_VALUE)) {
            pauseAsked = paused;        // nothing shown yet: nothing to hold
            return;
        }
        paused = want;
        ui.paused(want, shown);
        arm();
    }

    // ------------------------------------------------------------------ the source's thread

    @Override
    public void begin(String origin) {
        measure.begin(origin);
        tape.begin();
        offset = Long.MIN_VALUE;
        frames = 0;
        busRows = 0;
        hinted = false;
        pending.marker(new Pending.Marker(true, origin));
        arm();
    }

    @Override
    public void row(Row row) {
        measure.row(row);
        tape.row(row);
        long o = row.monoNanos() - System.nanoTime();
        if (o > offset) {
            offset = o;
        }
        if (row.lane().equals(BusRows.LANE)) {
            busRows++;
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

    /** One frame: move show time on, release what it reached, move the messages. Whether anything is left to show. */
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
                    bus.row(row, now);
                }
                view.row(row);
            } else if (item instanceof Pending.Marker m) {
                if (m.begin()) {
                    clock.reset();
                    bus.reset();
                    if (!paused) {
                        strip.reset();      // while paused the strip is the frozen moment; going on resets it
                    }
                    view.begin(m.text());
                } else {
                    view.end(m.text());
                }
            }
        }
        boolean moving = bus.frame(now, dt, clock, real == Long.MIN_VALUE ? 0 : real, show);
        if (!paused) {
            shown = show;
            strip.frame(show, now);
        }
        return moving || !pending.isEmpty() || (real != Long.MIN_VALUE && clock.lag(real) > 0);
    }
}
