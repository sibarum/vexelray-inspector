# The Inspector on 0.2

What the Inspector is when it is rebuilt for VexelRay 0.2, what it draws, how it is put together on the message
model, what it needs from the stack, and the order to build it in. **Proposed 2026-10-29; every decision in section
9 ruled by the author 2026-10-30; R1 to R7 done 2026-11-02, R8 2026-11-03; what follows is section 10's *Later*.** It is phase 7½ of `vexelray-framework/ROADMAP.md`: the first
application written for 0.2 from nothing, and the test of whether 0.2 is easy to use correctly.

The 0.1.3 Inspector (`Documents/GitHub/vexelray-inspector`, on `develop`) is where the ideas come from, not the code.
Its `docs/roadmap.md`, `component-graph.md`, `threads.md` and `framework-notes.md` are cited here as *0.1*.

## 1. What stays the same

The ask: DevTools for VexelRay applications, where every working part of a running application is on screen and
everything on screen is a real subsystem. **0.1 drew it as clockwork; 0.2 does not** (ruled, 9.4): the design is
strictly utilitarian, with no metaphor and nothing drawn for effect, and it grows into a fully customizable dashboard
on a timeline that can be rewound (section 3). These carry over as they were:

- **Agent first.** Every fact the Inspector draws can be asked through a port verb with a fixed reply shape. If an
  agent cannot ask it, the Inspector does not draw it. The Inspector is the GUI and `ottermate` is its CLI.
- **The port is the automation socket's flight recorder** (`vexelray-gui/docs/plans/flight-recorder.md`): a ring of
  probe rows in the target, query verbs over it, many observers and one hand, and a typed live stream over the
  elektro-Q link (`vexelray-gui-inspector-link`). Recordings stay probe CSV.
- **Never block the target, and say what was lost.** A gap is not made up: rows the stream lost are counted, nothing
  is measured across them, and the first frame after says so.
- **Pause.** It holds the whole window at one moment while the target runs on. Under 0.2 it is the timeline's
  cursor leaving the live edge (section 3). 0.1's show clock, which showed everything a little late to stretch a
  burst, is dropped (9.6): everything is shown at its real time.
- **Attach, launch, replay.** The picker over the discovery folder, `--attach=<app|pid:n>`, `--launch <command>`,
  `--replay=<csv>`; *Restart in debug mode* from any application's title bar.
- **History by build and the command menu.** As 0.1 designed them. 0.1's tape becomes the timeline.

## 2. What changes, and why it cannot be ported

0.1's Inspector draws the 0.1 loop: one main thread that took input, ran the application's handlers, laid out, drew
and presented, then parked. 0.2 took that loop apart, and each fork removes something the old Inspector stands on.

| 0.1 assumed | 0.2 has | Fork |
| --- | --- | --- |
| One heartbeat: everything keyed to `frame.present` | Three clocks: input is taken when it arrives, logic runs in ticks, drawing is capped at the display rate. A pass of the main loop is not a frame | 7 |
| One correlation key: the frame number every row is stamped with | A click's answer crosses the logic lane before any draw, so the frame it was drawn in says nothing of the tick that decided it | 3, 7 |
| Frame stages INPUT, CLOCK, APP, SETTLE inside one frame | No application code on the main thread. APP is a tick on the logic lane | 4 |
| A draw happens when the loop decides to draw | A draw can be **held for the ceiling** (the 60 Hz slot is not due) or **held for the GPU** (the presenter is not ready), and stays owed while the thread goes back to input | 8 |
| Every message is delivered, so a flow's count is its deliveries | A state topic delivers only the last write in a tick. Writes and deliveries differ, and the difference is the coalescing | 1 |
| Senders are guessed from the thread and the topic, receivers from mailbox names or a stand-in table (0.1 FN-6 to FN-8) | Every send is a constructor parameter the processor sees, so the graph is known at compile time | 5 |
| Handlers are called, not messaged (0.1 FN-11); handler and offload pools say nothing (0.1 FN-15) | No handlers and no pools: components, views, jobs, ledgers | 4, 5 |
| A turn of the main loop is the unit of thread time (0.1 `threads.md`) | Each clock has its own unit: a pass, a tick, a draw | 7 |
| Replay plays a recording back | A run replays: the same input messages produce the same widget writes, in either mode | §5 item 5 |

And the 0.1 Inspector breaks 0.2's own rules: its show runs on the timeline inside the frame, `Show` is driven by
`KronoGui.ramp`, which no application can reach now, and it starts three platform threads of its own (0.1 FN-1).

## 3. What it shows: a dashboard on a timeline

*Ruled 2026-10-30, by the author (9.4):* strictly utilitarian, no gimmicks. Plain panels of lanes, numbers, tables and
charts; no metaphor, no animation for its own sake. The stack's design principles apply as everywhere else, **slightly
more leniently**, since this is a tool for whoever builds on the stack. Which rule bends, and how far, is settled with
the author when it comes up, and written down here.

**Where it is going: a fully customizable dashboard.** The person chooses the panels, places them and sets each one
up. Under all of them is one **timeline**, and every panel shows the run **as of the timeline's cursor**. At the live
edge that is now; drag the cursor back and every panel shows exactly what it showed then, ten seconds ago say,
computed again from the rows rather than remembered as a picture (section 8). The first build has a fixed set of
panels; customizing comes later, and nothing in the first build may stand in its way.

The first panels:

| Panel | Shows, as of the cursor |
| --- | --- |
| **Timeline** | One lane per clock, the cursor on it, the live edge at the right. **Input**: each event taken, injected input (automation) told apart. **Main thread**: each pass, its waits, and what woke it. **Logic**: each tick, its rounds and deliveries, and any tick over its deadline (still the 8 ms placeholder, ROADMAP phase 4). **Declared lanes**: their work within each tick. **Drawing**: each draw, and each draw **held for the ceiling** or **held for the GPU**. **GPU**: what the presenter waited on. **Jobs**: each request to its reply |
| **Readouts** | Draws a second against the ceiling and the display's refresh; ticks a second, tick time p50 and p95; draws held, by reason; rows lost |
| **Graph** | The declared parts and topics (section 4) |
| **Flows** | Every topic between two parts: written, delivered, rate, total |
| **Threads** | The ledger per clock (section 5): each clock's turn the cursor is in, cut into working, waiting, resting and unaccounted, with recording on its own (R5) |
| **Rows** | The raw rows around the cursor, the cursor's row selected (R4) |
| **Run** | What is inspected and from where (attached, launched, replayed), the logic mode, the GPU, the build |

What 0.2 makes visible that 0.1 could not:

- **A click's path.** Select an input event and every panel marks its path: the tick that heard it, the widget
  writes it caused, and the draw that showed them, with the time between each. This is fork 7's whole promise, and
  the one number every 0.2 measurement so far has been about (a click's result through the logic: 0.8 ms on the
  lane at 60 Hz, ROADMAP fork 8). Section 6 says how rows are linked.
- **A wasted pass and a held draw are different things.** A pass that took a pointer move and drew nothing is fine;
  a draw held for the GPU is the GPU being slow; a draw held for the ceiling is pacing doing its job. 0.1 could
  only say "a frame" or "no frame".
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
- **A state topic says its last value and two counts, written and delivered**; an edge topic, where every message is
  delivered, one count. A view that writes a state topic 200 times in a tick and is heard once shows it. The flows
  panel has the same two columns.
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

0.1 tied everything to one key: the frame each row happened in. 0.2 needs three things instead (ruled, 9.3). The
example they are for: a click is taken on the main thread, a tick on the logic lane hears it, the tick changes a
widget, and a later draw shows the change. Selecting the click lights all four.

