# The bus circuit: the contract

The Inspector draws an application's message bus as a circuit. Components are chips, each topic is a trace from every
component that publishes it to every mailbox that subscribes to it, and each message is an electron that leaves its
sender and splits at the junctions to reach every receiver. Only what the code declares is drawn: a trace exists
because an `@Publishes` and an `@Subscribe` say so, never because traffic was seen.

Three things have to cross from the application to the Inspector for that, and none does today
(framework-notes.md FN-6). This file fixes their shape, so the framework, atchung, vexelray-gui and the Inspector can
be built at the same time against one description. Proposed 2026-10-19. **Version 1.** A change to anything below
bumps the version on the graph's first line, and the Inspector refuses a version it does not know.

## 1. The graph

The declared message graph of one application, as text: one fact per line, UTF-8, `\n` line ends. A line is a
keyword, then a name, then `key=value` pairs, separated by one or more spaces. No value contains a space; a name is a
fully qualified Java class name, or that and `#` and a method name. Lines starting `#` are comments, except the first.

```
# vexelray bus graph 1
component  dev.example.Worker      lane=worker
component  dev.example.Echo        lane=<default>
publisher  dev.example.Ui          lane=-
mailbox    dev.example.Worker#onJob  topic=witness.job  type=dev.example.Job  overflow=BLOCK  capacity=8
mailbox    dev.example.Echo#onPing   topic=witness.ping type=dev.example.Ping overflow=FAIL   capacity=64
publishes  dev.example.Ui           topic=witness.job
publishes  dev.example.Ui           topic=witness.ping
publishes  dev.example.Worker       topic=witness.ping
```

| Keyword | Name | Pairs | Meaning |
| --- | --- | --- | --- |
| `component` | the class | `lane=` the `@Component`'s lane, `<default>` for the shared one | A chip that runs on its own thread, `vexel-component-<lane>` |
| `publisher` | the class | `lane=-` | A type that publishes and is not a component (a `Ui` on the main thread or a handler). A chip too, which `Channels` drops today |
| `mailbox` | `class#method` | `topic`, `type` (the payload class), `overflow` (`FAIL`, `BLOCK`, `DROP_NEWEST`, `DROP_OLDEST`, `COALESCE_LATEST`, as `Overflow` names them), `capacity` | One `@Subscribe` method: a pin on its component's chip |
| `publishes` | the class | `topic` | One topic named in an `@Publishes`, on the type or on a method of it (the method's type is the name) |

A topic is its name. Two `mailbox` lines with one topic name and different `type`s are two topics, as atchung sees
them, and the Inspector draws two traces. Order is not meaningful; the processor writes components, then publishers,
then mailboxes, then `publishes`, each sorted, so that two builds of the same declarations give the same text.

**What it does not hold:** sends that are not declared, subscribers in another compilation unit, and the framework's
own hand-wired topics (`vexelray.gui.*`, `tactroller.input`, `vexelray.engine.*`). The Inspector draws everything not
in the graph as one **framework** chip at the edge of the circuit.

## 2. Where the graph comes from (vexelray-framework)

The processor already holds this graph in `Channels` to check T4.1, T4.4 and T4.7, and threading.md's T4.5 calls
exposing it "additive and not built". It emits it:

- as a `String` constant in the generated wiring, `BUS_GRAPH`, the text above, so it is in a native image with no
  resource configuration and no reflection;
- handed at startup, by the generated wiring, to `dev.vexelray.gui.automation.BusGraph.declare(String)` when the
  automation module is on the module path, in the same way the build identity reaches `Discovery.build(...)`.

`@Publishes` on a type that is not a `@Component` becomes a `publisher` line. The checks that use `Channels` do not
change.

## 3. Mailbox names and delivery rows (atchung)

**A pumped mailbox has a name**: `class#method` for a declared one. `Pump.subscribe` gains an overload of each of its
three forms with a leading `String mailbox`; the existing forms keep working and leave the mailbox unnamed. The
framework's `Placements.mailbox` passes the name from the generated wiring, which knows it.

**A named mailbox writes three more probe rows** on the `bus` lane, beside the ones it writes today, which do not
change (csvview, the recorder's `Frames` and the Inspector already read them):

| kind | detail | when | thread |
| --- | --- | --- | --- |
| `enqueue` | `<mailbox> <depth>` | a message was queued; the depth after | the publisher's |
| `deliver` | `<mailbox> <count>` | the pump handed the subscriber a batch of this many | the mailbox's lane |
| `drop` | `<mailbox> <policy>` | the mailbox refused a message, and under which policy | the publisher's |

An unnamed mailbox writes none of them. Each row costs what the existing `<topic> depth` row costs, and only while
the probe is on. Strings are built once, at subscribe, as the existing labels are.

`enqueue` is the row that says where a message went, and its thread says who sent it: a component publishes on
`vexel-component-<lane>`, and the graph says which components on that lane declare that topic. When exactly one does,
the sender is known; when several do, the Inspector says "one of" and draws the electron from all of their pins
dimmed. A publish handle per component would make it exact, and is not part of version 1.

## 4. Where the Inspector reads it (vexelray-gui)

`dev.vexelray.gui.automation.BusGraph` holds the one graph of the process: `declare(String)` once at startup,
`text()` to read it, and nothing declared means none. The automation socket gains the read verb `bus graph`, which
answers `ok bus graph lines=<n>` and then the graph's lines, or `err no bus graph: <why>` when nothing was declared.
The Inspector link answers the same verb as a query. `hello`'s `bus` domain stays `missing` until `bus` itself (the
registry and tap) lands; `bus graph` is listed on its own. `ottermate --app <name> bus graph` prints it.

## 5. How the Inspector uses it

On attaching, the Inspector asks `bus graph` once over the link. It lays out the chips and the traces from the graph,
then reads the `bus` lane as it does now:

- a publish span is a message leaving its sender, on its topic;
- an `enqueue` is that message reaching one mailbox's pin, which is where its electron goes;
- a `deliver` drains that pin's charge into its chip;
- a `drop` is a spark at the pin;
- a topic that is not in the graph is the framework chip's.

Until the upstream half lands, it reads a hand-written graph (`samples/witness-graph.txt`, the framework's acceptance
witnesses) and a replay that writes these rows (`samples/witness-bus.csv`).
