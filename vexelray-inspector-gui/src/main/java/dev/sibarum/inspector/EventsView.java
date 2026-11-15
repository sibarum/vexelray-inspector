package dev.sibarum.inspector;

import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.core.Messages.EventSlice;
import dev.sibarum.inspector.core.Messages.GoToRow;
import dev.sibarum.inspector.core.Messages.PageEvents;
import dev.sibarum.inspector.core.Messages.Paused;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.gui.widget.Button;
import dev.vexelray.gui.widget.ListView;
import sibarum.atchung.Sender;

/**
 * The events window's view: the bench's {@link EventSlice}, coloured as the Events card colours its rows, from the same
 * {@link Traced}. A plain click on a row asks the bench to put the cursor on it, as a click on the card does; Earlier,
 * Later and Back to the cursor ask it to move the stretch. Copy on a row's menu copies this window's chosen rows.
 * Decides nothing: it holds what it was last told only to colour it again and to find a clicked row.
 */
@View(window = EventsWindow.KEY)
final class EventsView {

    private final EventsWindow window;
    private final Sender<PauseKey> pauseKey;
    private final Sender<GoToRow> goToRow;
    private final Sender<PageEvents> page;
    /** The stretch the bench last said. */
    private EventSlice slice = EventSlice.NONE;
    /** The path the bench last said, to colour the stretch's rows on it. */
    private Traced traced = Traced.NONE;

    EventsView(EventsWindow window, Sender<PauseKey> pauseKey, Sender<GoToRow> goToRow, Sender<PageEvents> page) {
        this.window = window;
        this.pauseKey = pauseKey;
        this.goToRow = goToRow;
        this.page = page;
    }

    @Subscribe
    public void slice(EventSlice s) {
        EventSlice was = slice;
        slice = s;
        // Following the present, kept on the newest row, as the card is. Otherwise scrolled to only when the stretch
        // moves, not as a late row arrives in it, so it can be scrolled through meanwhile: to the cursor's row when it
        // is among them, else to the middle, where the row it was paged to lands.
        boolean moved = (s.following() && !s.paused()) || s.following() != was.following()
                || s.paused() != was.paused() || was.rows().rows().isEmpty()
                || (!s.following() && !s.rows().rows().equals(was.rows().rows()))
                || (s.paused() && s.rows().selected() != was.rows().selected());
        int reveal = !moved || s.rows().rows().isEmpty() ? -1
                : s.rows().selected() >= 0 ? s.rows().selected() : s.rows().rows().size() / 2;
        window.show(dev.sibarum.inspector.View.eventCells(s), dev.sibarum.inspector.View.rowInks(s.rows(), traced),
                s.rows().selected(), dev.sibarum.inspector.View.eventsNote(s), s.earlier(), s.later(),
                !s.following(), reveal);
    }

    @Subscribe
    public void traced(Traced t) {
        traced = t;
        window.places().inks(dev.sibarum.inspector.View.rowInks(slice.rows(), t));
    }

    /** A plain click on a row: the cursor onto it. A Shift- or Ctrl-click only chooses rows to copy (FN-19). */
    @Subscribe
    public void clicked(ListView.Clicked c) {
        Places places = window.places();
        if (c.list().equals(places.address()) && c.item() instanceof Integer place && places.alone(place)
                && place < slice.rows().rows().size()) {
            goToRow.send(new GoToRow(slice.rows().rows().get(place)));
        }
    }

    /** Copy chosen from a row's menu in this window: its chosen rows onto the clipboard. */
    @Subscribe
    public void chosen(ListView.Chosen c) {
        if (c.action().equals(Landmarks.COPY) && c.at().equals(window.places().address())) {
            window.places().copied().ifPresent(csv -> window.gui().clipboard().set(csv));
        }
    }

    /** Whether the recorder is paused: this window's pause button shows what its click would do. */
    @Subscribe
    public void paused(Paused p) {
        window.pause().show(p.paused());
    }

    /** This window's buttons: its pause button as Space, and Earlier, Later and Back to the cursor. */
    @Subscribe
    public void pressed(Button.Pressed p) {
        if (p.button().equals(window.pause().address())) {
            pauseKey.send(new PauseKey());
        } else if (p.button().equals(window.earlierAddress())) {
            page.send(new PageEvents(-1));
        } else if (p.button().equals(window.laterAddress())) {
            page.send(new PageEvents(1));
        } else if (p.button().equals(window.followAddress())) {
            page.send(new PageEvents(0));
        }
    }
}
