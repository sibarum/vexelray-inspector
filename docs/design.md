# The Inspector on 0.2

What the Inspector is when it is rebuilt for VexelRay 0.2, what it draws, how it is put together on the message
model, what it needs from the stack, and the order to build it in. **Proposed 2026-10-29, nothing built.** It is
phase 7½ of `vexelray-framework/ROADMAP.md`: the first application written for 0.2 from nothing, and the test of
whether 0.2 is easy to use correctly. Section 9 lists the decisions that are the author's to make before code.

The 0.1.3 Inspector (`Documents/GitHub/vexelray-inspector`, on `develop`) is where the ideas come from, not the code.
Its `docs/roadmap.md`, `component-graph.md`, `threads.md` and `framework-notes.md` are cited here as *0.1*.

## 1. What stays the same

The ask has not changed: DevTools for VexelRay applications, drawn as clockwork, where every working part of a
running application is a mechanism on screen and every mechanism is a real subsystem. These carry over as they were:

- **Agent first.** Every fact the Inspector draws can be asked through a port verb with a fixed reply shape. If an
  agent cannot ask it, the Inspector does not draw it. The Inspector is the GUI and `ottermate` is its CLI.
- **The port is the automation socket's flight recorder** (`vexelray-gui/docs/plans/flight-recorder.md`): a ring of
  probe rows in the target, query verbs over it, many observers and one hand, and a typed live stream over the
  elektro-Q link (`vexelray-gui-inspector-link`). Recordings stay probe CSV.
- **Never block the target, and say what was lost.** A gap is not made up: rows the stream lost are counted, nothing
  is measured across them, and the first frame after says so.
- **The show clock.** Everything is shown a little late, so a burst can be stretched to be followed and caught up
  after, never more than 400 ms behind. Pause holds show time for the whole window while the target runs on.
- **Attach, launch, replay.** The picker over the discovery folder, `--attach=<app|pid:n>`, `--launch <command>`,
  `--replay=<csv>`; *Restart in debug mode* from any application's title bar.
- **The tape, history by build, the command menu.** As 0.1 designed them.

## 2. What changes, and why it cannot be ported

0.1's Inspector draws the 0.1 loop: one main thread that took input, ran the application's handlers, laid out, drew
and presented, then parked. 0.2 took that loop apart, and each fork removes something the old Inspector stands on.

| 0.1 assumed | 0.2 has | Fork |
| --- | --- | --- |
| One heartbeat: the balance wheel swings once per `frame.present` | Three clocks: input is taken when it arrives, logic runs in ticks, drawing is capped at the display rate. A pass of the main loop is not a frame | 7 |
| One correlation key: the frame number every row is stamped with | A click's answer crosses the logic lane before any draw, so the frame it was drawn in says nothing of the tick that decided it | 3, 7 |
| Frame stages INPUT, CLOCK, APP, SETTLE inside the swing | No application code on the main thread. APP is a tick on the logic lane | 4 |
| A draw happens when the loop decides to draw | A draw can be **held for the ceiling** (the 60 Hz slot is not due) or **held for the GPU** (the presenter is not ready), and stays owed while the thread goes back to input | 8 |
| Every message is delivered, so a flow's count is its deliveries | A state topic delivers only the last write in a tick. Writes and deliveries differ, and the difference is the coalescing | 1 |
| Senders are guessed from the thread and the topic, receivers from mailbox names or a stand-in table (0.1 FN-6 to FN-8) | Every send is a constructor parameter the processor sees, so the graph is known at compile time | 5 |
| Handlers are called, not messaged (0.1 FN-11); handler and offload pools say nothing (0.1 FN-15) | No handlers and no pools: components, views, jobs, ledgers | 4, 5 |
| A turn of the main loop is the unit of thread time (0.1 `threads.md`) | Each clock has its own unit: a pass, a tick, a draw | 7 |
| Replay plays a recording back | A run replays: the same input messages produce the same widget writes, in either mode | §5 item 5 |

