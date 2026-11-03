package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Attach;
import dev.sibarum.inspector.core.Messages.Detach;
import dev.sibarum.inspector.core.Messages.Detached;
import dev.sibarum.inspector.core.Messages.Row;
import dev.sibarum.inspector.core.Messages.Rows;
import dev.vexelray.framework.api.Source;
import dev.vexelray.gui.inspector.link.LinkClient;
import dev.vexelray.gui.inspector.link.QueryReply;
import dev.vexelray.gui.inspector.link.RowBatch;
import dev.vexelray.gui.inspector.link.StreamOpen;
import dev.vexelray.gui.inspector.link.StreamOpened;
import dev.vexelray.gui.inspector.link.InspectorLink;
import sibarum.atchung.Sender;
import sibarum.intrabuf.IntrabufException;
import sibarum.probe.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 *
 * <p><b>A run of another link version is shown, under a warning.</b> A run of this Inspector's own link version sends
 * everything it reads: a run with its link open records every probe lane, traced. One of another version may not, so
 * {@link #versionWarning} says so in words, logged and added as one more row, {@link Model#WARNING}, which the view
 * shows as a banner for as long as the run is attached.
 */
@Source(stop = Detach.class)
public final class LinkRows {

    private static final Log LOG = Log.of("vexelray-inspector.link");

    /**
     * The probe's lanes asked for: those {@link Model}'s timeline lanes are read from; {@code state} and {@code layout},
     * whose ledger steps and GUI changes are hops of a cause's path; {@code draw}, whose {@code draw.ran} numbers a
     * draw and names the causes it showed (a few rows a draw); and {@code app}, whose rows name what set an event off
     * (its cause's source, an automation command). The bench reads what it knows, so asking for more only costs the
     * link. A run with its link open records them all, whatever its probe settings say (atchung's
     * {@code LogConfig.inspected}).
     */
    static final List<String> LANES = List.of("input", "frame", "bus", "gpu", "state", "layout", "draw",
            Model.APP_LANE);

    /**
     * The thread a run's replay runs on (vexelray-framework's {@code Shell.REPLAY_LANE}): its rows are the replay's
     * components', which would otherwise be counted as the run's, and are left out as they cross.
     */
    static final String REPLAY_THREAD = "vexel-component-replay";

    /** The end of the link, queued after its last batch. */
    private static final RowBatch CLOSED = new RowBatch(0, 0, List.of());

    /** A batch of no rows that lost none, which carries what the link said when it opened. */
    private static final RowBatch NOTHING = new RowBatch(0, -1, List.of());

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
                        + " placed in time: rebuild it against this stack", versionWarning(opened.version()));
            }
            long origin = opened.originNanos().get();
            // The run's sources were named in rows written before the stream began, and what its probe records in
            // none: the link says both, and they are sent as rows at once, so they are folded like the rest.
            List<String> said = opened.sources() == null ? List.of() : opened.sources();
            List<String> recorded = opened.recorded();
            String warning = versionWarning(opened.version());
            if (!warning.isEmpty()) {
                LOG.warn("{}: {}", request.run(), warning);
            }
            // Said at once, with no rows, rather than with the first batch: an idle run sends none for as long as it
            // stays idle, and the banner and the dimmed lanes are what to know before anything else.
            Rows opening = rows(request.run(), origin, NOTHING, said, recorded, warning);
            rows.send(withGraph(opening, graphOf(link)));
            Lag lag = new Lag();
            for (RowBatch b = arrived.take(); b != CLOSED; b = arrived.take()) {
                if (!b.rows().isEmpty()) {
                    lag.add(request.run(), System.nanoTime() - (origin + b.rows().getLast().tMonoNs()), b.rows().size());
                }
                rows.send(rows(request.run(), origin, b));
            }
            return new Detached(request.run(), "it went away");
        } catch (InterruptedException stopped) {
            return new Detached(request.run(), "stopped");
        } catch (RuntimeException e) {
            String unreadable = unreadable(e);
            if (!unreadable.isEmpty()) {
                LOG.warn("{}: {} ({})", request.run(), unreadable, e.getMessage());
            }
            return new Detached(request.run(), "its link failed: " + e.getMessage(), unreadable);
        }
    }

    /**
     * How far behind the run its rows arrive: from a batch's newest row being written to the batch being sent on, which
     * is the link's latency and the batching together. Logged at debug every {@link #EVERY_NANOS}, with how many rows a
     * second arrived, so a run's log says what the link cost it (design.md section 10, R1's "measured").
     */
    static final class Lag {

        static final long EVERY_NANOS = 10_000_000_000L;

        private long[] lags = new long[2048];
        private int n;
        private long rows;
        private long since = System.nanoTime();

        void add(String run, long lagNanos, int batchRows) {
            if (n == lags.length) {
                lags = java.util.Arrays.copyOf(lags, n * 2);
            }
            lags[n++] = lagNanos;
            rows += batchRows;
            long now = System.nanoTime();
            if (now - since >= EVERY_NANOS) {
                java.util.Arrays.sort(lags, 0, n);
                LOG.debug("{}: {} rows a second; a batch arrives p50 {} ms, p95 {} ms, max {} ms behind its newest row",
                        run, rows * 1_000_000_000L / (now - since), lags[n / 2] / 1_000_000.0,
                        lags[(int) (n * 0.95)] / 1_000_000.0, lags[n - 1] / 1_000_000.0);
                n = 0;
                rows = 0;
                since = now;
            }
        }
    }

    /**
     * What to warn of a link that failed because one side could not read what the other sent, in words; empty when it
     * failed for any other reason. Its schema differs, which in a snapshot build is any change to it at all
     * ({@code SNAPSHOT_DRIFT}): the two were built from different versions of the stack.
     */
    static String unreadable(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IntrabufException) {
                return "This application's link and this Inspector's were built from different versions of the stack,"
                        + " and this Inspector cannot read it: nothing it sends is shown. Rebuild whichever is older"
                        + " against this stack.";
            }
        }
        return "";
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
        return rows(run, origin, b, sources, null);
    }

    /**
     * As {@link #rows(String, long, RowBatch, List)}, and, unless {@code recorded} is null, the lanes the run's probe
     * records as one more row first ({@link Model#RECORDED}).
     */
    static Rows rows(String run, long origin, RowBatch b, List<String> sources, List<String> recorded) {
        return rows(run, origin, b, sources, recorded, null);
    }

    /**
     * As {@link #rows(String, long, RowBatch, List, List)}, and, unless {@code warning} is null, it as one more row
     * first ({@link Model#WARNING}): empty says there is nothing to warn of, which takes down a banner a failed attach
     * left up.
     */
    static Rows rows(String run, long origin, RowBatch b, List<String> sources, List<String> recorded,
                     String warning) {
        List<Row> out = new ArrayList<>(b.rows().size() + 3 + sources.size());
        long first = b.rows().isEmpty() ? System.nanoTime() : origin + b.rows().getFirst().tMonoNs();
        if (warning != null) {
            out.add(new Row(-1, first, "", Model.APP_LANE, Model.WARNING, warning));
        }
        if (recorded != null) {
            out.add(new Row(-1, first, "", Model.APP_LANE, Model.RECORDED, String.join(" ", recorded)));
        }
        for (String source : sources) {
            out.add(new Row(-1, first, "", Model.APP_LANE, Model.SOURCE, source));
        }
        if (b.lost()) {
            long at = first;
            out.add(new Row(-1, at, "", Model.LOST_LANE, Model.LOST,
                    Long.toString(b.lostTo() - b.lostFrom() + 1)));
        }
        for (dev.vexelray.gui.inspector.link.Row r : b.rows()) {
            if (REPLAY_THREAD.equals(r.thread())) {
                continue;   // a replay's components, run again in the run's process: their rows are not the run's
            }
            out.add(new Row(r.seq(), origin + r.tMonoNs(), r.tWallNs(), r.frame(), r.thread(), r.lane(), r.kind(),
                    r.detail(), r.cause() == null ? "" : r.cause()));
        }
        return new Rows(run, b.lostFrom(), b.lostTo(), out);
    }

    /**
     * What the run declares about its messages, asked once on attaching (the {@code bus graph} query,
     * {@code docs/bus-graph.md}): the graph's text, header first, or why there is none, in words.
     */
    static String graphOf(LinkClient link) {
        try {
            QueryReply reply = link.query("bus graph");
            if (!reply.ok()) {
                return "it declared no bus graph (" + reply.text().replaceFirst("^err ", "") + ")";
            }
            String text = reply.text();
            int first = text.indexOf('\n');
            return first < 0 ? "it declared an empty bus graph" : text.substring(first + 1);
        } catch (RuntimeException e) {
            return "its bus graph could not be read: " + e.getMessage();
        }
    }

    /** {@code opening} with the run's bus graph as one more row ({@link Model#GRAPH}): its text, or why there is none. */
    static Rows withGraph(Rows opening, String graph) {
        List<Row> out = new ArrayList<>(opening.rows());
        long at = out.isEmpty() ? System.nanoTime() : out.getFirst().atNanos();
        out.add(new Row(-1, at, "", Model.APP_LANE, Model.GRAPH, graph));
        return new Rows(opening.run(), opening.lostFrom(), opening.lostTo(), out);
    }

    /**
     * What to warn of a run whose link is of version {@code theirs}, in words; empty when it is this Inspector's own.
     * Empty {@code theirs} is a run older than the version itself.
     */
    static String versionWarning(Optional<Integer> theirs) {
        int ours = InspectorLink.VERSION;
        if (theirs.isPresent() && theirs.get() == ours) {
            return "";
        }
        String which = theirs.isEmpty() ? "an older version of the link than this Inspector's (" + ours + ")"
                : "link version " + theirs.get() + ", and this Inspector is version " + ours;
        String upgrade = theirs.isPresent() && theirs.get() > ours ? "upgrade this Inspector"
                : "rebuild the application against this stack";
        return "This application speaks " + which + ", so some of what it sends may be missing or misread here: "
                + upgrade + ".";
    }
}
