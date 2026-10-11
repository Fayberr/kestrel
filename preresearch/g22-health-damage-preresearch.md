# G22 Pre-Research: Health, Damage, Death, and Respawn Parity Specification

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) in `~/kestrel-run/vanilla-src-26.2`, datapack game data, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for roadmap item G22 (health, damage, death, respawn) in Kestrel.
Scope: Architectural analysis, vanilla code audits with exact file:line citations, network wire protocols, environmental damage math, combat tracking, death message generation, respawn algorithms, keepInventory handling, dependency mapping, gap audits, and vertical slice plans with oracle gates and mutants. No Java source changes, no builds, no servers.

---

## 1. Executive Summary and Scope

### 1.1 Scope Boundary
Roadmap item G22 establishes the complete survival health, damage, death, and respawn subsystem for Kestrel:
- Authoritative health tracking on players and living entities (`LivingEntity.java:1180-1186`, `Player.java:678-706`), clamped to `[0.0, maxHealth]`.
- Damage pipeline dispatch (`hurtServer`, `actuallyHurt`), damage sources (`DamageSources.java:18-73`, `DamageTypes.java:8-60`), difficulty scaling, and the 20-tick invulnerability window with greater-damage delta absorption (`LivingEntity.java:1223-1238`).
- Environmental damage systems:
  - Void damage (`checkBelowWorld()`, `Y < minY - 64`, 4.0F damage bypassing invulnerability).
  - Fall damage math (`calculateFallPower`, `calculateFallDamage`), safe fall distance attributes, block landing dampening (hay block, bed, honey, slime), and fluid resets (water, lava).
  - Drowning and air supply (300 ticks max, decreasing while submerged, bubble particles, 2.0F drown damage at -20 ticks).
  - Fire and lava (ignition durations, 1.0F fire tick damage every 20 ticks, 4.0F lava damage every 10 ticks, fire clearing in water/rain, `ON_FIRE` entity data flag).
  - Suffocation (`isInWall()`, eye bounding box overlap against suffocating blocks, 1.0F in-wall damage).
  - Contact hazards (cactus block `entityInside` dealing 1.0F damage; sweet berry bush age-based damage, movement threshold >= 0.003, and slowdown).
- Hunger and food interlock touching health (`FoodData.java:32-72`):
  - Fast natural regeneration (food 20, saturation > 0: 1 HP / 10 ticks, consuming saturation).
  - Slow natural regeneration (food >= 18: 1 HP / 80 ticks, consuming 6.0 exhaustion).
  - Starvation (food 0: 1 HP / 80 ticks, gated by difficulty floors: Hard kills, Normal caps at 1.0 HP, Easy caps at 10.0 HP).
  - Damage exhaustion addition via `DamageSource.getFoodExhaustion()`.
- Combat tracking and death messages (`CombatTracker.java:19-163`):
  - `CombatEntry` history, assisted fall tracking, intentional game design links.
  - Death sequence (`ServerPlayer.die`): `ClientboundPlayerCombatKillPacket` (`0x44`) triggering client death screen, death event broadcast (entity event 3), and chat broadcasts gated by `showDeathMessages` and team visibility.
  - Immediate respawn gamerule (`immediate_respawn`) sending `ClientboundGameEventPacket` event 11.
- Respawn protocol (`ServerboundClientCommandPacket` `0x0C` `PERFORM_RESPAWN`):
  - Spawn resolution (`ServerPlayer.findRespawnAndUseSpawnBlock`): respawn anchor charge decrement and sound, bed stand-up offset search, missing respawn block game event 0 fallback, and world spawn adjustment via heightmaps.
  - Respawn network sequence (`PlayerList.respawn`): `ClientboundRespawnPacket` (`0x52`), position teleport, difficulty sync, experience sync, health reset to 20.0F.
  - `keepInventory` gamerule: inventory drop vs retention across respawn.

### 1.2 Relationship to Other Roadmap Items
G22 sits directly on top of the entity engine and forms the core requirement for combat, food, and game modes:
- **S14 (Entity System):** Provides entity spatial index, ID allocation (`EntityIds`), entity tick lifecycle in `TickLoop`, synched entity data (`SynchedEntityData`), entity events (`ClientboundEntityEventPacket`), and network tracking (`EntityTracker`, `EntityOutbound`). G22 adds living entity state (health, damage, death) to these entities.
- **G15 (Player Visibility & Bodies):** Today `KestrelPlayer` is a network session state rather than an entity. G15 supplies player entity tracking and visual models. G22 hooks into G15 to broadcast hurt tilt (`ClientboundHurtAnimationPacket`), damage events (`ClientboundDamageEventPacket`), death animations (entity event 3), and fire overlays to tracking peers.
- **G17 (Items & Inventory):** When a player dies without `keepInventory`, `Player.dropEquipment()` drops inventory and equipment into the world as `ItemEntity`s (which requires G17 stack codecs and S14 item entities). G17 equipment slots (armor) also supply defense points reducing fire/lava/cactus damage.
- **G23 (Hunger & Food):** G22 implements food ticking only as far as it heals health (regeneration) or damages health (starvation) or adds damage exhaustion. G23 implements full eating mechanics, food items, nutrition tables, and action exhaustion (sprinting, jumping, swimming, mining).
- **G25 (Combat):** G22 provides the receiver side of attacks (`hurtServer`, invulnerability frames, knockback, damage events, death). G25 implements attacker mechanics (melee sweep, attack cooldown, crit multipliers, weapon damage, shield blocking disable).
- **G31 (Game Modes):** Creative and Spectator modes set `abilities.invulnerable = true`, which suppresses all damage in `Player.hurtServer(ServerLevel, DamageSource, float)` (except `bypasses_invulnerability` like the void).

---

## 2. 26.2 Health and Attribute Architecture

### 2.1 Max Health and Synced Health State
In Minecraft 26.2, maximum health is an attribute registered in `Registries.ATTRIBUTE` (`Attributes.MAX_HEALTH`):
- `LivingEntity.getMaxHealth()` (`LivingEntity.java:1997-1999`):
  ```java
  public final float getMaxHealth() {
     return (float)this.getAttributeValue(Attributes.MAX_HEALTH);
  }
  ```
- Base default value for players: `20.0F` (10 hearts).
- Current health storage:
  `LivingEntity` synchronizes health via `SynchedEntityData` using `DATA_HEALTH_ID` (`LivingEntity.java:1180-1182`):
  ```java
  public void setHealth(final float health) {
     this.entityData.set(DATA_HEALTH_ID, Mth.clamp(health, 0.0F, this.getMaxHealth()));
  }
  ```
  Health is strictly clamped between `0.0F` and `getMaxHealth()`.
- Alive check (`LivingEntity.java:1184-1186`):
  ```java
  public boolean isDeadOrDying() {
     return this.getHealth() <= 0.0F || this.dead;
  }
  ```

### 2.2 LivingEntity Damage Pipeline
The authoritative entry point for all damage in 26.2 is `hurtServer(ServerLevel, DamageSource, float)` (`LivingEntity.java:1189-1280`):
1. **Invulnerability & Living Filters (`LivingEntity.java:1190-1196`):**
   - Tests `this.isInvulnerableTo(level, source)`.
   - Tests `this.isDeadOrDying()`: returns false immediately if already dead.
   - Fire resistance check: if `source.is(DamageTypeTags.IS_FIRE)` and entity has `MobEffects.FIRE_RESISTANCE`, returns false.