And the 0.1 Inspector breaks 0.2's own rules: its show runs on the timeline inside the frame, `Show` is driven by
`KronoGui.ramp`, which no application can reach now, and it starts three platform threads of its own (0.1 FN-1).

## 3. What it draws: the movement

The movement keeps the clockwork, and gains a train per clock. Each part is still one subsystem.

| Part | Subsystem | What you see |
| --- | --- | --- |
| **The input pawl and ratchet** | tactroller to the input dispatcher, on the main thread | A tooth per input event taken, the moment it is taken. Injected input (automation) on a second pawl. Never waits for the other trains |
| **The main barrel** | the main thread's pass | Turns once per pass of the loop, whatever the pass did. Rests while the thread waits for events, with what woke it |
| **The logic escapement** | a tick of the logic lane | Swings once per tick. The rounds the tick ran to quiescence are the teeth the escape wheel steps; what it delivered is the beat's weight. A tick over its deadline jams the pallet (the deadline is still the 8 ms placeholder, ROADMAP phase 4) |
| **Declared lanes** | the lanes of the same tick (`Crew`) | Wheels geared to the escapement, turning only within its beat |
| **The balance wheel** | drawing | One swing per draw. **Held for the ceiling**: the wheel stands locked on the pallet until the slot. **Held for the GPU**: it stands waiting on the magazine |
| **The magazine** | the presenter (DXGI: latency waitable, shared fence, back buffer) | The chamber the GPU is still in, and the ready thread's wake when it comes free |
| **The job lane** | `@Job`s | A tray of work in flight: a request in, a reply out, and how long it took |
| **The tachometer and governor** | the ceiling and the show clock | Draws a second against the ceiling and the display; how dilated and how late show time is |

What 0.2 makes visible that 0.1 could not:

- **A click's thread.** Select an input event and the movement lights its path: the tooth the pawl took, the tick
  that heard it, the widget writes it caused, and the draw that showed them, with the time between each. This is
  fork 7's whole promise, and the one number every 0.2 measurement so far has been about (a click's result through
  the logic: 0.8 ms on the lane at 60 Hz, ROADMAP fork 8). Needs a causal key on rows (section 6).
- **A wasted pass and a held draw are different things.** A pass that took a pointer move and drew nothing is fine;
  a draw held for the GPU is the GPU being slow; a draw held for the ceiling is pacing doing its job. 0.1's single
  wheel could only show "a frame" or "no frame".
- **Coalescing.** Section 4.

## 4. What it draws: the graph

0.1's component graph inferred its arrows from traffic, because nothing declared them; its earlier bus circuit drew
only declared wiring and showed nothing, because no application declared any. In 0.2 every application declares
everything, so the graph is **drawn from the declaration and lit by the traffic**:

- **The parts** are what the processor knows: each component and view, each job, each ledger and its steps, each
  timer, and the framework's own parts (input, automation, the shell's window and dialogs). Host OS and Graphics
  stay as parts, dashed until their events are messages (0.1 FN-9, FN-10).
- **An arrow exists because it is declared**, before any message crosses it, dim until one does. An arrow that never
  lights in a run is worth knowing too.
- **A state topic is drawn as a register, an edge topic as a belt.** A belt carries every message; a register shows
  its last value and two counts: written and delivered. A view that writes a state topic 200 times in a tick and is
  heard once shows it. The flow table gets the same two columns.
- **Senders and receivers are exact.** No thread table and no stand-in: the payload's type names the topic, and the
  declaration names both ends.

The bus graph the 0.1 Inspector reads (`BUS_GRAPH`) still speaks 0.1: every mailbox says `overflow=FAIL`, state
topics included, and the logic lane is `lane=<default>` (ROADMAP phase 7's finding, left alone because the old
Inspector parses it). Worse, it lists no receiver that hears through an `Inbox`, which on 0.2 is most of them
(section 7). The new Inspector does not read version 1, so the format is free to change: a version 2 that says each
topic's kind, each part's kind, every receiver, a ledger's sends, and the lanes' real names (section 7½).

## 5. What it draws: threads and ticks

