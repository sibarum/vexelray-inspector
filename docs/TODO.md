# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] R1 ([design.md](design.md) §10), slices 2 to 5: the bench's checkpoints and the cursor (pause and rewind);
      every lane and the readouts; the run panel, the picker, launch and replay; then measure, and inspect W1 to W3
      in both modes. Slice 1 (attach, the passes lane live on the frame contract) is done.
- [ ] Zoom chords (Ctrl+= / Ctrl+- / Ctrl+0) went with the template's `Intent`; bring them back with the next view
      that takes keys.
- [ ] Replace the palette anchors in `Look.java` with the design's, measured in Oklab. See the note in that file
      about which authored colours the construction can reproduce and which have to be declared.

## Later

- [ ] Automation coverage: `--automation=0` (or `-Dautomation=0`) opens a driving socket on a free port and prints it, and the landmarks in `Landmarks.java` are
      already the names a script would use. One scripted run through the main path is worth more than several
      unit tests of the view.
