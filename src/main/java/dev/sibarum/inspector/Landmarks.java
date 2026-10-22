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

    /** The component graph's panel: the board, the governor and the flow table. */
    static final String GRAPH = "graph";

    /** The board: the parts, the arrows between them and the messages on the arrows. */
    static final String GRAPH_BOARD = "graph.board";

    /** Each part's box is {@code graph.part.<key>}: {@code graph.part.input}, {@code graph.part.component.physics}. */
    static final String GRAPH_PART = "graph.part";

    /** The flow table; each row is {@code graph.flow.<n>}, in the order the flows were first seen. */
    static final String GRAPH_FLOWS = "graph.flows";
    static final String GRAPH_FLOW = "graph.flow";

    /** Why the graph shows no traffic, when there is a reason. */
    static final String GRAPH_HINT = "graph.hint";

    /** How fast show time runs against real time, and how far behind: {@code real time}, {@code ×0.1 · 182 ms behind}. */
    static final String GOVERNOR = "graph.governor";

    /** The tape's panel: the tracks, their names, the ruler and the reach line. */
    static final String TAPE = "tape";

    /** The tracks, one per lane, ending at show time. */
    static final String TAPE_TRACKS = "tape.tracks";

    /** Each track's name is {@code tape.track.<n>}, in the order the lanes were first seen. */
    static final String TAPE_TRACK = "tape.track";

    /** How far back the tape reaches: {@code last 10 s}, or how much less it holds after a storm. */
    static final String TAPE_REACH = "tape.reach";

    /** The attach picker, shown while nothing is being inspected. Each entry is {@code picker.<app>-<pid>}. */
    static final String PICKER = "picker";

    /** The status line, and where the rows come from. */
    static final String STATUS = "status";
    static final String STATUS_SOURCE = "status.source";

    private Landmarks() {
    }
}