2. **Player Game Mode Filter (`Player.java:678-683`):**
   - Tests `this.abilities.invulnerable && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)`: returns false.
3. **Difficulty Scaling (`Player.java:689-701`):**
   - If `source.scalesWithDifficulty()` is true:
     - `PEACEFUL`: `damage = 0.0F`
     - `EASY`: `damage = Math.min(damage / 2.0F + 1.0F, damage)`
     - `HARD`: `damage = damage * 3.0F / 2.0F`
4. **Item Blocking (`LivingEntity.java:1206-1209`):**
   - Shield blocking via `applyItemBlocking` reduces incoming damage.
5. **Invulnerability Ticks & Greater-Damage Delta Window (`LivingEntity.java:1223-1238`):**
   Discussed in detail in Section 2.3.
6. **Damage Application (`actuallyHurt`, `Player.java:745-765`):**
   - Armor absorb: `getDamageAfterArmorAbsorb(source, dmg)`.
   - Magic absorb: `getDamageAfterMagicAbsorb(source, dmg)`.
   - Absorption buffer subtraction (`absorptionAmount`).
   - Food exhaustion addition: `this.causeFoodExhaustion(source.getFoodExhaustion())` (`Player.java:757`).
   - Combat tracker record: `this.getCombatTracker().recordDamage(source, finalDamage)` (`Player.java:758`).
   - Health reduction: `this.setHealth(this.getHealth() - finalDamage)` (`Player.java:759`).
   - Game event: `this.gameEvent(GameEvent.ENTITY_DAMAGE)` (`Player.java:764`).
7. **Broadcast & Animations on Full Damage (`LivingEntity.java:1242-1271`):**
   - Damage event broadcast: `level.broadcastDamageEvent(this, source)` (emits `ClientboundDamageEventPacket` `0x19` to tracking players and self).
   - Hurt mark: `this.markHurt()`.
   - Knockback: `this.dealDefaultKnockback(source, damage, blocked)` calling `this.indicateDamage(xd, zd)`.
   - Hurt animation: `ServerPlayer.indicateDamage` calculates camera tilt angle `hurtDir` and sends `ClientboundHurtAnimationPacket` (`0x2A`) (`ServerPlayer.java:2159-2162`).
   - Death check: if `isDeadOrDying()`, evaluates `checkTotemDeathProtection(source)` (`LivingEntity.java:1395-1420`). If no totem protects, triggers `die(source)` (`LivingEntity.java:1266`, `ServerPlayer.java:913`).

### 2.3 Invulnerability Ticks and Greater-Damage Delta
Minecraft applies a half-second (10-tick) post-hit invulnerability window, but allows higher incoming damage within that window to deal the delta difference.
In `LivingEntity.java:1223-1238`:
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

Key invariant rules:
1. `invulnerableTime` is initialized to 20 when a fresh full-damage hit lands.
2. In `ServerPlayer.java:614-616`, `invulnerableTime` is decremented once per tick on the player tick loop:
   ```java
   if (this.invulnerableTime > 0) {
      this.invulnerableTime--;
   }
   ```
   (Note: mobs decrement this in `LivingEntity.java:482-484`, but `ServerPlayer` explicitly overrides the decrement in its own `tick()` method).
3. The delta window is active while `invulnerableTime > 10` (i.e. the first 10 ticks / 500 ms after taking damage).
4. If a secondary hit arrives while `invulnerableTime > 10`:
   - If `damage <= lastHurt`: hit is ignored completely and returns false.
   - If `damage > lastHurt`: deals only `damage - lastHurt`, updates `lastHurt = damage`, sets `tookFullDamage = false` (which skips knockback and re-broadcasting damage event).
5. Once `invulnerableTime <= 10`, any incoming hit is treated as a full fresh hit: resets `invulnerableTime = 20`, `lastHurt = damage`, and triggers full knockback and hurt packets.
6. Damage types carrying tag `#minecraft:bypasses_cooldown` ignore the `invulnerableTime > 10` check and always execute as a full hit.

---

## 3. Environmental Damage Systems

### 3.1 Fall Damage Calculation and Impact Dampening
Fall damage occurs when downward movement hits a solid horizontal surface.

#### Fall Distance Tracking (`Entity.java:1569-1587`)
- While entity is airborne and moving downward (`ya < 0.0`), `fallDistance` accumulates:
  ```java
  if (!this.isInWater() && ya < 0.0) {
     this.fallDistance -= (float)ya;
  }
  ```
- When `onGround` becomes true, if `fallDistance > 0.0`:
  1. Invokes block landing hook: `onState.getBlock().fallOn(this.level(), onState, pos, this, this.fallDistance)` (`Entity.java:1576`).
  2. Emits `GameEvent.HIT_GROUND` (`Entity.java:1578-1582`).
  3. Resets fall distance: `this.resetFallDistance()` (`Entity.java:1585`, sets `fallDistance = 0.0`).

#### Damage Formulas (`LivingEntity.java:1858-1869`)
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
- `Attributes.SAFE_FALL_DISTANCE` defaults to `3.0` blocks.
- `Attributes.FALL_DAMAGE_MULTIPLIER` defaults to `1.0`.
- An unaugmented fall from 4 blocks: `4.0 + 1.0E-6 - 3.0 = 1.000001` -> `floor(1.000001) = 1` damage (half heart).
- An unaugmented fall from 23 blocks: `23.0 - 3.0 = 20` damage (kills a 20 HP player).

#### Block Landing Modifiers
Specific blocks override `Block.fallOn`:
- `HayBlock.java:25-27`: `entity.causeFallDamage(fallDistance, 0.2F, level.damageSources().fall())` (80% damage reduction).
- `BedBlock.java:132-134`: `super.fallOn(level, state, pos, entity, fallDistance * 0.5)` (50% fall distance reduction).
- `HoneyBlock.java:52-61`: broadcasts entity event 54, `entity.causeFallDamage(fallDistance, 0.2F, level.damageSources().fall())` (80% damage reduction).
- `SlimeBlock.java:23-27`: if entity is not suppressing bounce (not crouching), deals `0.0F` damage and bounces the entity (`deltaMovement.y = -deltaMovement.y`). If crouching, takes full standard fall damage.
- Water: entering water resets fall distance (`this.resetFallDistance()`), dealing 0 damage regardless of height.
- Lava: `fallDistance *= 0.5` per tick while in lava (`Entity.java:561`).

### 3.2 Drowning and Air Supply
Living entities consume air when submerged in water.

#### Submersion Check (`LivingEntity.java:447-469`)
- Condition: `this.isEyeInFluid(FluidTags.WATER) && !level.getBlockState(BlockPos.containing(this.getX(), this.getEyeY(), this.getZ())).is(Blocks.BUBBLE_COLUMN)`
- Drown eligibility:
  `boolean canDrownInWater = !this.canBreatheUnderwater() && !MobEffectUtil.hasWaterBreathing(this) && (!isPlayer || !((Player)this).getAbilities().invulnerable);`

#### Air Tick Mechanics (`LivingEntity.java:454-469`, `599-613`)
- Initial/max air supply: `300` ticks (15 seconds) (`Entity.java:258`, `DATA_AIR_SUPPLY_ID`).
- While submerged:
  `this.setAirSupply(this.decreaseAirSupply(this.getAirSupply()));`
  - Respiration bonus: `Attributes.OXYGEN_BONUS`. If bonus $> 0$, chance `random.nextDouble() >= 1.0 / (oxygenBonus + 1.0)` to preserve air; otherwise `currentSupply - 1`.
