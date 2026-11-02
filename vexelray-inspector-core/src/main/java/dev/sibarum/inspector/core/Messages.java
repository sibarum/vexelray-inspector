package dev.sibarum.inspector.core;

import sibarum.atchung.Topic;

import java.util.List;
import java.util.Optional;

/**
 * Every message the application's parts send each other: what each one says, and the topic it travels on.
 *
 * <h2>Message passing is how the parts talk</h2>
 *
 * <p>The application is four kinds of part, and none of them holds another. The {@link Bench} decides; it owns what is
 * known of the run being inspected, and changes it only by running its own declared steps. The view, in the
 * {@code -gui} module, shows; it owns the widgets, and declares the chords that send the person's keys to the bench
 * ({@link Pause}, {@link Step}). {@link ListRuns}, a job, reads the discovery folder on the job lane;
 * {@link LinkRows}, a source, reads a run's rows over its link on a thread of its own for as long as it is attached;
 * {@link RecordingRows} plays a recording's back, and {@link Child} runs a launched command, likewise.
 * Each sends a record on a topic and the others hear it on their own lane, so no state is shared and nothing needs a
 * lock.
 *
 * <p>A record here is what crossed, and it crosses as itself: the processor refuses a payload that is not deeply
 * immutable (T2.4). A list may cross when what it holds may, and the list itself must not change after it is sent,
 * which no type can show: so each record that holds one copies it in its constructor, and no sender can get that
 * wrong.
 *
 * <h2>Reading this file</h2>
 *
 * <p>Grouped by direction, each record beside its topic. A channel is its record's type: each topic is named by its
 * payload ({@code Topic.edge(Runs.class)}), and every part refers to it by the type, a {@code @Subscribe} method's
 * parameter, a {@code Sender<Attach>}, a job's {@code run}, a class literal in {@code @Hears}. There is no topic name
 * anywhere to misspell, and a record with no topic here is a compile error wherever it is heard or sent (T4.1).
 *
 * <h2>Time</h2>
 *
 * <p>Every time here is a moment on the machine's monotonic clock, {@code System.nanoTime()}'s, in nanoseconds. A run's
 * rows count from its probe's own start, and {@link LinkRows} adds that start (the link says it) before a row crosses,
 * so the bench and the view never see a run's private zero: the view sets a row against its frame's
 * {@code monoNanos}, which is the same clock on the same machine.
 */
public final class Messages {

    private Messages() {
    }

    // ------------------------------------------------------------------ the bench, its job and its source

    /** Which applications are running now ({@link ListRuns}): asked on the first window, then on the timer's beat. */
    public static final Topic<FindRuns> FIND_RUNS_TOPIC = Topic.edge(FindRuns.class);

    public record FindRuns() {
    }

    /** One running application that can be inspected: its discovery file's base name and where its link listens. */
    public record Run(String name, String title, long pid, String stream) {
    }

    /** The applications running now with a link, oldest first, this one left out; {@code problem} when unreadable. */
    public static final Topic<Runs> RUNS_TOPIC = Topic.edge(Runs.class);

    public record Runs(List<Run> runs, String problem) {
        public Runs {
            runs = List.copyOf(runs);
        }
    }

    /** Read {@code run}'s rows over its link at {@code stream} ({@link LinkRows}), one run of the source per name. */
    public static final Topic<Attach> ATTACH_TOPIC = Topic.edge(Attach.class, Attach::run);

    public record Attach(String run, String stream) {
    }

    /**
     * A run's link has ended: why, in words for the status line, and, when it ended because the two sides could not
     * read each other, what the banner warns of; empty otherwise.
     */
    public static final Topic<Detached> DETACHED_TOPIC = Topic.edge(Detached.class);

    public record Detached(String run, String why, String warning) {

        /** A link that ended with nothing to warn of. */
        public Detached(String run, String why) {
            this(run, why, "");
        }
    }

