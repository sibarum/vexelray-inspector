package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.vexelray.framework.api.Source;
import dev.vexelray.gui.inspector.link.LinkClient;
import dev.vexelray.gui.inspector.link.RowBatch;
import dev.vexelray.gui.inspector.link.StreamOpen;
import dev.vexelray.gui.inspector.link.StreamOpened;
import sibarum.atchung.Sender;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A run's rows, read over its link for as long as it is attached: a source (vexelray-framework's
 * {@code docs/components.md} section 11), one run per {@link Attach}, on a thread of its own.
 *
 * <p>The link hands each batch to its own receive thread, which must not block; so a batch is queued there and sent
 * from the run's thread, which keeps one run's messages in the order they arrived and on one sender. The run waits on
 * that queue, so stopping it (its thread interrupted) ends the wait, and it closes the link on the way out.
 *
 * <p><b>Rows cross on the machine's clock.</b> A row's own time counts from its run's probe start, which the link says
 * once, when the stream opens ({@code StreamOpened.originNanos}). It is added here, so nothing past this class ever
 * sees a run's private zero. A run too old to say it is refused with that reason: a timeline that guessed would place
 * every row wrong by an amount it could not show.
 *
 * <p>Only the {@code frame} lane for now: the passes are the first lane the timeline draws (R1's first slice).
 */
@Source
public final class LinkRows {

    /** The lanes asked for. The bench reads what it knows, so asking for more only costs the link. */
    static final List<String> LANES = List.of("frame");

    /** The end of the link, queued after its last batch. */
    private static final RowBatch CLOSED = new RowBatch(0, 0, List.of());

    private final Sender<Rows> rows;

    public LinkRows(Sender<Rows> rows) {
        this.rows = rows;
    }

    public Detached run(Attach request) {
        LinkedBlockingQueue<RowBatch> arrived = new LinkedBlockingQueue<>();
        LinkClient link;
        try {
            link = LinkClient.connect(request.stream());
        } catch (RuntimeException e) {
            return new Detached(request.run(), "its link could not be reached: " + e.getMessage());
        }
        try (link) {
            link.onClose(() -> arrived.add(CLOSED));
            StreamOpened opened = link.open(new StreamOpen(LANES, -1, 0), arrived::add, snap -> { });
            if (opened.err().isPresent()) {
                return new Detached(request.run(), "it refused the stream: " + opened.err().get());
            }
            if (opened.originNanos().isEmpty()) {
                return new Detached(request.run(), "it is older than its link's clock origin, so its rows cannot be"
                        + " placed in time: rebuild it against this stack");
            }
            long origin = opened.originNanos().get();
            for (RowBatch b = arrived.take(); b != CLOSED; b = arrived.take()) {
                rows.send(rows(request.run(), origin, b));
            }
            return new Detached(request.run(), "it went away");
        } catch (InterruptedException stopped) {
            return new Detached(request.run(), "stopped");
        } catch (RuntimeException e) {
            return new Detached(request.run(), "its link failed: " + e.getMessage());
        }
    }

    /** One batch, its rows set on the machine's clock. */
    static Rows rows(String run, long origin, RowBatch b) {
        List<Row> out = new ArrayList<>(b.rows().size());
        for (dev.vexelray.gui.inspector.link.Row r : b.rows()) {
            out.add(new Row(r.seq(), origin + r.tMonoNs(), r.thread(), r.lane(), r.kind(), r.detail()));
        }
        return new Rows(run, b.lostFrom(), b.lostTo(), out);
    }
}
