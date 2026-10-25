# The thread ledger

For each frame, an account of every thread: how long it worked, how long it waited on someone else, how long it
rested, how many times it was woken, and what it was doing when the frame ran out. It answers the question the frame
breakdown (`why`) does not: *the main loop took 9 ms, but what was everyone else doing meanwhile?* Proposed
2026-10-24. It is the data under the roadmap's gear train (M6), and nothing in this document is built yet.

## What it answers

- **Working, waiting and resting in frame N, per thread.** Not one "busy" figure: a thread blocked on the GPU, on
  the timeline or on a lock is held up, not idle, and the difference is usually the answer.
- **Wakes per frame, per thread.** A thread woken forty times in one frame is a storm, even if each wake costs
  nothing.
- **What each thread was doing when the frame ran out**, and for how long it had been doing it. A handler that was
  45 ms into one click when the frame expired is the frame's culprit, whatever the main thread's stages say.
- **The time between being woken and running.** The scheduler's share, which no span on either side can see.

## The ledger

### A turn

A **turn** is one pass of the main loop: from the start of frame N's `frame` span to the start of frame N+1's. It
holds the frame's work and the park after it. Every thread's time is cut to turns **by time, not by frame stamp**:

- The probe writes a span when it ends, with its duration (`Probe.zone`, `Zone.close`). So a span's start is its
  row's time minus its duration, and its stamp is the frame it *ended* in. A 30 ms handler task that ended in frame
  N+2 started in frame N, and belongs to three turns.
- A turn's start is the `frame` span's row time minus its duration. Its end is the next one's start.
- **The deadline** is a second instant inside the turn: the time by which the frame had to be done. The loop does
  not mark one (FN-17). Until it does, the ledger uses the end of the `frame` span (when the loop finished its work)
  and says `deadline not reported (FN-17)`.

### A thread's time

Within a turn, each thread's time is split into four, which add up to the turn:

| State | Means | Comes from |
| --- | --- | --- |
| **working** | inside a span that is not a wait | ordinary spans (`Probe.zone`) |
| **waiting** | blocked on another party: the GPU, the timeline, a lock, a full mailbox | spans named as waits (below) |
| **resting** | parked with nothing to do | idle zones (`Probe.idleZone`), or the gap a thread's own rows say is a park |
| **unaccounted** | between rows, with nothing to say which | the rest. Shown, never shared out among the other three |

A thread with no rows of this kind at all is **not reported**, never "idle". A thread that had not been born, or had
ended, is **absent** for that turn.

Spans nest. Time inside a span inside another one counts once, as the innermost: `present frame` is working, and the
`dxgi wait` inside it is waiting.

### Wakes

A **wake** is a transition from resting or waiting to working that begins inside the turn. Each has a time, the
thread that woke it where a row says so (`gate.open -> X`, a `wake` mark), and its **latency**: from the wake being
asked for to the woken thread's first row.

### At the deadline

For each thread, the span open at the deadline: its kind, how long it had been open, and how long it went on after.
Because spans are written when they end, this is known only once that span has closed (see *Settling*).

## What today's rows give

Surveyed 2026-10-24 against `samples/calculator.csv` and the stack's source:

| Thread | Rows | Working | Waiting | Resting | Wakes |
| --- | --- | --- | --- | --- | --- |
| **`main`** (the loop) | `frame`, `before frame`, `tree update`, `present frame` and the stages inside; `dxgi wait`, `wait fence`; `wait for events` is an idle zone (`GuiApp.java:1988`) | yes | the GPU waits, yes; its wait for the timeline inside `time batch`, no (FN-16) | yes | the end of `wait for events`; what woke it, when a `wake` mark says |
| **`kron-kernel`, `shred-N`** (the timeline; virtual threads) | `gate.open -> X` and `gate.woke` bracket every baton pass (`Gate.java:38,59`); `handoff` and `batch` spans | from each `gate.woke` to the same thread's next `gate.open`, approximately | no | the kernel's rest after a batch is not marked (FN-16) | exactly, with latency: `gate.open` to `gate.woke` |
| **`vexel-component-<lane>`** | `pump drain` spans; the park between them is deliberately outside the span (`Pump.java:183`) | yes | a `BLOCK` publish, no (FN-16) | the gap between two drains, by rule rather than by row (FN-16) | each drain after a park; a park that timed out is a wake with `pump delivered 0` |
| **`vexel-handler-N`** | only what its handlers publish | no (FN-15) | no | no | no |
| **`vexel-offload-N`** | nothing | no (FN-15) | no | no | no |
| the stack's own threads (`vexelray-automation`, `vexelray-caret-blink`, elektro-Q's receivers) | what they publish, and `wake` marks | no | no | no | partly, from `wake` marks |
| native threads (GC, JIT, Vulkan and DXGI driver threads) | nothing | no (FN-18) | | | |

None of it says whether a thread was **on a CPU**. Working means inside a span, which includes being preempted and
page faults. That is FN-18.

### Worked example: frame 16 of the calculator sample

From `samples/calculator.csv`, rows 4026 to 4058, cut by time:

- **The turn** runs from 1809.494 ms (the `frame` span of 3.32 ms, ending at 1812.814) to 1815.342 (the next one's
  start): 5.85 ms.
- **`main`**: 3.32 ms in the frame, 2.42 ms resting (`wait for events`, on a 3 ms budget), 0.11 ms unaccounted
  either side of the park. Inside the frame, 0.10 ms waiting on the GPU (`dxgi wait` 23 µs, `wait fence` 80 µs), and
  `time batch` of 366 µs, most of which `main` spent waiting for the timeline, which no row says (FN-16).