    /**
     * One probe row of a run, its time on the machine's monotonic clock, and what the run's wall clock said when it was
     * written, in nanoseconds since the epoch; 0 when it did not say.
     *
     * @param frame the main loop's pass in progress when it was written, its number; 0 when the run did not say
     * @param cause what set its work off, as the probe writes a cause ({@code 3f2a1.t07.4f2}); empty when nothing
     *              did, or the run did not say
     */
    public record Row(long seq, long atNanos, long wallNanos, long frame, String thread, String lane, String kind,
                      String detail, String cause) {

        /** A row whose pass and cause are not known. */
        public Row(long seq, long atNanos, long wallNanos, String thread, String lane, String kind, String detail) {
            this(seq, atNanos, wallNanos, 0L, thread, lane, kind, detail, "");
        }

        /** A row whose wall clock, pass and cause are not known. */
        public Row(long seq, long atNanos, String thread, String lane, String kind, String detail) {
            this(seq, atNanos, 0L, thread, lane, kind, detail);
        }
    }

    /**
     * The rows of {@code run} that arrived since the last batch; {@code lostFrom..lostTo} never arrived, before them.
     * {@link LinkRows} also says the loss as a row of its own ({@link Model#LOST}), so that it is folded and kept with
     * the rest and the readouts can count it as of any moment.
     */
    public static final Topic<Rows> ROWS_TOPIC = Topic.edge(Rows.class);

    public record Rows(String run, long lostFrom, long lostTo, List<Row> rows) {
        public Rows {
            rows = List.copyOf(rows);
        }
    }

    /**
     * Stop reading {@code run}'s rows: the stop of {@link LinkRows} and {@link RecordingRows}, keyed as their requests
     * are, so it stops that run alone. The bench sends it when the person detaches or chooses another run.
     */
    public static final Topic<Detach> DETACH_TOPIC = Topic.edge(Detach.class, Detach::run);

    public record Detach(String run) {
    }

    /**
     * Play the probe CSV at {@code path} back at its recorded pace ({@link RecordingRows}), its rows sent as
     * {@code run}'s, as if it were running now. {@code --replay=<csv>}.
     */
    public static final Topic<Replay> REPLAY_TOPIC = Topic.edge(Replay.class, Replay::run);

    public record Replay(String run, String path) {
    }

    /**
     * Start {@code command} with its automation on ({@link Child}), and choose it once it can be inspected.
     * {@code --launch="<command>"}.
     */
    public static final Topic<LaunchChild> LAUNCH_CHILD_TOPIC = Topic.edge(LaunchChild.class);

    public record LaunchChild(String command) {
    }

    /** A launched command has ended, or could not be started: why, in words for the status line. */
    public static final Topic<ChildExited> CHILD_EXITED_TOPIC = Topic.edge(ChildExited.class);

    public record ChildExited(String command, String why) {
    }

    /**
     * Inspect {@code run} and stay with it: the picker, {@code --attach}, or a launched child once its discovery file
     * appears. A run chosen is not left for a newer one, and when it goes away its tape stays on screen.
     *
     * @param from how it was reached, one of {@link Bench}'s {@code ATTACHED}, {@code LAUNCHED}
     */
    public static final Topic<Choose> CHOOSE_TOPIC = Topic.edge(Choose.class);

    public record Choose(Run run, String from) {
    }

    /** The Detach button: stop inspecting the run attached, keep its tape, and attach to nothing until one is chosen. */
    public static final Topic<Leave> LEAVE_TOPIC = Topic.edge(Leave.class);

    public record Leave() {
    }

    // ------------------------------------------------------------------ the person tells the bench

    /**
     * Space: following, stop the timeline at {@code restNanos}; stopped, follow the present again. The bench decides
     * which. The moment is the view's: where its lane comes to rest if it slows from the present now, which only it can
     * know, so that a pause never moves the lane backwards (design.md 9.8). It may be a little ahead of the newest row.
     */
    public static final Topic<Pause> PAUSE_TOPIC = Topic.edge(Pause.class);