- Drowning trigger (`LivingEntity.java:455-459`, `517-519`):
  ```java
  protected boolean shouldTakeDrowningDamage() {
     return this.getAirSupply() <= -20;
  }
  ```
  When air reaches `-20` ticks (1 second after exhausting 0):
  1. `this.setAirSupply(0);` (resets air counter to 0 so the next damage occurs in exactly 20 ticks).
  2. `level.broadcastEntityEvent(this, (byte)67);` (spawns drowning bubble particles).
  3. Deals `2.0F` (1 heart) damage via `damageSources().drown()`.
- While not submerged:
  Air refills rapidly: `increaseAirSupply(currentSupply)` adds `+4` ticks per game tick until reaching 300 ticks (`LivingEntity.java:611-613`).

### 3.3 Fire and Lava Hazards

#### Fire Duration (`remainingFireTicks`)
- Ignited by lava (`Entity.java:618-622`): `lavaIgnite()` calls `igniteForSeconds(15.0F)` (300 ticks).
- Ignited by fire block (`FireBlock.java`): calls `igniteForSeconds(8.0F)` (160 ticks).
- Clear fire (`Entity.java:556-558`): entity clears fire ticks immediately if `isInWaterOrRain()` or `fireImmune()`.

#### Tick Damage Cadence (`Entity.java:545-555`, `624-635`)
1. **Lava Contact (`lavaHurt()`):**
   - If not fire immune, deals `4.0F` (2 hearts) damage via `damageSources().lava()` (`DamageTypes.LAVA`, exhaustion 0.1) every 10 ticks.
   - Plays `SoundEvents.GENERIC_BURN` at volume 0.4F.
2. **Burning on Fire (`Entity.java:549-551`):**
   - While `remainingFireTicks > 0` and not in lava:
     `if (this.remainingFireTicks % 20 == 0 && !this.isInLava())`
     Deals `1.0F` (half heart) damage via `damageSources().onFire()` (`DamageTypes.ON_FIRE`, exhaustion 0.0).
   - Decrements `remainingFireTicks--`.
3. **Visual Fire Overlay:**
   - SynchedEntityData index 0 (`DATA_SHARED_FLAGS_ID`): bit 0 is `ON_FIRE` (`Entity.java:586-588`). Updated whenever `remainingFireTicks > 0`.

### 3.4 Suffocation (In-Wall Damage)
Living entities take suffocation damage when their head is encased inside solid suffocating blocks.

#### Detection Math (`Entity.java:2262-2278`)
- Bounding box at eye height:
  ```java
  float checkWidth = this.dimensions.width() * 0.8F;
  AABB eyeBb = AABB.ofSize(this.getEyePosition(), checkWidth, 1.0E-6, checkWidth);
  ```
- Evaluates whether any block intersecting `eyeBb` is non-air, has `state.isSuffocating(level, pos) == true`, and possesses a collision shape that intersects `eyeBb`.
- If true, in `LivingEntity.java:435-436`:
  `this.hurtServer(level, this.damageSources().inWall(), 1.0F);`
  Deals `1.0F` (half heart) damage via `DamageTypes.IN_WALL` (exhaustion 0.0).
- Because standard invulnerability is 20 ticks, suffocation damage occurs at exactly 20-tick (1-second) intervals.

### 3.5 Void and World Boundaries

#### Below-World Check (`Entity.java:590-594`, `LivingEntity.java:2184-2186`)
```java
public void checkBelowWorld() {
   if (this.getY() < this.level().getMinY() - 64) {
      this.onBelowWorld();
   }
}
```
- In normal Overworld worlds (`minY = -64`), the threshold is `Y < -128.0`.
- In Nether and End (`minY = 0`), the threshold is `Y < -64.0`.
- When triggered:
  `this.hurt(this.damageSources().fellOutOfWorld(), 4.0F);`
- `DamageTypes.FELL_OUT_OF_WORLD` (`out_of_world`) is tagged with `#minecraft:bypasses_invulnerability` (`data/minecraft/tags/damage_type/bypasses_invulnerability.json`). It deals 4.0F (2 hearts) every tick/interval directly, bypassing creative flight and resistance.

### 3.6 Contact Hazards: Cactus and Sweet Berry Bushes

#### Cactus Block (`CactusBlock.java:126-130`)
- Collision shape is `column(14.0, 0.0, 15.0)` (0.875 x 0.9375 x 0.875) while outline shape is `column(14.0, 0.0, 16.0)`.
- When an entity touches the 1-pixel outer boundary:
  `entity.hurt(level.damageSources().cactus(), 1.0F);`
  Deals `1.0F` damage via `DamageTypes.CACTUS` (exhaustion 0.1).

#### Sweet Berry Bush (`SweetBerryBushBlock.java:82-98`)
- Foxes and Bees are immune (`!entity.is(EntityTypes.FOX) && !entity.is(EntityTypes.BEE)`).
- Applies movement friction: `entity.makeStuckInBlock(state, new Vec3(0.8F, 0.75, 0.8F))`.
- If `state.getValue(AGE) != 0` (age 0 saplings deal zero damage):
  Calculates horizontal movement:
  > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.
  Deals `1.0F` damage via `DamageTypes.SWEET_BERRY_BUSH` (exhaustion 0.1).

---

## 4. Hunger and Food Interlock Touching Health

Roadmap item G22 does not implement player eating or consumable food data (which is G23), but must strictly implement the tick loop interlock where `FoodData` impacts health.

### 4.1 Natural Health Regeneration (`FoodData.java:44-59`)
Regeneration requires game rule `GameRules.NATURAL_HEALTH_REGENERATION = true` and `player.isHurt()`:
1. **Fast Regeneration (`FoodData.java:45-52`):**
   - Condition: `saturationLevel > 0.0F && player.isHurt() && foodLevel >= 20`
   - Every 10 ticks:
     ```java
     float saturationSpent = Math.min(this.saturationLevel, 6.0F);
     player.heal(saturationSpent / 6.0F);
     this.addExhaustion(saturationSpent);
     this.tickTimer = 0;
     ```
     At maximum saturation, heals 1.0 HP (half heart) every 10 ticks (0.5 seconds), consuming 6.0 exhaustion.
2. **Slow Regeneration (`FoodData.java:53-59`):**
   - Condition: `foodLevel >= 18 && player.isHurt()`
   - Every 80 ticks (4.0 seconds):
     ```java
     player.heal(1.0F);
     this.addExhaustion(6.0F);
     this.tickTimer = 0;
     ```
     Heals 1.0 HP (half heart) every 4 seconds, adding 6.0 exhaustion.

### 4.2 Starvation Mechanics (`FoodData.java:60-68`)
- Condition: `foodLevel <= 0`.
- Every 80 ticks (4.0 seconds):
  Checks difficulty-based survival floors:
  ```java
  if (player.getHealth() > 10.0F || difficulty == Difficulty.HARD || player.getHealth() > 1.0F && difficulty == Difficulty.NORMAL) {
     player.hurtServer(level, player.damageSources().starve(), 1.0F);
  }
  ```
  - `HARD`: Starvation damages player down to 0.0F (starvation kills).
  - `NORMAL`: Starvation stops at 1.0F HP (leaves half a heart).
  - `EASY`: Starvation stops at 10.0F HP (leaves 5 hearts).
  - `PEACEFUL`: Food never depletes.
- Damage source: `DamageTypes.STARVE` (exhaustion 0.0).

