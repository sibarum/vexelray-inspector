# The bus graph, version 2

What an application declares about its messages, as text: every part, where it runs, every way it hears a topic, what
it sends, and each topic's kind. vexelray-framework's processor writes it into the generated wiring as `BUS_GRAPH`, the
wiring hands it to vexelray-gui's `BusGraph.declare` at startup, and the Inspector reads it once on attaching, with the
`bus graph` query on its link. It is drawn as the graph panel and is the frame of the flows panel ([design.md](design.md)
section 4).

Version 1 was 0.1's (the 0.1 Inspector's `docs/bus-circuit.md`) and is gone: it had no line for a receiver that hears
through an `Inbox`, which on 0.2 is most of them, said `overflow=FAIL` of every mailbox and `lane=<default>` of the logic
lane, and had no topic kinds, no job requests and no sources. `BusGraph.declare` refuses any text that does not begin
with this version's header.

## The text

One line each, `\n` between them, words split by single spaces. The first line is the header; the rest are in four
sections, each sorted, so two builds of the same declarations give the same text.

```
# vexelray bus graph 2
part component dev.sibarum.inspector.core.Bench lane=logic timer
part job dev.sibarum.inspector.core.ListRuns lane=job
part source dev.sibarum.inspector.core.LinkRows lane=own
part view dev.sibarum.inspector.View lane=gui
hears dev.sibarum.inspector.View#timeline topic=dev.sibarum.inspector.core.Messages.Timeline by=mailbox capacity=64
hears dev.sibarum.inspector.core.Bench topic=dev.sibarum.inspector.core.Messages.Rows by=inbox
hears dev.sibarum.inspector.core.LinkRows topic=dev.sibarum.inspector.core.Messages.Attach by=start
hears dev.sibarum.inspector.core.LinkRows topic=dev.sibarum.inspector.core.Messages.Detach by=stop
hears dev.sibarum.inspector.core.ListRuns topic=dev.sibarum.inspector.core.Messages.FindRuns by=request
sends dev.sibarum.inspector.core.Bench topic=dev.sibarum.inspector.core.Messages.Timeline
sends dev.sibarum.inspector.core.ListRuns topic=dev.sibarum.inspector.core.Messages.Runs
topic dev.sibarum.inspector.core.Messages.Rows kind=edge
topic dev.sibarum.inspector.core.Messages.Timeline kind=state
```

**`part <kind> <name> lane=<lane> [timer]`**, one per part.

| Kind | What it is | Its lane |
| --- | --- | --- |
| `component` | a `@Component` | `logic`, or the lane it declares |
| `view` | a `@View` | `gui`, the main thread |
| `job` | a `@Job` | `job` |
| `source` | a `@Source` | `own`: a thread of its own per run |
| `destination` | a `@Destination` | `own` |
| `sender` | a class that sends and that the wiring does not run, a `Ui` or a recipe | `-` |

A lane is named as its threads are (`vexel-logic`, `vexel-job-1`, `main`). `timer` says the component takes the
framework's `Timer`, which then sends some of what it sends, on its own thread.

**`hears <part> topic=<topic> by=<how> ...`**, one per way a part hears a topic.

| `by=` | How | More |
| --- | --- | --- |
| `mailbox` | a `@Subscribe` method; the part is `class#method` | `capacity=<n>`, and `keys=a,b` when it hears only those keys |
| `inbox` | a component's `Inbox`, by what its `@Hears` lists | |
| `request` | a job's request | |
| `start`, `stop` | what starts a source's run, and what stops it | |
| `open`, `write`, `stop` | a destination's | |

**`sends <part> topic=<topic>`**, one per topic a part sends: by a `Sender` it takes, by `@Publishes` (a ledger's steps
send through their `Out`, which no constructor shows), or as a job's or a source's reply.

**`topic <topic> kind=<kind>`**, one per topic the lines above name: `state` (within a tick, the last write per key wins)
or `edge` (every message, in order), with `keyed` after an edge that declares a key. Read from the topic's own constant
when the wiring loads, so a topic declared in a library says its kind too; one whose constant the wiring cannot see is
read from its declaration's source, and is `kind=?` when there is none to read.

A topic is named by its payload's canonical name, which is the name the topic carries at run time and the kind of the
rows that publish it. A topic that some part hears and no line sends is sent by the framework: a widget's
(`Button.Pressed`), the window's, input's or automation's.

## What it does not say

- **Last values.** A state topic's last value is a payload, and rows carry none.
- **The framework's own parts.** Input, automation and the shell's window and dialogs send without a `part` line; the
  Inspector draws them as the framework.
- **Who a timer sends for.** `timer` says a component takes one; what it schedules is among that component's `sends`.
