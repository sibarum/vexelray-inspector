package dev.sibarum.inspector;

/**
 * Every name an automation script may rely on, in one place. A control a test needs to reach gets a name here, not
 * a string literal at the use site; the names are what {@code ottermate find}, {@code await} and {@code click} take.
 */
final class Landmarks {

    /** The whole escapement: plate, wheels, cock and plaque. */
    static final String MOVEMENT = "movement";

    /** The balance wheel's box. It turns; its box does not. */
    static final String BALANCE = "balance";

    /**
     * What the loop is doing, on the movement: {@code TICK · #12}, {@code AT REST · 200ms},
     * {@code STALL · #9 · 1.20 s}.
     */
    static final String PLAQUE = "plaque";

    /** The readouts beside the movement. */
    static final String FRAME = "readout.frame";
    static final String FRAME_TIME = "readout.frame-time";
    static final String INTERVAL = "readout.interval";
    static final String PARK = "readout.park";
    static final String WAKE = "readout.wake";
    static final String WAKER = "readout.waker";
    static final String LOST = "readout.lost";
    static final String STALLS = "readout.stalls";

    /** This application's builds under the movement; each line is {@code history.<n>}, newest first. */
    static final String HISTORY = "history";

    /** The bus panel: conveyors, governor and combo. */
    static final String BUS = "bus";

    /** Each belt's topic is {@code bus.lane.<n>}, and its mailbox's fill {@code bus.lane.<n>.mailbox}. */
    static final String BUS_LANE = "bus.lane";

    /** Why there is no belt yet, while there is none. */
    static final String BUS_HINT = "bus.hint";

    /** How fast show time runs against real time, and how far behind: {@code real time}, {@code ×0.1 · 182 ms behind}. */
    static final String GOVERNOR = "bus.governor";

    /** The burst being counted, in real time ({@code 1,000}), then its total ({@code 1,000!}). */
    static final String COMBO = "bus.combo";

    /** Under the combo: {@code NEW RECORD}, or the record to beat. */
    static final String COMBO_NOTE = "bus.combo.note";

    /** The attach picker, shown while nothing is being inspected. Each entry is {@code picker.<app>-<pid>}. */
    static final String PICKER = "picker";

    /** The status line, and where the rows come from. */
    static final String STATUS = "status";
    static final String STATUS_SOURCE = "status.source";

    private Landmarks() {
    }
}