### 4.3 Damage-Induced Exhaustion
In `Player.java:757`, whenever actual damage is sustained:
```java
this.causeFoodExhaustion(source.getFoodExhaustion());
```
Each damage type specifies its exhaustion in its JSON data (`data/minecraft/damage_type/*.json`):
- `0.1F`: `in_fire`, `lava`, `cactus`, `sweet_berry_bush`, `mob_attack`, `player_attack`, `arrow`.
- `0.0F`: `fall`, `drown`, `starve`, `in_wall`, `out_of_world`, `generic`.
When `exhaustionLevel > 4.0F`, `FoodData.tick` subtracts 4.0F exhaustion and drains 1.0F saturation (or 1 food point if saturation is 0) (`FoodData.java:35-42`).

---

## 5. Combat Tracking, Death Screen, and Death Messages

### 5.1 CombatTracker Architecture
`CombatTracker` (`net/minecraft/world/damagesource/CombatTracker.java:19-163`) maintains a rolling log of damage entries (`CombatEntry.java`):
- Timers: `RESET_DAMAGE_STATUS_TIME = 100` ticks (5 s), `RESET_COMBAT_STATUS_TIME = 300` ticks (15 s) (`CombatTracker.java:20-21`).
- `recordDamage(DamageSource, float)` (`CombatTracker.java:37-50`):
  Records `CombatEntry(source, damage, fallLocation, fallDistance)`.
  If source entity is a `LivingEntity`, enters combat (`inCombat = true`), triggering `ServerPlayer.onEnterCombat()` which sends `ClientboundPlayerCombatEnterPacket` (`0x43`) (`ServerPlayer.java:588-591`).
- `recheckStatus()` (`CombatTracker.java:149-162`):
  If 300 ticks elapse without damage, leaves combat (`inCombat = false`), triggering `ServerPlayer.onLeaveCombat()` which sends `ClientboundPlayerCombatEndPacket` (`0x42`) (`ServerPlayer.java:594-597`).

### 5.2 The Death Sequence and Death Screen
When a player's health drops to 0, `ServerPlayer.die(DamageSource)` executes (`ServerPlayer.java:913-973`):
1. **Game Event:** `this.gameEvent(GameEvent.ENTITY_DIE)`.
2. **Death Message Generation:** Evaluates `showDeathMessages` game rule.
   - If true: resolves localized message `deathMessage = this.getCombatTracker().getDeathMessage()`.
     Sends `ClientboundPlayerCombatKillPacket(this.getId(), deathMessage)` (`0x44`) (`ServerPlayer.java:918-933`).
     Broadcasts `deathMessage` to server chat via `ClientboundSystemChatPacket` (`0x79`) based on team visibility (`ServerPlayer.java:935-941`).
   - If false: sends `ClientboundPlayerCombatKillPacket(this.getId(), CommonComponents.EMPTY)`.
   Receiving `ClientboundPlayerCombatKillPacket` forces the client to open the red Death Screen.
3. **Drop Death Loot:** If not spectator, drops equipment and inventory (`this.dropAllDeathLoot(this.level(), source)`).
4. **Death Animation:** `this.level().broadcastEntityEvent(this, (byte)3);` (entity event 3 triggers death tumble animation and smoke particles for tracking clients).
5. **State Reset:**
   - Stats incremented: `Stats.DEATHS`, `TIME_SINCE_DEATH` reset, `TIME_SINCE_REST` reset (`ServerPlayer.java:964-966`).
   - Fire and freeze cleared: `clearFire()`, `setTicksFrozen(0)`, `setSharedFlagOnFire(false)` (`ServerPlayer.java:967-969`).
   - Stored death location: `setLastDeathLocation(Optional.of(GlobalPos.of(dimension, blockPosition)))` (`ServerPlayer.java:971`).
   - Marks connection: `this.connection.markClientUnloadedAfterDeath()` (`ServerPlayer.java:972`).

### 5.3 Death Message Localization Keys
`CombatTracker.getDeathMessage()` generates translation keys based on the killing blow and combat history (`CombatTracker.java:89-107`):
- Void: `"death.attack.outOfWorld"`
- Fall: `"death.fell.accident.generic"` (or assisted fall variants `"death.fell.assist"` / `"death.fell.finish"` if knocked off by an entity).
- Drowning: `"death.attack.drown"`
- Fire/Lava: `"death.attack.inFire"`, `"death.attack.onFire"`, `"death.attack.lava"`
- Suffocation: `"death.attack.inWall"`
- Cactus: `"death.attack.cactus"`
- Sweet Berry Bush: `"death.attack.sweetBerryBush"`
- Starvation: `"death.attack.starve"`
- Intentional Game Design: `"death.attack.badRespawnPoint.message"` with clickable link to `MCPE-28723` (exploding beds in Nether/End or anchors in Overworld).

### 5.4 Immediate Respawn Gamerule
In `PlayerList.java:165` and `MinecraftServer.java:2133-2136`:
- Game rule `GameRules.IMMEDIATE_RESPAWN` (`immediate_respawn`, default false).
- When set, server transmits `ClientboundGameEventPacket` (`0x26`) with event type 11 (`IMMEDIATE_RESPAWN`, value 1.0F).
- Client skips rendering the death screen entirely and immediately sends `ServerboundClientCommandPacket` (`0x0C`) with `Action.PERFORM_RESPAWN`.

---

## 6. Respawn Protocol and Spawn Location Resolution

### 6.1 Respawn Request Inbound Handshake
When the player clicks "Respawn" on the death screen (or auto-respawns):
1. Client transmits `ServerboundClientCommandPacket` (`0x0C`):
   - Action: `Action.PERFORM_RESPAWN` (`ServerboundClientCommandPacket.java`).
2. Server validates in `ServerGamePacketListenerImpl.handleClientCommand` (`ServerGamePacketListenerImpl.java:1884-1908`):
   > Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

### 6.2 Spawn Position Evaluation
`ServerPlayer.findRespawnAndUseSpawnBlock` (`ServerPlayer.java:1077-1107`) locates the stand-up coordinate:
1. **Respawn Anchor Check (`ServerPlayer.java:1087-1093`):**
   - Tests if block is `RespawnAnchorBlock` and has charge (`CHARGE > 0`):
     `Optional<Vec3> standUpPosition = RespawnAnchorBlock.findStandUpPosition(EntityTypes.PLAYER, level, pos);`
   - If found and not forced: decrements charge (`CHARGE - 1`) via `level.setBlock(pos, ..., 3)`.
   - On client, plays `SoundEvents.RESPAWN_ANCHOR_DEPLETE` (`PlayerList.java:436-451`).
2. **Bed Check (`ServerPlayer.java:1094-1096`):**
   - Tests if block is `BedBlock` and environment allows bed spawn (`BED_RULE.canSetSpawn(level)`):
     `BedBlock.findStandUpPosition(EntityTypes.PLAYER, level, pos, facing, yaw);`
   - Evaluates surrounding stand-up offsets (`BedBlock.java:208-250`) and bunk bed hierarchy.
3. **Missing or Obstructed Bed/Anchor (`ServerPlayer.java:1052`, `TeleportTransition.java:77-84`):**
   - If bed/anchor was destroyed or surrounded by solid blocks, `findRespawnAndUseSpawnBlock` returns `Optional.empty()`.
   - Triggers `TeleportTransition.missingRespawnBlock(this, ...)`:
     - In `PlayerList.java:399-401`: clears saved respawn coordinates so future respawns go to world spawn.
     - Sends `ClientboundGameEventPacket` event 0 (`NO_RESPAWN_BLOCK_AVAILABLE`, `0.0F`) (`PlayerList.java:410`).
     - Client displays chat message: *"Your home bed was missing or obstructed"*.
