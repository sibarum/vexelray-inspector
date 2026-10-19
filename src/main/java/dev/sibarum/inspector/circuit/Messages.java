package dev.sibarum.inspector.circuit;

import dev.sibarum.inspector.clock.BusRows;
import dev.sibarum.inspector.rows.Row;

import java.util.HashMap;
import java.util.Map;

/**
 * The bus lane's rows, read against a {@link Layout}: which path each message takes, and what each mailbox holds.
 *
 * <h2>Who sent it</h2>
 *
 * A publish row names its topic and its thread. A component publishes on {@code vexel-component-<lane>}, and
 * anything else on the main thread or a handler; so the sender is the one chip declared on that topic that runs
 * there. A topic with one declared publisher has its sender whatever the thread. When several declared chips could
 * have sent it, the message starts on the trunk, where the topic is certain and the sender is not claimed.
 *
 * <h2>Where it went</h2>
 *
 * A named mailbox writes {@code enqueue <mailbox> <depth>} as a message is queued, inside the publish that queued it
 * and on the same thread (docs/bus-circuit.md §3), so the {@code enqueue} rows a thread writes before its publish row
 * are that message's receivers. A publish with none, from an application that names no mailboxes yet, goes to every
 * mailbox declared on its topic: they are its subscribers, as declared. A topic the graph does not declare is the
 * framework's.
 *
 * <p>Single-threaded: the timeline's. Nothing allocated per row once each thread has been seen.
 */
public final class Messages {

    /** What the rows mean, as they are read. */
    public interface Wire {

        /** A message runs along {@code path} to {@code mailbox}, or to nowhere ({@code -1}) on the framework trunk. */
        void electron(int path, int mailbox);

        /** {@code mailbox} now holds {@code depth}. */
        void charge(int mailbox, long depth);

        /** {@code mailbox}'s subscriber was handed {@code count}. */
        void drain(int mailbox, long count);

        /** {@code mailbox} refused a message. */
        void spark(int mailbox);
    }

    private static final String COMPONENT_THREAD = "vexel-component-";

    /** The mailboxes a thread has queued to since its last publish row. */
    private static final class Queued {
        final int[] mailboxes = new int[64];
        int count;
    }

    private final Layout layout;
    private final Wire wire;
    private final Map<String, Queued> byThread = new HashMap<>();

    public Messages(Layout layout, Wire wire) {
        this.layout = layout;
        this.wire = wire;
    }

    /** A new run: nothing queued. */
    public void reset() {
        byThread.clear();
    }

    /** A row has been reached by show time. Rows not on the bus lane are ignored. */
    public void row(Row row) {
        if (!row.lane().equals(BusRows.LANE)) {
            return;
        }
        String kind = row.kind();
        if (kind.equals(BusRows.ENQUEUE)) {
            int m = mailbox(row.detail());
            if (m >= 0) {
                Queued q = queued(row.thread());
                if (q.count < q.mailboxes.length) {
                    q.mailboxes[q.count++] = m;
                }
                wire.charge(m, number(row.detail()));
            }
        } else if (kind.equals(BusRows.DELIVER)) {
            int m = mailbox(row.detail());
            if (m >= 0) {
                wire.drain(m, number(row.detail()));
            }
        } else if (kind.equals(BusRows.DROP)) {
            int m = mailbox(row.detail());
            if (m >= 0) {
                wire.spark(m);
            }
        } else if (BusRows.publish(row)) {
            publish(kind, row.thread());
        }
    }

    private void publish(String topicName, String thread) {
        Queued q = byThread.get(thread);
        int topic = layout.topic(topicName);
        if (topic < 0) {
            wire.electron(layout.frameworkPath(), -1);
            if (q != null) {
                q.count = 0;
            }
            return;
        }
        int source = sender(topic, thread);
        boolean any = false;
        if (q != null) {
            for (int i = 0; i < q.count; i++) {
                int m = q.mailboxes[i];
                if (layout.topicOf(m) == topic) {
                    send(topic, source, m);
                    any = true;
                }
            }
            q.count = 0;
        }
        if (!any) {
            for (int m : layout.pins(topic)) {
                send(topic, source, m);
            }
        }
    }

    private void send(int topic, int source, int mailbox) {
        int path = layout.path(topic, source, mailbox);
        wire.electron(path >= 0 ? path : layout.path(topic, -1, mailbox), mailbox);
    }

    /**
     * The chip that sent a message on {@code topic} from {@code thread}: the only declared publisher, or the only one
     * that runs on that thread; -1 when that is more than one, or none.
     */
    int sender(int topic, String thread) {
        int[] sources = layout.sources(topic);
        if (sources.length == 1) {
            return sources[0];
        }
        boolean component = thread.startsWith(COMPONENT_THREAD);
        int found = -1;
        for (int c : sources) {
            Layout.ChipBox chip = layout.chips().get(c);
            boolean match = component
                    ? chip.lane().length() == thread.length() - COMPONENT_THREAD.length()
                    && thread.regionMatches(COMPONENT_THREAD.length(), chip.lane(), 0, chip.lane().length())
                    : chip.lane().equals("-");
            if (match) {
                if (found >= 0) {
                    return -1;
                }
                found = c;
            }
        }
        return found;
    }

    private Queued queued(String thread) {
        Queued q = byThread.get(thread);
        if (q == null) {
            q = new Queued();
            byThread.put(thread, q);
        }
        return q;
    }

    /** The mailbox a {@code <mailbox> <n>} detail names, without cutting the string: -1 if the graph has none. */
    private int mailbox(String detail) {
        int space = detail.indexOf(' ');
        int len = space < 0 ? detail.length() : space;
        for (int m = 0; m < layout.mailboxes(); m++) {
            String name = layout.mailboxName(m);
            if (name.length() == len && detail.regionMatches(0, name, 0, len)) {
                return m;
            }
        }
        return -1;
    }

    /** The number after the space in {@code <mailbox> <n>}, or 0. */
    private static long number(String detail) {
        int space = detail.lastIndexOf(' ');
        long n = 0;
        for (int i = space + 1; i < detail.length(); i++) {
            char c = detail.charAt(i);
            if (c < '0' || c > '9') {
                return n;
            }
            n = n * 10 + (c - '0');
        }
        return n;
    }
}
