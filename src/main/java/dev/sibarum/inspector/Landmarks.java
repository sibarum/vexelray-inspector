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

    /** What the loop is doing, on the movement: {@code TICK · #12}, {@code AT REST · 200ms}. */
    static final String PLAQUE = "plaque";

    /** The readouts beside the movement. */
    static final String FRAME = "readout.frame";
    static final String FRAME_TIME = "readout.frame-time";
    static final String INTERVAL = "readout.interval";
    static final String PARK = "readout.park";
    static final String WAKE = "readout.wake";
    static final String WAKER = "readout.waker";
    static final String LOST = "readout.lost";

    /** The attach picker, shown while nothing is being inspected. Each entry is {@code picker.<app>-<pid>}. */
    static final String PICKER = "picker";

    /** The status line, and where the rows come from. */
    static final String STATUS = "status";
    static final String STATUS_SOURCE = "status.source";

    private Landmarks() {
    }
}