4. **World Spawn Fallback (`TeleportTransition.java:93-95`, `Entity.java:1425-1430`):**
   - Queries `ServerLevel.getRespawnData().pos()`.
   - Adjusts Y coordinate using the heightmap:
     ```java
     int spawnHeight = level.getChunkAt(spawnBlockPos)
        .getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnBlockPos.getX(), spawnBlockPos.getZ()) + 1;
     return BlockPos.containing(spawnPos.x, spawnHeight, spawnPos.z);
     ```

### 6.3 Server Respawn Network Handshake
In `PlayerList.respawn(ServerPlayer, boolean, Entity.RemovalReason)` (`PlayerList.java:389-455`), the server sends an exact 12-packet sequence to reinitialize the client:
1. `ClientboundGameEventPacket(0, 0.0F)` (if respawn block missing).
2. `ClientboundRespawnPacket(CommonPlayerSpawnInfo, dataToKeep)` (`0x52`, line 416).
3. `ClientboundPlayerPositionPacket` (`0x48`, teleport to spawn position, line 417).
4. `ClientboundSetDefaultSpawnPositionPacket` (`0x61`, line 418).
5. `ClientboundChangeDifficultyPacket` (`0x0A`, line 419).
6. `ClientboundSetExperiencePacket` (`0x67`, line 420).
7. Active potion effects (`ClientboundUpdateMobEffectPacket`, line 421).
8. Level time and gamerules (`ClientboundSetTimePacket`, line 422).
9. Permission level (`ClientboundEntityEventPacket`, line 423).
10. `level.addRespawnedPlayer(player)` (registers into entity tracking, line 424).
11. `player.initInventoryMenu()` (line 427).
12. `player.setHealth(player.getHealth())` sending `ClientboundSetHealthPacket` (`0x68`, 20.0F health, line 428).

### 6.4 `keepInventory` Lifecycle
Controlled by `GameRules.KEEP_INVENTORY` (`keep_inventory`, default false):
- **On Death (`Player.java:550-556`):**
  ```java
  if (!level.getGameRules().get(GameRules.KEEP_INVENTORY)) {
     this.destroyVanishingCursedItems();
     this.inventory.dropAll();
  }
  ```
  If false: drops all items, deletes Curse of Vanishing items.
  If true: leaves inventory completely untouched.
- **On Respawn (`ServerPlayer.restoreFrom`, `ServerPlayer.java:1642-1646`):**
  ```java
  this.setHealth(this.getMaxHealth());
  if (this.level().getGameRules().get(GameRules.KEEP_INVENTORY) || oldPlayer.isSpectator()) {
     this.transferInventoryXpAndScore(oldPlayer);
  }
  ```
  If false: respawned player gets a fresh, empty inventory.
  If true: restores inventory items, XP level, and total XP from `oldPlayer`.

---

## 7. Network Wire Protocol and Packet Inventory

All packet numeric IDs cited here are counted directly off the registration order in `net/minecraft/network/protocol/game/GameProtocols.java:60-274` in the decompiled vanilla 26.2 server jar.

### 7.1 Serverbound Packets (Play Phase)
| Packet Type | Name in 26.2 | Index / Hex ID | Registration Citation |
|---|---|---|---|
| `SERVERBOUND_CLIENT_COMMAND` | `ServerboundClientCommandPacket` | `12` (`0x0C`) | `GameProtocols.java:73` |

Payload for `ServerboundClientCommandPacket` (`0x0C`):
- `action`: VarInt enum (`0`: `PERFORM_RESPAWN`, `1`: `REQUEST_STATS`).

### 7.2 Clientbound Packets (Play Phase)
Note: Clientbound index 0 is `CLIENTBOUND_BUNDLE` (`0x00`), which shifts all subsequent packet IDs by +1 relative to legacy protocols.

| Packet Type | Name in 26.2 | Index / Hex ID | Registration Citation |
|---|---|---|---|
| `CLIENTBOUND_CHANGE_DIFFICULTY` | `ClientboundChangeDifficultyPacket` | `10` (`0x0A`) | `GameProtocols.java:143` |
| `CLIENTBOUND_DAMAGE_EVENT` | `ClientboundDamageEventPacket` | `25` (`0x19`) | `GameProtocols.java:158` |
| `CLIENTBOUND_ENTITY_EVENT` | `ClientboundEntityEventPacket` | `34` (`0x22`) | `GameProtocols.java:167` |
| `CLIENTBOUND_GAME_EVENT` | `ClientboundGameEventPacket` | `38` (`0x26`) | `GameProtocols.java:171` |
| `CLIENTBOUND_HURT_ANIMATION` | `ClientboundHurtAnimationPacket` | `42` (`0x2A`) | `GameProtocols.java:175` |
| `CLIENTBOUND_PLAYER_COMBAT_END` | `ClientboundPlayerCombatEndPacket` | `66` (`0x42`) | `GameProtocols.java:199` |
| `CLIENTBOUND_PLAYER_COMBAT_ENTER` | `ClientboundPlayerCombatEnterPacket` | `67` (`0x43`) | `GameProtocols.java:200` |
| `CLIENTBOUND_PLAYER_COMBAT_KILL` | `ClientboundPlayerCombatKillPacket` | `68` (`0x44`) | `GameProtocols.java:201` |
| `CLIENTBOUND_PLAYER_POSITION` | `ClientboundPlayerPositionPacket` | `72` (`0x48`) | `GameProtocols.java:205` |
| `CLIENTBOUND_RESPAWN` | `ClientboundRespawnPacket` | `82` (`0x52`) | `GameProtocols.java:215` |
| `CLIENTBOUND_SET_DEFAULT_SPAWN_POSITION` | `ClientboundSetDefaultSpawnPositionPacket` | `97` (`0x61`) | `GameProtocols.java:230` |
| `CLIENTBOUND_SET_ENTITY_DATA` | `ClientboundSetEntityDataPacket` | `99` (`0x63`) | `GameProtocols.java:232` |
| `CLIENTBOUND_SET_EXPERIENCE` | `ClientboundSetExperiencePacket` | `103` (`0x67`) | `GameProtocols.java:236` |
| `CLIENTBOUND_SET_HEALTH` | `ClientboundSetHealthPacket` | `104` (`0x68`) | `GameProtocols.java:237` |
| `CLIENTBOUND_SYSTEM_CHAT` | `ClientboundSystemChatPacket` | `121` (`0x79`) | `GameProtocols.java:254` |

### 7.3 Detailed Wire Codecs

#### `ClientboundSetHealthPacket` (`0x68`) (`ClientboundSetHealthPacket.java:28-32`)
1. `health`: Float (4 bytes, IEEE 754)
2. `food`: VarInt (1-5 bytes)
3. `saturation`: Float (4 bytes, IEEE 754)

#### `ClientboundDamageEventPacket` (`0x19`) (`ClientboundDamageEventPacket.java:50-60`)
1. `entityId`: VarInt
2. `sourceType`: VarInt (registry ID in `Registries.DAMAGE_TYPE`)
3. `sourceCauseId`: VarInt (`writeOptionalEntityId`: writes `id + 1`, 0 if absent)
4. `sourceDirectId`: VarInt (`writeOptionalEntityId`: writes `id + 1`, 0 if absent)
5. `sourcePosition`: Optional Vec3 (`writeBoolean(hasPos)`, followed by 3 Double values if present)

