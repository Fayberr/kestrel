# G24 Pre-Research: Server-Side Player Physics and Movement Validation Specification

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) at `~/kestrel-run/vanilla-src-26.2/net/minecraft`, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for roadmap item G24 (server-side player physics) and tightening roadmap item S8 (movement checks) in Kestrel.
Scope: Architectural analysis, vanilla code audits with exact file:line citations, end-to-end movement packet lifecycle, terrain collision and step-up algorithms, every movement-affecting block and fluid family, tolerance and anti-cheat thresholds, gap audit of current Kestrel implementations, measurement plan with vanilla-oracle verification, vertical slices with mutation campaigns, and handoff report. Plain markdown only: no em-dash or en-dash characters, no builds, no servers, no Java source changes.

---

## 1. Scope and Dependencies

### 1.1 Scope Partitioning
Roadmap item G24 establishes the authoritative server-side physics simulation and movement validation for player avatars in Kestrel. It resolves terrain collision, stepping over obstacles, climbing, swimming and fluid buoyancy, slipperiness, block slowdowns, cobwebs, powder snow, and elytra flight dynamics.

In Minecraft's client-server architecture, the client predicts player motion locally for responsiveness, transmitting updated positions and rotation angles to the server via movement packets. The server does not passively trust these packets: it executes speculative movement simulation against authoritative world terrain, calculates deviations, detects invalid movements or block phase attempts, clamps and snaps offending players, and enforces server-side environmental side effects (fluid submersion, bubble column forces, fall damage, and block contact hooks).

The boundary between G24, S8, S14, G15, G22, G25, G66, and G68 is strictly defined below:

| Roadmap Item | Subsystem / Domain | Scope Owned | Handed Off / Downstream Dependency |
|---|---|---|---|
| **S8** | Movement Checks & Rate Limiting | Inbound packet bounds, NaN/Inf coordinate rejection, rate tracking (`deltaPackets`), teleport ack timeout (20 ticks), floating kick (80 ticks). Tightened by G24. | Initial naive clamping replaced by G24's exact vanilla error-distance and collision validation. |
| **G24** | Server-Side Player Physics (This Work) | Speculative `move(MoverType.PLAYER)`, `collide` swept AABB, step-up pass (up to 0.6 blocks), supporting block detection, block friction/drag arithmetic, `maybeBackOffFromEdge` crouching edge hold, fluid interaction (water/lava buoyancy and flow push), bubble column forces, block speed factors, powder snow/honey/web stuck speeds, bounce restitution, pose bounding boxes (standing, crouching, swimming, gliding, sleeping), and `isEntityCollidingWithAnythingNew` validation. | Supplies authoritative player position, bounding box, collision state, onGround flag, and fall distance to all player subsystems. |
| **S14** | Entity System Engine | Spatial indexing (`EntitySectionStorage`), entity ID allocation (`EntityIds`), entity tick loop scheduling, tracking stream (`EntityTracker`), and packet encoders (`EntityOutbound`). | G24 uses S14 entity IDs and tracking observer notifications (`entityTracker.movePlayer`). |
| **G15** | Player Visibility & Bodies | Spawning player avatar entities (`ServerPlayer`), broadcasting motion/look packets (`ClientboundMoveEntityPacket`), swing/hurt animations, and equipment sync. | G24 moves the player body; G15 broadcasts the resulting coordinates to tracking peers. |
| **G22** | Health, Damage, Death, Respawn | Fall damage application (`checkFallDamage`, `doCheckFallDamage`), drowning air supply decrement, suffocation (`isInWall`), fire/lava ignition ticks, void damage (`Y < minY - 64`). | G24 computes `fallDistance`, fluid eye submersion (`isEyeInFluid`), in-wall intersection (`isInWall`), and floor boundary crossings that trigger G22 damage. |
| **G25** | Combat & Attacking | Melee hit detection, reach distance checks (`ENTITY_INTERACTION_RANGE`), knockback impulses, sweeping attacks, shield blocking. | G25 applies knockback vectors to player `deltaMovement` and sets `postImpulseGraceTime`; G24 validates resulting high-speed movement without false cheat violations. |
| **G66** | Block Updates | Live write pipeline (`Level.setBlock`), neighbor shape updates, falling block entities. | G24 queries terrain states produced by G66; falling blocks land and create colliders that G24 navigates. |
| **G68** | Entities Inside Blocks | `entityInside` and `applyEffectsFromBlocks` for non-player entities (items, falling blocks, mobs). | G24 handles the player-specific inside-block and movement-affecting paths; G68 mirrors these for non-player entities. |
| **G43** | Nether and End Portals | Portal coordinate translation (8:1 Nether scaling), portal frame search and generation, dimension transitions. | G24 manages portal block intersection and sets `isChangingDimension` which disables movement cheat checks during teleportation. |
| **G54** | Vehicles and Riding | Mounting horses, boats, minecarts; vehicle steering packets (`ServerboundPlayerInputPacket`), vehicle movement validation (`handleMoveVehicle`). | G24 handles unmounted player physics; when mounted, passenger physics defer to vehicle simulation. |

---

## 2. Vanilla Server-Side Player Movement, End to End

### 2.1 The Movement Packet Pipeline
Player movement packets arrive from the network as `ServerboundMovePlayerPacket` variants:
- `Pos` (`0x1B`): X, Y, Z, onGround
- `PosRot` (`0x1C`): X, Y, Z, Yaw, Pitch, onGround
- `Rot` (`0x1D`): Yaw, Pitch, onGround
- `StatusOnly` (`0x1E`): onGround only

The authoritative handling pipeline executes in `ServerGamePacketListenerImpl.handleMovePlayer(ServerboundMovePlayerPacket)` (`ServerGamePacketListenerImpl.java:1068-1205`):