    public record Pause(long restNanos) {
    }

    /**
     * End, or the button at the focused lane's right end: follow the present again, leaving the island the cursor was kept on.
     * Following already, nothing. Not {@link Pause}, which toggles: a button pressed while following must not stop it.
     */
    public static final Topic<Follow> FOLLOW_TOPIC = Topic.edge(Follow.class);

    public record Follow() {
    }

    /**
     * An arrow: move the cursor by {@code byNanos}, back when negative. Stopped, from the cursor; following, from
     * {@code fromNanos}, where the view's lane would come to rest. An edge, not a state topic: a step is relative, so
     * two quick presses must be two steps, not the newer one.
     */
    public static final Topic<Step> STEP_TOPIC = Topic.edge(Step.class);

    public record Step(long byNanos, long fromNanos) {
    }

    /**
     * An arrow: move the cursor {@code by} events of {@code lane}, back when negative: onto its next or previous event,
     * which it is then on. The lane is the one the view has focused. Stopped, from the cursor; following, from
     * {@code fromNanos}, where the view's lane would come to rest, so the first step back is onto the lane's newest
     * event. An edge, as {@link Step} is.
     */
    public static final Topic<StepEvent> STEP_EVENT_TOPIC = Topic.edge(StepEvent.class);

    public record StepEvent(String lane, int by, long fromNanos) {
    }

    /**
     * A click where there is no event near: put the cursor at {@code atNanos}, pausing if following; never off the
     * island the bench keeps while paused. The absolute cursor (design.md section 8): a state topic,
     * so a drag through a storm of moments costs the bench one a tick, the newest winning.
     */
    public static final Topic<Seek> SEEK_TOPIC = Topic.state(Seek.class);

    public record Seek(long atNanos) {
    }

    /** A click on an event: put the cursor on it, pausing if following. Ignored unless it is one the tape holds. */
    public static final Topic<GoTo> GO_TO_TOPIC = Topic.edge(GoTo.class);

    public record GoTo(Event event) {
    }

    // ------------------------------------------------------------------ the bench tells the view

    /**
     * One event on a lane, on the machine's monotonic clock: a stretch from its start to its end (a pass, a tick, a
     * draw, a wait), or a moment when the two are equal (an input, a draw held). {@code kind} says which, in words the
     * status line can use, and is one of {@link Model}'s kinds.
     *
     * @param thread the thread whose row said it, as the probe names it; empty when not known
     * @param frame  the main loop's pass it happened in, its number; 0 when the run did not say
     * @param cause  what set it off, as the probe writes a cause ({@code 3f2a1.t07.4f2}); empty when not known
     * @param fields what is known of it, in the event schema's order ({@link Model#FIELDS}): only the fields the rows
     *               filled, so a field that is absent was not recorded, and none says so in words
     */
    public record Mark(long fromNanos, long toNanos, String kind, String thread, long frame, String cause,
                       List<Field> fields) {

        public Mark {
            fields = List.copyOf(fields);
        }

        /** An event whose thread is not known and whose rows say nothing more of it. */
        public Mark(long fromNanos, long toNanos, String kind) {
            this(fromNanos, toNanos, kind, "");
        }

        /** An event whose pass, cause and fields are not known. */
        public Mark(long fromNanos, long toNanos, String kind, String thread) {
            this(fromNanos, toNanos, kind, thread, 0L, "", List.of());
        }

        /** This event, set off by {@code cause}: for a pass, what woke it, which is what made it run. */
        public Mark because(String cause) {
            return cause.equals(this.cause) ? this : new Mark(fromNanos, toNanos, kind, thread, frame, cause, fields);
        }

        /** The value of its field {@code name}; empty when it has none. */
        public String field(String name) {
            for (Field f : fields) {
                if (f.name().equals(name)) {
                    return f.value();
                }
            }
            return "";
        }

        /**
         * This event with its field {@code name} set to {@code value}, in the schema's order; with it left as it was
         * when {@code value} is empty, or when {@code keep} and it is already set.
         */
        public Mark with(String name, String value, boolean keep) {
            if (value.isEmpty() || (keep && !field(name).isEmpty()) || value.equals(field(name))) {
                return this;
            }
            List<Field> out = new java.util.ArrayList<>(fields.size() + 1);
            int order = Model.FIELDS.indexOf(name);
            boolean placed = false;
            for (Field f : fields) {
                if (f.name().equals(name)) {
                    continue;
                }
                if (!placed && order >= 0 && Model.FIELDS.indexOf(f.name()) > order) {
                    out.add(new Field(name, value));
                    placed = true;
                }
                out.add(f);
            }
            if (!placed) {
                out.add(new Field(name, value));
            }
            return new Mark(fromNanos, toNanos, kind, thread, frame, cause, out);
        }
    }