#### `ClientboundHurtAnimationPacket` (`0x2A`) (`ClientboundHurtAnimationPacket.java:22-25`)
1. `id`: VarInt (entity ID)
2. `yaw`: Float (camera tilt direction `hurtDir`)

#### `ClientboundPlayerCombatKillPacket` (`0x44`) (`ClientboundPlayerCombatKillPacket.java:12-18`)
1. `playerId`: VarInt
2. `message`: NBT / JSON Component via `ComponentSerialization.TRUSTED_STREAM_CODEC`

#### `ClientboundRespawnPacket` (`0x52`) (`ClientboundRespawnPacket.java:20-23`)
1. `commonPlayerSpawnInfo`:
   - `dimensionType`: VarInt registry ID
   - `dimension`: Identifier / ResourceKey
   - `seed`: Long (first 8 bytes of SHA-256 seed)
   - `gameType`: Byte (game mode)
   - `previousGameType`: Byte
   - `isDebug`: Boolean
   - `isFlat`: Boolean
   - `lastDeathLocation`: Optional GlobalPos
   - `portalCooldown`: VarInt
   - `seaLevel`: VarInt
2. `dataToKeep`: Byte (`KEEP_ATTRIBUTE_MODIFIERS = 1`, `KEEP_ENTITY_DATA = 2`, `KEEP_ALL_DATA = 3`)

#### `ClientboundGameEventPacket` (`0x26`) (`ClientboundGameEventPacket.java:41-44`)
1. `event`: Unsigned Byte (`0`: `NO_RESPAWN_BLOCK_AVAILABLE`, `11`: `IMMEDIATE_RESPAWN`)
2. `param`: Float (4 bytes)

---

## 8. Current State of Kestrel and Gap Analysis

### 8.1 Repository Audit
Auditing `core/src/vanilla/java/dev/kestrel/`:

1. **`KestrelPlayer.java` (`dev/kestrel/net/KestrelPlayer.java:1-146`):**
   - Owns authoritative position (`x, y, z, yaw, pitch, onGround, lastX, lastY, lastZ`), teleport state, keepalive, and `heldSlot`.
   - **Gaps:** Completely lacks health fields (`health`, `maxHealth`), invulnerability state (`invulnerableTime`, `lastHurt`), food state, air supply ticks, fire ticks, death status (`isDead`), and respawn configuration (`RespawnConfig`).
2. **`NetServer.java` (`dev/kestrel/net/NetServer.java:1425-1525`):**
   - Handles player movement (`0x1E-0x21`), teleport accept (`0x00`), dig action (`0x29`), item use (`0x42`), and chat (`0x07-0x09`).
   - **Gaps:** Completely ignores `PacketIds.PLAY_CLIENT_COMMAND` (`0x0C`). When a dead player clicks respawn, Kestrel silently drops the packet.
3. **`PacketIds.java` (`dev/kestrel/net/PacketIds.java:1-190`):**
   - Contains handshake, login, config, basic play, and S14 entity tracking IDs.
   - **Gaps:** Missing `PLAY_CLIENT_COMMAND` (`0x0C`), `PLAY_DAMAGE_EVENT` (`0x19`), `PLAY_HURT_ANIMATION` (`0x2A`), `PLAY_SET_HEALTH` (`0x68`), `PLAY_PLAYER_COMBAT_KILL` (`0x44`), `PLAY_RESPAWN` (`0x52`), `EVENT_NO_RESPAWN_BLOCK` (0), and `EVENT_IMMEDIATE_RESPAWN` (11).
4. **`EntityOutbound.java` (`dev/kestrel/net/EntityOutbound.java:1-278`):**
   - Serializes S14 tracking packets (`AddEntity`, `MovePosRot`, `SetEntityMotion`, `SetEntityData`).
   - **Gaps:** Lacks packet builders for `SetHealth`, `DamageEvent`, `HurtAnimation`, `PlayerCombatKill`, and `Respawn`.
5. **Entity System (`dev/kestrel/entity/`):**
   - `KestrelEntity` and `KestrelMovingEntity` provide non-living entity physics and tracking for items and falling blocks.
   - **Gaps:** No `LivingEntity` abstraction in Kestrel yet. Player entities (`G15`) do not exist as entities in `EntityLookup` or `EntityTracker`.

### 8.2 Exhaustive Feature Gap Matrix
| Subsystem | Existing Kestrel Code | What is Missing for G22 |
|---|---|---|
| **Health State** | None. `KestrelPlayer` has no health variable. | `health` float clamped to `[0.0, 20.0]`, `maxHealth` attribute, hurt timers. |
| **Damage Pipeline** | None. | `hurtServer` logic, difficulty scaling, damage type registry lookups. |
| **Invulnerability** | None. | 20-tick `invulnerableTime` window, greater-damage delta absorption rule. |
| **Void Damage** | None. Players fall forever into the void. | `checkBelowWorld` at `Y < -128`, dealing 4.0F void damage bypassing invulnerability. |
| **Fall Damage** | `KestrelMovingEntity` calculates block collision boxes, but players do not run it. | Player downward movement accumulation (`fallDistance`), landing damage calculation, hay/bed/honey/slime dampening. |
| **Drowning** | None. | Eye water detection, 300-tick air supply, air decrement, 2.0F drown damage at -20 ticks. |
| **Fire & Lava** | None. | `remainingFireTicks`, 4.0F lava damage every 10 ticks, 1.0F fire tick damage every 20 ticks, fire clearing in water. |
| **Suffocation** | None. | `isInWall` eye bounding box overlap with suffocating blocks, 1.0F damage every 20 ticks. |
| **Cactus & Berries** | None. | `CactusBlock` 1.0F damage on touch, `SweetBerryBush` age/movement damage and slowdown. |
| **Hunger Interlock** | None. | Fast/slow natural regeneration consuming exhaustion, starvation damage down to difficulty floor. |
| **Combat Tracker** | None. | `CombatEntry` history, assisted fall tracking, death message string resolution. |
| **Death Screen** | None. | `ClientboundPlayerCombatKillPacket` (`0x44`), death event 3 broadcast, chat broadcast. |
| **Respawn Protocol** | Inbound `0x0C` unhandled; outbound `0x52` absent. | `handleClientCommand` `PERFORM_RESPAWN`, bed/anchor/spawn resolver, respawn packet burst. |
| **keepInventory** | None. | Inventory drop on death vs retention across respawn. |

---

## 9. Proposed Implementation Slices, Vanilla-Oracle Gates, and Mutants

To safely deliver G22 without regressions, the implementation is decomposed into 6 testable vertical slices.

### Slice 1: Health State Machine, Invulnerability Ticks, and Damage Wire Codecs
- **Objective:** Establish the living health model on `KestrelPlayer`, the 20-tick invulnerability window with delta damage, and binary wire encoders for all outbound damage packets.
- **Classes to Implement / Extend:**
  - `dev.kestrel.net.PacketIds`: Add `PLAY_CLIENT_COMMAND` (`0x0C`), `PLAY_DAMAGE_EVENT` (`0x19`), `PLAY_HURT_ANIMATION` (`0x2A`), `PLAY_SET_HEALTH` (`0x68`), `PLAY_PLAYER_COMBAT_KILL` (`0x44`), `PLAY_RESPAWN` (`0x52`).
  - `dev.kestrel.net.EntityOutbound`: Add binary builders for `setHealth`, `damageEvent`, `hurtAnimation`, `playerCombatKill`.
  - `dev.kestrel.net.KestrelPlayer`: Add `health`, `maxHealth`, `invulnerableTime`, `lastHurt`, `hurtDir`.
  - Implement `hurt(DamageSource, float)` state machine adhering to `LivingEntity.java:1223-1238`.