```
[Inbound Packet]
       |
       v
1. Thread Handoff (`PacketUtils.ensureRunningOnSameThread`, :1069)
       |
       v
2. Sanity Validation (`containsInvalidValues`, :1070)
   - Checks NaN, Infinity on X, Y, Z, Yaw, Pitch (:434-436)
   - If invalid: disconnect("multiplayer.disconnect.invalid_player_movement")
       |
       v
3. Tick 0 Reset (`if (tickCount == 0) resetPosition()`, :1075-1077)
   - Initializes firstGoodX/Y/Z and lastGoodX/Y/Z to player current pos (:379-386)
       |
       v
4. Client Loaded Gate (`if (hasClientLoaded())`, :1079)
   - Rotation wrapping: targetYRot = wrapDegrees(packet.getYRot()), targetXRot = wrapDegrees(packet.getXRot()) (:1080-1081)
       |
       v
5. Teleport Ack Gate (`if (updateAwaitingTeleport())`, :1082)
   - If awaiting teleport ack: player.absSnapRotationTo(targetYRot, targetXRot) (:1083)
   - If timeout (> 20 ticks): resends teleport to awaitingPositionFromClient (:1220-1229)
   - Movement position update is entirely bypassed while teleport is pending!
       |
       v
6. Coordinate Clamping (:1085-1087)
   - targetX = clampHorizontal(packet.getX()) -> clamp to [-3.0E7, 3.0E7] (:438-440)
   - targetY = clampVertical(packet.getY())   -> clamp to [-2.0E7, 2.0E7] (:442-444)
   - targetZ = clampHorizontal(packet.getZ()) -> clamp to [-3.0E7, 3.0E7]
       |
       v
7. Passenger Check (`if (player.isPassenger())`, :1088-1091)
   - Snaps position and rotation to vehicle, calls chunkSource.move(player), returns.
       |
       v
8. Distance & Sleeping Checks (:1092-1104)
   - xDist = targetX - firstGoodX, yDist = targetY - firstGoodY, zDist = targetZ - firstGoodZ
   - movedDist = xDist^2 + yDist^2 + zDist^2
   - If sleeping: if movedDist > 1.0, teleport back to bed (:1101-1103)
       |
       v
9. Packet Rate & Speed Sanity Check (:1106-1124)
   - deltaPackets = receivedMovePacketCount - knownMovePacketCount (:1108)
   - If deltaPackets > 5: log debug, clamp deltaPackets = 1 (:1109-1114)
   - If shouldCheckPlayerMovement:
       metersPerTick = isFallFlying ? 300.0F : 100.0F (:1117)
       If movedDist - expectedDist > metersPerTick * deltaPackets (:1118):
           log warn ("moved too quickly!")
           teleport(player.getX(), player.getY(), player.getZ(), yaw, pitch) (:1120)
           return
       |
       v
10. Ground Jump Prediction (:1131-1133)
    - If player.onGround() && !packet.isOnGround() && (targetY - lastGoodY) > 0.0:
        player.jumpFromGround() (:1132)
       |
       v
11. Server-Side Collision Simulation (`player.move(MoverType.PLAYER, delta)`, :1136)
    - Runs swept AABB collision through KestrelCollide / collideWithShapes
    - Updates player's temporary simulated position
       |
       v
12. Movement Error & Cheat Verification (:1137-1155)
    - xDist = targetX - player.getX()
    - yDist = targetY - player.getY()
    - If yDist in (-0.5, 0.5): yDist = 0.0 (vertical leniency window) (:1139-1141)
    - zDist = targetZ - player.getZ()
    - movedDist = xDist^2 + yDist^2 + zDist^2
    - fail = (!changingDimension && movedDist > 0.0625 && !sleeping && !creative && !spectator && !inPostImpulseGraceTime) (:1146-1154)
    - If fail: log warn ("{} moved wrongly!") (:1153)
       |
       v
13. Acceptance Decision & Collision Test (:1156-1159)
    - Accept IF: noPhysics || sleeping || (!fail || !level.noCollision(player, oldAABB)) && !isEntityCollidingWithAnythingNew(targetX, targetY, targetZ)
    - Case A: ACCEPTED (:1160-1193)
        player.absSnapTo(targetX, targetY, targetZ, targetYRot, targetXRot) (:1160)
        clientIsFloating = yDist >= -0.03125 && !verticalCollisionBelow && !spectator && !allowFlight && !mayfly && !hasEffect(LEVITATION) && !isFallFlying && !isAutoSpinAttack && noBlocksAround(player) (:1162-1170)
        chunkSource.move(player) (:1171)
        player.setOnGroundWithMovement(packet.isOnGround(), packet.horizontalCollision(), clientDeltaMovement) (:1173)
        player.doCheckFallDamage(dx, dy, dz, packet.isOnGround()) (:1174)
        If movedUpwards: player.resetFallDistance() (:1177)
        lastGoodX/Y/Z = targetX/Y/Z (:1190-1192)
    - Case B: REJECTED (:1194-1198)
        teleport(startX, startY, startZ, targetYRot, targetXRot) (:1194)
        player.doCheckFallDamage(dx, dy, dz, packet.isOnGround()) (:1195-1196)
```

### 2.2 Player Tick, AI Step, and Travel Types
While `handleMovePlayer` accepts the client's packet, the server's tick thread updates entity state inside `ServerGamePacketListenerImpl.tickPlayer()` (`ServerGamePacketListenerImpl.java:317-362`):

> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

#### The `absSnapTo` Snapback Rule
Notice the critical sequence:
1. `resetPosition()`: Sets `firstGoodX/Y/Z` and `lastGoodX/Y/Z` to `player.getX/Y/Z()`.
2. `xo/yo/zo = player.getX/Y/Z()`.
3. `player.doTick()`: Executes vanilla living entity logic (regeneration, food exhaustion, item usage, block contact hooks, pose updates, inventory ticking).
4. `player.absSnapTo(this.firstGoodX, this.firstGoodY, this.firstGoodZ, ...)`: **Snaps the player position back to `firstGoodX/Y/Z`!**
Because `Player.isClientAuthoritative()` returns `true` (`Player.java:1270`), `isLocalInstanceAuthoritative()` returns `false` on the server (`Entity.java:3608`). Therefore, `canSimulateMovement()` returns `false` (`Entity.java:3622`), which suppresses `travel(input)` during the server's `doTick` (`LivingEntity.java:3141`). Even if any velocity modification occurred, `absSnapTo` restores the packet-accepted position. The server relies on `handleMovePlayer` to advance coordinates!

#### Travel Modes in `LivingEntity.travel` (`LivingEntity.java:2433-2441`)
When travel is calculated (on the client or inside speculative movement simulations):
1. **Fluid Travel (`travelInFluid`, :2505-2515):** Triggered when `shouldTravelInFluid` is true (`(isInWater() || isInLava()) && isAffectedByFluids() && !canStandOnFluid()`, `:2448`). Dispatches to `travelInWater` (`:2517-2544`) or `travelInLava` (`:2550-2566`).
2. **Fall Flying / Elytra Travel (`travelFallFlying`, :2582-2596):** Triggered when `isFallFlying()` is true.
3. **Air & Ground Travel (`travelInAir`, :2471-2498):** Default locomotion. Evaluates block friction at `getBlockPosBelowThatAffectsMyMovement()`, applies gravity, and handles horizontal/vertical air drag.

### 2.3 Collision Pipeline and Step-Up Fast Path
The swept AABB collision walk is executed by `Entity.collide(Vec3)` (`Entity.java:1150-1179`):

1. **Entity Colliders:** `level.getEntityCollisions(entity, aabb.expandTowards(movement))` (`:1152`).
2. **First Pass (`collideBoundingBox`, :1153):**
   - Collects block colliders via `BlockCollisions` (`Cursor3D` swept over `[floor(min)-1, floor(max)+1]`, `:1241`).
   - Resolves axis collisions in order defined by `Direction.axisStepOrder(movement)` (`Direction.java:382-384`):
     - **Y axis is ALWAYS resolved first.**
     - Between X and Z: the axis with **larger absolute movement magnitude** is resolved second, and the remaining axis third (`Math.abs(movement.x) < Math.abs(movement.z) ? YZX : YXZ`).
3. **Step-Up Evaluation (`:1158-1176`):**
   - Step-up activates if `maxUpStep > 0.0F` (Player attribute `Attributes.STEP_HEIGHT` defaults to `0.6F`), the player is grounded or lands (`onGroundAfterCollision || onGround`), and horizontal collision occurred (`xCollision || zCollision`).
   - Grounded reference box: `groundedAABB = onGroundAfterCollision ? aabb.move(0.0, step.y, 0.0) : aabb` (`:1159`).
   - Step-up query box: `stepUpAABB = groundedAABB.expandTowards(movement.x, maxUpStep, movement.z)` (`:1160`).
   - `collectCandidateStepUpHeights` (`:1181-1206`): Extracts every Y coordinate from collider shapes overlapping `stepUpAABB`, computes relative heights `coord - groundedAABB.minY`, filters to `[0.0, maxUpStep]` excluding `movementStep.y`, and sorts ascending.
   - For each candidate height: runs `collideWithShapes(Vec3(movement.x, candidateHeight, movement.z), groundedAABB, colliders)`. If horizontal distance squared exceeds the first-pass horizontal distance, step-up succeeds: returns `stepFromGround.subtract(0.0, distanceToGround, 0.0)` (`:1171-1174`).

### 2.4 Sneaking Edge Backoff: `maybeBackOffFromEdge`
When a player sneaks on the edge of a block, `Player.maybeBackOffFromEdge(Vec3, MoverType)` (`Player.java:876-923`) prevents falling:
1. Gated by: `!abilities.flying && !(delta.y > 0.0) && (mover == SELF || mover == PLAYER) && isStayingOnGroundSurface() && isAboveGround(maxDownStep)` (`:878-882`).
2. `isStayingOnGroundSurface()` checks `isShiftKeyDown()` (`:298-300`).
3. `canFallAtLeast(deltaX, deltaZ, minHeight)` (`:929-943`): Tests whether `noCollision` is true for an AABB 1.0E-7 below the player's bounding box offset by `(deltaX, deltaZ)` with height `minHeight`.
4. If falling is possible: decrements `deltaX` in steps of `0.05` until safe (`:889-894`), then decrements `deltaZ` in steps of `0.05` (`:896-903`), then steps back both simultaneously (`:905-917`).
5. Returns modified horizontal delta preserving vertical `delta.y`.

### 2.5 Physics Constants and Arithmetic Values