    /**
     * One field of an event: a name from the event schema ({@link Model#FIELDS}) and its value, in words a filter can
     * match and the details panel shows as they are.
     */
    public record Field(String name, String value) {
    }

    /**
     * One lane of the timeline: a clock's events, in the order their rows arrived.
     *
     * @param recorded whether the run's probe records the rows this lane is read from: false says an empty lane is
     *                 not a quiet one, and so it is never said of a run that did not say what it records
     */
    public record Lane(String name, List<Mark> marks, boolean recorded) {
        public Lane {
            // A fold's lanes are already immutable, and copying a minute of events for each batch is what it avoids.
            marks = marks instanceof Appended<Mark> kept ? kept : List.copyOf(marks);
        }

        /** A lane the run is not known not to record. */
        public Lane(String name, List<Mark> marks) {
            this(name, marks, true);
        }
    }

    /** An event, and the lane it is on. */
    public record Event(String lane, Mark mark) {
    }

    /**
     * The timeline: which run, what to say about it, where the cursor is, and every event kept on every lane, in
     * {@link Model#LANES}' order. A state topic: a storm of rows costs the view one a frame.
     *
     * <p>The events are all those kept, not only those before the cursor: the lanes glide between moments (design.md
     * 9.8) and show what they pass on the way, and at rest the cursor may be anywhere across them, with what came after
     * it to its right.
     *
     * @param run       the run attached, or empty when none is
     * @param status    what the status line says
     * @param live      whether the timeline follows the present: rows are arriving and the cursor is not stopped
     * @param edgeNanos the newest row while following; the cursor while stopped, which the view shows as a line and keeps
 *                  in view, its lanes at rest wherever it chooses (design.md 9.8)
     * @param on        the event the cursor is on, after a step by events or a click; the cursor is then at its end
     * @param warning   what makes what is shown less than whole, in words, for a banner in plain sight: a run of
     *                  another link version; empty when nothing does
     */
    public static final Topic<Timeline> TIMELINE_TOPIC = Topic.state(Timeline.class);

    public record Timeline(String run, String status, boolean live, long edgeNanos, List<Lane> lanes,
                           Optional<Event> on, String warning) {
        public Timeline {
            lanes = List.copyOf(lanes);
        }

        /** A timeline with nothing to warn of. */
        public Timeline(String run, String status, boolean live, long edgeNanos, List<Lane> lanes,
                        Optional<Event> on) {
            this(run, status, live, edgeNanos, lanes, on, "");
        }
    }

