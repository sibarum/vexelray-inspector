# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] R1 ([design.md](design.md) §10), slices 4 and 5: the run panel, the picker, launch and replay; then measure (the
      tape's `RETAIN` included, and the fold, which copies every lane kept on each batch), and inspect W1 to W3 in
      both modes. Slices 1 to 3 (attach and the live lane; checkpoints, pause and stepping; every lane and the
      readouts) are done.
- [ ] The Messages lane is a solid band at ten seconds across: a message is a moment, the band's whole height, and a
      run publishes one every few milliseconds. Draw a moment by how many share its pixel, or let the filter hide the
      busiest types. Its event highlight is lost in it too.
- [ ] Hover on an event, a colour change only: hear `NodeEvent.POINTER` on the lanes (FN-7, now upstream) and turn
      `localX`/`localY` into an event through the view's `Strip`.
- [ ] Design 9.8: Sequence (a sequence per lane, each landing on the cursor on its own), and the filter.
- [ ] Injected input on the Input lane is held by `BenchTest` only: drive a traced target with keys while another
      Inspector watches, and look.
- [ ] Zoomed in, a long glide (Space after stepping far back, or a Shift step at a wide zoom) can run past the strip's
      seven lanes each side, leaving the lane briefly empty mid-glide. Size the strip by the zoom, or rebuild the
      picture at the glide's midpoint.
- [ ] Dragging the cursor on the lanes: hear `NodeEvent.DRAG` and send `Seek` (already the absolute cursor, a state
      topic) for each position; the view turns a pixel into a moment as a click does.
- [ ] A zoom while stopped pivots on the right edge and then glides the cursor to the middle if it would leave the
      window. Zooming about the cursor itself would keep it still; it needs the edge's motion tied to the zoom's.
- [ ] A catch-up from far back overflows the window's vertex buffer: following again after 75 s paused, the strip is
      built over the whole minute it glides across, the log says `window needs 200244 vertices and the buffer holds
      194180; drawing what fits`, and for those frames the title bar, details and status are not drawn. Draw at most
      what a pixel can show (the Messages lane's density entry above would do it), or build only the glide's ends.
- [ ] A catch-up from far back (Space after stepping a minute back) is a long, fast glide: `Glide.WINDOW_MAX_NANOS`
      caps it at half a second. Watch it, and lengthen or slow it if it reads as a blur.
- [ ] Zoom chords (Ctrl+= / Ctrl+- / Ctrl+0) went with the template's `Intent`; bring them back with the next view
      that takes keys.
- [ ] The follow button is enabled whenever the timeline is not live, which includes following a run that went away,
      where it does nothing. `Timeline` could say whether the cursor is stopped rather than leave the view to infer it.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that file
      about which authored colours the construction can reproduce and which have to be declared.

## Later

- [ ] Automation coverage: `--automation=0` (or `-Dautomation=0`) opens a driving socket on a free port and prints it, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