| Constant Name | Value | Vanilla Source Citation | Functional Purpose |
|---|---|---|---|
| `Attributes.GRAVITY` | `0.08` blocks/tick^2 | `Attributes.java:55-57` | Default downward acceleration applied per tick in air. |
| `Attributes.JUMP_STRENGTH` | `0.42F` blocks/tick | `Attributes.java:58-60` | Initial vertical upward velocity applied by `jumpFromGround()`. |
| `Attributes.STEP_HEIGHT` | `0.6F` blocks | `Attributes.java:98-100` | Maximum vertical step-up height without jumping (slabs, stairs). |
| `Attributes.MOVEMENT_SPEED` | `0.1F` | `Player.java:207`, `Attributes.java:77-79` | Player base walking speed attribute (living entity default is 0.7; player overrides to 0.1). |
| `Attributes.SNEAKING_SPEED` | `0.3F` | `Attributes.java:92-94` | Sneaking speed multiplier (30% of normal walking speed). |
| Vertical Air Drag | `0.98F` (2% loss/tick) | `LivingEntity.java:2495`, `Entity.java:55` | Vertical velocity retention per tick in air: `v_y = (v_y - gravity) * 0.98`. |
| Horizontal Air Drag | `0.91F` (9% loss/tick) | `LivingEntity.java:2493` | Base horizontal velocity retention per tick in air. |
| Water Drag (`slowDown`) | `0.80F` (walking) / `0.90F` (sprint) | `LivingEntity.java:2518`, `2415` | Horizontal fluid velocity retention per tick in water. |
| Lava Drag | `0.50F` | `LivingEntity.java:2463`, `2558` | Horizontal and vertical velocity retention per tick in lava. |
| Flying Drag | `0.91F` | `LivingEntity.java:2467` | Horizontal velocity retention per tick when flying. |
| Default Block Friction | `0.60F` | `BlockBehaviour.java:988` | Base slipperiness of normal blocks (dirt, stone, wood, etc.). |
| Ice / Packed / Frosted Ice Friction | `0.98F` | `Blocks.java:1950, 3021, 3732` | Slipperiness of standard ice variants. |
| Blue Ice Friction | `0.989F` | `Blocks.java:4227` | Highest slipperiness in vanilla. |
| Slime Block Friction | `0.80F` | `Blocks.java:2926` | Slipperiness of slime blocks. |
| Terminal Velocity (Air) | `-3.92` blocks/tick (-78.4 m/s) | Derived: `-0.08 * 0.98 / (1 - 0.98)` | Theoretical maximum downward speed in freefall. |
| Terminal Velocity (Water) | `-0.02` blocks/tick (-0.4 m/s) | Derived: `-0.005 * 0.8 / (1 - 0.8)` | Theoretical settling downward velocity in water without input. |
| Sprint Jump Horizontal Boost | `0.20F` blocks/tick | `LivingEntity.java:2399` | Additional impulse along look yaw added upon jumping while sprinting. |
| Jump Boost Amplifier Step | `0.10F` blocks/tick per level | `LivingEntity.java:2388` | Additional jump power: `0.1F * (amplifier + 1)`. |
| Water Flow Push Impulse | `0.014` blocks/tick | `Entity.java:1673` | Acceleration applied by flowing water current per tick. |
| Lava Flow Push Impulse (Normal) | `0.0023333333333333335` | `Entity.java:1677` | Acceleration applied by normal lava current (1/6th of water). |
| Lava Flow Push Impulse (Fast/Nether) | `0.007` blocks/tick | `Entity.java:1677` | Acceleration applied by Nether lava current (half of water). |
| Bubble Column Above Up/Down | `+0.10` / `-0.03` blocks/tick | `Entity.java:2865, 2867` | Velocity delta applied at the surface of bubble columns. |
| Bubble Column Inside Up/Down | `+0.06` / `-0.03` blocks/tick | `Entity.java:2897, 2899` | Velocity delta applied submerged inside bubble columns. |

#### Friction and Slipperiness Formulas
On ground, effective friction is computed via `LivingEntity.travelInAir` (`LivingEntity.java:2473-2475`):
```java
float blockFriction = this.onGround()
   ? computeModifiedFriction(this.level().getBlockState(posBelow).getBlock().getFriction(), (float)this.getAttributeValue(Attributes.FRICTION_MODIFIER))
   : 1.0F;
```
Where `computeModifiedFriction(friction, modifier)` (`LivingEntity.java:526-528`) is:
```java
Mth.clamp(1.0F - (1.0F - friction) * modifier, 0.0F, 1.0F);
```
The ground acceleration factor `getFrictionInfluencedSpeed(blockFriction)` (`LivingEntity.java:2721-2727`) is:
```java
if (this.onGround()) {
   return blockFriction > 0.6F
      ? this.getSpeed() * (0.21600002F / (blockFriction * blockFriction * blockFriction))
      : this.getSpeed();
} else {
   return this.getFlyingSpeed(); // 0.02F for unmounted player
}
```
Notice that `0.21600002F` is `(0.6)^3`. On standard blocks (`friction = 0.6`), the term evaluates to `(0.6)^3 / (0.6)^3 = 1.0`, yielding full speed `getSpeed() = 0.1F`. On ice (`friction = 0.98`), `(0.6)^3 / (0.98)^3 = 0.216 / 0.941192 = 0.2295`, drastically reducing acceleration per tick while momentum retention (`friction * airDrag = 0.98 * 0.91 = 0.8918` vs normal `0.6 * 0.91 = 0.546`) causes prolonged sliding.

#### Player Bounding Box and Eye Heights by Pose (`Avatar.java:13-35`, `LivingEntity.java:191`)

| Pose | Width (X/Z) | Height (Y) | Eye Height (Y) | Trigger Condition |
|---|---|---|---|---|
| `STANDING` | `0.6F` | `1.8F` | `1.62F` | Default unconstrained upright stance (`Avatar.java:21-23`). |
| `CROUCHING` | `0.6F` | `1.5F` | `1.27F` | Shift key held, not flying, can fit within 1.5 blocks (`Avatar.java:30-35`). |
| `SWIMMING` | `0.6F` | `0.6F` | `0.40F` | Sprinting in water or forced crawling in 1-block spaces (`Avatar.java:28`). |
| `FALL_FLYING` | `0.6F` | `0.6F` | `0.40F` | Gliding with Elytra (`Avatar.java:27`). |
| `SPIN_ATTACK` | `0.6F` | `0.6F` | `0.40F` | Riptide trident launch active (`Avatar.java:29`). |
| `SLEEPING` | `0.2F` | `0.2F` | `0.20F` | Resting in bed (`LivingEntity.java:191`). |

---

## 3. Movement-Affecting Blocks and Fluids

### 3.1 Water and Lava
- **Buoyancy & Sinking:**
  - Water: `travelInWater` (`LivingEntity.java:2517-2544`). Gravity is scaled by `baseGravity / 16.0` (`0.08 / 16 = 0.005`). Downward velocity settling threshold: if `Math.abs(movement.y - 0.005) >= 0.003` and `Math.abs(movement.y - 0.005) < 0.003`, `yd = -0.003` (`:2692-2696`). Drag factor: `0.80F` (or `0.90F` if sprinting).
  - Lava: `travelInLava` (`LivingEntity.java:2550-2566`). Downward velocity addition: `-baseGravity / 4.0 = -0.02` blocks/tick. Drag factor: `0.50F`.
  - Jump out of fluid: `jumpOutOfFluid` (`:2568-2573`): If horizontal collision occurs in fluid and space above is free (`movement.y + 0.6F - getY() + oldY`), applies vertical boost `movement.y = 0.3F`.
- **Depth Strider & Dolphin's Grace:**
  - Depth Strider increases attribute `Attributes.WATER_MOVEMENT_EFFICIENCY` (`LivingEntity.java:2520`). If not on ground, modifier is halved (`* 0.5F`).
  - At level 3 (`efficiency = 1.0` on ground): `slowDown += (0.54600006F - slowDown) * 1.0 = 0.546` (matching normal land drag `0.91 * 0.6`), and `speed += (getSpeed() - speed) * 1.0 = getSpeed()`.
  - Dolphin's Grace (`:2530-2532`): Sets `slowDown = 0.96F`, dramatically accelerating water swimming.