0.1's thread ledger (`threads.md`, proposed, not built) cut every thread's time into turns of the main loop and
judged it against one frame deadline. In 0.2 that is the wrong unit: the logic lane has nothing to do with a turn.
The ledger keeps its four states (working, waiting, resting, unaccounted), its wakes and wake latency, and is cut per
clock instead:

- the **main thread** per pass, with its waits on input and messages;
- the **logic lane** and declared lanes per tick, against the tick's deadline;
- **drawing** per draw, against the ceiling's slot, with recording (fork 8's last thing on the thread: "measure it
  before any render-thread talk") as its own span, so that measurement is a reading here;
- the **job lane** per job.

## 6. Correlation: what replaces the frame number

0.1 tied everything to one key: the frame each row happened in. 0.2 needs two things instead:

1. **A number per clock.** Rows carry the pass, the tick and the draw they belong to, so the tape can rule lines for
   any of the three, and the ledger can be cut by any of them.
2. **Cause.** To follow one input through a tick to a draw, a row has to say what caused it. atchung already stamps a
   cause when it stages a delivery (`TickReg.java:64`, `sibarum.probe.Cause`). Today the recorder drops it, and a
   draw runs under its own pass's cause, so neither half of the path reaches the Inspector (section 7).

## 7. What 0.2 records today

Surveyed 2026-10-29 on the `v0.2` clones (atchung, vexelray-gui, vexelray-framework), with GPU, time and animation
rows read from vexelray, kronometer and tactroller on `develop`. Paths are from `Documents/Spike`.

**Rows exist only when the probe traces.** `count` and `mark` write a row only with tracing on (`-Dprobe.format=csv`
or `-Dprobe.trace`), and a zone otherwise only when it is slow (atchung `Probe.java:143, 203, 226, 375`). A live
sink without tracing gets slow rows and nothing else. 0.1 FN-4 is the same fact from the other side, and still holds.

**What the movement can be built from today:**

| Clock | Rows | Missing |
| --- | --- | --- |
| Main thread | `frame` zone per pass, `frame.present` / `frame.update` with `#<pass>`, `loop.park`, `wait for events` (idle), `wake*` (gui `GuiApp.java:1873-1970`, `Gui.java:1584-2221`) | nothing for the three-clock view; `Lane.FRAME`'s javadoc still says "one span per frame" |
| Input | tactroller's `publish`, `snapshot`, `edges`; automation's `type`, `key`, ...; `state.<hover>` (`Lane.INPUT`) | — |
| Logic | `tick` zone on the coordinator's thread (`vexel-logic`, or main under `--logic=main`), counts `tick rounds` and `tick delivered`, mark `tick failure` (atchung `Tick.java:207, 239-240`, `Inbox.java:312`) | **no tick number**: `Tick` keeps `lastRounds` and `carried`, no counter. A tick is stamped with whatever pass the GUI loop was in. Nothing marks the deadline |
| Drawing | counts `draw skipped`, `draw held for the ceiling`, `draw held for the GPU` (gui `GuiWindow.java:350-361`); `emit canvas`, `vertices`, `runs` on `Lane.DRAW` | **no draw number**: `frame.present #N` carries the pass. The held counts are rows only, not in `LoopCounters` or `Snap` |
| GPU | `present frame`, `wait fence`, `queue submit`, `queue present`, `acquire image` (vexelray `WindowedPresenter`) | DXGI's ready thread writes nothing |
| Jobs, timers, ledgers | a mark per chain step (`Chain.java:111, 120`) | the framework's `Timer`, `Jobs`, `Logic`, `Crew` and views write **no rows at all**; a timer's publishes show as `uncaused vexel-timer` |

