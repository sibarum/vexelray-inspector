# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] R1 ([design.md](design.md) §10), slices 3 to 5: every lane and the readouts; the run panel, the picker, launch
      and replay; then measure (the tape's `RETAIN` included), and inspect W1 to W3 in both modes. Slices 1 and 2
      (attach and the live passes lane; checkpoints, pause and stepping) are done.
- [ ] Hover on a pass, a colour change only: needs the pointer's position over the lane (FN-7), an upstream addition.
- [ ] Design 9.8, with slice 3's lanes: Sequence (a sequence per lane, each landing on the cursor on its own), and the
      filter.
- [ ] Zoomed in, a long glide (Space after stepping far back, or a Shift step at a wide zoom) can run past the strip's
      seven lanes each side, leaving the lane briefly empty mid-glide. Size the strip by the zoom, or rebuild the
      picture at the glide's midpoint.
- [ ] Dragging the cursor on the lane: design §8's absolute cursor on a state topic, newest position wins. The view
      turns a pixel into a moment from its `Strip`.
- [ ] A catch-up from far back (Space after stepping a minute back) is a long, fast glide: `Glide.WINDOW_MAX_NANOS`
      caps it at half a second. Watch it, and lengthen or slow it if it reads as a blur.
- [ ] Zoom chords (Ctrl+= / Ctrl+- / Ctrl+0) went with the template's `Intent`; bring them back with the next view
      that takes keys.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that file
      about which authored colours the construction can reproduce and which have to be declared.

## Later

- [ ] Automation coverage: `--automation=0` (or `-Dautomation=0`) opens a driving socket on a free port and prints it, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
