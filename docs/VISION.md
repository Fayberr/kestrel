# Kestrel Vision: performance, plugins, content, distribution

Status: DIRECTION (2026-09-12). None of this is built; it constrains how
Phase A (tick loop) is designed so nothing below needs a rewrite later.

Status update (2026-09-25), so the constraint list below is not misread: the
tick loop, storage, persistence and networking have all landed since this was
written, and cancellable-event semantics now exist in `EventHooks` (see
`docs/EVENTS.md` and the H9 gate, `EventReentrancyCheck`). The plugin API, the
content loader and distribution remain unbuilt; nothing in this document has
been implemented.

## The promise

A 26.x Java server that behaves exactly like vanilla (parity-exact,
including transient/chaotic behavior) while using a fraction of the memory
and running several times faster, with zero performance cost for features
a server does not use.

## Plugins: Kestrel-native API, zero cost when unused

- No Fabric/Forge mod compatibility: Fabric mods rewrite vanilla
  internals Kestrel intentionally does not have. Chasing compat would
  swallow the project and reintroduce the overhead being removed.
- Instead: a Kestrel-native plugin API designed for zero cost. Every hook
  checks a subscriber bitset first; no listener means no allocation, one
  branch. Plugins run on the tick thread in deterministic order, so the
  differential harness doubles as a plugin test kit (run a corpus, diff
  the trace).
- Porting story: popular Carpet-style rules land as built-ins or
  first-party add-ons; the rule-like API should feel familiar to Carpet
  authors ("a Carpet rule with a different import").
- Cancellable-event semantics are decided before networking lands, not
  retrofitted during parallel-tick work.

## Content: real add-ons, loader both sides

- Simple content (custom items, retextured blocks/mobs, server-driven UI
  and sounds) is delivered automatically on join; no install needed.
- Real content (true custom geometry, HUD, client prediction) ships as
  add-ons with a server half (plugin) and a client half, installed through
  a Kestrel loader into the standard game. No custom client, no launcher,
  no forked player base. Players without the loader keep playing servers
  that do not require custom-geometry content.
- The palette/borrowed-state tricks are the lightweight tier for simple
  content, not the flagship story.
- Login negotiation reserves "which add-on versions does this client
  carry" so servers verify before gameplay, not mid-session.

## Distribution: Modrinth as an explicit post-launch milestone

Ship the server, stabilize the add-on format (manifest, versioning
promise), publish the loader tooling, apply for a Modrinth loader
listing, seed the catalog with the first 10-20 add-ons. CurseForge second.
The performance story ("same setup, several times faster") pulls the first
server owners; their demand pulls the first authors.

## Phase A constraints (so none of the above needs rework)

1. All world access behind the seam; vanilla types never leak past it
   (facade rule, keeps the future API version-stable).
2. Hook points check subscriber bitsets; zero listeners equals zero cost.
3. Cancellable-event semantics decided before networking lands.
4. One config/command/permission template for all subsystems (from
   `AcFlag`), so future authors learn one pattern.

Documentation sweep 2026-09-28, main `198a6b2`: reviewed for stale claims. This
file states intent and constraints rather than status or counts, so it carries no
dated claims to expire; none were contradicted by the code.