- **Fluid Submersion & Current Flow:**
  - `EntityFluidInteraction.update` (`EntityFluidInteraction.java:32-89`) iterates `getFluidInteractionBox()`. Reads `FluidState.getFlow(level, pos)`.
  - For players, accumulated current is averaged: `impulse = accumulatedCurrent.scale(1.0 / currentCount)` (`:178`).
  - Scaled by `0.014` for water (`Entity.java:1673`), `0.007` for fast lava, `0.0023333333333333335` for normal lava (`Entity.java:1677`). Added to `deltaMovement`.
- **Server vs Client Computation:**
  - Client predicts fluid buoyancy, swimming controls, and flow push per frame.
  - Server computes fluid immersion in `updateFluidInteraction()`, checks eye submersion for drowning (`G22`), applies current flow impulse to server delta movement, and verifies that vertical rise does not exceed water limits (`MAX_SWIM_RISE = 7.0`).

### 3.2 Cobwebs (`WebBlock.java:27-36`)
- Implemented via `entityInside`:
  ```java
  Vec3 speedMultiplier = new Vec3(0.25, 0.05F, 0.25);
  if (entity instanceof LivingEntity living && living.hasEffect(MobEffects.WEAVING)) {
     speedMultiplier = new Vec3(0.5, 0.25, 0.5);
  }
  entity.makeStuckInBlock(state, speedMultiplier);
  ```
- `makeStuckInBlock` (`Entity.java:2951-2954`): Resets `fallDistance = 0.0`, stores `stuckSpeedMultiplier`.
- In `Entity.move`: delta movement is multiplied by `stuckSpeedMultiplier` (`(0.25, 0.05, 0.25)`), reducing horizontal speed by 75% and vertical speed by 95%! `deltaMovement` is zeroed (`Entity.java:745`).
- Server vs Client: Client predicts slowed movement; server validates that movement within web blocks obeys `stuckSpeedMultiplier`.

### 3.3 Powder Snow (`PowderSnowBlock.java:62-132`)
- `getCollisionShape` (`:116-132`):
  - Returns `Shapes.empty()` by default: entities sink into powder snow!
  - If `entity.fallDistance > 2.5`: returns `FALLING_COLLISION_SHAPE` (`box(0, 0, 0, 1, 0.9, 1)`), catching falling entities 0.1 blocks below top.
  - If `canEntityWalkOnPowderSnow(entity)` (leather boots equipped): returns full cube if above block and not descending (`:125-127`).
- `entityInside` (`:62-98`):
  - If not walking on top: `entity.makeStuckInBlock(state, new Vec3(0.9F, 1.5, 0.9F))` (`:66`).
  - Triggers freezing ticks: `effectApplier.apply(InsideBlockEffectType.FREEZE)`.
- Jumping out: If entity has leather boots and jumps inside powder snow, `LivingEntity.handleRelativeFrictionAndCalculateMovement` applies upward climb boost `movement = new Vec3(movement.x, 0.2, movement.z)` (`LivingEntity.java:2682-2684`).
- Server vs Client: Client renders freezing vignette and predicts movement; server tracks freeze ticks (`ticksFrozen`), resets fall damage, and applies suffocation/freezing damage (`G22`).

### 3.4 Honey and Slime Blocks
- **Honey Block (`HoneyBlock.java:25-115`):**
  - Collision shape: `Block.column(14.0, 0.0, 15.0)` (`:31`, `:48`): 14/16 width, 15/16 height. Player sinks slightly into honey.
  - `fallOn` (`:52-61`): Deals only 20% fall damage (`entity.causeFallDamage(fallDistance, 0.2F, ...)`). Emits entity event 54.
  - Sliding (`entityInside`, `:64-74`, `:84-115`): If sliding against side of honey block (`isSlidingDown`), vertical fall velocity is clamped to `-0.05` blocks/tick, horizontal momentum is reduced by `-0.05 / oldDeltaY`, and fall distance is reset (`:114`).
  - Speed & Jump Factors: `speedFactor(0.4F).jumpFactor(0.5F)` (`Blocks.java:4843`). Jump height is halved!
- **Slime Block (`SlimeBlock.java:10-39`):**
  - `fallOn` (`:23-27`): Deals zero fall damage unless suppressing bounce (`isSuppressingBounce()` / sneaking): `entity.causeFallDamage(fallDistance, 0.0F, ...)`.
  - Bounce Restitution: `bounceRestitution(1.0F)` (`Blocks.java:2926`). In `Entity.restituteMovementAfterCollisions` (`Entity.java:834-852`), vertical velocity is inverted: `v_y = -currentMovement.y * 1.0` (with gravity compensation and drag interpolation). Sneaking cancels bounce (`restitution = 0.0`).
  - `stepOn` (`:30-38`): If walking on top with low vertical speed (`absDeltaY < 0.1`), horizontal momentum is scaled down by `0.4 + absDeltaY * 0.2`.
- Server vs Client: Client predicts bounce trajectory and sliding; server validates bounce velocity and resets fall distance.

### 3.5 Ladders, Vines, Scaffolding, and Trapdoors
- **Climbing Condition (`LivingEntity.onClimbable`, `LivingEntity.java:1734-1752`):**
  - Evaluates block state at `blockPosition()`. Returns true if `state.is(BlockTags.CLIMBABLE)` (Ladders, Vines, Weeping/Twisting Vines, Scaffolding).
  - Trapdoor ladder: If `state.getBlock() instanceof TrapDoorBlock`, open flag is true (`state.getValue(TrapDoorBlock.OPEN)`), block below is `Blocks.LADDER`, and facing matches trapdoor facing (`:1754-1761`).
- **Motion Clamping (`handleOnClimbable`, `LivingEntity.java:2704-2719`):**
  - Resets `fallDistance = 0.0`.
  - Clamps horizontal velocity: `xd = clamp(delta.x, -0.15, 0.15)`, `zd = clamp(delta.z, -0.15, 0.15)`.
  - Clamps downward velocity: `yd = max(delta.y, -0.15)`.
  - Sneak stop: If `isSuppressingSlidingDownLadder()` (shift held) and not on scaffolding, `yd = 0.0` (player hangs stationary on ladder!).
- **Scaffolding Mechanics (`ScaffoldingBlock.java:26-152`):**
  - `getCollisionShape` (`:137-147`): Solid platform on top (`SHAPE_STABLE`) if player is above and not sneaking (`!context.isDescending()`).
  - Sneaking through: When shift is held (`context.isDescending() == true`), collision shape is `Shapes.empty()`, letting player drop through platform!
- Server vs Client: Client applies climb movement; server verifies climbable tags and clamps vertical speed.

### 3.6 Beds, Soul Sand, and Mud
- **Beds (`BedBlock.java:132-134`, `Blocks.java:684`):**
  - `bounceRestitution(0.75F)`: Bounces player with 75% velocity restitution.
  - `fallOn`: Halves fall distance before computing damage (`fallDistance * 0.5`).
- **Soul Sand & Mud Sinking (`SoulSandBlock.java:15`, `MudBlock.java:15`):**
  - Both blocks declare `SHAPE = Block.column(16.0, 0.0, 14.0)` (14 pixels tall).
  - Because collision box is 0.875 blocks high instead of 1.0, player sinks 2 pixels (0.125 blocks) into the block.
  - Soul Sand applies `speedFactor(0.4F)` (`Blocks.java:2024`), reducing horizontal speed by 60%. Mud has default `speedFactor(1.0F)` (sink only, no slowdown).
- Server vs Client: Collision shapes determine resting Y position on both client and server.

### 3.7 Ice, Packed Ice, and Blue Ice
- Slipperiness values: Ice `0.98F`, Packed Ice `0.98F`, Blue Ice `0.989F` (`Blocks.java:1950, 3021, 4227`).
- In `LivingEntity.travelInAir`: `blockFriction` scales ground acceleration down to ~23% on normal ice and ~16% on blue ice, while preserving up to 90% of horizontal velocity per tick.
- Server vs Client: Client simulates sliding momentum; server validates that high horizontal speed across ice does not trigger speed cheat false-positives.

### 3.8 Bubble Columns (`BubbleColumnBlock.java:53-65`, `Entity.java:2857-2900`)
- Drag Down (Magma Block base):
  - At surface (`onAboveBubbleColumn`, `Entity.java:2865`): `yd = Math.max(-0.9, movement.y - 0.03)`.
  - Submerged (`onInsideBubbleColumn`, `Entity.java:2897`): `yd = Math.max(-0.3, movement.y - 0.03)`.
