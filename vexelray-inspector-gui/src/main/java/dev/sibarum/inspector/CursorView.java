package dev.sibarum.inspector;

import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.core.Messages.Paused;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;
import dev.sibarum.inspector.core.Messages.Details;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;

import java.time.ZoneId;

/**
 * The cursor window's view: the same details as the Cursor card, from the same {@link Details}, so the two never
 * disagree. Decides nothing.
 */
@View(window = CursorWindow.KEY)
final class CursorView {

    private final CursorWindow window;
    private final Sender<PauseKey> pauseKey;

    CursorView(CursorWindow window, Sender<PauseKey> pauseKey) {
        this.window = window;
        this.pauseKey = pauseKey;
    }

    @Subscribe
    public void details(Details d) {
        window.details().set(dev.sibarum.inspector.View.details(d, ZoneId.systemDefault()));
    }

    /** Whether the recorder is paused: this window's pause button shows what its click would do. */
    @Subscribe
    public void paused(Paused p) {
        window.pause().show(p.paused());
    }

    /** This window's pause button: as Space, which the main view turns into a pause where its lanes come to rest. */
    @Subscribe
    public void pressed(Button.Pressed p) {
        if (p.button().equals(window.pause().address())) {
            pauseKey.send(new PauseKey());
        }
    }
}