**Cause is recorded and then dropped.** A cause is a `long` (process, kind, source, sequence) in a per-thread slot,
staged with each delivery (`TickReg.java:64`, `Inbox.java:212-215, 305`) and re-entered around it, so a widget write
made while hearing an input carries the input's cause. The CSV has a `cause` column. But the recorder implements
only `LiveSink`'s seven-argument `row`, not the eight-argument one that carries the cause (gui
`Recorder.java:173`), and neither `RecordedRow` nor the link's `Row` has a field for it. atchung's
`docs/correlation.md` says the recorder "needs to hold only the ID", which the code does not do. And a draw runs under
its pass's `gui.frame` cause, so even with causes kept, input → write is linked and write → draw is only by time.

**The bus graph** is written by the processor (`Channels.text`, `Channels.java:409-468`), embedded as `BUS_GRAPH`,
served by the `bus graph` verb. Version 1: `component <class> lane=...`, `publisher <class> lane=-`, `mailbox
<Comp#method> topic= type= overflow=FAIL capacity=<n>`, `publishes <class> topic=`. `overflow=FAIL` is written for
every mailbox; the capacity is the `@Subscribe`'s. It has no topic kind (the processor knows it, `Topics.java:31`),
no key filters, no mailbox line for a component that hears through an `Inbox` (only `@Subscribe` methods are read,
`Channels.java:105-119`), only the reply side of a job, no timers, and views only as `lane=<gui>`. On the logic-lane
model most of an application's receivers are `Inbox`es, so **version 1 cannot draw a 0.2 application's graph**.

**Coalescing is not counted.** A tick's state fold unlinks and relinks the entry (`Inbox.java:190-198`), as does a
pumped fold (`PumpedReg.java:268-271`); nothing counts what was superseded.

**The link and the verbs.** `RowBatch(lostFrom, lostTo, rows)` of `Row(seq, tMonoNs, tWallNs, frame, thread, lane,
kind, detail)`, `Snap(frame, drawn, presented, wakes, wakesCoalesced, focus, parks, ringHeld, ringLost)`, `Query` and
its reply, `StreamOpen`/`StreamOpened`/`StreamClose`. Built verbs: `recorder`, `rows`, `gaps`, `frames`, `why`,
`stream`, `bus graph`, `tree`, `find`, `log`, `mark`, `hello`. Stubbed (`err not available yet`): `bus`, `kron`,
`gpu`, `ledger`, `threads`, `heap`, `meter`, `diagnose`. **`frames` and `why` are 0.1's**: they count only the loop
thread's rows, so a tick on the logic lane is invisible to them, and they read `draw skipped` but neither held count.

**The mode is not said.** `--logic=main` is parsed by the shell and told to nobody: not `hello`, the discovery file,
`Snap` or the log. It can only be inferred from the thread the `tick` zone ran on.

**Replay does not exist.** Nothing records input messages to play them again. The nearest is
`LogicTest.theSameInputsGiveTheSameDeliveriesWithTheTicksOnTheirThreadOrOnTheMainThread`, in-process.

So R1's movement can be built from today's rows, with ticks and draws counted by the Inspector itself and labelled
as such; everything past it needs section 7½'s upstream work first.

## 7½. What it needs from the stack

Each item reaches the Inspector through a port verb or the stream, in the owning repository, and is written into that
repository's TODO when this design is accepted.

| Repo | Addition | For |
| --- | --- | --- |
| atchung | A tick number, and a row per tick with rounds, delivered, carried and whether it met its deadline | the logic escapement; the tape's tick rulings |
| atchung | Superseded writes counted per state topic and per inbox | registers' written and delivered (R2) |
| atchung, vexelray-framework | A cause source for the framework's `Timer` and for jobs | no `uncaused` rows from the framework's own parts |
| vexelray-gui | A draw number apart from the pass; the held counts in `LoopCounters` and `Snap` | the balance wheel; the tachometer |
| vexelray-gui | The recorder keeps the cause: the eight-argument `row`, a field on `RecordedRow` and the link's `Row` | a click's thread (R3) |
| vexelray-gui | A draw says which causes' writes it drew | write → draw in a click's thread (R3) |
| vexelray-gui | `frames` and `why` rebuilt on the three clocks: logic rows counted, held draws read | agent first: the movement's facts askable |
| vexelray-framework | Bus graph version 2: topic kind and key filters, `Inbox` receivers, a job's request side, ledger steps, timers, views as their own kind, real lane names, no invented overflow | the declared graph (R2) |
| vexelray-framework | The logic mode and the lane-to-thread map in `hello` (and the discovery file) | the movement knowing which mode it shows |
| vexelray-framework | An input recorder and a replay driver | R6, and ROADMAP §5 item 5 |
| vexelray-framework, -shell | Rows from `Timer`, `Jobs`, `Logic` and `Crew` | the job tray; declared lanes |
| vexelray-present-dxgi | A row when the ready thread waits and wakes | the magazine |
| vexelray-framework | **Sources** (ruled, 9.2): a part that keeps producing from outside for as long as it runs, with its own thread, named in the wiring, started after its listeners and stopped before them | reading the link, a recording and a launched child (R1); netcode and every other client of a stream |