- Push Up (Soul Sand base):
  - At surface (`onAboveBubbleColumn`, `Entity.java:2867`): `yd = Math.min(1.8, movement.y + 0.1)`. Launches player into air!
  - Submerged (`onInsideBubbleColumn`, `Entity.java:2899`): `yd = Math.min(0.7, movement.y + 0.06)`.
- Player flying suppression: If `abilities.flying` is true, bubble column impulses are bypassed (`Player.java:308-319`).
- Server vs Client: Server applies vertical acceleration directly to server velocity; client predicts locally.

### 3.9 Modern FallOn, StepOn, and InWall Hooks
- In modern Minecraft (including 26.2), the old `updateEntityMovementAfterFallOn` method does not exist. Block bounce restitution is handled centrally via `Entity.restituteMovementAfterCollisions` (`Entity.java:813-855`) reading `block.getBounceRestitution()`.
- `Block.fallOn` (`Block.java:491-493`): Dispatches fall damage through `entity.causeFallDamage(fallDistance, damageModifier, damageSource)`. Overridden by Honey (0.2x), Slime (0.0x), Bed (0.5x distance), Hay (0.2x).
- `Block.stepOn` (`Block.java:512`): Called when entity moves on ground across block at `getOnPosLegacy` (`Entity.java:415-424`). Slime blocks apply horizontal speed reduction; Redstone Ore triggers light-up.
- `Entity.isInWall()` (`Entity.java:2262-2278`):
  ```java
  float checkWidth = this.dimensions.width() * 0.8F; // 0.6 * 0.8 = 0.48 blocks for player
  AABB eyeBb = AABB.ofSize(this.getEyePosition(), checkWidth, 1.0E-6, checkWidth);
  return BlockPos.betweenClosedStream(eyeBb).anyMatch(pos -> {
     BlockState state = this.level().getBlockState(pos);
     return !state.isAir() && state.isSuffocating(this.level(), pos)
        && Shapes.joinIsNotEmpty(state.getCollisionShape(this.level(), pos).move(pos), Shapes.create(eyeBb), BooleanOp.AND);
  });
  ```
  Tests whether a 0.48x0.48 box centered at the player's eye height intersects the collision shape of any block where `isSuffocating` is true. If true, deals 1.0F in-wall suffocation damage per tick (`G22`).

---

## 4. What the Server Movement Check Tolerates (Vanilla Anti-Cheat Specification)

### 4.1 Exact Arithmetic and Validation Sequence
Vanilla's movement anti-cheat in `ServerGamePacketListenerImpl.handleMovePlayer` operates as a layered filter rather than a single hard speed clamp:

#### 1. Invalid Values Kick
- Condition: `Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z) || !Floats.isFinite(yRot) || !Floats.isFinite(xRot)` (`:434-436`).
- Action: Immediate disconnect (`multiplayer.disconnect.invalid_player_movement`).

#### 2. Packet Flooding Diagnostic
- Tracks `deltaPackets = receivedMovePacketCount - knownMovePacketCount` (`:1108`).
- If `deltaPackets > 5`: Server logs debug warning (`"{} is sending move packets too frequently"`), and clamps `deltaPackets = 1` for subsequent speed evaluations (`:1113`).

#### 3. Macro Speed Check ("moved too quickly!")
- Evaluated when `shouldCheckPlayerMovement` is true (`:1116-1123`).
- Base speed threshold:
  ```java
  float metersPerTick = isFallFlying ? 300.0F : 100.0F; // ServerGamePacketListenerImpl.java:1117
  ```
- Distance comparison:
  ```java
  double movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
  double expectedDist = this.player.getDeltaMovement().lengthSqr();
  if (movedDist - expectedDist > metersPerTick * deltaPackets) {
     LOGGER.warn("{} moved too quickly! {},{},{}", player.getPlainTextName(), xDist, yDist, zDist);
     this.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
     return;
  }
  ```
- **Key finding:** `metersPerTick` is **100.0F distance squared per packet** (`10.0` blocks of movement for 1 packet), or `300.0F` distance squared (`17.32` blocks) for Elytra flight!
- **Violation Action:** Teleports player back to authoritative server coordinates (`this.teleport(...)`). **Vanilla DOES NOT disconnect the player for moving too quickly!**

#### 4. Speculative Movement and Error Distance ("moved wrongly!")
- Server simulates player movement through terrain via `this.player.move(MoverType.PLAYER, new Vec3(xDist, yDist, zDist))` (`:1136`).
- Computes deviation between client requested position and server simulated position:
  ```java
  xDist = targetX - this.player.getX();
  yDist = targetY - this.player.getY();
  if (yDist > -0.5 || yDist < 0.5) {
     yDist = 0.0; // 0.5-block vertical tolerance window!
  }
  zDist = targetZ - this.player.getZ();
  movedDist = xDist * xDist + yDist * yDist + zDist * zDist;
  ```
- Threshold check:
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- **Key finding:** The error threshold is `0.0625` blocks squared (`0.25` blocks horizontal error, or `> 0.525` blocks vertical error).
- **Post-Impulse Grace Time:** If the player recently took knockback, explosion damage, or external impulse, `isInPostImpulseGraceTime()` returns true, suppressing `fail`!

#### 5. Acceptance Decision and `isEntityCollidingWithAnythingNew`
Even if `fail == true`, vanilla STILL ACCEPTS the move under specific conditions (`:1156-1159`):
```java
if (this.player.noPhysics
   || this.player.isSleeping()
   || (!fail || !level.noCollision(this.player, oldAABB))
      && !this.isEntityCollidingWithAnythingNew(level, this.player, oldAABB, targetX, targetY, targetZ))
```
- Meaning: If the move passed without failure (`!fail`), OR if the player was already stuck in blocks (`!level.noCollision(player, oldAABB)`), the move is accepted AS LONG AS the player is not phasing into new solid blocks!
- `isEntityCollidingWithAnythingNew` (`:1238-1252`):
  - Constructs `newAABB` at `(targetX, targetY, targetZ)` deflated by `1.0E-5F`.
  - Checks if any collision shape in `level.getPreMoveCollisions` intersects `newAABB` without also intersecting `oldShape`.
  - If no new block collision occurs: **Client position is accepted!** The server snaps the player to `(targetX, targetY, targetZ)`!
- If the player IS colliding with something new: **Move is rejected.** Server immediately teleports player back to `(startX, startY, startZ)` (`:1194`).

#### 6. Fall Damage 8-Block Water Sweep Rule
During `Entity.move` (`Entity.java:752-760`), if falling velocity is high:
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
When moving >= 1.0 block in a single tick with positive fall distance, the server raycasts up to **8.0 blocks** along the movement trajectory. If it intercepts a water fluid or a block tagged `FALLDAMAGE_RESETTING`, fall distance is reset before impact, preventing high-speed client drops from taking bogus fall damage due to packet quantization!

#### 7. Flying Kick (`clientIsFloating` and `aboveGroundTickCount`)
- Evaluated in `handleMovePlayer` (`:1162-1170`) and ticked in `tickPlayer` (`:326-335`):
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- `noBlocksAround(entity)` (`:530-534`): Inflates bounding box by `0.0625` (1 pixel) and expands downwards by `-0.55` blocks; checks that all intersected blocks are `isAir()`.
- Floating tick limit:
  ```java
  // ServerGamePacketListenerImpl.java:364-371
  private int getMaximumFlyingTicks(final Entity entity) {
     double gravity = entity.getGravity();
     if (gravity < 1.0E-5F) return Integer.MAX_VALUE;
     double gravityModifier = 0.08 / gravity;
     return Mth.ceil(80.0 * Math.max(gravityModifier, 1.0));
  }
  ```
- For standard gravity (`0.08`), limit is exactly **80 ticks (4.0 seconds)**.
- If `aboveGroundTickCount > 80`: logs warning and disconnects player: `this.disconnect(Component.translatable("multiplayer.disconnect.flying"))` (`:328-330`).

---

## 5. Kestrel Today: Comprehensive Gap Audit

### 5.1 Step-by-Step Subsystem Comparison

