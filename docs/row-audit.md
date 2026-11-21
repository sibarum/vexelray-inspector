# Row audit: what each kind of row tells you

Every row kind the stack writes, what it says on the dashboard today, and what it could say. Written 2026-11-21, after
"pump drain on main, lane bus" turned out to tell a reader nothing about what happened, where, or why.

**The sample.** The Inspector's own last recording (`vexelray-inspector-gui/target/logs/vexelray-inspector-probe.csv`,
20 November, under automation): **186,807 rows in 19.4 seconds, 130 kinds**. The call sites are the stack's
`Probe.zone`/`count`/`mark` calls, 226 of them.

**How a row reaches you.** The rows panel and the details' Row line show a row bare: thread, lane, kind, detail.
Only about 25 kinds are put into words anywhere: those that become an event (pass, tick, draw, message, job, timer,
input, held draws), a hop of a path (send, receive, publish, a ledger's step, a GUI change, a wake), or a field of a
pass (drew, parked, waited, woken in). The other ~105 kinds are shown as written.

## The headline

**84% of the rows are the GUI tree being changed, and they arrive as three or four separate rows for each change.**

| what | rows | share |
| --- | ---: | ---: |
| a GUI change published (`vexelray.gui.<window>.mutations`, a timed span) | 55,018 | 29% |
| the queue's depth after it (`… mutations depth`, always 1 here) | 55,018 | 29% |
| what the change did when applied (`mutation unchanged` / `layout` / `draw` / …) | 33,182 | 18% |
| `uncaused main` (a publish outside any cause, a count of 1) | 14,184 | 8% |

And **78% of the applied changes changed nothing** (`mutation unchanged`: 25,836 of 33,182; 15,614 of those are the
`SDG` property alone, then `HEIGHT` 3,899, text 2,130, `VISIBLE` 1,368). That is not a labelling problem. It is a
finding about the Inspector's view, or the framework's comparison of `SDG` values, and is listed at the end.

## The kinds, by what it would take

### A. Already said in words (leave alone)

`frame` (pass), `tick` (logic), `present frame` (draw), a message type's publish, `send`, `receive`, `timer`,
`job …`, `edges`, the automation verbs (`key`, `type`, `keydown`, `keyup`), `draw held for the ceiling` / `GPU`,
`draw skipped`, `frame.present`, `frame.update`, `loop.park`, `wait for events`, frame `wake`, `draw.ran`, `tick.ran`,
`mutation layout` / `draw` (as the hop "changed the GUI"), `ledger …`, `cause.source`, `agent.command`, `log.*`.

### B. Fixable in the Inspector, from rows already recorded

| kind (rows) | says today | could say | from |
| --- | --- | --- | --- |
| `pump drain` (1,586) + `pump delivered` (1,586) | 46.2us; 0 | "applied 30 changes to the main window, 25 µs, in pass #812, for clicks c13.2 and c13.3"; or folded away when empty (901 of 1,586 delivered 0) | the `<topic> batch <n>` rows inside the drain name each mailbox it emptied (`vexelray.gui.main.mutations batch 4690`, `vexelray.gui.main.input batch 2`), so they say *which* pump; the `mutation` rows inside carry causes (28,103 of 33,182 have one), which say *why*; the pass number is on the row. `pump delivered` is the sum of the batches: fold it into the drain row |
| `<topic>` + `<topic> depth` + `uncaused <thread>` (≈124,000) | 418.6us; 1; 1 | one row: "main window's GUI changes: published, 1 waiting, outside any cause" | the three are written back to back on one thread by one publish (`Atchung.java:99,108,114`, `PumpedReg.java:284`) |
| `mutation unchanged` / `layout` / `draw` / `geometry` / `missing` (33,182) | SetProp node=75 PADDING_X=null | a summary per drain: "41 changes: 30 did nothing, 8 relaid out, 3 redrew"; the rows themselves behind it | the outcome is the kind; the drain is the span they fall in |
| a pass's phases: `before frame`, `dispatch input`, `tree update`, `flex layout`, `resolve geometry`, `scroll shift`, `emit canvas`, `vertices`, `runs`, `snapshot`, `publish` (input), time `batch`, time `tick`, gpu `queue submit`, `wait fence`, `dxgi wait`, `dxgi copy+present`, `acquire image`, `queue present` (≈10,000) | a duration or a count, each its own row | a breakdown on the pass event: "pass #812, 157 ms: tree update 90 ms, emit canvas 24 ms, present 59 ms, 3,042 vertices" | all on main, all inside the pass's `frame` span, with its pass number |
| `tick rounds`, `tick delivered` (255 each) | 2; 2 | nothing: `tick.ran` says both | fold into the tick |
| `logic park` (178), bus `wake` on a job thread (173, "message on logic") | 11.30ms; message on logic | "logic parked 11.3 ms, woken by a message from job 1" | the wake's thread and the park after it; the Clocks ledger already reads them |
| `wake.post` (215) | nudging the OS message queue | a part of the wake before it ("woke pass #813 for a GUI change, through the OS queue") | the `wake` row before it on main |
| `gate.open` / `gate.woke` (2,425 each), `handoff` (817) | -> shred-0; (empty); 1.24ms | "the clock kernel handed shred-0 its turn; it woke 40 µs later" | pair each `gate.open` with the next `gate.woke` on the thread it names |
| `layout.publish` (649) | v1 nodes=660 | "the window's tree, version 1, 660 nodes, published to readers (automation)" | the kind alone; words only |
| gpu `open` (71) | AtlasTexture #722417467 | paired with its `close`: "atlas texture, open 4.2 s (still open)" | the resource ledger the Inspector already reads |
| `effect@frames`, `invalidate` (anim) | 1.57ms; 1 | which pass the animation ran for | the pass whose span it ends in |
| `state set`, `state status`, `state replace` | 36.8us | at most "a state committed its *set* step" | see C for the rest |

### C. Needs the framework to say more (each a candidate FN entry)

| kind | what is missing | why the Inspector cannot work it out |
| --- | --- | --- |
| `pump drain` | which pump | an empty drain has no batch rows inside it to name it, and the GUI's two pumps drain one after the other on main |
| `deliver`, `enqueue`, `drop` | never written for the GUI's mailboxes | `PumpedReg` writes them only for a *named* mailbox, and the GUI's are unnamed; that is why no `deliver` row is in the sample |
| `mutation …` | `node=75`: which widget | nothing records a node's widget or landmark; only the automation tree knows, and it is not in the recording |
| `<topic>` publish of a GUI change, `uncaused main` | who published it, and for what | 14,184 publishes on main carry no cause; the count names the thread and nothing else |
| `state set` / `status` / `replace` | which state | the zone is named by the committer's name, not the state's (`State.java:106`) |
| frame `wake`, `wake.wired` | which window | `Gui@2ef3eef9` is an identity hash, not a window's name |
| `gate.woke` | anything | its detail is empty |
| `effect@frames` | whose effect | the span is named by the effect's kind only |

## Not a labelling problem

- **Most GUI changes do nothing.** 25,836 of 33,182 applied changes left the node as it was, `SDG` most of all. Either
  the Inspector's view sends every property on every update, or the framework compares two equal `SDG` values as
  different and then finds them the same when applying. Worth its own look: each one is three rows and a trip through
  the reconciler.
- **Where do the other windows' changes go?** The Events, Keymap, Cursor, Replay, Topics, About, Graph and Dialogs
  windows publish about 21,000 GUI changes, but every `batch` row in the sample is the main window's (`main.mutations`,
  `main.input`), and there are only two drains per pass. Either those windows' trees are applied by a path that writes
  no row, or their changes are not being applied while closed. Not checked.
- **The rows panel shows eleven rows and cannot filter** (already in TODO.md): with 84% of rows being GUI changes,
  any other row is rarely in view.

## Suggested order

1. **The pass as a container.** Its phases, its drains and a summary of its GUI changes become one event with a
   breakdown, and the rows behind it stay reachable. This takes the most rows off the dashboard and answers "where".
   *Done 2026-11-21:* a pass's details say **Spent on** (the update, and its drains, layout and input inside it; the
   app's input and clocks) and **GUI changes** (how many, to which window, and what they did), from `PassParts`. The
   rows themselves are still in the rows panel: folding them away is step 2.
2. **One publish, one row.** Fold the publish, its depth and its `uncaused` count into one, and `pump delivered`,
   `tick rounds` and `tick delivered` into what they repeat.
3. **Say the rest in words:** the pairs in B (gate, wake.post, park and wake, open and close).
4. **Write the C rows into `framework-notes.md`** as one FN entry: rows should name what they are about (pump,
   mailbox, node, state, window).
5. **Look into the unchanged GUI changes** separately.
