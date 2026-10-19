package dev.sibarum.inspector.clock;

import dev.sibarum.inspector.rows.Row;

/**
 * What the {@code bus} lane's rows say, as atchung writes them ({@code Atchung.publish}, {@code PumpedReg},
 * {@code Pump}):
 *
 * <ul>
 *   <li>{@code <topic>}: one publish, a span over its whole fan-out; {@code detail} is how long that took. A publish
 *       nobody subscribes to is the same name as a count rather than a span, and reads the same here;</li>
 *   <li>{@code <topic> depth}: a pumped mailbox's depth after a message was queued;</li>
 *   <li>{@code <topic> dropped}: a pumped mailbox refused a message;</li>
 *   <li>{@code <topic> batch}: a pumped mailbox handed its subscriber this many at once;</li>
 *   <li>{@code pump drain} and {@code pump delivered}: the main thread's pump emptying every mailbox.</li>
 *   <li>{@code enqueue}, {@code deliver} and {@code drop}: a named mailbox's own rows, {@code <mailbox> <n>}, which say
 *       which mailbox a message went to (docs/bus-circuit.md §3).</li>
 * </ul>
 */
public final class BusRows {

    public static final String LANE = "bus";

    static final String DEPTH = " depth";
    static final String DROPPED = " dropped";
    static final String BATCH = " batch";
    static final String PUMP = "pump ";

    /** The pump emptied every mailbox. */
    public static final String DRAIN = "pump drain";

    /** A named mailbox queued a message: {@code <mailbox> <depth>} (docs/bus-circuit.md §3). */
    public static final String ENQUEUE = "enqueue";

    /** A named mailbox's subscriber was handed a batch: {@code <mailbox> <count>}. */
    public static final String DELIVER = "deliver";

    /** A named mailbox refused a message: {@code <mailbox> <policy>}. */
    public static final String DROP = "drop";

    private BusRows() {
    }

    /** One message published: the row a bead stands for. */
    public static boolean publish(Row row) {
        String k = row.kind();
        return row.lane().equals(LANE) && !k.startsWith(PUMP) && !k.endsWith(DEPTH) && !k.endsWith(DROPPED)
                && !k.endsWith(BATCH) && !k.equals(ENQUEUE) && !k.equals(DELIVER) && !k.equals(DROP);
    }

    /** A pumped mailbox's depth; the topic is {@link #topic}. */
    public static boolean depth(Row row) {
        return row.lane().equals(LANE) && row.kind().endsWith(DEPTH);
    }

    /** A pumped mailbox refused a message. */
    public static boolean dropped(Row row) {
        return row.lane().equals(LANE) && row.kind().endsWith(DROPPED);
    }

    /** The pump emptied every mailbox. */
    public static boolean drain(Row row) {
        return row.lane().equals(LANE) && row.kind().equals(DRAIN);
    }

    /** The topic a publish, depth, drop or batch row is about. */
    public static String topic(Row row) {
        return topic(row.kind());
    }

    /** The topic a publish, depth, drop or batch row's kind is about. */
    public static String topic(String k) {
        int cut = k.endsWith(DEPTH) ? DEPTH.length() : k.endsWith(DROPPED) ? DROPPED.length()
                : k.endsWith(BATCH) ? BATCH.length() : 0;
        return cut == 0 ? k : k.substring(0, k.length() - cut);
    }

    /** A depth row's number, or 0 when it is not one. */
    public static long count(Row row) {
        try {
            return Long.parseLong(row.detail().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
