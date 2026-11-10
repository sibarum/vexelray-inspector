package dev.sibarum.inspector;

import sibarum.atchung.Topic;

/**
 * What a key chord asks of the {@link View}: the message the chord was declared to send.
 *
 * <p>A chord is a declaration (a keymap action's {@code sends(sender, message)}, threading.md T7.5): the dispatcher
 * sends this when the chord is pressed and runs nothing of the application's, and the view hears it in the frame, on
 * the GUI thread, like any other message. The keys come to the view rather than straight to the bench because what they send
 * the bench needs a moment only the view knows: where its lane comes to rest (design.md 9.8).
 */
final class Keys {

    private Keys() {
    }

    /** Space: pause, or follow again. */
    record PauseKey() {
        static final Topic<PauseKey> TOPIC = Topic.edge(PauseKey.class);
    }

    /** End: follow the present again. */
    record FollowKey() {
        static final Topic<FollowKey> TOPIC = Topic.edge(FollowKey.class);
    }

    /** Shift and an arrow: step the cursor a tenth of what the lane spans, back when {@code direction} is negative. */
    record StepKey(int direction) {
        static final Topic<StepKey> TOPIC = Topic.edge(StepKey.class);
    }

    /** {@code =} and {@code -}: zoom in, halving what the lane spans, or out, doubling it. */
    record ZoomKey(boolean in) {
        static final Topic<ZoomKey> TOPIC = Topic.edge(ZoomKey.class);
    }

    /** Up or Down: step the cursor onto the next or previous row, {@code by} of them, back when negative. */
    record RowKey(int by) {
        static final Topic<RowKey> TOPIC = Topic.edge(RowKey.class);
    }

    /** Ctrl+C: copy the selected rows of the table last clicked, as CSV. */
    record CopyKey() {
        static final Topic<CopyKey> TOPIC = Topic.edge(CopyKey.class);
    }

    /** An arrow: step the cursor onto the next or previous event, {@code by} of them, back when negative. */
    record EventKey(int by) {
        static final Topic<EventKey> TOPIC = Topic.edge(EventKey.class);
    }
}
