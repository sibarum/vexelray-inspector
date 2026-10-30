package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.FindRuns;
import dev.sibarum.inspector.core.Messages.Run;
import dev.sibarum.inspector.core.Messages.Runs;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.automation.Discovery;

import java.util.ArrayList;
import java.util.List;

/**
 * Which applications are running now, from the discovery folder (vexelray-gui's {@code docs/plans/discovery.md}): one
 * read of the folder per request, on the job lane, because it touches the disk.
 *
 * <p>Only a run with a link can be inspected, so one without is left out, and so is this application's own: it opens a
 * link of its own whenever its automation is on, and inspecting itself would draw its own drawing. What can go wrong
 * is the reply's {@code problem}, never a throw.
 */
@Job
public final class ListRuns {

    private final long self = ProcessHandle.current().pid();

    public Runs run(FindRuns request) {
        try {
            List<Run> runs = new ArrayList<>();
            for (Discovery.Entry e : Discovery.live()) {
                if (e.pid() != self && e.stream() != null) {
                    runs.add(new Run(e.base(), e.title(), e.pid(), e.stream()));
                }
            }
            return new Runs(runs, "");
        } catch (RuntimeException e) {
            return new Runs(List.of(), "the discovery folder could not be read: " + e.getMessage());
        }
    }
}
