# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead.

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next

- [ ] The Jobs lane shows a job's run, from its request reaching it to its reply sent, not from the request sent to
      the reply delivered, which design.md section 3 asks for: the send and the tick that took the reply are on other
      lanes, and the path already links them, so mark the span on the lane or leave it to the path.
- [ ] The jobs line's rest is by rule, as drawing's: the job lane's threads wait in the executor with no row. A
      `Crew` or executor row would make it measured, and the lane's threads busy at once could be counted.
- [ ] A source's start and stop and a destination's open and write still write no row (FN-15's rest), so their
      ends in the flows table stay uncounted.
- [ ] A declared lane's (`Crew`) own rows: its work shows inside the tick, as W2's worker's does, but its thread's
      rest and wakes are not read.
- [ ] Run again shows what the replay sent where the run's send differs, not what the run sent: the framework keeps
      only a fingerprint of each recorded send (its TODO, *Found by building replay*).
- [ ] Recording doubles the watched Inspector's tick (p50 0.4 to about 1 ms, p95 1.3 to 2 to 5 ms) because each
      send is fingerprinted as it is sent; a launched run is always recorded, so a heavy one pays it. Upstream first;
      until then a launch could ask for it only when Run again is wanted.
- [ ] Run again on a recording (`--replay=<csv>`) says it cannot: a probe recording holds rows, not the messages the
      logic took in.
- [ ] The threads panel is numbers only: a bar per clock, split into its four states against the turn's width, with
      the wakes as ticks on it.
- [ ] Following, the ledger reads the newest rows again on every batch (p95 about 0.5 ms, 200 bytes a row more than
      R4); the bench's state could keep the newest whole turns and move them on as rows arrive.
- [ ] A turn longer than a second (an unfocused window's park, an idle logic lane) is not found (`ClockLedger.SPAN`).
- [ ] Drawing's turn is not judged against the ceiling's slot: no row says the ceiling's rate.
- [ ] The GPU lane does not read `wait image` (`Model.WAITS`), which Vulkan's windowed presenter waits in; the
      ledger does.
- [ ] A long note is clipped in the threads panel, as a long detail is in the rows panel.

- [ ] The rows panel shows eleven rows whatever its height (`Bench.AROUND`); it could show as many as fit, the view
      telling the bench how many.
- [ ] The rows panel has no filter: by lane, thread, kind or cause, so a busy main thread's rows can hide a tick's.
- [ ] A row that arrives more than a second after it was stamped is not found by the rows panel or Up and Down
      (`Tape.around`, `Tape.next` read the seconds either side only).
- [ ] On a row the cursor marks no path; a row with a cause could mark that cause's, as an event does.
- [ ] A row's detail is clipped in the panel; the details' Row line has it whole, but a bus graph row is pages long.

- [ ] A state topic's last value (design.md section 4): rows carry no payload, so the flows panel shows counts only. It
      needs the publish row to carry the value, or a summary of it, upstream.
- [ ] The graph panel's labels overlap where several lit arrows meet one part (W2's `Shown`), and nothing on it can be
      clicked: a part or an arrow chosen should filter the flows table to its topics.
- [ ] "The framework" is one box: a widget's, the window's, input's and automation's messages cannot be told apart, as
      the bus graph names none of them. Name them as parts upstream, or tell them apart by topic package here.
- [ ] A recording has no graph: the probe could write the bus graph as a row when it starts, so a replay is drawn
      as its run was.
- [ ] The flows table does not sort: its items are the topics' names, so a sort by written or rate needs the counts
      beside them in the item, and a comparator for each column.
- [ ] FN-14 and FN-15 upstream: a publish row naming its sender, and a receive row for what the framework hands a
      job, a source or a destination. Then drop `Model.writesOn` and `End.UNCOUNTED`.
- [ ] The link's slowest batch grew over a 90 s watch at 31,000 to 48,000 rows a second, from 11 ms to 101 ms behind
      its newest row (p50 0.7 ms and p95 under 7 ms throughout): find whose pause it is, the watcher's or the target's.
- [ ] A minute of tape at 19,000 rows a second holds about 109 MB, so the 5 min reach design.md section 8 offered
      would not fit: rows kept as columns, or a coarser tape past the last minute, before the reach is made choosable.
- [ ] The Drawing lane is every window's draws together: a run with several windows (W1's notes and palette) cannot
      say which drew. A band per window, or the window as a field of the draw, once the rows say it.
- [ ] The Messages lane's moments are drawn by how many share a pixel now, but its event highlight is still lost in a
      busy band, and the filter should be able to hide the busiest types. Not yet seen against a target whose probe
      records the bus: the traced Inspector's Messages lane was empty.
- [ ] Hover on an event, a colour change only: hear `NodeEvent.POINTER` on the lanes (FN-7, now upstream) and turn
      `localX`/`localY` into an event through the view's `Strip`.
- [ ] Design 9.8: Sequence (a sequence per lane, each landing on the cursor on its own), and the filter, which
      matches `Mark.fields` by name and value (`Model.FIELDS`), the same on every lane.
- [ ] The path is a line of text a filter can match, marked in every panel since R3, but its hops are not chosen one
      at a time: clicking a hop should go to its event, which `Hop.row` already holds.
- [ ] A click's cause is the whole automation command, so its path begins with every `pointer.move` of the travel to
      the button (W2: twenty-odd hops before the press). Fold a run of moves into one hop that counts them, as the
      GUI's changes are.
- [ ] The details panel is a fixed 16 rem that scrolls (R3), so a long path keeps the graph and flows in view; the
      split between it and the panels under it could be the person's to drag.
- [ ] A delivery names its receiver and not its sender, so where two parts send a topic to one, the graph marks both
      arrows on a path. Upstream FN-14 (a publish naming its sender) would tell them apart.
- [ ] A recording's draws and ticks: the CSV has no `frame` column, so `draw.ran` cannot be put on its draw by pass
      and a replay's draws stay unnumbered; read it by thread and order instead, as a tick's is.
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