## 8. How it is put together on 0.2

The template's shape, used as it is meant to be: a `-core` that decides on a ledger, jobs for anything that blocks,
and a `-gui` view.

**`-core`: the bench.** One component on a ledger, `Bench`, owns the run being inspected: where it came from
(attached, launched, replayed), what has been read of it, and the model the view draws (the movement's state, the
graph's counts, the tape's recent rows, the ledger). Rows reach it as batches; a step folds a batch into the model
and publishes the model on **state topics**. Last-write-wins is exactly right for a view that draws the newest model:
a storm of rows costs the view one delivery a tick, not one per row, and the Inspector is a witness for fork 1 on
itself. Discrete things (a stall, a run that went away, a gap) are **edges**.

**Sources for what keeps coming, jobs for what finishes.** 0.1's three hand-made threads (0.1 FN-1) were each
something that keeps producing for as long as it runs. In 0.2 they become **sources**, a kind of part the framework
does not have yet (ruled, 9.2):

- `LinkRows`: the attached run's rows, a batch per message, for as long as the link is open.
- `RecordingRows`: a recording's rows at their recorded pace.
- `ChildOutput`: the launched child's output, for as long as it runs.

What finishes stays a job:

- `ListRuns`: read the discovery folder, once per request. The picker asks again while it is shown.
- `Launch`: start the child with automation and the probe on, and reply when its discovery file appears. Stopping it
  is another job.
- `Query`: one port verb, its reply as a record (the command menu, `gpu device`, `why`).

**`-gui`: the view, told and asked.** Panels, the picker, the flow table and the tape's text are told, as in the
template: the view hears the model and writes widgets. **The movement is asked**: the wheel's angle, the beads'
positions and the show clock are a function of present time, decided at draw time. 0.2 has no form for that yet.
`Tween` is one bus message per step, which is the cost a moving picture cannot pay, and is what the frame contract
(`vexelray-gui/docs/plans/frame-contract.md`, proposed, not started) is for: a part told by declared steps and asked
by a declared, checked draw at the present time. **The Inspector's movement is the frame contract's first
application witness, and its first that is not a video** (ruled, 9.1). Told builds, asked chooses: when a batch
arrives, the told half builds the wheels, the arrows and a fixed pool of beads; the asked half places them for show
time and returns when it is next due; the governor's readouts come from the part's clock topic. The show clock's
catch-up depends on history, which an asked half may not keep, so the told half folds rows into a schedule from real
time to show time and the asked half only evaluates it.

**Volume.** The tape was sized for 20,000 rows a second. Rows cross as `RowBatch`es, a batch is one message, and the
tape keeps rows in the bench's state as a ring it replaces rather than mutates (a payload must be deeply immutable,
T2.4). Whether that holds 20,000 a second with nothing allocated per row is the first thing to measure (R1).

## 9. Decisions for the author