1. **The cause on every row.** atchung already gives each input a cause and carries it through each delivery
   (`TickReg.java:64`, `sibarum.probe.Cause`), so the tick's delivery and the widget write made while hearing the
   click carry the click's cause. That links click → tick → write. Today the recorder drops it (section 7); it keeps
   it instead. One number per row.
2. **Ticks and draws numbered by rows of their own.** Each tick and each draw writes one row with its number, its
   start and its end. Other rows are not stamped with them: the Inspector places a row in a tick or a draw by its
   thread and its time. This is what the timeline rules its lines by and the ledger is cut by.
3. **A draw names the causes it drew.** A draw runs under its own pass's cause, so the cause alone cannot reach it.
   Its row lists the causes of the writes it drew, capped, and says when the list overflowed. That links write →
   draw.

## 7. What 0.2 records today

Surveyed 2026-10-29 on the `v0.2` clones (atchung, vexelray-gui, vexelray-framework), with GPU, time and animation
rows read from vexelray, kronometer and tactroller on `develop`. Paths are from `Documents/Spike`.

**Rows exist only when the probe traces.** `count` and `mark` write a row only with tracing on (`-Dprobe.format=csv`
or `-Dprobe.trace`), and a zone otherwise only when it is slow (atchung `Probe.java:143, 203, 226, 375`). A live
sink without tracing gets slow rows and nothing else. 0.1 FN-4 is the same fact from the other side, and still holds.

**What the timeline can be built from today:**

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
`LogicTest.theSameInputsGiveTheSameDeliveriesWithTheTicksOnTheirThreadOrOnTheMainThread`, in-process. *(Built for R6,
section 10: a run launched `--replayable` keeps what its logic takes in, and `replay` runs it again.)*

So R1's timeline can be built from today's rows, with ticks and draws counted by the Inspector itself and labelled
as such; everything past it needs section 7½'s upstream work first.

## 7½. What it needs from the stack

Each item reaches the Inspector through a port verb or the stream, in the owning repository, and is written into that
repository's TODO when this design is accepted.

| Repo | Addition | For |
| --- | --- | --- |
| atchung | ~~A tick number, and a row per tick with its number, start, end, rounds, delivered, carried and whether it met its deadline (9.3)~~ Done for R3: `tick.ran`, after the `tick` zone that times it (atchung `2d244f7`); it names no tick (FN-16) | the timeline's logic lane and its tick rulings |
| atchung | ~~Superseded writes counted per state topic and per inbox~~ Not needed: R2 counts written and delivered from the `send`, publish and `receive` rows | registers' written and delivered (R2) |
| atchung, vexelray-framework | A cause source for the framework's `Timer` and for jobs | no `uncaused` rows from the framework's own parts |
| vexelray-gui | ~~A draw number apart from the pass, and a row per draw with its number, start and end~~ Done for R3: `draw.ran`, after the `emit canvas` zone (vexelray-gui `c862fef`); still to do: the held counts in `LoopCounters` and `Snap` (9.3) | the timeline's drawing lane; the readouts |
| vexelray-gui | ~~The recorder keeps the cause: the eight-argument `row`, a field on `RecordedRow` and the link's `Row` (9.3)~~ Done in R1 (FN-12) | a click's thread (R3) |
| vexelray-gui | ~~A draw's row lists the causes of the writes it drew, capped, saying when it overflowed (9.3)~~ Done for R3: `draw.ran` names at most 16, then `+n` | write → draw in a click's thread (R3) |
| vexelray-gui | `frames` and `why` rebuilt on the three clocks: logic rows counted, held draws read | agent first: the timeline's facts askable |
| vexelray-framework | Bus graph version 2: topic kind and key filters, `Inbox` receivers, a job's request side, ledger steps, timers, views as their own kind, real lane names, no invented overflow | the declared graph (R2) |
| vexelray-framework | The logic mode and the lane-to-thread map in `hello` (and the discovery file) | the Run panel saying which mode it shows |
| vexelray-framework | ~~An input recorder and a replay driver~~ Done for R6: `--replayable` keeps what the logic tick takes in (atchung `2581268`, `Tick.Recorder`), and `replay` on the automation socket runs it again on fresh components and lists the sends that differ (vexelray-framework `8145095`, vexelray-gui `74f3c4f`); what it does not do yet is the framework's `docs/TODO.md`, *Found by building replay* | R6, and ROADMAP §5 item 5 |
| vexelray-framework, -shell | ~~Rows from `Timer`, `Jobs`, `Logic`~~ Done for R7: a `timer` row as a timer fires, a job's `receive` row and `job <name>` zone (`0cbb1f4`), the logic's park and wake and its named tick (`e4e4f55`, atchung `480953a`); still to do: `Crew` | the Jobs lane; the threads panel's logic and jobs lines; declared lanes |
| vexelray-present-dxgi | A row when the ready thread waits and wakes | the timeline's GPU lane |
| vexelray-framework | ~~A row for the logic lane's park (an idle zone) and for the unpark that ends it (framework-notes FN-17)~~ Done for R7 (`e4e4f55`) | the threads panel's logic rest and wakes |
| vexelray-framework | **Sources** (ruled, 9.2): a part that keeps producing from outside for as long as it runs, with its own thread, named in the wiring, started after its listeners and stopped before them | reading the link, a recording and a launched child (R1); netcode and every other client of a stream |

## 8. How it is put together on 0.2

The template's shape, used as it is meant to be: a `-core` that decides on a ledger, jobs for anything that blocks,
and a `-gui` view.

**`-core`: the bench.** One component on a ledger, `Bench`, owns the run being inspected: where it came from
(attached, launched, replayed), what has been read of it, and the model the panels show (the timeline's lanes, the
readouts, the graph's counts, the ledger). Rows reach it as batches; a step folds a batch into the model and publishes
the model on **state topics**. Last-write-wins is exactly right for a view that shows the newest model: a storm of
rows costs the view one delivery a tick, not one per row, and the Inspector is a witness for fork 1 on itself.
Discrete things (a stall, a run that went away, a gap) are **edges**.

**Time is a parameter of every panel, from the first build.** Rewinding (section 3) is not a feature added later; it
decides the bench's shape now. The bench keeps the rows of a retained reach (5 s to 5 min, as 0.1's tape offered,
bounded by what R1 measures memory to allow) and a **checkpoint** of the model every second. The model as of a moment
is the checkpoint before it with the rows since folded on, by the same step that folds them live. Because that step
is a ledger step, a pure function of the model and the rows, the past is recomputed exactly: "what the dashboard
showed ten seconds ago" holds by construction, not by keeping pictures. The cursor is a message from the view on a
state topic, so dragging it through a storm of moments costs one recomputation a tick, at most a second's rows from
the nearest checkpoint, and the newest position wins.

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

**`-gui`: the view, told and asked.** Every panel is told, as in the template: the view hears the model as of the
cursor and writes widgets. **One thing is asked: the timeline at the live edge.** Its lanes scroll with present time,
continuously, while rows arrive in batches; where the live edge sits is a function of present time, decided at draw
time. 0.2 has no form for that yet. `Tween` is one bus message per step, the cost a moving picture cannot pay, and is
what the frame contract (`vexelray-gui/docs/plans/frame-contract.md`, proposed, not started) is for: a part told by
declared steps and asked by a declared, checked draw at the present time. **The live timeline is the frame contract's
first application witness, and its first that is not a video** (ruled, 9.1). Told builds, asked chooses: when a batch
arrives, the told half builds the marks for its rows; the asked half only sets the lanes' offset for the present time
and says when it is next due. With the cursor off the live edge nothing is due, and a paused dashboard draws nothing.

