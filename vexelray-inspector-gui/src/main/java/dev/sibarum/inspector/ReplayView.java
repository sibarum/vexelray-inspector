package dev.sibarum.inspector;

import dev.sibarum.inspector.core.Messages.GoToTick;
import dev.sibarum.inspector.core.Messages.ReplayReport;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import sibarum.atchung.Sender;
import dev.vexelray.gui.widget.ListView;

/**
 * The replay window's view: every difference, from the same {@link ReplayReport} as the Run again card. A plain click
 * on one asks the bench to put the cursor on its tick, as a click on the card does; Copy on a row's menu copies this
 * window's chosen rows. Decides nothing.
 */
@View(window = ReplayWindow.KEY)
final class ReplayView {

    private final ReplayWindow window;
    private final Sender<GoToTick> goToTick;
    /** What running again last found, for a click on a difference to find its tick. */
    private ReplayReport report = ReplayReport.NONE;

    ReplayView(ReplayWindow window, Sender<GoToTick> goToTick) {
        this.window = window;
        this.goToTick = goToTick;
    }

    @Subscribe
    public void replayed(ReplayReport r) {
        report = r;
        window.said(r.said());
        window.places().set(dev.sibarum.inspector.View.replayCells(r));
    }

    /** A plain click on a difference: its tick. A Shift- or Ctrl-click only chooses rows to copy (FN-19). */
    @Subscribe
    public void clicked(ListView.Clicked c) {
        Places places = window.places();
        if (c.list().equals(places.address()) && c.item() instanceof Integer place && places.alone(place)
                && place < report.differences().size()) {
            goToTick.send(new GoToTick(report.differences().get(place).tick()));
        }
    }

    /** Copy chosen from a row's menu in this window: its chosen rows onto the clipboard. */
    @Subscribe
    public void chosen(ListView.Chosen c) {
        if (c.action().equals(Landmarks.COPY) && c.at().equals(window.places().address())) {
            window.places().copied().ifPresent(csv -> window.gui().clipboard().set(csv));
        }
    }
}