1. **The frame contract, now or later.** *Ruled 2026-10-30, by the author: (a).* The movement goes onto the frame
   contract, and no told-only movement is built in the meantime. The Inspector needs the contract's steps 2 and 3
   only (the host's `Frame`, `Drawn`, due times and parking, with `PaintMotion` and `LayoutMotion` moved onto it; the
   processor's `@Drawn` check), not the feed (step 4) or the codec (step 5), so those two land first with the
   Inspector as the application on them. Everything told in R1 goes ahead meanwhile; only the movement waits for step
   2. **Why:** a told-only movement is a bus message per draw, the cost the contract exists to remove, and it would be
   thrown away and would hide the gap this phase is for. *Rejected: (b)*, a movement only told, its state published
   at most once per draw, moved onto the contract later.
2. **A long-lived reader.** *Ruled 2026-10-30, by the author: a source, a new kind of part in the framework.* A
   source is something that keeps producing messages from outside the application for as long as it runs: a socket,
   a stream, a child process, a device. It only sends, has its own thread, is named in the wiring like any other
   part, starts after the parts that hear it and stops before them. **Why:** it is the right shape, and it will be
   needed for netcode and much else, so it belongs in the framework rather than worked around in each application.
   It is built in vexelray-framework before the Inspector's R1 needs it (section 7½). *Rejected:* a loop of short
   jobs, each waiting up to about 50 ms for what arrived and asked again at once.
3. **The correlation key.** A number per clock stamped on every row, cause carried on every row, or both (section 6).
   Both cost the recorder bytes per row, and the recorder's cost is shown from day one.
4. **The clockwork.** Keep the metaphor with a train per clock (section 3), or draw the three clocks as three plain
   lanes and keep the clockwork for the main view only.
5. **The old repository.** This checkout becomes `vexelray-inspector`'s history at phase 8's merge (its own history
   kept on a branch or tag, as mainframe's was at `archive/pre-v1`), or it stays a separate repository.

## 10. Order of work

Each step ends with something to watch or something an agent can ask, and lands its port verb first.

- **R1, first light on 0.2.** Sources upstream (9.2); attach, launch and replay as sources and jobs; the bench folding rows into a model; the readouts,
  told. Then, once the frame contract's step 2 lands, the movement's three trains, asked, with passes, ticks, draws and both kinds of held draw, read from rows that exist today (section 7:
  ticks and draws counted by the Inspector until the stack numbers them, and said so). Measured: the
  link's latency and what 20,000 rows a second cost the bench and the view. Inspected: the phase 7 witnesses
  (W1 to W3) and a freshly generated application, in both modes (`--logic=main`).
- **R2, the declared graph.** Bus graph version 2 upstream; the graph from the declaration, registers and belts,
  written and delivered.
- **R3, a click's thread.** The correlation key upstream (decision 9.3); select an input, light its path.
- **R4, the tape and pause**, on the three clocks' rulings.
- **R5, the ledger per clock**, with recording measured on its own.
- **R6, replay**: run the same inputs again and show which widget writes differ.
- **Later**, as 0.1 planned them: time (kronometer) where a view still has motion, GPU and present, the JVM, the
  tree, diagnosis rules, history by build, the command menu.

## 11. 0.1's findings, against 0.2

| 0.1 note | Under 0.2 |
| --- | --- |
| FN-1 no lane for a long-lived reader | ruled: sources, in the framework (9.2) |
| FN-4 a driven run does not probe the bus | still holds: rows are written only while the probe traces (section 7) |
| FN-6, FN-7, FN-8 who sent and who received | answered by fork 5 at compile time, once the bus graph says it (section 4) |
| FN-9, FN-10 host and graphics events not on the bus | to check |
| FN-11 a handler call is not a message | gone with handlers |
| FN-14 no refresh rate | to check; the ceiling is now a fact the loop knows |
| FN-15 the pools say nothing | gone with the pools |
| FN-16 a wait is not told from a rest | to check; fork 8 added `draw held for the GPU` |
| FN-17 the loop marks no frame deadline | changed: the tick has a deadline and drawing a slot |
| FN-20 `KronoGui.ramp` under reduced motion | gone from applications with `KronoGui` (gui step 18) |
| FN-2, FN-3, FN-5, FN-12, FN-13, FN-18, FN-19 | not about the architecture; carried over to check as they come up |
