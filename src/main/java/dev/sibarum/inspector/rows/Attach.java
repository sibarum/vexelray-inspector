package dev.sibarum.inspector.rows;

import dev.vexelray.gui.automation.Discovery;

import java.util.List;
import java.util.Locale;

/**
 * Which running application to inspect, read from the discovery folder (vexelray-gui {@code docs/plans/discovery.md}):
 * what {@code --attach} matches, which of them the picker offers, and the source for the one chosen.
 */
public final class Attach {

    private Attach() {
    }

    /**
     * The applications {@code spec} names: {@code pid:<n>} for one process, otherwise an application's name, matched
     * without regard to case; a blank spec matches every one. This process is never among them: the Inspector does
     * not inspect itself from its own picker.
     */
    public static List<Discovery.Entry> match(List<Discovery.Entry> live, String spec) {
        long self = ProcessHandle.current().pid();
        String s = spec == null ? "" : spec.trim();
        return live.stream()
                .filter(e -> e.pid() != self)
                .filter(e -> s.isEmpty() || (s.startsWith("pid:") ? Long.toString(e.pid()).equals(s.substring(4).trim())
                        : e.app().equalsIgnoreCase(s)))
                .toList();
    }

    /** {@code demo (pid 18292)}: an application, for people. */
    public static String who(Discovery.Entry e) {
        return e.app() + " (pid " + e.pid() + ")";
    }

    /** Why {@code e} cannot be attached to, or {@code null} when it can. */
    public static String unavailable(Discovery.Entry e) {
        return e.stream() == null
                ? "no inspector link: it was started with -Dautomation.link=off, or before the link existed"
                : null;
    }

    /** The live source for {@code e}, which must be {@link #unavailable available}. */
    public static RowSource source(Discovery.Entry e) {
        if (unavailable(e) != null) {
            throw new IllegalArgumentException(who(e) + " has " + unavailable(e));
        }
        return new LinkSource(e.stream(), who(e));
    }

    /**
     * The source {@code --attach=spec} starts with: the one application it names, or no source with the reason when it
     * names none or several, so the picker is shown with that reason in the status line.
     */
    public static RowSource initial(List<Discovery.Entry> live, String spec) {
        List<Discovery.Entry> matches = match(live, spec);
        if (matches.size() == 1 && unavailable(matches.getFirst()) == null) {
            return source(matches.getFirst());
        }
        if (matches.size() == 1) {
            return RowSource.none(who(matches.getFirst()) + " has " + unavailable(matches.getFirst()));
        }
        String what = "--attach=" + spec.trim();
        return RowSource.none(matches.isEmpty()
                ? what + " matches no running application; choose one"
                : what + " matches " + matches.size() + " applications; choose one");
    }

    /** {@code picker.<app>-<pid>}: the landmark for {@code e}'s entry in the picker. */
    public static String landmark(Discovery.Entry e) {
        return "picker." + e.base().toLowerCase(Locale.ROOT);
    }
}
