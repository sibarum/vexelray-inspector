package dev.sibarum.inspector;

/**
 * Every name an automation script may write down, in one place.
 *
 * <p><b>A ref is minted per run; a landmark still means something tomorrow.</b> That is the whole distinction
 * (vexelray-gui/docs/automation.md 5), and it is why these are constants rather than string literals scattered
 * through the view: a landmark is a published contract, so renaming one breaks a script somebody else wrote, and
 * the compiler should be the thing that notices.
 *
 * <p>Where you can, carry <em>state in the accessible name</em>. {@code await <landmark> <text>} is how a driver
 * waits for the application to be ready, and it needs no hook when readiness is declared somewhere the read-model
 * can already see. {@link #STATUS_RUN} works that way: it says which run is inspected, so
 * {@code await status.run Inspecting} waits for the first rows to arrive.
 */
final class Landmarks {

    /** The timeline: its lanes, the present at their right edge. */
    static final String TIMELINE = "timeline";

    /** The lane of the main loop's passes. */
    static final String LANE_PASSES = "lane.passes";

    /** The status line, and its slot saying which run is inspected or why none is. */
    static final String STATUS = "status";
    static final String STATUS_RUN = "status.run";

    private Landmarks() {
    }
}