| Physics Subsystem / Check | Vanilla 26.2 Implementation | Kestrel Implementation Today | Engine Requirement | Gap Description & Severity |
|---|---|---|---|---|
| **Invalid Coordinate Sanity** | Rejects NaN and Inf coordinates; disconnects client (`ServerGamePacketListenerImpl.java:1070-1072`). | `NetServer.java:1918-1922` rejects NaN/Inf; increments violation counter, does not disconnect. | Arithmetic only. | Minor. Kestrel retains connection per leniency policy; behavior is safe. |
| **Horizontal Speed Checking** | Tolerates up to 100 distance squared (10 blocks/packet) macro speed limit (`:1117-1118`). Simulates terrain move. | `NetServer.java:1936-1940` hard-clamps horizontal distance to `MAX_HORIZONTAL_SPEED = 1.20` blocks/packet. | Arithmetic only. | **CRITICAL DEFECT.** Refuses legal movements > 1.2 m/tick (sprint-jumping with speed effects, knockback, explosions, riptide, ice boat). |
| **Teleport Distance Checking** | Tolerates up to 10 blocks/packet macro limit; teleports back on excess (`:1118-1121`). No 64-block disconnect. | `NetServer.java:1930-1933` clamps to `MAX_TELEPORT_DISTANCE = 64.0` blocks; increments violation count. | Arithmetic only. | Medium. Comment in Kestrel claims vanilla disconnects at 64; vanilla does not. Clamping prevents legitimate admin teleports. |
| **Vertical Rise Checking** | No static vertical rise clamp. Simulates terrain move; checks `yDist` in `(-0.5, 0.5)`. | `NetServer.java:1950-1954` hard-clamps vertical rise to `0.50` (or `7.0` in water). | Arithmetic only. | High. Rejects vertical wind charge bursts, high-level jump boost, explosion launches, or slime block bounces. |
| **Gravity Sanity Checking** | Evaluates ground contact, simulated collision, and 80-tick floating timer. | `NetServer.java:1959-1988` tracks `airborneRise` and clamps to `MAX_JUMP_HEIGHT = 1.25`. | Arithmetic only. | High. Clamping prevents double jumps, jump boost effects, or legitimate vertical terrain stepping. |
| **Move Validation & Anti-Cheat** | Simulates `player.move(MoverType.PLAYER, delta)`; tests `movedDist > 0.0625` and `isEntityCollidingWithAnythingNew` (`:1136-1159`). | Completely absent. Moves are accepted blindly if within static distance caps (`NetServer.java:2043`). | S14 + `KestrelCollide`. | **CRITICAL DEFECT.** Players can walk through walls, phase through doors, and fly freely up to 1.2 m/tick without terrain collision checks. |
| **Floating Kick Enforcement** | `clientIsFloating` evaluated with 80-tick timeout (`:326-335`). | Absent. `clientIsFloating` is not tracked. | Arithmetic + chunk block lookups. | Medium. Players can hover indefinitely without being kicked for flying. |
| **Terrain Collision Fast Path** | Swept AABB collision over candidate shapes (`Entity.java:1150-1179`). | Implemented in `KestrelCollide.java` (gated by `KestrelCollideCheck`), but **never called on the live player path**! | Chunk store (`TickLoop`). | High. `KestrelCollide` exists as an isolated component proof; live server does not wire it to players. |
| **Step-Up Path (Auto-Step)** | Candidate step heights up to 0.6 blocks (`Entity.java:1181-1206`). | Implemented in `KestrelCollide.java:243-262`, but unused for live player movement. | `KestrelCollide`. | High. Player movement cannot step up slabs or stairs on the server. |
| **Sneaking Edge Backoff** | `maybeBackOffFromEdge` steps back 0.05 blocks iteratively (`Player.java:876-923`). | Completely absent. | Arithmetic + `KestrelCollide`. | Medium. Server cannot predict or validate crouching player edge-holding. |
| **Player Bounding Box & Poses** | Dynamic poses: standing (0.6x1.8), crouching (0.6x1.5), swimming (0.6x0.6) (`Avatar.java:21-35`). | Hardcoded static constants in `NetServer.java:2098-2099` (`PLAYER_HALF_WIDTH = 0.3`, `PLAYER_HEIGHT = 1.8`). | Player state (`KestrelPlayer`). | High. Sneaking, swimming, and crawling hitboxes are not modelled on the server. |
| **Water / Lava Buoyancy** | `travelInWater`, `travelInLava`, `EntityFluidInteraction` (`LivingEntity.java:2517-2566`). | Naive `isWater` check (`NetServer.java:2064-2076`); replaces caps with `MAX_SWIM_RISE = 7.0`. | Fluid physics engine. | High. Buoyancy, depth strider, dolphin's grace, and sinking velocity are completely unsimulated. |
| **Fluid Current Flow Push** | `applyCurrentTo` adds 0.014 impulse scaled by depth (`EntityFluidInteraction.java:172-190`). | Completely absent. | `TickLoop` fluid states. | Medium. Flowing water and lava do not push players. |
| **Bubble Columns** | `onAboveBubbleColumn`, `onInsideBubbleColumn` (`Entity.java:2857-2900`). | Completely absent. | `TickLoop` block states. | Medium. Whirlpools and elevator bubble columns exert zero vertical force. |
| **Cobwebs & Powder Snow** | `makeStuckInBlock` speeds: web (0.25, 0.05, 0.25), snow (0.9, 1.5, 0.9). | Completely absent. | `TickLoop` block states. | Medium. Players move through webs and powder snow at normal speed without slowdown. |
| **Honey & Slime Bounces** | Bounce restitution (1.0 slime, 0.75 bed), honey slide clamping to -0.05. | Completely absent. | `TickLoop` block states. | Medium. Bouncing on slime/beds and wall sliding on honey are unmodelled. |
| **Ladders, Vines & Scaffolding** | `onClimbable`, `handleOnClimbable` clamping to 0.15, sneak ladder stop. | Completely absent. | `TickLoop` block states. | Medium. Climbing mechanics and sneak-holding on ladders do not exist. |
| **Player Body Ticking (`doTick`)** | Executes food, health, pose, effect, and stat updates (`ServerPlayer.java:677-731`). | Completely absent. `KestrelPlayer` is a network POJO; no tick method runs. | `TickLoop` entity phase. | **CRITICAL DEFECT.** Players never tick body effects, pose transitions, or stat trackers. |

### 5.2 Deep Dive on the Two Primary Divergences

#### Divergence 1: The 1.20 m/tick Speed Clamp vs Vanilla Tolerance
In `NetServer.java:1936-1940`:
```java
// 3. Max horizontal speed per tick (sprint ~0.28 m/tick; allow margin up to 0.60 m/tick).
double horizontalDist = Math.hypot(dx, dz);
if (horizontalDist > MAX_HORIZONTAL_SPEED) { // 1.20
    handleMoveViolation(p);
    return;
}
```
- **The Divergence:** Kestrel arbitrarily rejects any movement packet where horizontal displacement exceeds 1.20 blocks (`MAX_HORIZONTAL_SPEED`).
- **What Vanilla Does:**
  1. Vanilla tolerates macro speeds up to `movedDist - expectedDist > 100.0F * deltaPackets` (`ServerGamePacketListenerImpl.java:1118`). For a single packet, that is **10.0 blocks** of horizontal movement!
  2. Vanilla simulates `player.move(MoverType.PLAYER, delta)`.
  3. If error exceeds 0.25 blocks (`movedDist > 0.0625`), vanilla still accepts the move if `!isEntityCollidingWithAnythingNew` (`:1159`).
- **Why this breaks gameplay:** Legitimate gameplay actions routinely exceed 1.20 m/tick:
  - Sprint-jumping with Speed II: ~0.65 to 0.85 m/tick peak.
  - Knockback from weapons (Knockback II / punch bow): up to 1.5 - 2.5 m/tick.
  - Explosion impulses (TNT / Creeper / Wind Charge): 2.0 - 5.0+ m/tick.
  - Slime block launcher / TNT cannons: 5.0 - 9.0 m/tick.
  - Ice boat transport: 2.0+ m/tick.
  - Network jitter: when client packets bunch up due to latency, 2 ticks of movement arriving in consecutive packets gets rejected, rubber-banding innocent players.

