# Retrospective

What each milestone of **VexelRay Inspector** showed when it was read back: the shapes in
[framework-notes.md](framework-notes.md) since the last one, what would stop each, what went wrong in how the work
was done, and what to keep. The notes are written one at a time, as each workaround is written. This file is the pass
over them, and it is where the framework takes its proposals from.

**When.** At the end of every milestone, before the next one starts, and always before a rebuild or an archive. A
milestone with no new findings gets one line saying so. The framework's `docs/developers/retrospective.md` has the
reasons and the full method.

**Never delete a section.** Newest milestone first. A rebuild carries this file over.

## How to write one

    ## Milestone name · 2026-10-22
    
    The count: N findings (FN-a to FN-b), how many of them architectural.
    
    ### The shapes
    | | The promise | What happened | How it looked |
    
    ### What would stop each
    1. One structural change per shape, numbered so a plan can cite it.
    
    ### My own mistakes
    What was trusted, what it turned out to be, and the rule that covers it.
    
    ### What to keep exactly as it is
    What carried the milestone, and why.

## Milestones

## R4, the rows panel · 2026-11-02

The count: no new findings. The rows panel and stepping by rows needed nothing from the stack. FN-11 (a control
focused by a click keeps the focus, and may claim the Inspector's keys) came up again, on the rows table, and was met
the way the other controls meet it. Under `--logic=main` the Logic lane is still empty, as FN-16 said.

### The shapes

None new. FN-11 again: the table took the focus on a click. Whether a focused list takes Space and the arrows was not
tested; it was unfocused before it could.

### What would stop each

Nothing new to propose. FN-11's own proposal stands, with R4's addition: a control an application means for the
pointer alone says so once, rather than each application unfocusing every one it places.

### My own mistakes

- **I ran a shell `cat` with no input.** A stray `cat >> /tmp/x` before a heredoc waited on stdin, and the whole
  command (the tests' edits and the build) hung for five minutes before it went to the background. *Rule:* read a
  chained command back before running it, not only its heredocs.
- **I wrote a test's window by hand and got it wrong.** The tape's test expected the rows before a moment to fill the
  window, where the code (rightly) puts half after it; the code was right and the expectation was not. *Rule:* a
  test's expected list is derived from the rule the javadoc states, not from the picture in my head.
- **I nearly heard the table's selection for a click.** `Table.SELECTED` is published on every change, the view's own
  included, so following the cursor's row with it would have looped; `ListView.CLICKED` is the person's click alone.
  Found by reading the widget before writing the mailbox. *Rule:* before hearing a widget's message, read what
  publishes it.
- **I checked the focus only by accident.** A click left the table focused, ring and all, which I saw only because I
  read the whole tree for the status line. *Rule:* after a click on any new control, read the tree for `focused` and
  check Space still pauses.
- **I edited CRLF files with patterns written for LF.** Two docs took none of a substitution and the command said
  nothing. *Rule:* after a scripted edit, grep for what it was to write.

### What to keep exactly as it is

- **One order for rows, used everywhere.** `Tape.ORDER`, by time then sequence, is what the panel shows, what Up and
  Down step through, and what a row is placed by; a moment is a key in that order (`Tape.key`), so "the row at the
  cursor" and "the next row" need no second rule.
- **Fixed places, changed text.** The panel's eleven places are built once and their text changes in place, as the
  flows table's counts do, so stepping rebuilds nothing.
- **A row is not an event.** On a row the cursor is on no event and marks no path; Left and Right then step the
  focused lane from the row's moment. Nothing had to guess which event a row belongs to.
- **The rows show what the lanes cannot.** Around a tick on the logic lane the panel interleaves its rows with the
  main thread's, in the order they happened, which no lane draws.

## R3, a click's path · 2026-11-02

The count: 1 finding (FN-16), a framework gap, not fixed upstream yet. The milestone's own upstream changes were a
tick's row (atchung `2d244f7`) and a draw's row naming the causes it showed (vexelray-gui `c862fef`). Architectural:
a row that numbers something must say whose count the number is (FN-16, and FN-8 before it).

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| I | A number names one thing | `tick.ran #1` is written by every `Tick`, each counting its own: the logic's, the widgets', `Senders'`. Off `main` only the logic's coordinator runs one; on `main` under `--logic=main` two do (FN-16) | Under `--logic=main` the Logic lane empty and a path naming no tick, though both ticks wrote their numbers |

### What would stop each

1. **I:** a tick is made with a name (`Atchung.tick(name, arrival)`) and its row says it; the framework names its own.
   The same holds for anything else that numbers itself in a row: the number's owner is part of the row, by type,
   not by which thread wrote it.

### My own mistakes

- **I added a lane's rows upstream and forgot the Inspector asks for lanes by name.** `draw.ran` was in W2's
  recording, and no path showed a draw: `LinkRows.LANES` asks the link for seven lanes, and `draw` was not one. Only
  reading the witness's CSV beside the Inspector's path showed the rows were there and not arriving. *Rule:* a new
  row on a lane the Inspector has not read before is checked in the Inspector's own rows, not the run's recording.
- **I took the first draw for the one that showed the click.** A click's cause is the whole automation command, so
  the hover redraws on the pointer's way to the button name it too; the first was one of those. The fix was to say
  every draw, which also shows the hover was drawn. *Rule:* a cause covers everything its source did, not the one
  thing it was named for.
- **I wrote an assertion the wrong way round.** `assertFalse` on a pass that the test itself had put on the path; the
  message I wrote beside it said why it was on it. Caught by reading it back before the build. *Rule:* read an
  assertion's message as a sentence before running it.
- **I let the details panel grow.** A path of thirty hops pushed the graph and flows panels below the window, which
  only a screenshot showed; the rule that nothing grows to make room was this application's own. *Rule:* a panel
  whose text comes from the run gets a fixed height before it is filled.
- **I chose the path's colour by its name.** `ACTION` read as the accent dimmed, beside lanes and arrows already in
  the accent. *Rule:* a highlight is chosen against the colours already on screen, in a screenshot.

### What to keep exactly as it is

- **The hops are read once, and every panel is told from them.** The Path's words and `Traced` come from the same
  `Model.hopsOf`, so the details, the lanes, the graph and the flows cannot disagree about a path.
- **A row of its own for each thing that happens on another clock.** The tick and the draw each say their number
  after the zone that timed them; nothing else was stamped, and the Inspector places the rest by thread and time, as
  design.md section 6 said it would.
- **Regenerate the witness out of someone else's way.** When another session had cleaned the framework's acceptance
  output, `-Dacceptance.root` from a clean worktree of its HEAD rebuilt W2 under this repository's `target/`, and
  nothing of theirs was touched.
- **Watching an Inspector with an Inspector.** When no witness was to hand, one Inspector's Space, watched by
  another, was a whole input → tick → draw path through the logic lane.

## R2, the declared graph · 2026-11-02

The count: 2 findings (FN-14, FN-15), both framework gaps, neither fixed upstream yet. The milestone's own upstream
change was the bus graph, version 2 ([bus-graph.md](bus-graph.md)): vexelray-framework's processor writes it and
vexelray-gui's `BusGraph` reads only it. Architectural: who a message came from and where it went are facts the rows
must state, not the Inspector infer (both findings).

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| G | Every write says who wrote it | A component's `send` row names it; a write from outside a tick (a source, a job, the timer, a widget) is a publish row naming only its thread (FN-14) | `Rows` "LinkRows 0, RecordingRows 0, outside a tick 3454" until the thread names were read as part names |
| H | Every delivery is a row | A tick's delivery to a mailbox or inbox is; what the framework hands a job, a source or a destination is not (FN-15) | `FindRuns` "ListRuns 0", a count nobody measured, until it said no number |

### What would stop each

1. **G:** a publish row names its sender when the sender is known, as `send` does: `Sender.outside` and the framework's
   source, job and timer senders are each built for one part.
2. **H:** the framework writes a `receive` row when it hands a job its request, a source its start or stop, and a
   destination its open or write, naming the part as bus graph 2 does; those hops then join a cause's path too.

### My own mistakes

- **I edited files with shell substitutions that silently matched nothing.** In vexelray-framework and vexelray-gui,
  sed and perl patterns over files with Windows line endings left them unchanged and said nothing; twice I moved on
  before a grep caught it. *Rule:* in the stack's repos, edit with the editor, or grep for the result straight after.
- **I took `keyed` to mean "declared a key".** atchung's `Topic.keyed()` is true of every state topic, so the first
  graph called an unkeyed state topic keyed. The javadoc said so in one line. *Rule:* read the accessor's javadoc
  before naming a field after it.
- **I showed a count that nothing counted.** Deliveries to a job or a source were shown as 0 because no row was found,
  which reads as "nothing arrived". R1's rule again, at the other end: a number shown is a number measured.
- **I broke my own fix in the next slice.** Folding the job lane's numbered threads into one sender (`vexel-job`) left
  the matcher looking for `vexel-job-`, so `Runs` went to the framework; only the next screenshot showed it. *Rule:*
  a change to how a key is made is tested where the key is read, in the same commit.
- **I installed a jar built from someone else's uncommitted work.** `mvn install` of vexelray-gui-automation took the
  working tree, which held another session's drag-and-drop changes, into `.m2`. Harmless this time; the commit itself
  was staged hunk by hunk. *Rule:* before installing a module of a shared checkout, look at `git status` and say what
  else goes with it.

### What to keep exactly as it is

- **The rows already held the counts.** Written and delivered came from the `send`, publish and `receive` rows FN-10
  put there, so the atchung counter design.md 7½ planned was not needed. Read what a run records before asking the
  stack for more.
- **What a run says once is a row on the tape.** The graph arrives as one row, beside the sources and the version
  warning: it rewinds with the cursor, a recording simply has none, and the bench's state did not change shape.
- **Facts read where they live.** A topic's kind is read off its own constant when the wiring loads, so a library's
  topics say theirs; nothing in the processor guesses it.
- **A table whose cells change in place.** The flows table's rows are the topics, built once a run; the counts that
  change every batch are written into the cells already built.

## R1, first light · 2026-11-02

The count: 13 findings (FN-1 to FN-13), 12 of them framework gaps, 6 fixed upstream during the milestone (FN-1, FN-2,
FN-7, FN-9, FN-10 in part, FN-12). Architectural: the clocks across a process boundary (FN-1, FN-2) and what a run
records and says it records (FN-8, FN-9, FN-10, FN-12). The rest are seams in a widget, a verb or the command line.

### The shapes

| | The promise | What happened | How it looked |
| --- | --- | --- | --- |
| A | Rows from another process can be set against this one's present | The present was krono's logical time and a row's time counted from a zero the link never said (FN-1, FN-2) | Lanes that drifted from the present; fixed with `Frame.monoNanos` and `StreamOpened.originNanos` |
| B | A run records what the Inspector needs, and an empty lane means a quiet one | `-Dprobe` never reached the app, automation recorded four lanes, causes were dropped, receivers were not written, and a main-thread tick looks like the widgets' (FN-8, FN-9, FN-10, FN-12) | Empty lanes that looked like idle ones, for days, the author's runs included |
| C | A part that follows the present is an ordinary part to the tools | `settle` never succeeds while live (FN-3); no verb closes an app (FN-4); the told half cannot read the present (FN-5) | Every check script pauses first; closing a run needs pixels or the OS |
| D | A picture is a node like any other | A slid picture is clipped to its own box (FN-6); hover over a mark was not heard (FN-7, fixed) | Marks blinking in at the edge after a glide |
| E | A control stays out of the way of the app's own keys | A focused or disabled button keeps claiming Space (FN-11) | Space stopped pausing after a click; the Inspector's controls are never focusable |
| F | The command line can carry another command line | No option takes the rest of the line (FN-13) | `--launch="<command>"`, one quoted value |

### What would stop each

1. **A:** every time that crosses a process carries its clock's name, and a stream says its origin in its first
   message, by type, not by convention. (Done for the link; the rule belongs in the link's and the probe's docs.)
2. **B:** a run says, in its handshake, what it records, at what level, in which mode (`--logic=main`), and each lane
   it does not; and every row that could be told apart only by context (a tick's owner) names itself. Silence must be
   impossible to mistake for quiet: the Inspector dims what is not recorded, and the stack should make "not recorded"
   a fact on the wire, not an inference.
3. **C:** `ottermate` treats a part due every frame as continuous motion and says so; a `close` verb; a public
   `Gui.monoNanos()` or the taking moment handed to a `@Subscribe`.
4. **D:** a picture drawn unclipped inside a clipping parent, culled by the parent's clip.
5. **E:** a disabled control hands its focus on and claims no keys; an app can mark keys its own (Space, End) so no
   control claims them.
6. **F:** `@Setting(rest = true)`: one key whose value is the rest of the line, parsed before unknown options are refused.

### My own mistakes

- **I trusted `contains` on an immutable list with a null.** `List.copyOf(...).contains(null)` throws; the run panel
  with nothing attached took the view down in the first live run. The unit tests never told the view a panel with no
  run. *Rule:* a view test tells the view the empty case of every new message before the full one.
- **I nearly took ottermate's tree kill for my own stop.** The launched child ended when the watcher did, which looked
  like `Child` ending its process tree; `ottermate --launch` kills the whole tree itself. Only closing the window
  through the OS showed the stop working. *Rule:* a claim that something ends on shutdown is checked with an orderly
  shutdown, not a kill.
- **I read an empty readout as a quiet run.** The readouts are over the second before the cursor, and the paused lane
  shows five seconds either side of it, so a burst eight seconds back is in neither. Twice I nearly concluded the
  witness did nothing. *Rule:* zoom out to the moment before reading what a burst did.
- **I reported more than I measured.** A first TODO entry said the inbox was filling at 40,000 rows a second and that
  the watcher's draws fell; neither was what was measured (the newest row was 6 s old; the watched Inspector's draws
  fell). *Rule:* a finding says the number read and where it was read, and nothing inferred beside it.
- **Small ones:** a record accessor clashed with a helper of the same name (`chosen()`), a hand-computed epoch in a
  test was wrong, and an option I invented to tag a process (`--insp-tag`) would have been refused as unknown. Each
  cost a build.
- **Carried from earlier slices** (design.md section 10 records them): the first event schema put the rows' facts into
  sentences instead of fields, rejected by the author as matching no filter; and every "traced" run before 2026-10-31
  was checked without noticing its Logic and GPU lanes were empty (FN-9).

### What to keep exactly as it is

- **The bench as one pure fold over a tape.** Rewind holds by construction, and it paid off twice this milestone:
  a recording replays through the same `Rows` and is paused and rewound with no code of its own, and the measurement
  drives the real bench through a real tick.
- **Sources for what keeps coming.** The link, a recording and a launched child are three small classes with no
  threads of their own, stopped by the framework at shutdown; a child process dying with the Inspector came for free.
- **One slice at a time, each seen with `ottermate` before it is called done.** Every slice found something its tests
  did not: the null in the run panel, the injected click missing from the Input lane, the bench falling behind at
  40,000 rows a second.
- **Saying what is missing instead of drawing around it.** A dimmed lane, "no logic tick off the main thread:
  --logic=main, or idle", a banner for another link version: each turned a silent gap into a finding.
