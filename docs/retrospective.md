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

*(none yet)*
## Milestones

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