#### Divergence 2: Complete Absence of Player Body Ticking (`doTick`)
In vanilla, `ServerGamePacketListenerImpl.tickPlayer()` (`ServerGamePacketListenerImpl.java:317-362`) runs `player.doTick()` (`ServerPlayer.java:677-731`), which cascades into `Player.tick()` (`Player.java:230-283`), `LivingEntity.tick()` (`LivingEntity.java:2766-2850`), and `Entity.tick()`.
In Kestrel, `KestrelPlayer` is never ticked inside `TickLoop`. Every one of the following steps is skipped:

1. **Pose Updating (`Player.updatePlayerPose`, `Player.java:341-355`):**
   - Vanilla tests whether the player can fit in `STANDING` (1.8m), `CROUCHING` (1.5m), or `SWIMMING` (0.6m) hitboxes.
   - Kestrel never updates bounding boxes. A sneaking or swimming player retains an upright 1.8m hitbox, preventing crawling through 1-block gaps.
2. **Fluid Interaction Updating (`Entity.updateFluidInteraction`, `Entity.java:1659-1683`):**
   - Vanilla updates `fluidInteraction`, checking eye submersion, resetting fall distance upon water contact, and applying water/lava flow impulses.
   - Kestrel skips fluid tracking, causing fall distance to persist through water and ignoring water currents.
3. **Floating Tick Accounting (`ServerGamePacketListenerImpl.tickPlayer`, `ServerGamePacketListenerImpl.java:326-335`):**
   - Vanilla increments `aboveGroundTickCount` when floating in air, kicking after 80 ticks.
   - Kestrel never tracks floating ticks, allowing indefinite floating.
4. **Food & Health Synchronization (`ServerPlayer.doTick`, `ServerPlayer.java:709-716`):**
   - Vanilla ticks `foodData.tick(this)` and dispatches `ClientboundSetHealthPacket` when health, food, or saturation change.
   - Kestrel does not run player food ticking or health broadcasts.
5. **Item Interaction & Equipment Ticking (`LivingEntity.tick`, `LivingEntity.java:2768, 2795`):**
   - Vanilla ticks active item usage (eating, charging bows, blocking shields) and detects armor updates.
   - Kestrel never ticks item usage on the body.
6. **Block Effects Dispatch (`LivingEntity.applyEffectsFromBlocks`, `LivingEntity.java:3145`):**
   - Vanilla dispatches `stepOn` and `entityInside` hooks for standing/submerged blocks.
   - Kestrel never executes block contact effects on players.

---

## 6. Measurement Plan

To ensure bit-exact fidelity with vanilla 26.2 without relying on guesswork, claims will be proven through deterministic differential parity checks (the house pattern established in `PlayerTrackingParityCheck`, `BlockUpdateParityCheck`, `FallingBlockParityCheck`, and `ItemEntityTickCheck`).

### 6.1 Headless Vanilla Oracle Architecture
- **Adapter Level (`OracleLevel` / `VanillaEntityLevel`):**
  Allocated via `sun.misc.Unsafe.allocateInstance` without executing constructors. Implements `ServerLevel` backed by Kestrel's deterministic `SectionStore` block state memory.
- **Oracle Instances:**
  - Real vanilla `ServerPlayer` instance constructed with a dummy network connection.
  - Real vanilla `ServerGamePacketListenerImpl` wired to the player.
  - Paired side-by-side with Kestrel's `KestrelPlayer` and live physics validation pipeline.
- **Verification Discipline:**
  - Bit-identical comparisons across positions (`double`), velocity (`Vec3`), bounding boxes (`AABB`), `onGround` (`boolean`), `fallDistance` (`double`), and pose (`Pose`).
  - Strict zero-epsilon tolerance on arithmetic: Kestrel must replicate vanilla IEEE 754 float/double rounding exactly.

### 6.2 Provenance Matrix: Headless Parity Check vs Recorded Trace

| Verification Target | Proved by Headless Parity Check (`OracleLevel`) | Proved by Recorded Live Trace | Verification Harness & Strategy |
|---|---|---|---|
| **Collision Resolution (`Entity.collide`)** | Yes | Optional | `PlayerCollisionParityCheck`: Feeds identical movement vectors and bounding boxes across 200+ block shapes (stairs, slabs, fences, walls, cauldrons, honey). Compares resolved movement vector to the exact bit. |
| **Step-Up Candidate Heights** | Yes | Optional | `PlayerCollisionParityCheck`: Compares `candidateStepUpHeights` array output between vanilla and `KestrelCollide` across diverse terrain profiles. |
| **Block Friction & Air Drag** | Yes | Optional | `PlayerMovementPhysicsCheck`: Executes 100 ticks of freefall and sliding across ice, blue ice, slime, and soul sand. Verifies position and delta movement bit-for-bit. |
| **Fluid Buoyancy & Currents** | Yes | Optional | `PlayerFluidParityCheck`: Places oracle and Kestrel bodies in flowing water, still lava, and bubble columns. Compares vertical sinking settling velocities and horizontal flow displacements. |
| **Sneaking Edge Backoff** | Yes | Optional | `PlayerEdgeBackoffCheck`: Simulates crouching player walking off cliffs, slab edges, and trapdoors. Compares clamped movement vector. |
| **Movement Validation (`handleMovePlayer`)** | Yes | Optional | `PlayerMovementValidationCheck`: Feeds scripted sequences of packets (normal sprint, jump, sprint-jump, packet bunching, high speed knockback, wall phasing attempts). Asserts identical accept/reject decisions and snapback coordinates. |
| **Flying Kick 80-Tick Threshold** | Yes | Optional | `PlayerMovementValidationCheck`: Feeds 79 floating packets followed by 80th packet; verifies kick packet dispatch on tick 81. |
| **Live Client Lag & Jitter Tolerance** | Optional | **Yes** | Recorded client packet trace from real client play: Replays packet stream through Kestrel's movement validator to ensure zero false rubber-bands during normal play. |

---

## 7. Vertical Slices

Four ordered, independently testable implementation slices. Each slice unblocks the subsequent milestone and is bounded by a single concrete parity gate.

### 7.1 Slice 1: Terrain Collision & Step-Up Fast Path for Players
- **Scope & Code Landed:**
  - Wire `KestrelCollide` into player movement resolution.
  - Supply player bounding boxes: `STANDING` (0.6x1.8), `CROUCHING` (0.6x1.5), `SWIMMING` (0.6x0.6).
  - Implement `Player.maybeBackOffFromEdge` and `canFallAtLeast` for crouching ledge hold.
  - Implement `Direction.axisStepOrder` (Y first, then largest horizontal component).
  - Port `collectCandidateStepUpHeights` supporting `maxUpStep = 0.6F`.
- **Vanilla Code Referenced:**
  - `Entity.java:1150-1206` (`collide`, `collectCandidateStepUpHeights`, `collideBoundingBox`)
  - `Player.java:876-943` (`maybeBackOffFromEdge`, `canFallAtLeast`, `isAboveGround`)
  - `Avatar.java:21-35` (Pose bounding boxes)
- **Gating Oracle:** `PlayerCollisionParityCheck`
  - Runs real vanilla `Entity.collide` and `Player.maybeBackOffFromEdge` against Kestrel's collision path over a synthetic obstacle course (stairs, slabs, fences, trapdoors, sneaking ledge walks).
- **Mutation Campaign:**
  1. Mutate `maxUpStep` from 0.6 to 0.5 (fails stair walking).
  2. Invert axis resolution order from Y-first to X-first (fails vertical obstacle collision).
  3. Mutate `maybeBackOffFromEdge` step increment from 0.05 to 0.10 (fails precision edge hold).
  4. Change crouching box height from 1.5 to 1.6 (fails 1.5-block tunnel entrance).
- **Risk:** Low. `KestrelCollide` is already proven in `KestrelCollideCheck`; this slice wires it to player geometries.

