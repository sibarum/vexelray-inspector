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
 * <p><b>Lost rows are a row.</b> A batch says which rows its link lost before it; that range is added to the batch as
 * one more row, {@link Model#LOST}, at the batch's first moment, so it is folded and kept on the tape like the rest and
 * the readouts count it as of any moment.
 */
@Source
public final class LinkRows {

    /**
     * The probe's lanes asked for: those {@link Model}'s timeline lanes are read from, and {@code app}, whose rows name
     * what set an event off (its cause's source, an automation command). The bench reads what it knows, so
     * asking for more only costs the link. The run must record them: an application started with
     * {@code -Dprobe=all} does, while automation alone records only {@code input}, {@code frame}, {@code layout} and
     * {@code app}, so its ticks and its GPU are not there to show.
     */
    static final List<String> LANES = List.of("input", "frame", "bus", "gpu", Model.APP_LANE);

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
            // The run's sources were named in rows written before the stream began; the link names them again.
            List<String> sources = opened.sources() == null ? List.of() : opened.sources();
            for (RowBatch b = arrived.take(); b != CLOSED; b = arrived.take()) {
                rows.send(rows(request.run(), origin, b, sources));
                sources = List.of();
            }
            return new Detached(request.run(), "it went away");
        } catch (InterruptedException stopped) {
            return new Detached(request.run(), "stopped");
        } catch (RuntimeException e) {
            return new Detached(request.run(), "its link failed: " + e.getMessage());
        }
    }

    /**
     * One batch, its rows set on the machine's clock, and what its link lost before it said first, at its first row's
     * moment, or now on this machine's clock when it holds none.
     */
    static Rows rows(String run, long origin, RowBatch b) {
        return rows(run, origin, b, List.of());
    }

    /**
     * As {@link #rows(String, long, RowBatch)}, with {@code sources} first, each as the {@code cause.source} row that
     * named it ({@code t03 gui.frame}), at the batch's first moment.
     */
    static Rows rows(String run, long origin, RowBatch b, List<String> sources) {
        List<Row> out = new ArrayList<>(b.rows().size() + 1 + sources.size());
        long first = b.rows().isEmpty() ? System.nanoTime() : origin + b.rows().getFirst().tMonoNs();
        for (String source : sources) {
            out.add(new Row(-1, first, "", Model.APP_LANE, Model.SOURCE, source));
        }
        if (b.lost()) {
            long at = first;
            out.add(new Row(-1, at, "", Model.LOST_LANE, Model.LOST,
                    Long.toString(b.lostTo() - b.lostFrom() + 1)));
        }
        for (dev.vexelray.gui.inspector.link.Row r : b.rows()) {
            out.add(new Row(r.seq(), origin + r.tMonoNs(), r.tWallNs(), r.frame(), r.thread(), r.lane(), r.kind(),
                    r.detail(), r.cause() == null ? "" : r.cause()));
        }
        return new Rows(run, b.lostFrom(), b.lostTo(), out);
    }
}
