package dev.sibarum.inspector;

import dev.sibarum.inspector.Keys.PauseKey;
import dev.sibarum.inspector.core.Messages.Paused;
import dev.vexelray.gui.widget.Button;
import sibarum.atchung.Sender;
import dev.sibarum.inspector.core.Messages.Flow;
import dev.sibarum.inspector.core.Messages.Flows;
import dev.sibarum.inspector.core.Messages.Traced;
import dev.vexelray.framework.api.Subscribe;
import dev.vexelray.framework.api.View;
import dev.vexelray.gui.widget.ListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The topics window's view: every topic, from the same {@link Flows} as the Topics card, and the path's topics marked
 * from the same {@link Traced}, so the two never disagree. Copy on a row's menu copies this window's chosen rows.
 * Decides nothing.
 */
@View(window = TopicsWindow.KEY)
final class TopicsView {

    private final TopicsWindow window;
    private final Sender<PauseKey> pauseKey;

    TopicsView(TopicsWindow window, Sender<PauseKey> pauseKey) {
        this.window = window;
        this.pauseKey = pauseKey;
    }

    @Subscribe
    public void flows(Flows f) {
        List<String> topics = new ArrayList<>(f.flows().size());
        Map<String, List<String>> cells = new HashMap<>();
        for (Flow flow : f.flows()) {
            topics.add(flow.topic());
            cells.put(flow.topic(), dev.sibarum.inspector.View.flowCells(flow));
        }
        window.why(dev.sibarum.inspector.View.flowsNote(f));
        window.topics().set(topics, cells);
    }

    @Subscribe
    public void traced(Traced t) {
        window.topics().crossed(dev.sibarum.inspector.View.crossedTopics(t));
    }

    /** Copy chosen from a row's menu in this window: its chosen rows onto the clipboard. */
    @Subscribe
    public void chosen(ListView.Chosen c) {
        if (c.action().equals(Landmarks.COPY) && c.at().equals(window.topics().address())) {
            window.topics().copied().ifPresent(csv -> window.gui().clipboard().set(csv));
        }
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
