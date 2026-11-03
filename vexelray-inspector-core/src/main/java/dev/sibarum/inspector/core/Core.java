package dev.sibarum.inspector.core;

import dev.vexelray.framework.api.Configuration;

/**
 * What this module is made of: the {@link Bench}, the job that lists the runs, and the source that reads one.
 *
 * <p>The annotation processor sees only the sources of the module it is compiling, so when {@code -gui} compiles,
 * everything here is a class file it would never construct. This list is how it hears of them: {@code -gui}'s
 * {@code InspectorApp} names this class among its {@code starters}, and the generated wiring builds what is listed
 * here as if it were its own, and checks every message between them. A new component, job or source in this module
 * goes on this list, or nothing builds it.
 */
@Configuration(components = {Bench.class, Rerun.class}, jobs = {ListRuns.class, AskReplay.class},
        sources = {LinkRows.class, RecordingRows.class, Child.class})
public final class Core {
}