### 7.2 Slice 2: Block & Fluid Environmental Physics
- **Scope & Code Landed:**
  - Implement `EntityFluidInteraction` for players: water/lava buoyancy, settling velocities, drag (0.8 water, 0.5 lava), and current flow push (0.014 water, 0.00233 lava).
  - Implement `BubbleColumnBlock` forces: drag down (-0.9 surface, -0.3 submerged) and push up (+1.8 surface, +0.7 submerged).
  - Implement `WebBlock` and `PowderSnowBlock` stuck speeds via `makeStuckInBlock`.
  - Implement `HoneyBlock` sliding (-0.05 clamp) and `SlimeBlock` bounce restitution (1.0).
  - Implement climbing physics for ladders, vines, and scaffolding.
  - Implement `isInWall` 0.48-width eye suffocation check.
- **Vanilla Code Referenced:**
  - `EntityFluidInteraction.java:32-190`
  - `Entity.java:1659-1683, 2262-2278, 2857-2900`
  - `WebBlock.java:27-36`, `PowderSnowBlock.java:62-132`, `HoneyBlock.java:64-115`, `SlimeBlock.java:23-38`
- **Gating Oracle:** `PlayerBlockPhysicsParityCheck`
  - Simulates an entity passing through webs, powder snow, honey, slime, bubble columns, and fluid currents, diffing position and velocity vectors bit-for-bit against vanilla.
- **Mutation Campaign:**
  1. Mutate web speed multiplier from 0.25 to 0.30 (fails web descent test).
  2. Invert bubble column push/drag directions (fails bubble elevator tests).
  3. Mutate slime bounce restitution from 1.0 to 0.9 (fails vertical bounce peak).
  4. Mutate water flow push scale from 0.014 to 0.020 (fails water stream displacement).
- **Risk:** Medium. Requires precise fluid height and flow direction evaluation from chunk stores.

### 7.3 Slice 3: Server-Side Movement Validation & Anti-Cheat Tightening (Tightens S8)
- **Scope & Code Landed:**
  - **TIGHTENS S8:** Replaces arbitrary `MAX_HORIZONTAL_SPEED = 1.20` and `MAX_TELEPORT_DISTANCE = 64.0` with vanilla's exact anti-cheat algorithms.
  - Implement `handleMovePlayer` pipeline in `NetServer`:
    - Macro speed check: `metersPerTick * deltaPackets` (100.0F / 300.0F distance squared).
    - Speculative move simulation via Slice 1 collision kernel.
    - Error tolerance threshold `movedDist > 0.0625` with post-impulse grace time suppression.
    - Phasing prevention via `isEntityCollidingWithAnythingNew`.
    - `clientIsFloating` evaluation and 80-tick flying kick.
- **Vanilla Code Referenced:**
  - `ServerGamePacketListenerImpl.java:1068-1205` (`handleMovePlayer`, `isEntityCollidingWithAnythingNew`, `shouldCheckPlayerMovement`)
  - `ServerGamePacketListenerImpl.java:326-372` (`tickPlayer` floating check, `getMaximumFlyingTicks`)
- **Gating Oracle:** `PlayerMovementValidationCheck`
  - Replays streams of movement packets (legal sprint jumps, high speed knockback, block phasing attempts, packet bunching) against vanilla `ServerGamePacketListenerImpl` and Kestrel's validator, verifying identical accept/teleport/kick outcomes.
- **Mutation Campaign:**
  1. Mutate macro speed limit from 100.0F to 80.0F (fails legal sprint-jump jitter test).
  2. Mutate error threshold from 0.0625 to 0.04 (fails legal step-up tolerance).
  3. Bypass `isEntityCollidingWithAnythingNew` (fails wall-clip exploit test).
  4. Mutate flying kick threshold from 80 ticks to 100 ticks (fails fly kick timing).
- **Risk:** High. Tuning validation thresholds too tightly causes false rubber-bands; tuning too loosely permits noclip. Grounding in exact vanilla arithmetic eliminates both failure modes.

### 7.4 Slice 4: Player Body Ticking & State Integration (Bridges G15/G24)
- **Scope & Code Landed:**
  - Integrate player body ticking into Kestrel's `TickLoop` entity phase.
  - Implement `resetPosition()`, `xo/yo/zo` snapshotting, and `absSnapTo(firstGoodX, ...)` position restoration.
  - Execute player pose transitions (`updatePlayerPose`) based on collision fit.
  - Wire fall distance accumulation and `checkFallDamage` integration with G22.
  - Wire food exhaustion hooks (`causeFoodExhaustion`) and health synchronization packets (`ClientboundSetHealthPacket`).
- **Vanilla Code Referenced:**
  - `ServerGamePacketListenerImpl.java:317-362` (`tickPlayer`)
  - `ServerPlayer.java:677-731` (`doTick`)
  - `Player.java:230-283, 341-355` (`tick`, `updatePlayerPose`)
- **Gating Oracle:** `PlayerTickParityCheck`
  - Runs 500 ticks of player living simulation, asserting identical state synchronization, pose transitions, and health/food packet delivery.
- **Mutation Campaign:**
  1. Omit `absSnapTo` at end of `tickPlayer` (fails position drift test).
  2. Mutate pose collision test box deflation (fails crawling pose admission).
  3. Omit `updateFluidInteraction` in tick (fails water fall-damage reset).
- **Risk:** Medium. Requires careful integration with `TickLoop` ordering.

---

## 8. Report and Handoff

### 8.1 What Was Verified
1. **Vanilla Movement Anti-Cheat Arithmetic:** Verified in `ServerGamePacketListenerImpl.java:1068-1205` that vanilla uses a macro threshold of `100.0F * deltaPackets` distance squared (10 blocks/packet) rather than a narrow per-tick clamp, and an error threshold of `0.0625` distance squared (0.25 blocks) paired with `isEntityCollidingWithAnythingNew`.
2. **The 80-Tick Flying Kick:** Verified in `ServerGamePacketListenerImpl.java:326-372` that vanilla kicks for flying after 80 consecutive floating ticks (`getMaximumFlyingTicks`).
3. **The `absSnapTo` Snapback Mechanism:** Verified in `ServerGamePacketListenerImpl.java:323` that the server snaps player coordinates back to `firstGoodX/Y/Z` at the end of every `tickPlayer`, confirming that client packets are authoritative for position while the server only verifies validity and applies side effects.
4. **All Movement Constants:** Grounded exact values for gravity (`0.08`), jump strength (`0.42F`), step height (`0.6F`), air drag (`0.98F` vertical, `0.91F` horizontal), water drag (`0.80F` / `0.90F`), block slipperiness (`0.60F`, `0.98F`, `0.989F`), and terminal velocities.
5. **Modern Block Hooks:** Confirmed that `updateEntityMovementAfterFallOn` does not exist in 26.2; block bounciness is handled via `Entity.restituteMovementAfterCollisions` reading `block.getBounceRestitution()`.

### 8.2 Assumed, Not Proven
1. **Network Jitter Distribution:** It is assumed that real client packet arrival jitter under typical high-latency connections does not exceed 5 packets per tick (the vanilla threshold where `deltaPackets` is clamped to 1 in `ServerGamePacketListenerImpl.java:1109-1114`).
2. **Entity-vs-Entity Push Impact on Players:** It is assumed that entity pushing (mobs or other players pushing the player) can be deferred until mob AI (`G7`/`G45`) and player bodies (`G15`) land, as no entities currently push players on Kestrel's live server.

### 8.3 The Three Things to Tell the Next Worker First
1. **Never use a hard horizontal speed cap like 1.20 m/tick:** Vanilla tolerates up to 10 blocks in a single packet (`100.0F` distance squared) for macro speed, and validates fine motion by running `player.move(MoverType.PLAYER)` and testing `isEntityCollidingWithAnythingNew`. Hard clamps will always rubber-band legitimate players during knockback, explosions, or network lag.
2. **Remember the `absSnapTo` position snapback in `tickPlayer`:** Do not attempt to let `player.doTick()` advance the player's world position on the server. In vanilla, `Player.isClientAuthoritative()` is true, so the server snaps coordinates back to `firstGoodX/Y/Z` every tick. Movement packets are the sole authority for coordinate progression.
3. **`KestrelCollide` is already built but unhooked:** The swept AABB collision and step-up algorithms already exist in `core/src/vanilla/java/dev/kestrel/collision/KestrelCollide.java` (proven in `KestrelCollideCheck` to be 6.6x faster than vanilla). Slice 1 only needs to wire player dimensions and step height (`0.6F`) into this existing fast path.
