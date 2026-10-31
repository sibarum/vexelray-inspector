package dev.sibarum.inspector;

import sibarum.atchung.Topic;

/**
 * What a key chord asks of the {@link View}: the message the chord was declared to send.
 *
 * <p>A chord is a declaration ({@code gui.shortcut(chord, sender, message)}, threading.md T7.5): the dispatcher sends
 * this when the chord is pressed and runs nothing of the application's, and the view hears it in the frame, on the GUI
 * thread, like any other message. The keys come to the view rather than straight to the bench because what they send
 * the bench needs a moment only the view knows: where its lane comes to rest (design.md 9.8).
 */
final class Keys {

    private Keys() {
    }

    /** Space: pause, or follow again. */
    record PauseKey() {
        static final Topic<PauseKey> TOPIC = Topic.edge(PauseKey.class);
    }

    /** Shift and an arrow: step the cursor by {@code byNanos}, back when negative. */
    record StepKey(long byNanos) {
        static final Topic<StepKey> TOPIC = Topic.edge(StepKey.class);
    }

    /** An arrow: step the cursor onto the next or previous event, {@code by} of them, back when negative. */
    record EventKey(int by) {
        static final Topic<EventKey> TOPIC = Topic.edge(EventKey.class);
    }
}