    /**
     * The readouts, over the second before the cursor (the newest row while following), from the same model the
     * timeline is drawn from, so they rewind with it. A state topic, as the timeline is.
     *
     * <p>Ticks are those of the logic lane's own thread: under {@code --logic=main} they run on the main thread beside
     * the widgets' tick, which no row tells apart yet (framework-notes FN-8), so there they read nothing.
     *
     * @param run            the run attached, or empty when none is
     * @param draws          draws in the second
     * @param heldForCeiling draws held for the ceiling in it: pacing doing its job
     * @param heldForGpu     draws held for the GPU in it: the GPU being slow
     * @param ticks          logic ticks in it
     * @param tickP50Nanos   how long they took, the median; 0 with none
     * @param tickP95Nanos   the 95th percentile; 0 with none
     * @param rowsLost       rows the link lost, from the first row read to the cursor
     */
    public static final Topic<Readouts> READOUTS_TOPIC = Topic.state(Readouts.class);

    public record Readouts(String run, int draws, int heldForCeiling, int heldForGpu, int ticks, long tickP50Nanos,
                           long tickP95Nanos, long rowsLost) {
    }

    /**
     * What is known about the cursor's moment and the event it is on, for the details panel: what the inspected run's
     * clock said then, how far into the run it was, and the event. Who received a message, how it travelled and which
     * component sent it are not known from the rows yet (framework-notes FN-10), so they are not fields here. A state
     * topic, as the timeline is.
     *
     * @param run         the run attached, or empty when none is
     * @param atNanos     the cursor's moment, the newest row's while following, on the machine's monotonic clock
     * @param intoNanos   how far into the run that is, from its first row read
     * @param clockNanos  what the run's wall clock said at that moment, in nanoseconds since the epoch; -1 when no row
     *                    has said
     * @param on          the event the cursor is on, if any
     */
    public static final Topic<Details> DETAILS_TOPIC = Topic.state(Details.class);

    /**
     * The run panel: what is inspected and from where, and what else could be. A state topic, as the timeline is.
     *
     * @param run     the run attached or replayed, or empty when none is
     * @param title   its window's title, or the recording's file name
     * @param pid     its process; 0 for a recording
     * @param from    how it was reached: attached, launched or replayed ({@link Bench}'s words); empty with no run
     * @param logic   where its logic ticks run, as far as the rows tell, in words; empty before any row
     * @param choices the runs that could be inspected now, oldest first, this one among them when it is running
     * @param chosen  whether the run was chosen (picked, {@code --attach}, launched, replayed) rather than found: a
     *                chosen run is kept when it goes away, and the bench attaches to no other by itself
     */
    public static final Topic<RunPanel> RUN_PANEL_TOPIC = Topic.state(RunPanel.class);

    public record RunPanel(String run, String title, long pid, String from, String logic, List<Run> choices,
                           boolean chosen) {
        public RunPanel {
            choices = List.copyOf(choices);
        }
    }

    public record Details(String run, long atNanos, long intoNanos, long clockNanos, Optional<Event> on) {
    }

    /**
     * The path of the event the cursor is on, for every panel to mark (design.md section 3, a click's path): what
     * shares its cause, and the passes, ticks and draws it went through, which run in causes of their own, and the
     * topics it crossed. Read from the same hops as the details' Path. A state topic, as the details are; {@link #NONE}
     * while following, or on no event.
     *
     * @param run     the run attached, or empty when none is
     * @param cause   the event's cause, as the probe writes one; empty when it has none
     * @param passes  the passes its hops were in or woke, by number
     * @param ticks   the ticks its hops were in, by number ({@code #12})
     * @param draws   the draws that showed what it changed, by number ({@code #31})
     * @param crossed every topic it crossed, a write or a delivery each, in the order they began
     */
    public static final Topic<Traced> TRACED_TOPIC = Topic.state(Traced.class);