**Volume.** 0.1's tape was sized for 20,000 rows a second. Rows cross as `RowBatch`es, a batch is one message, and
the bench keeps the retained rows as a ring it replaces rather than mutates (a payload must be deeply immutable,
T2.4). Whether that holds 20,000 a second with nothing allocated per row, and how long a reach memory allows, are the
first things to measure (R1).

## 9. Decisions for the author

1. **The frame contract, now or later.** *Ruled 2026-10-30, by the author: (a).* What moves on screen goes onto the
   frame contract, and nothing told-only is built for it in the meantime. Since 9.4, what moves is the live
   timeline's scroll (section 8), not 0.1's clockwork. The Inspector needs the contract's steps 2 and 3 only (the
   host's `Frame`, `Drawn`, due times and parking, with `PaintMotion` and `LayoutMotion` moved onto it; the
   processor's `@Drawn` check), not the feed (step 4) or the codec (step 5), so those two land first with the
   Inspector as the application on them. Everything told in R1 goes ahead meanwhile; only the live scroll waits for
   step 2. **Why:** a told-only scroll is a bus message per draw, the cost the contract exists to remove, and it would
   be thrown away and would hide the gap this phase is for. *Rejected: (b)*, a scroll only told, its position
   published at most once per draw, moved onto the contract later.
2. **A long-lived reader.** *Ruled 2026-10-30, by the author: a source, a new kind of part in the framework.* A
   source is something that keeps producing messages from outside the application for as long as it runs: a socket,
   a stream, a child process, a device. It only sends, has its own thread, is named in the wiring like any other
   part, starts after the parts that hear it and stops before them. **Why:** it is the right shape, and it will be
   needed for netcode and much else, so it belongs in the framework rather than worked around in each application.
   It is built in vexelray-framework before the Inspector's R1 needs it (section 7½). *Rejected:* a loop of short
   jobs, each waiting up to about 50 ms for what arrived and asked again at once.
3. **The correlation key.** *Ruled 2026-10-30, by the author:* the cause on every row; ticks and draws numbered by
   one row each, not stamped on every row; and a draw's row naming the causes it drew (section 6). **Why:** the cause
   alone stops at the widget write, and numbers alone only suggest what caused what. Together they give a click's
   whole path for one number per row, plus a row per tick and per draw. *Rejected:* a number per clock stamped on
   every row, which costs every row and links nothing.
4. **The clockwork.** *Ruled 2026-10-30, by the author: no.* Strictly utilitarian, no gimmicks: plain panels on a
   timeline (section 3). The stack's design principles apply, slightly more leniently. **Where it is going:** a fully
   customizable dashboard, with a timeline that can be rewound to show exactly what the dashboard showed, ten seconds
   ago say. So time is a parameter of every panel from the first build (section 8). *Rejected:* the clockwork, with
   a train per clock or for the main view only.
5. **The old repository.** *Ruled 2026-10-30, by the author: this one takes it over.* At phase 8 this checkout
   becomes `vexelray-inspector`'s history and keeps the name; the 0.1.3 code is kept under a tag, as mainframe's was
   at `archive/pre-v1`. Its docs (`roadmap.md`, `component-graph.md`, `threads.md`, `framework-notes.md`) stay
   readable there. Its `retrospective.md` had no sections yet, so there is none to carry over. *Rejected:* two
   separate repositories.
6. **The show clock.** *Ruled 2026-10-30, by the author: dropped.* Everything is shown at its real time. A burst is
   followed by stopping the cursor on it and stepping through it. **Why:** it bent time on screen, which a
   utilitarian design should not do, and rewinding does its job better. *Rejected:* 0.1's show clock (up to 400 ms
   late, a burst stretched up to ten times), as it was or as a setting of the live edge.
7. **What rewinding rewinds.** *Ruled 2026-10-30, by the author: the data only.* Panels added, moved or set up are as
   they are now, and show the past through today's arrangement. So the arrangement is not part of the bench's model
   and is never checkpointed. *Rejected:* rewinding the arrangement with the data.

8. **Two ways to show time, and no jumps.** *Ruled 2026-10-30, by the author.*
   - **Realtime and Sequence.** Realtime is the axis as time, with a zoom: a burst is a clump with a width, and zooming
     dilates time uniformly. Sequence is the axis as order: each event one slot, gaps removed (and marked as removed,
     with how long they were, so it never passes for time). Both take a filter, which decides which events count; in
     Sequence a hidden event has no slot, and stepping skips it. Once paused, the cursor steps **one event at a time**
     in either mode. Sequence is a paused mode: following the present is Realtime's.
   - **Each lane its own sequence, not synchronized.** Lanes do not share columns: each lays out its own events, and
     the cursor is the one place they agree. Each lane's target is where the cursor's moment falls in its own sequence,
     between two of its events if need be, so a small move of the cursor is a small move of every lane. Each lane moves
     to its target independently, at its own pace, and lands exactly. (IntelliJ's diff view, two panes on one
     scrollbar, jumps because one side has to catch up with the other; nothing is tied here, so nothing catches up.)
   - **No jumps, and no corners in any derivative.** What is under the cursor never moves unless the person moves it,
     and nothing else rearranges in one frame. Position, velocity, acceleration and every derivative after them are
     continuous: a stop, a start, an interrupted move and a landing included. A spring fails this (a retarget kinks
     the velocity, and it never lands without a snap), so the mechanism is a **handover**: every motion is a smooth
     path of time (stopped; following the present), and every change hands over from the path in progress to the new
     one over a short window, mixed by a step that is smooth in every derivative and flat in all of them at both ends.
     The mix is of speeds, with the remaining distance mixed in by the step, so a pause slows to rest without ever
     going backwards and a resume catches up with the present and settles onto it. A handover interrupted is the path
     the next one starts from. Under reduced motion the window is zero and the landing immediate.
   - **Perceived, so measured and tried.** The step's sharpness and the window's length are set by how it looks, not
     by argument; their values, and why, are in the view's code, and change as they are tried. *Watched by the author,
     2026-10-30:* pause and the catch-up after it read as plain linear motion, and that is the aim: "any tweening would
     only draw attention to itself". The smoothness is there so that nothing jumps, not to be seen; a change that makes
     the motion noticeable as motion is a regression.
   This bends 9.4 by its letter, not its intent: the motion is not decoration but what keeps the eye on what it was
   following. The pause moment is the view's to choose (only it knows the present and where the lane can come to rest),
   so `Pause` and `Step` carry the moment they start from. *Rejected:* one shared column per event across all lanes
   (sparse lanes, and a filter change shifts everything); synchronized scrolling; springs.

## 10. Order of work

Each step ends with something to watch or something an agent can ask, and lands its port verb first.

