# TODO

Work on **VexelRay Inspector** that is known about and not done. Distinct from
[framework-notes.md](framework-notes.md), which is about the framework rather than about this application —
if the fix belongs upstream, it goes there instead. The milestones themselves are in [roadmap.md](roadmap.md).

Keep an entry short enough that it does not need editing, and delete it when it is done rather than ticking it.

## Next (the rest of M1)

- [ ] **Attach by launching**: `--launch <command>`, reading `automation: localhost:<port>` from the child's stdout
      as `ottermate --launch` does (or waiting for its discovery file), attaching to it, and stopping the child (and
      a `mvn` wrapper's grandchild) on close.
- [ ] **The recorder's own cost**, shown from day one (roadmap M1). The link's `Snap` carries the loop and ring
      counters but no cost yet, and `LinkSource` does not read snapshots: both ends need it.
- [ ] **A gap no park covers** is a stall (automation.md §4, *Reading it*). The escapement knows the budget and the
      rest; when a rest outlasts its budget by more than a frame, the wheel should say so rather than just rest.