- **The timeline**, inside that batch: 3 wakes. `main` → `kron-kernel` took 91 µs to land, `kron-kernel` →
  `shred-0` 52 µs, and `shred-0` → `kron-kernel` 29 µs. `kron-kernel` worked 94 µs and then at least 27, and `shred-0`
  worked 34. So of 366 µs, **about 155 µs was the timeline working and 172 µs was the scheduler handing the baton
  on**. Being virtual threads, their wake latency is a carrier's.
- **`vexelray-caret-blink`** published its blink mutation at 1809.458 ms, 36 µs before the turn began. That counts
  against turn 15, which it woke.
- **`vexel-handler-4`**: not reported. It did nothing in this turn that published, which is all the rows can say.

## Settling

A turn's figures are not final when the turn ends, because a span that is still open has not written its row. So:

- **A turn is provisional** until every thread that might owe it time has written a row ending after the turn, or a
  settling horizon has passed. The horizon is the stall threshold the escapement already uses (`gaps`' 100 ms past
  the park's budget). A thread still silent after that is shown as unaccounted from its last row, and marked `open`.
- **A span that closes later rewrites the turns it covered**, back to the horizon. The live view shows settled turns.
  Paused, any turn is settled or says it is not.
- **Lost rows** (the stream's `# lost`) make every turn they touch say so, as the escapement does: nothing is measured
  across a gap.

## The port verb first

The roadmap's rule is agent first: whatever the Inspector draws, an agent can ask with a fixed reply shape, and from
M3 on each milestone lands its verb first. flight-recorder.md lists `threads` (every native thread: lane, busy,
parked or stalled, CPU over the span) and nobody has built it. The ledger is its per-frame form (FN-19):

```
$ ottermate threads --frame 16
ok frame=16 turn=+1.809s..+1.815s turn_us=5848 deadline=- over=+1.412s..+6.3s lost=0 settled=yes
thread        state   work_us  wait_us  rest_us  unacc_us  wakes  wake_lat_us  at_deadline
main          ok      2851     469      2420     110       1      -            -
kron-kernel   ok      121      -        -        -         2      91,29        -
shred-0       ok      34       -        -        -         1      52           -
vexel-handler-4  not reported (FN-15)
unknown  deadline (FN-17); cpu (FN-18); main's wait for the timeline (FN-16)
```

`main`'s `wait_us` in this sketch is the GPU waits (103 µs) plus the whole `time batch` (366 µs), counted as waiting
once FN-16 makes the batch a wait. Today the same row would put the batch under `work_us` (3,217 and 103), and say
why in the `unknown` line.

**Where the fold lives** is open. Computed twice, by the recorder for the verb and by the Inspector from its stream,
the two can disagree, which the roadmap promises they never do. One pure function over rows, in a module both
depend on, keeps them one answer. That is a question for vexelray-gui (`vexelray-gui-recorder`'s `Frames` is the
precedent) and is part of FN-19.

## On screen

A first slice, inside what the Inspector already has:

- **Paused, the ledger of the frame under the tape's cursor**: one line per thread, a bar split into working,
  waiting, resting and unaccounted against the turn's width, the wakes as ticks on it, and the deadline as a ruled
  line, with what each thread was in at that line. Ctrl+Left/Right steps it frame by frame, as it steps the cursor.
- **Live, nothing new moves.** The gear train (roadmap: a gear turns while its thread works, stands still while it
  rests, red teeth when it stalls) is the live form, and comes after the paused one has been seen to be right.
- **A thread that is not reported says why**, with its FN, in place of its bar, and never as an empty bar, which
  would read as idle.

## What the stack has to grow (upstream)

Each item has its entry in [framework-notes.md](framework-notes.md), with what was measured.

| FN | Repo | Addition | What the ledger gains |
| --- | --- | --- | --- |
| FN-15 | vexelray-framework, atchung | The **handler and offload pools** write a span per task, named for what was handed over, and a row when a pool thread starts and ends | The pool threads go from not reported to measured |
| FN-16 | atchung, kronometer, vexelray-gui | **Every wait is a row**, and a wait on another party is told from a rest: the host waiting for a timeline batch, a pump's park, a `BLOCK` publisher, `State.await`, the kernel's rest | Waiting is measured, not unaccounted; main's frame is split into its own work and the timeline's |
| FN-17 | vexelray-gui | **The frame's deadline**, marked at its start | "At the deadline" becomes the deadline |
| FN-18 | vexelray-os, vexelray-gui | **Cycles per thread at each frame boundary**, every native thread included | On-CPU is told from preempted, and the GC, JIT and driver threads appear |
| FN-19 | vexelray-gui | **The `threads` verb**, per frame, and the fold behind it in one place | An agent can ask it, and the Inspector may then draw it |

## Cost

- **Rows.** FN-15 adds two rows per handler and offload task; FN-16 one per wait. A handler storm doubles its rows.
  The `time` lane is already the loudest per frame (six baton rows a tick in the sample). The stream would carry
  `frame`, `bus`, `time`, `gpu` and the new rows instead of `frame` and `bus`; measure it against the 20,000 rows a
  second the tape is sized for before switching it on by default.
- **In the target.** A span is a thread-local push and a row when tracing; nothing new when the probe is off
  (`Probe.ON` is a static final). FN-18's reads are downcalls, and belong on a thread of their own, never in the
  frame loop.
- **In the Inspector.** The fold keeps fixed arrays per thread slot (as `Tape` keeps its table of names) and a ring of
  turns back to the settling horizon. It runs on the source's thread, like the tape; nothing in the frame loop.

## Not in this slice

- The gear train itself (above).
- CPU per thread over a span rather than per frame: that is meter.md's `meter`, and comes with FN-18.
- Locks by name: a wait on a monitor says it waited, not on which lock.
- Threads of more than one process: a target's helper processes.
