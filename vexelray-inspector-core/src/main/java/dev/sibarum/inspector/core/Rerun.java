package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.AskReplay;
import dev.sibarum.inspector.core.Messages.Difference;
import dev.sibarum.inspector.core.Messages.ReplayReport;
import dev.sibarum.inspector.core.Messages.Replayed;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.RunAgain;
import dev.sibarum.inspector.core.Messages.RunPanel;
import dev.vexelray.framework.api.Component;
import dev.vexelray.framework.api.Hears;
import dev.vexelray.framework.api.Publishes;
import sibarum.atchung.Inbox;
import sibarum.atchung.Ledger;
import sibarum.atchung.Out;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Running the attached run again (design.md R6): the Run again button asks the run to {@code replay}, and what it
 * answers becomes the replay panel's report. Apart from the bench, which it does not need: the run panel says which
 * run is attached and where its link listens, and the run answers the rest.
 *
 * <p>A run replays in its own process (vexelray-framework's {@code Shell.replay}), and only one launched
 * {@code --replayable}, so a run that keeps no recording answers why, and that is the report.
 */
@Component
@Hears({RunPanel.class, RunAgain.class, Replayed.class})
@Publishes({AskReplay.class, ReplayReport.class})
public final class Rerun {

    /** Deep enough for a burst of run panels; the button and the answer are one each. */
    static final int CAPACITY = 64;

    /**
     * What it knows: the run attached and its link, empty for a recording, which has none; and the report.
     */
    public record State(String run, String name, String stream, ReplayReport report) {
        static final State NONE = new State("", "", "", ReplayReport.NONE);
    }

    private final Ledger<State> ledger;

    public Rerun(Inbox inbox) {
        Ledger.Builder<State> b = Ledger.of(State.NONE);
        b.on(Messages.RUN_PANEL_TOPIC, b.step("run panel", Rerun::runPanel))
                .on(Messages.RUN_AGAIN_TOPIC, b.step("run again", Rerun::runAgain))
                .on(Messages.REPLAYED_TOPIC, b.step("replayed", Rerun::replayed));
        ledger = b.build(inbox, "rerun", CAPACITY);
    }

    /** Its state, as last committed: for reading, from any thread. */
    public State state() {
        return ledger.state().value();
    }

    /** Another run attached, or none: its report goes, since it was of the one before. */
    static State runPanel(State s, RunPanel p, Out out) {
        String stream = "";
        for (Run r : p.choices()) {
            if (r.name().equals(p.run())) {
                stream = r.stream();
            }
        }
        String name = p.title().isBlank() ? p.run() : p.title();
        if (p.run().equals(s.run()) && stream.equals(s.stream())) {
            return s;
        }
        State next = new State(p.run(), name, stream, p.run().equals(s.run()) ? s.report() : ReplayReport.NONE);
        if (!next.report().equals(s.report())) {
            out.send(Messages.REPLAY_REPORT_TOPIC, next.report());
        }
        return next;
    }

    /** The button: ask the run attached to replay, unless there is nothing to ask. */
    static State runAgain(State s, RunAgain r, Out out) {
        if (s.report().asking()) {
            return s;
        }
        String said = s.run().isEmpty() ? "Nothing is attached to run again"
                : s.stream().isEmpty() ? "A recording cannot be run again: only a running application keeps what its"
                + " logic took in" : "Running " + s.name() + " again";
        ReplayReport report = new ReplayReport(s.run(), said, !s.stream().isEmpty(), List.of());
        if (report.asking()) {
            out.send(Messages.ASK_REPLAY_TOPIC, new AskReplay(s.run(), s.stream()));
        }
        out.send(Messages.REPLAY_REPORT_TOPIC, report);
        return new State(s.run(), s.name(), s.stream(), report);
    }

    /** The run's answer, of the run still attached: the report. */
    static State replayed(State s, Replayed r, Out out) {
        if (!r.run().equals(s.run())) {
            return s;
        }
        ReplayReport report = report(s.run(), r.answer());
        out.send(Messages.REPLAY_REPORT_TOPIC, report);
        return new State(s.run(), s.name(), s.stream(), report);
    }

    private static final Pattern HEAD = Pattern.compile("ok replay ticks=(\\d+) sends=(\\d+) differ=(\\d+)( full)?");
    private static final Pattern LINE = Pattern.compile(
            "#(\\d+) (\\d+) (\\S+) recorded=(\\S+) replayed=(\\S+) ?(.*)");
    private static final Pattern THREW = Pattern.compile("#(\\d+) threw (.*)");
    /** The lines under a difference: what the replay sent there, and what the run sent, when it kept its sends. */
    private static final String NOW = "  now ";
    private static final String WAS = "  was ";

    /**
     * What {@code answer}, a run's reply to {@code replay}, says, in words, and each difference it lists. Pure, for a
     * test. A reply this Inspector cannot read is said as it came.
     */
    static ReplayReport report(String run, String answer) {
        String[] lines = answer.split("\n");
        if (lines[0].startsWith("err ")) {
            return new ReplayReport(run, "Could not run it again: " + lines[0].replaceFirst("^err (replay: )?", ""),
                    false, List.of());
        }
        Matcher head = HEAD.matcher(lines[0]);
        if (!head.matches()) {
            return new ReplayReport(run, "Its answer was not one this Inspector reads: " + lines[0], false, List.of());
        }
        long ticks = Long.parseLong(head.group(1));
        long sends = Long.parseLong(head.group(2));
        long differ = Long.parseLong(head.group(3));
        List<Difference> differences = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            Matcher m = LINE.matcher(lines[i]);
            Matcher threw = THREW.matcher(lines[i]);
            if (m.matches()) {
                differences.add(new Difference(Long.parseLong(m.group(1)), Integer.parseInt(m.group(2)), m.group(3),
                        m.group(4), m.group(5), m.group(6), ""));
            } else if (threw.matches()) {
                differences.add(new Difference(Long.parseLong(threw.group(1)), -1, "", "", "",
                        "the replay threw " + threw.group(2), ""));
            } else if (!differences.isEmpty() && (lines[i].startsWith(NOW) || lines[i].startsWith(WAS))) {
                // What the replay sent, and what the run did when it kept its sends, under the difference they are of.
                Difference d = differences.removeLast();
                boolean now = lines[i].startsWith(NOW);
                String said = lines[i].substring(NOW.length());
                differences.add(new Difference(d.tick(), d.send(), d.topic(), d.recorded(), d.replayed(),
                        now ? said : d.what(), now ? d.was() : said));
            }
        }
        String said = "Ran " + count(ticks, "tick") + " of its logic again: "
                + (differ == 0 ? (sends == 0 ? "nothing was sent" : "all " + count(sends, "send") + " the same")
                : differ + " of " + count(sends, "send") + " differ"
                + (differences.isEmpty() ? "" : ", the first in tick #" + differences.getFirst().tick())
                + (differ > differences.size() ? " (" + differences.size() + " listed)" : ""))
                + (head.group(4) != null ? "; its recording is full, so only the ticks to there" : "");
        return new ReplayReport(run, said, false, differences);
    }

    private static String count(long n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }
}
