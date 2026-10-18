package dev.sibarum.inspector.rows;

import dev.vexelray.gui.inspector.link.LinkClient;
import dev.vexelray.gui.inspector.link.RowBatch;
import dev.vexelray.gui.inspector.link.StreamOpen;
import dev.vexelray.gui.inspector.link.StreamOpened;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A running application, live: its Inspector link (vexelray-gui {@code docs/plans/flight-recorder.md} §6), dialled
 * at the endpoint its discovery file advertises.
 *
 * <p>It opens the stream on the {@code frame} lane only, which is everything the escapement reads, and maps each of
 * the link's rows onto a {@link Row}, frame stamp included, so {@link Row#presentedFrame()} reads the stamp rather
 * than a {@code frame.present} detail. A range of rows the application lost before a batch arrives as one row of its
 * own, lane {@code stream}, kind {@code lost}, detail {@code a..b}; the escapement does not read that lane yet.
 *
 * <h2>Threads</h2>
 *
 * Connecting happens on {@code inspector-link}, a platform daemon thread that ends once the stream is open, so
 * {@link #start} returns at once as {@link RowSource} asks. From then on the rows arrive on elektro-Q's receive
 * thread for this connection: one thread, one call at a time, in the order the application sent them, which is the
 * {@link RowSink} contract. {@link RowSink#end} comes from that thread too when the application goes away.
 */
public final class LinkSource implements RowSource {

    /** The lane read: the escapement's. M2 widens this. */
    static final List<String> LANES = List.of("frame");

    /** The kind of the row that stands for rows the application lost. */
    public static final String LOST = "lost";

    private final String endpoint;
    private final String who;
    private volatile LinkClient client;
    private volatile boolean closed;
    private volatile Thread thread;

    /**
     * @param endpoint where the link listens: {@code uds:<path>} or {@code tcp:<port>}, from a discovery file
     * @param who      the application, for people: {@code demo (pid 18292)}
     */
    public LinkSource(String endpoint, String who) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.who = Objects.requireNonNull(who, "who");
    }

    @Override
    public void start(RowSink sink) {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        Thread t = Thread.ofPlatform().name("inspector-link").daemon().unstarted(() -> connect(sink));
        thread = t;
        t.start();
    }

    private void connect(RowSink sink) {
        LinkClient c;
        try {
            c = LinkClient.connect(endpoint);
        } catch (RuntimeException e) {
            if (!closed) {
                sink.end("could not reach " + who + ": " + e.getMessage());
            }
            return;
        }
        client = c;
        if (closed) {
            c.close();
            return;
        }
        sink.begin(who + ", live");
        c.onClose(() -> {
            if (!closed) {
                sink.end(who + " went away");
            }
        });
        StreamOpened opened;
        try {
            opened = c.open(new StreamOpen(LANES, -1, 0), batch -> deliver(batch, sink), snap -> { });
        } catch (RuntimeException e) {
            if (!closed) {
                sink.end(who + " did not open its stream: " + e.getMessage());
            }
            return;
        }
        if (opened.err().isPresent() && !closed) {
            sink.end(who + " refused the stream: " + opened.err().get());
        }
    }

    /** One batch: the gap before it, if any, then its rows. */
    void deliver(RowBatch batch, RowSink sink) {
        if (closed) {
            return;
        }
        if (batch.lost()) {
            long at = batch.rows().isEmpty() ? 0 : batch.rows().getFirst().tMonoNs();
            sink.row(new Row(batch.lostFrom(), at, "-", "-", "stream", LOST,
                    batch.lostFrom() + ".." + batch.lostTo(), Row.NO_FRAME));
        }
        for (dev.vexelray.gui.inspector.link.Row r : batch.rows()) {
            sink.row(row(r));
        }
    }

    /** The link's row as the Inspector's: the same fields, the wall clock written as ISO-8601 as a file has it. */
    static Row row(dev.vexelray.gui.inspector.link.Row r) {
        return new Row(r.seq(), r.tMonoNs(), Instant.ofEpochSecond(0, r.tWallNs()).toString(), r.thread(), r.lane(),
                r.kind(), r.detail(), r.frame());
    }

    @Override
    public String describe() {
        return who;
    }

    @Override
    public void close() {
        closed = true;
        LinkClient c = client;
        if (c != null) {
            c.close();
        }
    }
}