- **Vanilla-Oracle Gate (`HealthDamageWireCheck.java`):**
  - Construct dummy players in Kestrel and in vanilla's headless server.
  - Assert that Kestrel's `EntityOutbound` packet bytes match vanilla's `ClientboundSetHealthPacket`, `ClientboundDamageEventPacket`, and `ClientboundHurtAnimationPacket` byte-for-byte.
  - Replay an incoming damage sequence:
    - Deal 4.0 damage -> verify 4.0 health deducted, `invulnerableTime = 20`, full damage packets emitted.
    - Advance 2 ticks (invulnerableTime = 18). Deal 2.0 damage -> verify rejected, 0 health deducted, no packets.
    - Advance 2 ticks (invulnerableTime = 16). Deal 7.0 damage -> verify delta 3.0 deducted, `lastHurt = 7.0`, no knockback packet.
    - Advance 12 ticks (invulnerableTime = 4). Deal 2.0 damage -> verify full hit executed, `invulnerableTime = 20`.
- **Mutants:**
  - *Mutant 1:* Change the delta threshold from 10 to 15 ticks (`invulnerableTime > 15.0F`) -> gate fails on 12-tick hit.
  - *Mutant 2:* Allow lower damage to hit during invulnerability (`damage < lastHurt`) -> gate fails on 2.0 damage rejection test.
  - *Mutant 3:* Encode `sourceCauseId` in `ClientboundDamageEventPacket` directly without +1 offset -> gate fails byte diff vs vanilla codec.
  - *Mutant 4:* Clamp health to `[1.0, maxHealth]` instead of `[0.0, maxHealth]` -> gate fails lethal blow check.

### Slice 2: Environmental Hazards Part 1 - Void, Falling, Suffocation
- **Objective:** Implement physical and spatial damage: below-world void checks, fall damage tracking with block dampening, and eye-height wall suffocation.
- **Classes to Implement / Extend:**
  - Extend player movement processing to track `fallDistance` (`ya < 0.0 -> fallDistance -= ya`).
  - Implement `checkFallDamage` and `calculateFallDamage`: formulas from `LivingEntity.java:1858-1869`.
  - Implement block landing hooks: `HayBlock` (0.2x), `BedBlock` (0.5x), `HoneyBlock` (0.2x), `SlimeBlock` (0.0x and bounce unless sneaking), Water (reset to 0.0), Lava (halve fall distance).
  - Implement `checkBelowWorld()` at `Y < -128.0` in Overworld, dealing 4.0F `out_of_world` damage.
  - Implement `isInWall()` using 0.8x width eye-height bounding box intersecting `state.isSuffocating()`.
- **Vanilla-Oracle Gate (`EnvironmentalDamageCheck.java`):**
  - Run identical movements in Kestrel and vanilla:
    - Fall from Y=68 to Y=64 (4 blocks) onto stone -> assert exactly 1.0 damage (half heart).
    - Fall from Y=80 to Y=64 (16 blocks) onto hay block -> assert `floor((13.0) * 0.2) = 2.0` damage.
    - Fall from Y=80 to Y=64 onto slime block while crouching vs uncrouched.
    - Step inside solid stone block -> assert 1.0 damage every 20 ticks.
    - Move below Y=-128.0 -> assert 4.0 void damage every 20 ticks even with invulnerability active.
- **Mutants:**
  - *Mutant 1:* Omit `+ 1.0E-6` in `calculateFallPower` -> fails boundary rounding check at exact integer heights.
  - *Mutant 2:* Forget water fall distance reset -> player dies from high fall into deep ocean.
  - *Mutant 3:* Evaluate suffocation at 1.0x width instead of 0.8x -> false suffocation when standing flush against a wall.
  - *Mutant 4:* Void threshold set to `minY` instead of `minY - 64` -> player dies prematurely at bedrock level.

### Slice 3: Environmental Hazards Part 2 - Fire, Lava, Drowning, Cactus, Sweet Berry Bushes
- **Objective:** Implement fluid, fire, and foliage hazards: air supply ticking, burning timers, lava contact, cactus spikes, and sweet berry bushes.
- **Classes to Implement / Extend:**
  - Air supply: `airSupply` (300 max), decremented in water, incremented by 4 on surface, drown damage 2.0F at -20 ticks with bubble event 67.
  - Fire & Lava: `remainingFireTicks` (300 for lava, 160 for fire), lava contact dealing 4.0F every 10 ticks, fire burning dealing 1.0F every 20 ticks, fire clearing in water/rain.
  - SynchedEntityData flag 0 (`ON_FIRE`) synched when `remainingFireTicks > 0`.
  - Cactus block `entityInside` dealing 1.0F damage.
  - Sweet Berry Bush `entityInside` dealing 1.0F damage when age > 0 and movement >= 0.003.
- **Vanilla-Oracle Gate (`FluidsAndPlantsDamageCheck.java`):**
  - Submerge player in water: assert air ticks decrease 300 to 0 over 300 ticks; assert first damage at tick 320; assert subsequent damage every 20 ticks.
  - Submerge player in lava: assert ignited for 300 ticks, assert 4.0 damage every 10 ticks, assert `ON_FIRE` flag set in entity data.
  - Walk into cactus: assert 1.0 damage.
  - Walk into sweet berry bush: test age 0 (0 damage) vs age 1 (1 damage on movement, 0 damage when stationary).
- **Mutants:**
  - *Mutant 1:* Refill air at +1 tick instead of +4 ticks per tick on surface -> fails recovery rate test.
  - *Mutant 2:* Set air supply to -20 upon drowning rather than 0 -> fails subsequent damage interval check.
  - *Mutant 3:* Deal sweet berry bush damage when age == 0 -> fails seedling pass-through check.
  - *Mutant 4:* Fire ticks not cleared when stepping into water -> fails water extinguish check.

### Slice 4: Hunger Interlock - Natural Regeneration and Starvation
- **Objective:** Wire `FoodData` health interactions: fast/slow natural regeneration consuming exhaustion, starvation damage down to difficulty floor, and damage exhaustion.
- **Classes to Implement / Extend:**
  - Implement `FoodData.tick(ServerPlayer)` logic:
    - Fast regen: food 20, saturation > 0 -> 1 HP / 10 ticks, consuming saturation.
    - Slow regen: food >= 18 -> 1 HP / 80 ticks, consuming 6.0 exhaustion.
    - Starvation: food <= 0 -> 1 HP / 80 ticks, respecting Hard (lethal), Normal (stops at 1 HP), Easy (stops at 10 HP).
  - Damage exhaustion: `causeFoodExhaustion(source.getFoodExhaustion())` on every damage application.
  - Send `ClientboundSetHealthPacket` whenever health, food, or saturation transitions across zero.
- **Vanilla-Oracle Gate (`FoodHealthParityCheck.java`):**
  - Initialize player with 10 HP, 20 food, 5.0 saturation -> verify healed to 20 HP in 100 ticks, saturation depleted.
  - Initialize player with 10 HP, 18 food, 0.0 saturation -> verify heals 1 HP every 80 ticks.
  - Initialize player with 0 food on Normal difficulty -> verify health stops draining at 1.0 HP.
  - Initialize player with 0 food on Hard difficulty -> verify health drains to 0.0 HP (death).
