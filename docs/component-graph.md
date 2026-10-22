# The component graph

The Inspector's main view is a graph of the application's parts, with the messages between them shown as they
flow. It answers the question the rest of the Inspector does not: *what is going on in my app?* Which part sent what to
whom, how often, and how many so far. Proposed 2026-10-21; it replaces the bus circuit
([bus-circuit.md](bus-circuit.md)) as the bus view.

## The parts

Each node is a part of the application that sends or receives messages. The set below is where the graph starts, not
a closed list: a part can be added, split or merged when the stack can tell it apart.

| Part | What it is | Shown as |
| --- | --- | --- |
| **Host OS** | everything the operating system tells the application: window resize, close, focus, minimise and maximise, display scale, STDIN | not on the bus yet (FN-9) |
| **Graphics** | everything from Vulkan and the presenters: presented frames, swapchain rebuilds, device loss, compute completion | not on the bus yet (FN-10) |
| **Input devices** | tactroller: keyboard, mouse, touchpad, pad | live |
| **Automation** | input injected through the automation port | live |
| **GUI subsystem** | the framework's listeners and senders on the GUI thread: the input dispatcher, the reconciler, navigation, focus, timers that write to the tree | live |
| **App logic** | what the application defines: its handlers, and its code on the main thread | live |
| **Components** | each `@Component` lane: a thread and its mailboxes. One node per lane, named for the components on it | live, with its real mailboxes |

## What moves, and what it means

- **An arrow is a part sending to another part.** It appears the first time a message crosses it and stays for the
  rest of the run, dim when quiet. Its brightness is the rate over the last second or so.
- **A dot on an arrow is a message**, crossing in 0.7 s of wall time. Several messages close together ride on one
  dot, which grows with how many it carries. The movement runs on the show clock, as everything else does: a burst
  is stretched so it can be followed, and the view is never more than 400 ms behind.
- **The flow table under the graph names every flow**: from, to, topic, the rate now, and the total this run. A row
  appears in the order the flow was first seen, and never moves or disappears during a run. A rate fades over about
  a second instead of dropping to nothing, and the total stays. So a storm leaves its record on screen after it has
  passed: which topics, between which parts, and how many.
- **A message within a part** (the GUI's caret timer writing to the tree, say) is a row in the table with the same
  part as both ends, and no arrow.
- **A dashed arrow is a path that exists and does not reach the bus**: Host OS → GUI, Graphics → GUI, and GUI → App
  logic, because the GUI calls handlers directly (FN-11). Each is labelled with the note that would make it real.

## Where each part comes from today

The probe's `bus` lane has one row per publish: the topic is its `kind`, and its `thread` is the publisher's. A named
mailbox (a component's `@Subscribe`) also writes `enqueue <mailbox> <depth>` on the publisher's thread, inside the
publish, so its receivers are known. Nothing else names a sender or a receiver (FN-7, FN-8). So in this first slice:

**The sender is read from the thread and the topic**, first rule that matches:

| Thread | Topic | Sender |
| --- | --- | --- |
| `vexelray-automation*` | any | Automation |
| `vexel-component-<lane>` | any | that lane's component |
| `vexel-handler-*`, `vexelray-gui-handler*` | any | App logic |
| any | `vexelray.engine.*` | Graphics |
| any | `tactroller.input` | Input devices |
| any | `vexelray.*`, `tactroller.*` | GUI subsystem |
| any | anything else | App logic |

Two limits of reading by thread: a mutation the application writes from its own code on the main thread is counted as
the GUI's, and automation is only told from real input by its thread (the message is the same).

**The receivers** are the named mailboxes a publish enqueued to, when there are any. When there are none, they come from
a **stand-in table**, written from a survey of the stack on 2026-10-21. It is replaced by the bus's own list of
subscribers once that exists (FN-7):

| Topic | Received by | Subscribers in the stack |
| --- | --- | --- |
| `tactroller.input` | GUI subsystem | `InputDispatcher` (pumped), `KronoInput` (the timeline) |
| `vexelray.gui.mutations` | GUI subsystem | the reconciler in `Gui` |
| `vexelray.gui.navigate` | GUI subsystem | `Gui`, `GuiApp`, `Popout` |
| `vexelray.gui.clicks` | GUI subsystem | `Select`, `ContextMenu` |
| `vexelray.gui.focus` | GUI subsystem | widgets; some applications too, which the graph cannot show yet |
| `vexelray.gui.keys`, `vexelray.gesture`, `vexelray.engine.*` | nobody | none in main code |
| any other topic | not known | the flow is shown with `?` as its receiver |

A component is one node per lane. With a declared bus graph (bus-circuit.md §1, asked over the link as `bus graph`, or
`--bus.graph=<file>` for a replay), a mailbox is placed on its component's lane and the node is named for the
components on it. Without one, a mailbox's class is its node.

## What the stack has to grow (upstream)

Each item has its entry in [framework-notes.md](framework-notes.md), with what was measured. Together they turn
every dashed arrow solid and remove the stand-in table.

| FN | Repo | Addition | What the graph gains |
| --- | --- | --- | --- |
| FN-7 | atchung | Every subscription has an **owner** (its part, and a name), the bus can **list its subscribers**, and **every delivery** writes a row naming its receiver, inline and async included | Receivers are measured, not looked up; the stand-in table goes |
| FN-8 | atchung, vexelray-framework | A **publish handle per owner**, and a row that names the sender | Senders are measured, not guessed from the thread; automation is told from input by the message |
| FN-9 | vexelray-gui, vexelray-os | **Host topics**, published on change by the window: resized, close requested, focus, window state, display scale, STDIN lines | The Host OS node goes live |
| FN-10 | vexelray-gui, vexelray | **Graphics topics** from `GuiApp` and the presenters: presented, swapchain rebuilt, device lost, and the engine's existing `EngineEvents` on the GUI app's path | The Graphics node goes live |
| FN-11 | vexelray-gui | **A handler call is a delivery**: the dispatcher's hand-off to the handler pool writes a row naming the event and the handler's node | The arrow from GUI to App logic, the most important one, goes live |

## Not in this slice

- Selecting an arrow or a table row to hold its last messages and their payloads.
- Per-window buses: popups and extra windows have buses of their own (roadmap, open question 3).
- The command menu on a flow.