    public record Traced(String run, String cause, List<Long> passes, List<String> ticks, List<String> draws,
                         List<Crossed> crossed) {

        public static final Traced NONE = new Traced("", "", List.of(), List.of(), List.of(), List.of());

        public Traced {
            passes = List.copyOf(passes);
            ticks = List.copyOf(ticks);
            draws = List.copyOf(draws);
            crossed = List.copyOf(crossed);
        }

        /** Whether lane {@code lane}'s event {@code m} is on the path: in its cause, or a pass, tick or draw of it. */
        public boolean has(String lane, Mark m) {
            if (!cause.isEmpty() && cause.equals(m.cause())) {
                return true;
            }
            return switch (lane) {
                case Model.MAIN -> passes.contains(m.frame());
                case Model.LOGIC -> ticks.contains(m.field(Model.TICK_NUMBER));
                case Model.DRAWING -> draws.contains(m.field(Model.DRAW_NUMBER));
                default -> false;
            };
        }
    }

    /**
     * A topic the path crossed: a write on it, or a delivery of it to {@code to}, a mailbox ({@code class#method}) or an
     * inbox's component, as the flows' receivers are named; empty for a write.
     */
    public record Crossed(String topic, String to) {
    }

    /**
     * The declared graph, lit by the traffic, as of the cursor: every part the run declares, and every topic with who
     * sends it, who hears it and how many messages crossed, for the graph and flows panels (design.md section 4). A
     * state topic, as the timeline is.
     *
     * <p>Counted from the rows: a component's write is a {@code send} row, a write from outside a tick (a source's, a
     * timer's, a widget's) a row whose kind is the topic, and a delivery a {@code receive} row. A state topic written
     * more often than it is heard in a tick shows it as written over delivered.
     *
     * @param run   the run attached, or empty when none is
     * @param why   why there is no graph, in words: a recording has none, or the run declared none; empty when there
     *              is one
     * @param parts every part the run declares, in its graph's order
     * @param flows every topic its graph names, by name
     */
    public static final Topic<Flows> FLOWS_TOPIC = Topic.state(Flows.class);

    public record Flows(String run, String why, List<Part> parts, List<Flow> flows) {
        public Flows {
            parts = List.copyOf(parts);
            flows = List.copyOf(flows);
        }
    }

    /**
     * One declared part.
     *
     * @param kind  {@code component}, {@code view}, {@code job}, {@code source}, {@code destination} or
     *              {@code sender}
     * @param name  its class's name, as the rows name it
     * @param lane  where it runs: {@code logic}, {@code gui}, {@code job}, {@code own} (a thread per run), a declared
     *              lane's name, or {@code -}
     * @param timer whether it takes the framework's timer, which then sends for it
     */
    public record Part(String kind, String name, String lane, boolean timer) {
    }

    /**
     * One topic, as of the cursor.
     *
     * @param topic     its name, the payload's canonical name
     * @param kind      {@code state}, {@code edge}, {@code edge keyed}, or {@code ?} when the run could not say
     * @param from      who sends it: each declared sender with what it wrote, and the framework for what no declared
     *                  part wrote
     * @param to        who hears it: each declared receiver with what was delivered to it, and how it hears
     * @param written   messages written on it, from the run's first row read to the cursor
     * @param delivered deliveries of it, to every receiver together, over the same
     * @param perSecond written a second, over the second or two before the cursor
     */
    public record Flow(String topic, String kind, List<End> from, List<End> to, long written, long delivered,
                       double perSecond) {
        public Flow {
            from = List.copyOf(from);
            to = List.copyOf(to);
        }
    }

    /**
     * One end of a topic and how many messages crossed it.
     *
     * @param part  the part's name, or {@link #FRAMEWORK} for what no declared part sent
     * @param how   {@code sends}, or how the part hears it: {@code mailbox}, {@code inbox}, {@code request},
     *              {@code start}, {@code stop}, {@code open}, {@code write}
     * @param count messages written by it, or delivered to it; {@link #UNCOUNTED} for a job, a source or a destination
     *              hearing, which no row counts
     */
    public record End(String part, String how, long count) {

        /** The part named for writes no declared part made: a widget's, the window's, input's, automation's. */
        public static final String FRAMEWORK = "the framework";

        /** The count of an end no row counts: what reached a job, a source or a destination. */
        public static final long UNCOUNTED = -1L;
    }
}