- **R1, first light on 0.2.** Sources upstream (9.2); attach, launch and replay as sources and jobs; the bench
  folding rows into a model, with checkpoints and the cursor from the start; the timeline (one lane per clock: passes,
  ticks, draws and both kinds of held draw), the readouts and the run panel, read from rows that exist today
  (section 7: ticks and draws counted by the Inspector until the stack numbers them, and said so). Pause and rewind
  work from R1, since the bench is built for them. The live scroll goes onto the frame contract when its step 2
  lands. Measured: the link's latency, what 20,000 rows a second cost the bench and the view, and the reach memory
  allows. Inspected: the phase 7 witnesses (W1 to W3) and a freshly generated application, in both modes
  (`--logic=main`).
  **Slice 1 done 2026-10-30:** the template's editor removed; `ListRuns` (a job over the discovery folder), `LinkRows`
  (a source, the `frame` lane only) and `Bench` (a ledger: attach to the newest run, fold its passes over a 10 s
  reach, say why it went away); the view's passes lane, told by `Timeline` and asked by `@Draw`, the first application
  witness of the frame contract. Two gaps were found and fixed upstream on the way (framework-notes FN-1, FN-2): the
  frame now carries the machine's monotonic present beside the tree clock's, and the link says where a run's rows
  count from, so both ends are on one clock. Seen in a window with `ottermate`: the lane follows the present at
  about 91 px a second on a 911 px, 10 s lane, and a detached timeline settles.
  **Slice 2 done 2026-10-30:** the bench's `Model` with its one pure `fold`, and the `Tape`: the last minute's rows a
  second at a time, each beside a checkpoint, the model as of a moment recomputed from them by the same fold (held
  by a test over a run crossing many checkpoints). Space pauses and resumes, the arrows step the cursor by a second
  or a tenth (`Pause`, `Step`, edges from declared chords); paused, the lane shows the run as of the cursor and
  nothing is drawn. Seen with `ottermate`: a paused lane is byte-identical two seconds later and `settle` succeeds;
  a step back moves the marks 92 px right. Not yet: dragging the cursor (design's absolute cursor on a state topic).
  **The glide (9.8) done 2026-10-30:** every change of the passes lane's motion is a handover (`Glide`): a pause
  coasts to rest without going back, a step glides and lands exactly, a resume catches up and settles onto the
  present, and an interruption hands over from the motion in progress. Keys go to the view first, which sends the
  bench `Pause` and `Step` with the moment the lane rests at. Held by `GlideTest` (no jump in speed or acceleration
  through five interruptions; exact landings; a pause never backwards); seen with `ottermate`: every glide settles,
  and a step back lands the marks 91 px right. Not yet: event stepping, zoom, Sequence and the filter.
  **Event stepping done 2026-10-30:** the arrows step the cursor onto the previous or next pass (`StepEvent`), Shift
  with them a second; the pass the cursor is on is drawn over the others in the text's colour, two pixels wide at
  least, and the status line says how long it took and when it ended. Seen with `ottermate`: steps through passes 3 to
  9 ms apart, stopping at the oldest. Found: at the 10 s lane those steps move it under a pixel, so zoom comes next.
  **Zoom and click done 2026-10-30:** `=` and `-` halve and double the lane's span (10 ms to 60 s) about the right
  edge, a second glide in doublings, drawn by stretching the picture along x (`Drawn.stretch`, added upstream in
  vexelray-gui with the author's approval) until the picture is built at the new scale; a pass is a fill as long as
  it took and a pixel-wide line at its end, so it neither vanishes zoomed out nor fattens zoomed in. A click on a pass
  goes to it (`GoTo`). Seen with `ottermate`: zoomed nine times, passes 0.3 to 2.3 ms long stand apart and step one by
  one; a click on one glides it to the edge. Hover waits on FN-7.
  **Slice 3, every lane and the readouts, done 2026-10-30 (Sequence and the filter still to come):** five lanes, one
  band each in one picture since they share the axis: Input (tactroller's `edges` when it took any, automation's verbs
  told apart), Main thread (passes), Logic (the `tick` zone off `main`), Drawing (the presenter's `present frame`, and
  draws held for the ceiling and for the GPU as moments, faint and in caution), GPU (what the presenter waited on).
  Read from a traced run's rows, dumped first to check section 7's survey. `Timeline` carries `Lane`s of `Mark`s and
  the cursor's `Event`; the readouts are a state topic of their own, from the same fold, so they rewind (the link's lost
  rows are a row the source adds, folded like the rest). A click on a lane focuses it, and the arrows step through the
  focused lane's events (ruled by the author 2026-10-30). Seen with `ottermate` against a traced Inspector: zoomed to
  625 ms, passes, ticks, draws, holds and waits line up band by band; 58 draws a second under the ceiling, 146 passes
  held for it, 3 held for the GPU, 96 ticks a second at p50 0.12 ms. Found: under `--logic=main` a logic tick cannot be
  told from the widgets' (framework-notes FN-8); a run with automation and no `-Dprobe` records no ticks or GPU, and
  a lane cannot say so (FN-9). Jobs and declared lanes write no rows yet (section 7½), so they have no lane.
  **The cursor is a line, ruled by the author 2026-10-30:** a click puts the cursor where it lands and the lanes do not
  move, rather than gliding the clicked moment to the right edge; a step or a zoom moves the line, and only when it
  would come within 5% of either side do the lanes glide to put it in the middle. A click with no event near is the
  absolute cursor of section 8, `Seek` on a state topic, which dragging will reuse. Space still pauses with the cursor
  at the right edge. Seen with `ottermate`: clicked at x 600, the line settled at 592 (the coast from following) with
  the lanes still; zoomed in three times after, the lanes glided it to the middle, x 578.
  **A stretch stands as tall as it took, ruled by the author 2026-10-31:** packed end to end, a storm of 1 ms passes
  and one 650 ms pass were the same solid bar. Each stretch now stands on its band's floor at a height by the log of
  its length, a sixth of the reach at 0.1 ms or less up to all of it at a second (`View.stature`); a moment keeps the
  whole reach. Seen with `ottermate`: steady passes, under a millisecond, are a low comb.
  **The details panel and the Messages lane, ruled by the author 2026-10-31:** what troubleshooting asks first goes
  right under the lanes, a label and a value a line: the clock time (what the run's wall clock said at the cursor, as
  local time of day and as UTC, from the first row's wall less its monotonic moment, so between rows too), the event,
  its thread, and for a message its schema, From, To and Transport. Today's rows give the type and the publishing
  thread only; To and Transport say they are not recorded yet, and From names the thread (framework-notes FN-10).
  Messages are a lane of their own, a moment per bus row whose kind is a class name. `Details` is a state topic of its
  own beside the readouts; `Row` carries the wall clock and `Mark` the thread and the message type. Seen with
  `ottermate`: a click on the Messages lane said `Messages.Rows` from `vexel-source-link-rows`, and one on a pass its
  thread and "not a message".
  **A stopped cursor is kept on an island, ruled by the author 2026-10-31:** the tape trimmed to the last minute of
  the present whether or not the cursor was stopped, so a minute after a pause the cursor's second was let go and the
  lanes went blank. Stopping now freezes the tape and the kept events as they stand (`Bench.Island`), which nothing
  trims; recording goes on beside it on the live tape. The cursor cannot leave the island: every step, seek and
  click is clamped to it, and a move stopped at its newest end says so and that End follows the present again. End,
  or the button before the readouts (a new `Follow` message, since Space toggles), follows the present and lets the
  island go. Seen with `ottermate` against a traced Inspector: paused, a shot 75 s later was byte-identical, and so
  was one after stepping back and forth; a Shift step past the end stopped there and said so; the button and End both
  followed again. Found: the catch-up after so long a pause overflows the window's vertex buffer (TODO), and a focused
  button kept Space from pausing (FN-11). Rejected: stopping the recording while paused, which leaves a hole where the person was looking; and an
  island that moves with the cursor across a gap, which needs a gap to step across.
  **A pixel shows one mark, 2026-10-31:** the strip drew a mark an event, so a minute's catch-up needed more vertices
  than the window holds and a busy lane of moments was one solid band. Each lane is now drawn a pixel column at a
  time (`View.Columns`): a column keeps the tallest stretch ending in it, or how many moments are in it, drawn at
  0.45 of their colour alone and stronger a step each doubling up to full at 32; a run of alike columns is one fill,
  and touching alike stretches wider than a pixel are one fill too. A mark alone is still a line, in the pixel its
  end is in. The picture is bounded by its width, not by how many events it spans. Seen with `ottermate` against a
  traced Inspector: live, the comb and the Drawing lane read as before, the draws shaded by how many share a pixel;
  End after 75 s paused logged no overflow and drew the title bar, details and status through the glide. Its first
  frames show empty lanes: following again lets the island go, and the live tape keeps only the last minute.
  **A strip as wide as its glide, 2026-10-31:** zoomed in, a glide crossed more lanes than the strip's seven each
  side, and the lanes were empty mid-glide. The strip now reaches as far as the glide it is built for goes
  (`View.reach`, seven lanes at least and 256 at most), and once wider than 20 000 px it is drawn a pixel a cell only
  three lanes about where the glide starts and lands, and in about 4 000 coarser cells between, which a glide crosses
  too fast to read (`View.Cells`); at rest it is the least reach and every pixel is fine. Seen with `ottermate`
  against a traced Inspector: zoomed to 1.25 s a lane and paused 30 s, End's catch-up of 24 lanes showed passes and
  draws in every frame shot, with no vertex warning. **The empty start of End from an island older than the tape's
  minute stays, ruled by the author 2026-10-31:** the glide is too fast for it to be seen, and what it shows is right.
  **What the lanes cannot draw is said, ruled by the author 2026-10-31:** the view logs, as `vexelray-inspector.view`,
  when a glide is longer than the strip can reach (now at most a quarter of a million pixels, whatever the lane's
  width), and when the right edge or a handover is not a number, in which case it starts again from the timeline's
  edge rather than slide by it. A picture of more than 12 000 marks, which would overflow the window's vertex buffer
  and cost it its title bar and status line, is drawn again in cells twice as wide until it fits (`View.fit`), and
  an event that ends before it begins is left out and counted (`View.malformed`); both are logged when they start
  and said in the status line's right-hand slot (`status.drawing`) for as long as they last. Seen with `ottermate`:
  a zoomed catch-up left that slot empty and the log without a word from the view.
  **Every event is a record of one schema, ruled by the author 2026-10-31:** what the author asked for from the
  first day, From, To and Type, as fields every event has, filterable. A first try put the rows' new facts into
  sentences (an event line, a detail line, a cause line); the author rejected it: that is the same From, To and
  Type, following no schema and matching no filter. Now `Mark.fields` holds an event's fields, named from
  `Model.FIELDS` and kept in that order: Type, From (what set it off), To (where it went), Pass, Took and Thread on
  every lane, then a type's own: Drew, Parked and Waited for a pass; Package for a message; Verb and Sent for injected
  input; Edges for device input; Reason for a draw held. A field the rows cannot fill is absent, never a placeholder,
  and the panel is a row a field, shown only while the event at the cursor has it, each with its landmark
  (`detail.from`, `detail.drew`). To is absent everywhere until the bus records a message's receivers (FN-10). From
  is the run's own words for the row's cause (`Model.causeOf`), from its `cause.source` and `agent.command` rows,
  which `LinkRows` now asks for on the `app` lane, and the source table the link hands over when the stream opens;
  a pass's From is what woke it, since every pass is the loop's timer, read as the recorder's `why` reads the loop's
  rows: "mutation on Gui@1d082e88, set off by I/O …LinkRows, firing 2098". `Row` and `Mark` carry the pass and the
  cause. This took the framework too (FN-12). A click now finds its event by lane, moments and kind, since the rows
  after a pass fill fields the view may not have had when it was clicked. Seen with `ottermate` against a traced
  Inspector: pass #707 showed Type, From ("asked on Gui@1d082e88, set off by timer gui.frame, firing 706"), Pass,
  Took 1.27 ms, Thread, Drew yes, Parked 15ms and Waited 328.5us, and no other row.
  **A lane the run does not record says so, ruled by the author 2026-10-31:** every traced run checked before this
  day recorded only automation's lanes, since `-Dprobe` on the mvn line never reached the app (FN-9), so Logic,
  Messages and GPU were always empty and nothing said why. The pom passes the probe on by name now, the link says
  which lanes the run records, and `Lane.recorded` dims a lane's name; the status line's `status.lanes` names those
  lanes and how to record them. Seen with `ottermate`, two Inspectors each watching the other: the one watching a
  run started without the probe dimmed Logic, Messages, Drawing and GPU and said so; the one watching a run started
  with `-Dprobe=all` on the mvn line said nothing, and for the first time showed ticks (97/s, p50 0.34 ms), messages,
  draws (54/s) and GPU waits. Drawing is read from the `gpu` lane for draws and the `frame` lane for holds, so without
  the GPU's lane it still shows holds.
  **Next, ruled by the author 2026-10-31: an event's path, not From and To.** A cause travels through several hops
  (I/O, an app component, the GUI, a pass), and every hop writes rows under the same cause: in a fully traced run one
  `LinkRows` cause was `Messages.Rows` published on `vexel-source-link-rows`, the `bench` ledger on `vexel-logic`, 37
  mutations on the GUI thread, and the wake of the next pass. The ledger's sends and the View's deliveries write no
  row, so that gap is upstream (FN-10).
  **An event's path, ruled by the author 2026-10-31:** From and To gave way to Path, every hop the event's cause took,
  in the order they began: the source (`Model.causeOf`), each publish, each component's send, each delivery to an
  inbox, each ledger commit, the GUI's changes folded into one hop that counts them (relaid out, redrawn), and the
  wake of the next pass. A pass's cause is what woke it, so its path is what made it run. The model kept the hops by
  cause (`Model.paths`, trimmed with the reach; read from the tape since R1's fold was made to add), and the bench fills Path when it says the details, since a cause goes
  on after the event. A component's send is a message on the Messages lane too. Upstream, atchung's `Inbox` writes
  `send` and `receive` rows in the message's cause (FN-10). The panel shows a hop a line; the field keeps them on one,
  joined by arrows, as a filter matches it. Seen with `ottermate`, an Inspector watching one started with
  `-Dprobe=all`: pass #882 said I/O LinkRows, firing 430 → published Messages.Rows on vexel-source-link-rows →
  reached Messages.Rows at Bench → sent Timeline, Readouts and Details from Bench → ledger bench committed its state →
  reached Messages.Timeline at widgets → woke pass #882 → reached Readouts and Details at widgets → 39 changes to the
  GUI on main (2 relaid out, 1 redrawn). Not yet: which `@View` method a delivery to `widgets` reached.
  **Slice 4, the run panel and the picker, done 2026-11-02, as proposed to and ruled by the author that day:** a line
  over the lanes says what is inspected, its pid, how it was reached (attached, launched, replayed) and where its logic
  ticks run, as far as the rows tell (`Bench.logicOf`: the thread of a tick off `main`, or that none was seen, which
  under FN-8 is `--logic=main` or idle; the mode itself is still not said by the stack, section 7). Beside it a `Select`
  of the running applications (`ListRuns`' answers, the bench's `Pick.choices`) and a Detach button, neither focusable,
  since a focused control claims Space (FN-11). Choosing (`Choose`) or `--attach=<name|pid:n>` (a `@Setting` on the
  bench, waited for until that run is running) makes a run **chosen**: the bench no longer attaches to the newest by
  itself, and a chosen run that goes away keeps its tape on screen until another is chosen (ruled). Detaching
  (`Leave`) stops the link with `Detach`, the link source's declared stop, keeps the tape, and counts as a choice of
  none. A found run that goes away is still replaced by the newest, as before. Seen with `ottermate` against two
  background Inspectors: attached to the newer by itself; the picker listed both by pid, choosing the older attached
  to it and Space still paused after; Detach said so, kept the lanes and emptied the picker; `--attach=pid:<older>`
  attached to the older at launch. Found on the way: `List.contains(null)` throws on an immutable list, so the view
  fell over on the first run panel with nothing attached (held by `ViewTest` now).
  **Slice 5, launch and replay, done 2026-11-02, as proposed to and ruled by the author that day:**
  `--replay=<csv>` plays a probe recording back through `RecordingRows`, a source keyed by the recording's run
  (`replay:<file>`) and stopped by the same `Detach` as the link: its rows move onto the machine's clock as of the
  playback's start and cross as the link's `Rows`, batched every 10 ms at most, so the bench folds, keeps, pauses and
  rewinds a recording exactly as a running application, and its end is a `Detached` ("the recording ended") that keeps
  the tape. Columns are found by the header, as `csvview` finds them. `--launch="<command>"` starts the command through
  `Child`, one source rather than design section 8's launch job, stop job and output source (ruled): it sets
  `VEXELRAY_AUTOMATION=0`, logs the command's output, sends `Choose` once a discovery file names the process or a
  descendant (a `mvn` wrapper's JVM is a grandchild), and ends the process tree when stopped, which the framework does
  to every source at shutdown. Its end is `ChildExited`, said in the status line while nothing is attached. The command
  is one value because no option takes the rest of the line (FN-13). Seen with `ottermate`: a 13.7 s, 262,758-row
  recording of an Inspector played in about as long, its lanes and readouts as live (57 draws a second, 95 ticks) and
  its clock time the recording's own, then stayed to step through; `--launch="mvn.cmd -o -q exec:exec -pl
  vexelray-inspector-gui"`, with the Inspector started by `java`, said Launching, attached to the JVM four seconds
  later as `launched`, and when the Inspector's window was closed (not killed, which `ottermate` does to its tree) the
  wrapper and the JVM ended with it. A recording has no pass numbers (the CSV has no `frame` column), so its passes are
  unnumbered.
  **Measured 2026-11-02, closing R1.** *The bench alone* (`BenchMeasureTest`, a 13.7 s recording of an Inspector,
  19,237 rows a second, looped for 90 s and published a 10 ms batch at a time onto a tick): a batch folds in p50
  0.41 ms rising to 0.78 ms as the tape fills, p95 under 1 ms, max about 6.5 ms; but it **allocates 3.2 KB a row at
  first and 10 KB once the tape is full**, about 190 MB a second, because the fold copies every kept lane on each batch;
  a full minute of tape (1.16 million rows, 55,868 kept events) holds about 145 MB, so the 5 min reach section 8
  offered would need about 700 MB as rows are kept today. *Live*, an Inspector replaying a 96 s loop of that recording
  watched by another: its logic ran 366 to 457 ticks a second at p50 0.9 to 1.1 ms, p95 2.3 to 4.3 ms, and kept up;
  once its tape was full its own draws fell from 55 to 29 a second, with 101 passes a second holding a draw for the
  GPU. The watcher read it over the link at 37,000 to 46,000 rows a second, **a batch arriving p50 0.5 to 0.6 ms, p95
  1.8 to 6 ms, max 15 ms behind its newest row** (`LinkRows.Lag`, logged at debug every 10 s), no row lost; but its
  bench fell behind at that rate, its newest row 6 s old after a minute. So 20,000 rows a second holds, with the
  allocation its cost, and twice that does not. Its working set was 2.8 GB, the heap grown by the allocation. All five
  are TODO entries for after R1: the fold first.
  **The fold made to add rather than copy, 2026-11-02.** A profile of the same measurement put half the allocation in
  copying each changed lane (an `ArrayList` copy, then `Lane`'s own `List.copyOf`), a quarter in copying the map of
  every cause's path and its hops, and an eighth in copying the tape's newest second. The lanes and a second's rows are
  now an `Appended`: an immutable list over a store it shares with the list it was made from, added to in place by the
  list that ends where the store does, with a short tail of its own where a pass is changed as the rows after it say
  whether it drew. A path is no longer kept by the fold but read when an event's details are said, from the seconds of
  the tape whose filter of causes (4,096 bits, two a cause) may hold its cause. Measured again: **about 295 bytes a row,
  flat as the tape fills** (from 3.2 to 10 KB), a batch in p50 0.04 to 0.07 ms, p95 0.05 to 0.3 ms (from 0.41 to
  0.78 ms), the same 54,202 events kept at 70 s, and a minute of tape holding about 109 MB (from 145). A path reads in
  5 to 12 ms (92 ms over every row of the tape, 44 ms with each cause's hash compared first, before the filter).
  **Watched live again**, an Inspector replaying the 96 s loop watched by another at 31,000 to 48,000 rows a second:
  the watcher kept up throughout, its newest row within a few hundred ms of the clock past the tape's first minute
  (6 s behind before), no row lost; the replaying one drew 49 to 55 times a second after its tape was full (29
  before), so the fall in draws was the allocation's. The link's p50 was 0.6 to 0.8 ms and p95 under 7 ms, but its
  slowest batch grew from 11 ms to 101 ms over the run, a TODO entry.
  **Inspected W1 to W3 in both modes, 2026-11-02**, each launched by `--launch` from the witness projects the
  acceptance profile generates, driven by `ottermate` on its own buttons while the Inspector watched. *W3 (long run)*,
  running: 57 draws a second under the ceiling and 139 passes a second held for it, logic ticks on `vexel-logic` at p50
  0.06 ms (phase 7's 56.8 a second and "about 200 passes a second against 57 draws", seen again); under
  `--logic=main`, 59 draws and 132 held, and no tick, since a tick on the main thread cannot be told from the widgets'
  (FN-8), which the run panel says. *W2 (components)*: work, echo, tap and counts as bursts of ticks, messages, draws and
  GPU waits lined up band by band, 55 draws a second through a burst; under `--logic=main`, the same without the Logic
  lane. *W1 (windows)*: the notes window and the palette opened, the same in both modes. **Found: an injected click was
  not on the Input lane.** Automation's `Cursor` writes `pointer.press`, `pointer.release`, `pointer.scroll` and
  `pointer.move`, not only the key verbs the lane read, so a click looked like no input at all; now they are injected
  input too (held by `BenchTest`), and the next run showed the clicks. Also found: the Drawing lane is every window's
  draws together, so W1's windows cannot be told apart.
  **R1 done 2026-11-02.** Its retrospective is `docs/retrospective.md`'s first section.
- **R2, the declared graph.** Bus graph version 2 upstream; the graph and flows panels from the declaration, written
  and delivered.
  **Built 2026-11-02, in four slices.** *Upstream:* the processor writes bus graph version 2 ([bus-graph.md](bus-graph.md)):
  a part line per component, view, job, source, destination and sender with its real lane and whether it takes the
  timer, a hears line per way of hearing (a mailbox, an inbox's `@Hears`, a job's request, a source's start and stop, a
  destination's), a sends line per send, and a topic line per channel with its kind, read off the topic's constant when
  the wiring loads; vexelray-gui's `BusGraph` reads only version 2; W2's acceptance test checks the new lines.
  *The graph read:* `LinkRows` asks the run for it once and adds it as a row (`Model.GRAPH`), so it is on the tape and a
  recording has none. *Counted:* written from `send` rows (a component's, naming it) and publish rows (from outside a
  tick, naming a thread, put to the sender whose thread it is, FN-14), delivered from `receive` rows; what reaches a
  job, a source or a destination writes no row and says no number (FN-15). The bench says `Flows`, a state topic, with
  the timeline, as of the cursor, the rate from the tape's checkpoint a second or two back. *Flows panel:* a table a
  topic beside the graph, its cells changed in place. *Graph panel:* the parts in tiers from outside in (the framework,
  sources, jobs, senders, components, views), an arrow a pair of parts, faint until its sender writes, then as thick as
  the log of what it wrote and named with its topics. The bench allocates about 410 bytes a row with the counts (295
  before), flat.
  **Inspected W2 in both modes, 2026-11-02**, launched by `--launch` and driven by its own buttons: six clicks were six
  `Button.Pressed` from the framework to `Shown#pressed`; three counts, three `Bump` to `Counter` and three `Counted`
  back; one work and one echo each a `Job`, `Worked`, `Ping` and `Echoed`; `Wedge`, `Reset` and `Stalled` declared and
  faint at 0. The same under `--logic=main`, where the Logic lane is empty (FN-8): the counts come from rows on every
  thread. **R2 done 2026-11-02.** Its retrospective is `docs/retrospective.md`'s R2 section.
- **R3, a click's path.** The cause kept, ticks and draws numbered, draws naming their causes, upstream (9.3);
  select an input, mark its path in every panel.
  **Built 2026-11-02, in four slices as proposed to and ruled by the author that day.** *Upstream:* atchung's `Tick`
  writes `tick.ran #<n> <rounds> rounds <delivered> delivered [carried]` after its zone (`2d244f7`); vexelray-gui's
  window writes `draw.ran #<n> [<cause> ...] [+<more>]` after `emit canvas`, the causes being those of the tree's
  edits that changed what it drew, kept by the reconciler while the layout lane is traced, at most 16 (`c862fef`). The
  cause itself had been kept since R1 (FN-12). *Read:* a tick's row numbers its Logic mark, a draw's its Drawing mark
  and its pass; a path places each hop in the numbered tick it fell in, ends with every draw that names its cause,
  and says the time from each hop to the next; the link asks for the `draw` lane, and the tape's filter of causes
  takes a draw's. Ticks on `main` are not read, since the logic's and the widgets' both number themselves there
  (FN-16). *Marked:* the bench says `Traced`, a state topic, from the same hops as the Path, read once a say: the
  cause, the passes, ticks and draws it went through, the topics it crossed; the lanes draw those events over theirs,
  the graph its arrows, named though faint, the flows table its topics' names, all in the theme's success colour,
  apart from the accent everything else is drawn in. The details panel keeps a height of its own and scrolls, so a
  path of thirty hops leaves the graph and flows in view.
  **Inspected 2026-11-02**, W2 regenerated from vexelray-framework's HEAD into this repository's `target/` (another
  session had cleaned the framework's own) and launched by `--launch`, driven by its buttons: the last of three
  counts said, after the pointer's travel, *published Button.Pressed on main → reached Button.Pressed at
  Shown#pressed → published Bump → reached Bump at Counter#bump in tick #5 → sent Counted in tick #5 → reached
  Counted at Shown#counted → 2 changes to the GUI (2 relaid out) → shown by draw #49*, 3.6 ms from the press to the
  draw; the framework→Shown, Shown→Counter and Counter→Shown arrows and the three topics marked, and the pass, the
  messages and the draw on the lanes. Under `--logic=main` the same path to draw #39 in about 4.0 ms, naming no tick.
  An Inspector watching another said its Space the same way: `PauseKey` → `Pause` at `Bench` in tick #6304 → six
  sends → the view → 66 changes → a draw. **R3 done 2026-11-02.** Its retrospective is `docs/retrospective.md`'s R3
  section.
- **R4, the rows panel**, and stepping the cursor row by row, tick by tick and draw by draw. *Built:* the bench says
  `RowsAround` with every say, eleven rows in order of time and then sequence (`Tape.ORDER`): paused, about the
  cursor's row, the newest at or before it (on an event, the row that ended it), read from its second and the ones
  either side, since a late thread's row is kept where it arrived; following, the newest of the last 256 to arrive.
  The panel stands beside the details, as tall, each row's time from the cursor's, the cursor's in the accent and the
  path's in the path's colour. Up and Down step the cursor a row (`StepRow`), a click on one goes to it (`GoToRow`),
  within the island; on a row the cursor is on no event, the details say the row and its thread, and Left and Right
  step the focused lane from there. Ticks and draws were already stepped by focusing their lanes (event stepping,
  R1), and say their numbers since R3. The table never takes the focus (FN-11).
  **Inspected 2026-11-02** on W2, launched by `--launch` and counted three times: on its own lane, the newest tick
  (#3, 0.13 ms) and two rows on, `tick delivered` among the logic thread's `receive`, `send`, `tick` and `tick.ran` rows
  and the main thread's `tree update` and `draw held for the ceiling`, in the order they happened, which no lane
  shows; the newest draw (#53) is its `present frame` row, its `draw.ran` and `emit canvas` before it. A click on a
  row put the cursor there; Left from it stepped onto the pass before; Space after a click still followed. Under
  `--logic=main` the same steps by rows and draws, and the Logic lane empty (FN-16). **R4 done 2026-11-02.** Its
  retrospective is `docs/retrospective.md`'s R4 section.
- **R5, the ledger per clock**, with recording measured on its own. *Built:* the bench says `LedgerAt` with every
  say, an `Account` a clock (`ClockLedger`): the main thread's pass, the logic lane's tick and drawing's draw the cursor
  is in, each from its own zone's start to the next one's, cut by its thread's rows into working, waiting (the GPU's
  waits, counted once, as the innermost span), resting (`wait for events`; for drawing, the time between draws, by
  rule, as its note says) and unaccounted, never shared out; recording (`emit canvas`) on its own; the main thread's
  wakes and how long the first that came while it rested took to run. A turn not ended in the rows read gives the one
  before, and says so. The logic lane's thread parks between ticks with no row (FN-17, new), so a tick's turn is its
  zone and then unaccounted, never rest, and one over its 8 ms placeholder says so; under `--logic=main` the line says
  its ticks cannot be told from the widgets' (FN-8); a job writes no rows (FN-15) and its line says that. The threads
  table stands under the details and the rows, its places changed in place, never focused. A draw carries its
  recording as a field (`Recording`), and the readouts its p50 and p95 over the second. Paused, the ledger reads the
  rows within a second of the cursor; following, on every batch, only the rows it takes (`ClockLedger.reads`), back
  from the newest a window that widens from 50 ms until the main thread's and drawing's turns are whole (`Tape.since`).
  **Measured 2026-11-02.** *The bench alone* (`BenchMeasureTest`, the 13.7 s recording looped for 70 s): slices 1 and
  2 as first built, rereading two seconds of rows on every batch for the ledger and the readouts, put a batch at p50
  1.45 ms and 2.7 KB a row; as built, p50 0.07 ms, p95 about 0.5 ms, max 3.5 ms, 640 bytes a row, against R4's last
  commit measured the same way, p50 0.06 ms, p95 0.11 ms, 440 bytes. *Recording, live*, an Inspector replaying the 96 s
  loop watched by another, which read it at 70,000 to 80,000 rows a second and kept up (its cursor's clock within a
  second of the wall's at 50 s in): the watched one drew 46 to 50 times a second, a draw working 2.8 to 3.6 ms, of
  which **recording took p50 1.8 to 2.3 ms, p95 2.4 to 4 ms**, about two thirds of the draw and a tenth of the main
  thread's time; its main thread worked 99% of every pass and never rested, passes 6.5 to 10 ms back to back, each
  woken once, so it is the view's work around the draw, not the draw, that fills the thread. On W2, launched and
  counted three times: pass #202 16.47 ms, working 2.53, waiting on the GPU 0.04, resting 13.72, unaccounted 0.18,
  recording 0.50; draw #48 1.74 ms of work and wait, 15.11 ms between draws; tick #2 0.07 ms and 167 ms unaccounted
  after it; idle, 200 ms passes resting 99%. Under `--logic=main` the same, the logic line saying why it is empty.
  **R5 done 2026-11-02.** Its retrospective is `docs/retrospective.md`'s R5 section.
- **R6, replay**: run the same inputs again and show which widget writes differ.
  **Done 2026-11-02, as proposed to and ruled by the author that day:** in-process, from launch, the framework's part
  first. *Upstream:* atchung's `Tick.Recorder` (`2581268`) is told each message a run takes in, by its inbox's place and
  its subscription's, with its chain run; each send of the tick's components as the outboxes empty; and where each
  run stopped, so `restage` and `runFor(rounds)` run a recorded tick again on a fresh set of the same inboxes and stop
  at the same round whatever the time. vexelray-framework (`8145095`): a run launched `--replayable` (or with
  `VEXELRAY_REPLAYABLE`) keeps that from its first tick, each send as a fingerprint (its `hashCode`, which compares
  within the process), bounded at 2^18 messages either way; `replay` builds the components again from
  `Wiring.again()` (the processor generates it) on a shell of their own, its CONFIG and MODEL only, with a silent
  timer and no jobs or sources, whose answers are among what arrived, maps chain runs by the sends that carry them,
  and answers `ok replay ticks= sends= differ=` and a line for each of the first 16 differences, or why it cannot. A
  verb of vexelray-gui's port (`74f3c4f`), declared by the framework's driver as the bus graph is. *Here* (`1ec11cb`):
  **Run again** beside Detach; `Rerun`, a component apart from the bench, which keeps which run is attached and its
  link from the run panel, so the bench's state did not change; `AskReplay`, a job with a connection of its own; the
  replay panel over the graph, hidden until the button is pressed, its table only when something differs, and a
  difference clicked going to its tick (`GoToTick`, the bench's one new step). A launched run is always replayable.
  *Seen:* W2, seven clicks (a job and a declared lane among them), `ok replay ticks=6 sends=6 differ=0` in both logic
  modes on its port, and the same through Run again; an Inspector replaying the 13.7 s recording (262,758 rows), run
  again from the one watching it: 4,442 ticks and 68,387 sends, all the same, answered in about 5 s. A difference was
  seen only in tests (`ReplayTest`, a count kept outside the components: `#1 0 test.replay.answered ... a #4`, and the
  panel's cells in `ViewTest`), since no run to hand differs. *Measured:* recording costs the watched Inspector's
  ticks p50 0.7 to 1.2 ms and p95 2.3 to 5.2 ms, against 0.40 to 0.45 and 1.1 to 1.4 without, growing with its state:
  the fingerprint walks each large state record as it is sent. W2's ticks do not notice. *Not done:* a difference's
  tick cannot be gone to under `--logic=main`, where ticks are unnumbered (FN-16); the recorded send's value is not
  kept, only its fingerprint, so a difference shows what the replay sent; the rest is the framework's TODO.
  **R6 done 2026-11-02.** Its retrospective is `docs/retrospective.md`'s R6 section.
- **R7, every thread accounts for itself**: rows from the framework's own threads, then what they let the Inspector
  show. **Done 2026-11-02, as proposed to and ruled by the author that day.** *Slice 1, the logic lane:* atchung's
  `Tick.named` (`480953a`) puts a tick's name after its number in `tick.ran`, and the framework names the logic's
  `logic`; its thread parks inside an idle zone, `logic park`, and the arrival that ends a park writes one `wake`,
  `message on logic` (vexelray-framework `e4e4f55`). Here (`397f9b2`): the threads panel's logic line has its rest,
  wakes and how long a wake took to run the next tick, as the main thread's has, and a run whose park writes no row
  keeps FN-17's note; under `--logic=main` the main thread's tick zone is held (`Model.mainTick`) until the `tick.ran`
  after it says whose it is, so the Logic lane, its numbers, a path's ticks and Run again's differences are the
  logic's in both modes, and the run panel says the logic is on the main thread. FN-8, FN-16 and FN-17 are fixed.
  *Slice 2, jobs and timers:* a job's request writes a `receive` row and its run a `job <name>` zone, and a timer a
  `timer` row naming its topic as it fires (vexelray-framework `0cbb1f4`; `-core`'s `Timer` imports `sibarum.probe`,
  which `atchung-core` brings). Here (`448ead2`): a **Jobs** lane under GPU, a job's run a mark named for it; the jobs
  line, the job the cursor is in, working for its zone and resting until the next, by rule as drawing's is; a timer a
  moment on the Input lane; and what reaches a job counted in the flows table, in a run whose jobs write rows. FN-15
  is fixed for jobs. *Seen:* W2 in both modes, the logic line resting 292.00 of a 292.43 ms tick, its one wake run in
  0.07 ms, and under `--logic=main` its ticks numbered on the Logic lane; an Inspector watched by another, `ListRuns`
  on the Jobs lane every 2 s with its timer on Input, the jobs line 3.06 ms working and 1,997.99 ms resting, FindRuns
  delivered to `ListRuns` 8 times. *Measured:* the bench, p50 0.07 ms and p95 0.55 ms a batch as before, 670 bytes a row
  against 610, the seventh lane; a watched Inspector's ticks p50 0.41 to 0.49 ms and p95 1.05 to 1.54 ms, as without
  the rows. *Not done:* the Jobs lane is the job's run, not request to reply; a source's and a destination's ends,
  `Crew`'s rows, and the job lane's rest by row (`TODO.md`).
  **R7 done 2026-11-02.** Its retrospective is `docs/retrospective.md`'s R7 section.
- **R8, replay finished**: recording that costs a run nothing, and a difference that says what the run sent. **Done
  2026-11-03, as proposed to and ruled by the author that day**, the choice between keeping sends and fingerprinting
  them behind the tick made by the author on these numbers, from an Inspector replaying the 13.7 s recording and
  watched by another: not recording, ticks p50 0.46 to 0.52 ms, p95 1.3 to 1.6 ms, 160 MB after a full GC; hashing on
  the tick (R6), 0.8 to 1.6 ms rising, 2.6 to 7.6 ms, 165 MB; keeping every send, 0.50 to 0.61 ms, 1.3 to 1.7 ms,
  331 MB. *Ruled:* fingerprint behind the tick by default, keep the sends when asked. *Upstream* (vexelray-framework
  `d4b910b`): the tick keeps each send, a thread of the recording's own (`replay-hasher`) fingerprints each whole
  tick's behind it and lets them go, in chunks that never move so the two never share an array; measured 0.48 to
  0.63 ms, 1.4 to 2.0 ms, 167 MB. `--replayable=values` keeps the sends, and a difference says `  now ...` and
  `  was ...` under its line; measured 0.53 to 0.98 ms, 1.5 to 3.0 ms, 199 MB. A replay runs on a thread of its own,
  `vexel-component-replay` (`c305588`), found needed when one replay counted its components' 56 deliveries as the
  run's. *Here* (`17c5d47`): `Rerun` reads the two lines (and R6's one-line answer); the replay panel's columns are
  *Sent by the run* and *Sent when run again*, the first saying the fingerprint when the run kept no values; the
  replay's own rows are left out as they cross. *Seen:* a scratch app generated from the template with a component
  that keeps its count outside itself, launched `--replayable=values` and attached to: `Drifted[beats=1]` sent by the
  run, `Drifted[beats=48]` when run again, and its timer's topic written and delivered 21 times each after two
  replays; W2 `differ=0` in both modes. *Not done:* the processor's warning on a type a replay cannot compare,
  deferred while another session had the processor open (the framework's TODO).
  **R8 done 2026-11-03.** Its retrospective is `docs/retrospective.md`'s R8 section.
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
