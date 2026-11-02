# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] R1 ([design.md](design.md) §10): measure (the
      tape's `RETAIN` included, and the fold, which copies every lane kept on each batch), and inspect W1 to W3 in
      both modes. Slices 1 to 5 (attach and the live lane; checkpoints, pause and stepping; every lane and the
      readouts; the run panel and the picker; launch and replay) are done.
- [ ] The Messages lane's moments are drawn by how many share a pixel now, but its event highlight is still lost in a
      busy band, and the filter should be able to hide the busiest types. Not yet seen against a target whose probe
      records the bus: the traced Inspector's Messages lane was empty.
- [ ] Hover on an event, a colour change only: hear `NodeEvent.POINTER` on the lanes (FN-7, now upstream) and turn
      `localX`/`localY` into an event through the view's `Strip`.
- [ ] Design 9.8: Sequence (a sequence per lane, each landing on the cursor on its own), and the filter, which
      matches `Mark.fields` by name and value (`Model.FIELDS`), the same on every lane.
- [ ] The path is a line of text a filter can match; a hop at a time (click one to go to its event) waits for the
      graph panel (R2).
- [ ] Injected input on the Input lane is held by `BenchTest` only: drive a traced target with keys while another
      Inspector watches, and look.
- [ ] Dragging the cursor on the lanes: hear `NodeEvent.DRAG` and send `Seek` (already the absolute cursor, a state
      topic) for each position; the view turns a pixel into a moment as a click does.
- [ ] A zoom while stopped pivots on the right edge and then glides the cursor to the middle if it would leave the
      window. Zooming about the cursor itself would keep it still; it needs the edge's motion tied to the zoom's.
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