- **Mutants:**
  - *Mutant 1:* Allow fast regen at food 19 -> fails food requirement check.
  - *Mutant 2:* Set slow regen timer to 40 ticks instead of 80 ticks -> fails regen frequency check.
  - *Mutant 3:* Starve player to death on Normal difficulty -> fails 1.0 HP survival floor check.
  - *Mutant 4:* Omit exhaustion addition on cactus damage -> fails exhaustion accumulation check.

### Slice 5: Death Sequence, Combat Tracker, Death Screen & Messages
- **Objective:** Deliver the death lifecycle, combat tracking history, death message translation, and client death screen activation.
- **Classes to Implement / Extend:**
  - Implement `CombatTracker` and `CombatEntry` history records.
  - Implement death message resolution conforming to vanilla translation keys.
  - Trigger `die(DamageSource)` when health reaches 0:
    - Emit `ClientboundPlayerCombatKillPacket` (`0x44`) with formatted death message.
    - Broadcast death message to chat via `ClientboundSystemChatPacket` (`0x79`).
    - Broadcast entity event 3 (death animation/particles).
    - Clear fire, frozen ticks, and burning flag.
  - Support `immediate_respawn` gamerule sending `ClientboundGameEventPacket` event 11.
  - Add inbound `ServerboundClientCommandPacket` (`0x0C`) handler in `NetServer.java`.
- **Vanilla-Oracle Gate (`DeathScreenParityCheck.java`):**
  - Kill player with various damage sources (void, fire, fall, cactus, drowning).
  - Compare generated death message component strings against vanilla `CombatTracker.getDeathMessage()` for 100% string key and argument equality.
  - Verify client receives `ClientboundPlayerCombatKillPacket` (`0x44`) and entity event 3.
  - Verify server rejects `PERFORM_RESPAWN` if sent while player is still alive.
- **Mutants:**
  - *Mutant 1:* Omit player ID in `ClientboundPlayerCombatKillPacket` -> client fails to open death screen.
  - *Mutant 2:* Broadcast death message when `show_death_messages = false` -> fails privacy check.
  - *Mutant 3:* Allow living player to trigger respawn command -> fails exploit check.
  - *Mutant 4:* Forget to clear burning entity flag on death -> player visibly burns on death screen.

### Slice 6: Respawn Protocol, Spawn Location Resolver, and keepInventory
- **Objective:** Complete the respawn cycle: resolve bed, anchor, or world spawn positions, execute the 12-packet respawn handshake, and enforce `keepInventory`.
- **Classes to Implement / Extend:**
  - `ServerPlayer.findRespawnAndUseSpawnBlock`:
    - Check respawn anchor: `RespawnAnchorBlock.findStandUpPosition`, decrement charge, play sound.
    - Check bed: `BedBlock.findStandUpPosition`, bunk bed offsets.
    - Missing block fallback: send game event 0 (`NO_RESPAWN_BLOCK_AVAILABLE`), clear player respawn config, fall back to world spawn.
    - World spawn: `findAdjustedSharedSpawnPos` with motion-blocking heightmap.
  - `PlayerList.respawn` network sequence:
    - Emit `ClientboundRespawnPacket` (`0x52`), teleport to spawn, set default spawn position, change difficulty, set experience, set health to 20.0F.
  - `keepInventory` handling:
    - If false: drop inventory on death, wipe items on respawn.
    - If true: retain inventory, XP, and score on respawn.
- **Vanilla-Oracle Gate (`RespawnProtocolParityCheck.java`):**
  - Place player at charged respawn anchor -> kill player -> send respawn -> assert player spawns at anchor and anchor charge decrements from 4 to 3.
  - Place player at bed -> break bed -> kill player -> send respawn -> assert player spawns at world spawn and receives `NO_RESPAWN_BLOCK_AVAILABLE` game event.
  - Test `keep_inventory = true`: assert inventory and XP retained across death.
  - Test `keep_inventory = false`: assert inventory dropped as `ItemEntity`s and respawned player has empty inventory.
- **Mutants:**
  - *Mutant 1:* Do not decrement anchor charge on respawn -> anchor has infinite charges.
  - *Mutant 2:* Omit `NO_RESPAWN_BLOCK_AVAILABLE` game event when bed is broken -> client never sees missing bed notification.
  - *Mutant 3:* Drop inventory items when `keep_inventory = true` -> inventory wiped on respawn.
  - *Mutant 4:* Duplicate items across respawn when `keep_inventory = false` -> fails item conservation check.

---

## 10. Verification Audit and Unverified Items

### 10.1 Ground Truth Verified Against 26.2 Server Jar
The following core subsystems have been decompiled, read, and verified line by line in `~/kestrel-run/vanilla-src-26.2`:
1. `LivingEntity.java:1180-1280` (`setHealth`, `hurtServer`, invulnerability window, damage delta rule, death check).
2. `LivingEntity.java:1799-1869` (`causeFallDamage`, `calculateFallPower`, `calculateFallDamage`).
3. `LivingEntity.java:447-469`, `517-519`, `599-613` (drowning condition, bubble broadcast event 67, air decrement and refill).
4. `Entity.java:540-575`, `618-635` (fire tick reduction, lava tick damage, fire overlay flag 0).
5. `Entity.java:590-594`, `LivingEntity.java:2184-2186` (void threshold at `minY - 64`, 4.0F damage).
6. `Entity.java:2262-2278`, `LivingEntity.java:435-436` (suffocation eye bounding box math and in-wall damage).
7. `CactusBlock.java:126-130` and `SweetBerryBushBlock.java:82-98` (contact hazard damage and movement thresholds).
8. `FoodData.java:11-115` (fast regen, slow regen, starvation floors on Easy/Normal/Hard, exhaustion thresholds).
9. `CombatTracker.java:19-163` (combat timeouts, assisted fall logic, death message localization).
10. `ServerPlayer.java:913-973` (`die`, kill packets, death animation event 3, fire cleanup).
11. `ServerPlayer.java:1077-1107` (`findRespawnAndUseSpawnBlock`, anchor charge decrement, bed stand-up offsets).
12. `PlayerList.java:389-455` (`respawn` 12-packet burst).
13. `ServerGamePacketListenerImpl.java:1884-1908` (`handleClientCommand` `PERFORM_RESPAWN` handling and dead check).
14. `GameProtocols.java:60-274` (all serverbound and clientbound numeric packet IDs counted and verified).

### 10.2 Unverified Items
The following secondary interactions were not directly checked in the 26.2 jar and are marked as unverified:
1. *Totem of Undying animation:* Exact particle and sound sequence when `checkTotemDeathProtection` triggers (`LivingEntity.java:1395-1420`); totem item usage is outside G22 base scope.
2. *Wither Rose placement on player death:* `ServerPlayer.createWitherRose(killer)` when killed by a Wither (`ServerPlayer.java:960`); unverified whether Wither boss is in Phase A scope.
3. *End Portal credit sequence respawn:* Handling `wonGame = true` during `Action.PERFORM_RESPAWN` (`ServerGamePacketListenerImpl.java:1890-1896`); unverified until the End dimension (G10/G43) is integrated.
4. *Scoreboard team death message colors:* Exact formatting nuances of team kill tracking objectives (`ServerPlayer.java:997-1012`); unverified until scoreboards (S31) are implemented.
