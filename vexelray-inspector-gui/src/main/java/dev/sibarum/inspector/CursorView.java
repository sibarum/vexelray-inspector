package dev.sibarum.inspector;

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

    CursorView(CursorWindow window) {
        this.window = window;
    }

    @Subscribe
    public void details(Details d) {
        window.details().set(dev.sibarum.inspector.View.details(d, ZoneId.systemDefault()));
    }
}
