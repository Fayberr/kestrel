# Kestrel cancellable event semantics (Stage 0 decision)

Status: DECIDED 2026-09-12. Decided before networking (Stage 3) lands, per
VISION.md Phase A constraint 3, so no plugin hook needs retrofitting during
parallel-tick work. Stage 0 scope only: this doc plus subscriber-bitset
scaffolding in `TickLoop` (no listeners, no plugin API, no loader).

Status update 2026-09-25: still Stage 0 in the sense that matters. Networking
has landed since (live play server, chunk streaming, the single mutation
journal) and G11 fluids and G12 redstone landed inside the engine, but neither
added a hook point: `EventHooks` still carries exactly the two points below,
and the only registrations in the tree are the conformance checks
(`TickLoopCheck`, `EventReentrancyCheck`, `DuplicateScheduleCheck`). No plugin
API, no loader, no listener that ships.

Status update 2026-09-28: unchanged in kind, and the registrant list above was
incomplete. The two hook points are still exactly the ones below and still no
production listener ships, but three further checks register on them:
`BlockUpdateParityCheck`, `PostProcessParityCheck` and `ItemEntityTickCheck`.
The full list is six checks: `TickLoopCheck`, `EventReentrancyCheck`,
`DuplicateScheduleCheck`, `BlockUpdateParityCheck`, `PostProcessParityCheck`
and `ItemEntityTickCheck`. No non-check class calls `hooks().onBlockChange` or
`hooks().onBlockTick` anywhere in the tree.

## Hook points (decided)

The tick thread exposes exactly two hook points in Stage 0, both on the
`TickLoop` seam (core/.../loop). Two, not more: every extra point is a
branch a server without plugins pays for, and VISION.md promises zero cost
when unused.

1. `BlockChange`: fires from `TickLoop.setBlock`, after the store swap and
   shape updates, before any redstone dispatch the write causes. Covers
   corpus builds, power commits, torch/repeater flips (all of them go
   through `setBlock`).
2. `BlockTick`: fires from `TickLoop.tick` at the scheduled-tick drain,
   once per due entry, before `runScheduled` runs the body. Since G12 the loop
   runs a scheduled body for every redstone block event it transcribes, and the
   point covers all of them: torch LIT flips (including burnout and the
   160-tick restart), repeater and comparator ticks, observers, redstone lamps
   and buttons. The facade kind handle (`KestrelTickTarget.Kind`) still names
   only the standing torch and the repeater, so a wall torch or any of the
   later blocks reaches a handler as `OTHER`. Fluid ticks (G11 water/lava, a
   separate queue drained after block ticks as in vanilla) do NOT fire
   `BlockTick`; the writes a fluid tick makes still fire `BlockChange` through
   `setBlock`.

Deferred (named so Stage 1 facade work does not accidentally create them):
`RedstonePower` (per-wire recompute), `BlockEntityTick` (per-sleeper body),
`ChunkLoad/Unload`, `PlayerJoin/Leave`. These arrive with their owning
subsystems (facade, persistence, networking), not before. As of 2026-09-25
persistence and networking have landed and the redstone engine recomputes wires
internally, but none of the four has been created: the per-wire recompute is
engine code with no handler seam, the block-entity pass (the SleepLoop walk and
the moving-piston phase) runs with no hook, and the live server's chunk and
player lifecycle paths register nothing.

## Cancel meaning per point (decided)

Cancel is `cancel()` on a mutable per-fire event object (it only sets a flag;
handlers read it back with `cancelled()`, and the fire method returns it to the
caller that owns the dispatch), pooled
per point and per nesting depth (steady state allocates nothing; a frame is
allocated only the first time a deeper nesting level is reached). A handler
whose own write re-fires the same point gets its own frame, so the outer
event's position, states, flags and cancel survive the inner fire (H9,
gated by `EventReentrancyCheck`). Handlers must not retain the event past
the call. Meaning is point-specific:

- `BlockChange` cancel: the write is already committed to the store. Cancel
  means veto propagation: downstream dispatch the write would have caused
  (redstone recompute, neighbor fan-out, shape-driven wake signals, and
  the G11 fluid side effects: liquid onPlace, neighbor and shape updates
  that schedule fluid ticks) does not run. It does NOT roll back the store: rollback would need a snapshot
  per write, which is step-13 structural-sharing territory, not a Stage 0
  primitive. A plugin that wants deny semantics (protection checks) cancels
  and then reverts via its own compensating `setBlock`, which itself fires
  a new `BlockChange` (no re-entrancy guard: the second fire is a real
  write, and a plugin that cancels its own revert loops loudly instead of
  deadlocking silently).
- `BlockTick` cancel: the scheduled body is skipped for this firing only.
  The entry is still consumed (removed from the queue and index). Cancel
  does NOT reschedule: a plugin that wants delay semantics schedules its
  own tick from the handler. Skipping without consuming would wedge the
  queue head (every future `drainScheduled` would re-fire the same entry).

Non-cancellable by design: the `SleepLoop` pass discipline (beginPass, walk
awake ids in ordinal order, reportIdle post-tick) is parity infrastructure,
not policy. Plugins observe block-entity behavior through `BlockChange`
wake signals, never by reordering the pass.

## Deterministic plugin ordering (decided)

Plugins run on the tick thread in registration order per point
(first-registered fires first). No priorities: priority integers are a
second ordering mechanism that disagrees with the first, and every Carpet
rule conflict they were invented to solve is instead solved here by making
cancel semantics explicit per point (above). Registration order is stable
across restarts given the same plugin load order, so the differential
harness doubles as a plugin test kit: run a corpus with the plugin loaded,
diff the trace against the no-plugin baseline.

Within one fire, handlers run to completion in order; a later handler sees
`cancelled()` from an earlier one but still runs (veto is observable, not
short-circuit). Rationale: short-circuit makes handler N's execution depend
on handler M's decision, which makes traces order-fragile to debug; running
all handlers keeps the fire count equal to the subscriber count always.

## Scaffolding (what Stage 0 adds to TickLoop)

`EventHooks`: one `int` subscriber mask (bit per point, two bits used),
handler lists per point (empty until the plugin loader exists), and the
fire methods. Cost with zero subscribers is one int-mask branch per seam
point and zero allocation (the fire path returns before touching the
handler list or the pooled event frames). The mask is checked, not the
list: an empty list with a set bit is a registration bug, and the mask
check keeps the fast path to a single predictable branch.

Registration (`onBlockChange`, `onBlockTick`) exists so Stage 3 networking
code and the future loader have a seam to call; nothing calls it in Stage
0 except the conformance battery's ordering test, and as of 2026-09-25
networking, chunk streaming and persistence have landed without calling it
either. Handler interfaces take
the loop plus the mutable event, so handlers can read world state and
schedule compensating writes through the same public `TickLoop` path.

## What is explicitly NOT decided here

- The public plugin API shape (package, loader, manifest): Stage 5 at the
  earliest.
- Whether cancel ever means rollback: revisited only if step 13
  (structural sharing) makes snapshots free.
- Priorities, async handlers, off-tick-thread plugins: rejected, not
  deferred. The tick thread owns the world; anything else is a second
  consistency model.

Last audited against the code and the gates: 2026-09-28, main `919ce0d`. The
registrant list was re-derived by grepping every caller of `hooks().onBlockChange`
and `hooks().onBlockTick` in the tree at that commit.
