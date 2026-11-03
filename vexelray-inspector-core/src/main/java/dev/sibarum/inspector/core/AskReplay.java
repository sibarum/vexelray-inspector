package dev.sibarum.inspector.core;

import dev.sibarum.inspector.core.Messages.Replayed;
import dev.vexelray.framework.api.Job;
import dev.vexelray.gui.inspector.link.LinkClient;
import dev.vexelray.gui.inspector.link.QueryReply;

/**
 * Ask a run to {@code replay} (vexelray-framework's {@code Shell.replay}): a connection of its own to the run's link,
 * one query, closed. A job, since the run answers only when it has run its logic again, which takes as long as that
 * logic took, or less. What goes wrong is the answer's text, never a throw.
 */
@Job
public final class AskReplay {

    public Replayed run(Messages.AskReplay request) {
        try (LinkClient link = LinkClient.connect(request.stream())) {
            QueryReply reply = link.query("replay");
            return new Replayed(request.run(), reply.text());
        } catch (RuntimeException e) {
            return new Replayed(request.run(), "err replay: its link could not be asked: " + e.getMessage());
        }
    }
}
