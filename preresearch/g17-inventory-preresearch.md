# G17 Pre-Research: Items, Inventory, and Container Protocol Specification

Status: PRE-RESEARCH, grounded in the decompiled vanilla 26.2 server jar (`server-26.2.jar`) in `~/kestrel-run/vanilla-src-26.2`, datapack game data, and existing Kestrel implementations in `core/src/vanilla/java/dev/kestrel/`.
Target: Ground truth specification and architecture roadmap for roadmap item G17 (items and inventory) in Kestrel.
Scope: Specification analysis, vanilla code audits with exact file:line citations, 26.2 Data Components system, inventory slot mappings, container click protocols, cursor stack mechanics, item drop physics, and persistence formats. No Java source changes, no builds, no servers.

---

## 1. Executive Summary and Scope

### 1.1 Scope Boundary
Roadmap item G17 establishes the fundamental player item and inventory subsystem in Kestrel:
- Item representation using Minecraft 26.2's **Data Components** architecture (`ItemStack`, `DataComponentMap`, `PatchedDataComponentMap`, `DataComponentPatch`).
- Player inventory storage (`Inventory.java`): 36 main inventory slots (0-8 hotbar, 9-35 storage), 4 armor equipment slots, and 1 offhand slot.
- The player inventory menu (`InventoryMenu.java`, container ID 0): 46 menu slots spanning crafting results, crafting grid, armor, storage, hotbar, and offhand.
- Network synchronization packets: `ClientboundContainerSetContentPacket` (`0x14`), `ClientboundContainerSetSlotPacket` (`0x16`), `ClientboundSetPlayerInventoryPacket`, `ClientboundSetHeldSlotPacket` (`0x51`), and `ClientboundTakeItemEntityPacket` (`0x68`).
- Container click transaction protocol: `ServerboundContainerClickPacket` with the 7 `ContainerInput` modes in 26.2 (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`), `HashedStack` client prediction verification, and transaction state recovery (`stateId`).
- Item dropping (Q and Ctrl+Q) and player item pickup physics.
- Player inventory persistence in `playerdata/*.dat` (`Inventory: [ {Slot: b, id: s, count: i, components: {} } ]`).

### 1.2 Relationship to Other Roadmap Items
G17 is the central prerequisite for almost all survival mechanics:
- **S14 (Entity System):** S14 provides the physical `ItemEntity` and tick loop lifecycle. G17 provides the `ItemStack` payload, player drop action, and item pickup handler.
- **G18 (Block Breaking):** Determines tool mining speed via `itemStack.getDestroySpeed(state)` and checks correct harvest level (`hasCorrectToolForDrops`).
- **G19 (Block Drops):** Spawns dropped items as `ItemEntity` holding an `ItemStack` from block loot tables.
- **G20 (Placing Held Item):** Reads block state to place from `player.getItemInHand(hand)` and decrements stack count in survival.
- **G27 (Crafting):** Consumes ingredient items from `craftSlots` and creates the result stack in slot 0.
- **G28 (Containers):** Reuses the container click protocol and slot abstraction for chests, barrels, hoppers, dispensers, and shulker boxes.
- **S33 (Vanilla World Files):** Serializes player inventory NBT into `playerdata/<UUID>.dat`.

---

## 2. 26.2 Item Stack and Data Components Architecture

In Minecraft 26.2, arbitrary unstructured NBT on items has been completely replaced by typed, validated **Data Components**.

### 2.1 The `ItemStack` Model
`ItemStack` (`net/minecraft/world/item/ItemStack.java:102-1132`) implements `DataComponentHolder`:
- `item` (`Holder<Item>`, line 156): Registry reference to the item type.
- `count` (`int`, line 152): Current stack count (clamped to `getMaxStackSize()`, typically 1, 16, or 64).
- `components` (`PatchedDataComponentMap`, line 157): Map holding the active component patch against the item prototype's default components.

#### Codec and Serialization (`ItemStack.java:110-148`)
The canonical NBT representation is defined by `ItemStack.MAP_CODEC`:
```
CompoundTag {
   "id": StringTag,             // e.g. "minecraft:diamond_sword"
   "count": IntTag,             // 1 to 99, default 1
   "components": CompoundTag    // DataComponentPatch
}
```

#### Network Wire Format (`ItemStack.STREAM_CODEC`, lines 170-195)
On the network wire:
1. If `itemStack.isEmpty()` is true, writes a single VarInt: `0` (`output.writeVarInt(0)`, line 187).
2. Otherwise:
   - `count`: VarInt (`output.writeVarInt(itemStack.getCount())`, line 189).
   - `item`: VarInt registry ID in `Registries.ITEM` (`Item.STREAM_CODEC.encode(...)`, line 190).
   - `components`: Serialized via `DataComponentPatch.STREAM_CODEC` (line 191).

### 2.2 DataComponentPatch Wire Format
`DataComponentPatch` (`net/minecraft/core/component/DataComponentPatch.java:81-130`) serializes the delta between the item prototype and the modified stack:
1. `positiveCount`: VarInt (number of added or modified components, line 124).
2. `negativeCount`: VarInt (number of removed components, line 125).
3. Positive entries (repeated `positiveCount` times):
   - `type`: VarInt registry ID in `Registries.DATA_COMPONENT_TYPE`.
   - `value`: Payload written by that component's typed `StreamCodec`.
4. Negative entries (repeated `negativeCount` times):
   - `type`: VarInt registry ID (marks the component as explicitly deleted).
5. If the patch is empty, writes two zero VarInts: `0, 0` (lines 108-109).

### 2.3 `HashedStack` and Component Hashing in 26.2
In 26.2, client-to-server container click predictions use `HashedStack` (`net/minecraft/network/HashedStack.java:12-59`) rather than transmitting full component maps:
- `HashedStack.create(ItemStack, hasher)` (lines 32-36): If empty, returns `HashedStack.EMPTY`.
- Otherwise sends `HashedStack.ActualItem` (lines 38-57):
  - `item`: VarInt item ID.
  - `count`: VarInt count.
  - `components`: `HashedPatchMap` (64-bit Murmur/SipHash fingerprints of modified component entries).
- Matching: When validating client clicks, the server checks `this.count == itemStack.getCount() && this.item.equals(itemStack.typeHolder()) && this.components.matches(...)` (`HashedStack.java:50-56`). This eliminates multi-kilobyte NBT echoes on every click.

---

## 3. Player Inventory Architecture

### 3.1 Inventory Data Structure
`Inventory` (`net/minecraft/world/entity/player/Inventory.java:29-530`) owns all items carried directly on the player:
- `INVENTORY_SIZE = 36` (line 31):
  - Slots 0 to 8: Active hotbar slots.
  - Slots 9 to 35: Main storage inventory.
- `items` (`NonNullList<ItemStack>`, line 56): Fixed-size list of 36 `ItemStack` elements, initialized to `ItemStack.EMPTY`.
- `selected` (`int`, line 57): Currently selected hotbar slot index (0 to 8).
- `equipment` (`EntityEquipment`, line 59): Storage for equipped items, shared with living entity mechanics.

#### Equipment Slot Mapping (`Inventory.java:37-54`)
Equipment slots are mapped into inventory indices:
- Slot 36: `EquipmentSlot.FEET` (Boots)
- Slot 37: `EquipmentSlot.LEGS` (Leggings)
- Slot 38: `EquipmentSlot.CHEST` (Chestplate)
- Slot 39: `EquipmentSlot.HEAD` (Helmet)
- Slot 40: `EquipmentSlot.OFFHAND` (Shield / Offhand item)
- Slot 41: `EquipmentSlot.BODY` (Wolf armor / horse armor)
- Slot 42: `EquipmentSlot.SADDLE` (Saddle)

#### Hotbar Selection Mechanics
- Selected item getter: `inventory.getSelectedItem()` returns `this.items.get(this.selected)` (`Inventory.java:80`).
- Scrolling the hotbar: Client sends `ServerboundSetCarriedItemPacket(short slot)` (`net/minecraft/network/protocol/game/ServerboundSetCarriedItemPacket.java:8-39`). The server validates `slot >= 0 && slot < 9` and updates `inventory.setSelectedSlot(slot)`.
- Server forces hotbar slot: Server sends `ClientboundSetHeldSlotPacket(int slot)` (`net/minecraft/network/protocol/game/ClientboundSetHeldSlotPacket.java:9-23`, packet `0x51`).

#### Offhand Swapping (F Key)
When the player presses the Swap Offhand key (F):
- Client sends `ServerboundPlayerActionPacket` with `Action.SWAP_ITEM_WITH_OFFHAND` (`ServerboundPlayerActionPacket.java:76`).
- Server handler (`ServerGamePacketListenerImpl.java:1294-1300`):
  ```java
  ItemStack mainHand = this.player.getItemInHand(InteractionHand.MAIN_HAND);
  ItemStack offHand = this.player.getItemInHand(InteractionHand.OFF_HAND);
  this.player.setItemInHand(InteractionHand.MAIN_HAND, offHand);
  this.player.setItemInHand(InteractionHand.OFF_HAND, mainHand);
  this.player.stopUsingItem();
  ```

---

## 4. The Player Inventory Menu (`InventoryMenu`)

### 4.1 Slot Index Layout
When the player opens their survival inventory (E key), or interacts with the HUD hotbar, the server interacts with `InventoryMenu` (`net/minecraft/world/inventory/InventoryMenu.java:14-100`):
- `CONTAINER_ID = 0` (line 15).

Total slot count: **46 slots** (indices 0 to 45):
```
Slot Range | Internal Menu Role        | Backing Inventory Location        | Vanilla Source
-----------+---------------------------+-----------------------------------+---------------------------
Slot 0     | Crafting result slot      | resultSlots (ResultContainer)     | InventoryMenu.java:16, 53
Slots 1-4  | 2x2 Crafting input grid   | craftSlots (TransientCraftingCont)| InventoryMenu.java:19-21, 54
Slots 5-8  | Armor equipment slots     | inventory.equipment (Head to Feet)| InventoryMenu.java:22-24, 56-60
           | (5=Head, 6=Chest, 7=Legs, 8=Feet)                             |
Slots 9-35 | Main storage inventory    | inventory.items (slots 9 to 35)   | InventoryMenu.java:25-26, 62
Slots 36-44| Hotbar inventory          | inventory.items (slots 0 to 8)    | InventoryMenu.java:27-28, 62
Slot 45    | Offhand slot              | inventory.equipment (OFFHAND)     | InventoryMenu.java:29, 63-78
```

Note the inversion between `Inventory` and `InventoryMenu`:
- In `Inventory.java`, hotbar is slots `0..8`, and main storage is `9..35`.
- In `InventoryMenu.java`, main storage is slots `9..35`, and hotbar is slots `36..44`!

### 4.2 Network Synchronization Packets

#### 1. Full Content Sync: `ClientboundContainerSetContentPacket` (`0x14` / 20)
Sent on initial join, when opening any menu, or when a prediction error forces a full desync recovery (`ClientboundContainerSetContentPacket.java:11-23`):
- `containerId`: Container ID (`0` for player inventory).
- `stateId`: VarInt sequence ID.
- `items`: List of `ItemStack` objects (length 46 for `InventoryMenu`).
- `carriedItem`: `ItemStack` currently held on the mouse cursor.

#### 2. Single Slot Sync: `ClientboundContainerSetSlotPacket` (`0x16` / 22)
Sent when a slot changes during gameplay (`ClientboundContainerSetSlotPacket.java:9-23`):
- `containerId`: Container ID (`0` for player inventory, `-1` or `255` for cursor stack).
- `stateId`: VarInt sequence ID.
- `slot`: Short slot index (0 to 45).
- `itemStack`: `ItemStack` content.

#### 3. Cursor Stack Sync
When the cursor item stack changes:
- `ClientboundContainerSetSlotPacket` is dispatched with `containerId = -1` (or `255`), `slot = -1`, and the carried `ItemStack`.

---

## 5. Container Click Protocol and Transaction State

### 5.1 Click Packet Layout
Every click in an inventory menu emits `ServerboundContainerClickPacket` (`net/minecraft/network/protocol/game/ServerboundContainerClickPacket.java:14-37`):
- `containerId`: Byte container ID (0 for inventory).
- `stateId`: VarInt transaction state sequence ID.
- `slotNum`: Short slot index clicked (-999 for clicking outside window).
- `buttonNum`: Byte button or key code.
- `containerInput`: Enum `ContainerInput` (0 to 6).
- `changedSlots`: `Int2ObjectMap<HashedStack>` (client's predicted slot states).
- `carriedItem`: `HashedStack` (client's predicted cursor stack).

### 5.2 The 7 `ContainerInput` Modes (`ContainerInput.java:9-17`)

| ID | `ContainerInput` | Button | User Action | Exact Server Execution in `AbstractContainerMenu.doClick` |
|---|---|---|---|---|
| `0` | `PICKUP` | 0 | Left-click slot | If cursor empty, picks up entire slot stack. If cursor has item, deposits all or swaps (`AbstractContainerMenu.java:441-454`). |
| `0` | `PICKUP` | 1 | Right-click slot | If cursor empty, picks up half slot stack (`ceil(count / 2)`). If cursor has item, deposits exactly 1 item into slot (`AbstractContainerMenu.java:448, 461`). |
| `0` | `PICKUP` | 0 / 1 | Click outside (-999) | Drops carried item onto ground. Button 0 drops entire cursor stack; button 1 drops 1 item (`AbstractContainerMenu.java:407-415`). |
| `1` | `QUICK_MOVE` | 0 / 1 | Shift + Left-click | Quick-transfers item between player storage and hotbar/armor/container via `quickMoveStack` (`AbstractContainerMenu.java:416-430`). |
| `2` | `SWAP` | 0 to 8 | Number keys 1-9 | Swaps clicked slot with hotbar slot 0-8 (`AbstractContainerMenu.java:470-496`). |
| `2` | `SWAP` | 40 | Press F over slot | Swaps clicked slot with offhand slot (`AbstractContainerMenu.java:473`). |
| `3` | `CLONE` | 2 | Middle-click | Creative mode clone: duplicates item to cursor up to max stack size (`AbstractContainerMenu.java:497-511`). |
| `4` | `THROW` | 0 | Press Q over slot | Drops 1 item from clicked slot into the world (`AbstractContainerMenu.java:512-526`). |
| `4` | `THROW` | 1 | Press Ctrl + Q | Drops entire stack from clicked slot into the world (`AbstractContainerMenu.java:512-526`). |
| `5` | `QUICK_CRAFT` | 0 / 1 / 2 | Drag-click across slots | Evenly splits or distributes cursor items across dragged slots (`AbstractContainerMenu.java:339-402`). |
| `6` | `PICKUP_ALL` | 0 | Double-click slot | Collects matching items from all compatible slots into cursor stack (`AbstractContainerMenu.java:527-549`). |

### 5.3 Transaction State and Desynchronization Recovery
In `ServerGamePacketListenerImpl.handleContainerClick` (`net/minecraft/server/network/ServerGamePacketListenerImpl.java:1939-1975`):
1. **Validation:** Checks that `containerId` matches and slot index is valid (`isValidSlotIndex`, line 1949).
2. **State Check:** Evaluates `boolean fullResyncNeeded = packet.stateId() != this.player.containerMenu.getStateId()` (line 1955).
3. **Execution:** Suppresses remote updates, executes `this.player.containerMenu.clicked(...)`, applies predictions to remote slot tracker, and resumes remote updates (lines 1956-1966).
4. **Broadcast Resolution:**
   - If `fullResyncNeeded == true`: Client state desynchronized! Dispatches `broadcastFullState()` (`ClientboundContainerSetContentPacket`), wiping out client prediction and restoring authoritative server state (line 1968).
   - If `fullResyncNeeded == false`: Dispatches incremental delta `broadcastChanges()` (`ClientboundContainerSetSlotPacket` for dirty slots only, line 1970).

---

## 6. Item Dropping and Item Pickup Integration

### 6.1 Dropping Items from the Player
When a player drops their selected item via Q or Ctrl+Q:
- Dispatched via `ServerboundPlayerActionPacket` (`Action.DROP_ITEM` for 1 item, `Action.DROP_ALL_ITEMS` for full stack, `ServerPlayer.java:2107-2118`):
> Excerpt of the decompiled vanilla source omitted from this public copy. See the class and line range cited above in the Minecraft 26.2 server jar.

#### Trajectory Vector Equations (`LivingEntity.createItemStackToDrop`, lines 3449-3481)
The spawned `ItemEntity` is placed at:
$$\text{pos} = \left(x, y_{\text{eye}} - 0.3, z\right)$$

Velocity is calculated from player head pitch ($X_{\text{rot}}$) and yaw ($Y_{\text{rot}}$):
$$\Delta X = -\sin(Y_{\text{rot}} \cdot \frac{\pi}{180}) \cdot \cos(X_{\text{rot}} \cdot \frac{\pi}{180}) \cdot 0.3 + \cos(\text{dir}) \cdot \text{pow2}$$
$$\Delta Y = -\sin(X_{\text{rot}} \cdot \frac{\pi}{180}) \cdot 0.3 + 0.1 + (\text{rnd} - \text{rnd}) \cdot 0.1$$
$$\Delta Z = \cos(Y_{\text{rot}} \cdot \frac{\pi}{180}) \cdot \cos(X_{\text{rot}} \cdot \frac{\pi}{180}) \cdot 0.3 + \sin(\text{dir}) \cdot \text{pow2}$$
where $\text{dir} = \text{random} \cdot 2\pi$ and $\text{pow2} = 0.02 \cdot \text{random}$.

Pickup delay is initialized to **40 ticks** (2 seconds):
`entity.setPickUpDelay(40);` (`LivingEntity.java:3455`).

### 6.2 Item Pickup by the Player
In `ItemEntity.playerTouch` (`net/minecraft/world/entity/item/ItemEntity.java:340-356`):
1. **Pickup Gate:** Evaluated if `pickupDelay == 0 && (target == null || target.equals(player.getUUID()))`.
2. **Inventory Insertion:** Invokes `player.getInventory().add(itemStack)`.
   - Iterates hotbar and storage slots.
   - Merges with existing non-full matching stacks (`ItemStack.isSameItemSameComponents`).
   - Places remaining items into first empty slot.
3. **Network Animation:** If any items were absorbed (`orgCount`):
   - Invokes `player.take(this, orgCount)` (`ItemEntity.java:346`), broadcasting `ClientboundTakeItemEntityPacket(this.getId(), player.getId(), orgCount)` (`ClientboundTakeItemEntityPacket.java:16-32`, packet `0x68`).
   - If the stack is completely absorbed (`itemStack.isEmpty()`), calls `this.discard()` (line 348).

### 6.3 Item Merging Physics
Nearby `ItemEntity` objects merge every 2 or 40 ticks (`ItemEntity.java:174-177, 215-220`):
- Search volume: `this.getBoundingBox().inflate(0.5, 0.0, 0.5)`.
- Criteria: Both entities are mergeable, within max stack size, and `ItemStack.isSameItemSameComponents(stack1, stack2)` is true.
- Reconciliation:
  - Stack count is summed into target entity.
  - `target.age = Math.min(target.age, other.age)`
  - `target.pickupDelay = Math.max(target.pickupDelay, other.pickupDelay)`
  - `other.discard()` is called on the absorbed entity.

---

## 7. Player Inventory NBT Persistence

In `playerdata/<UUID>.dat` (`net/minecraft/world/entity/player/Player.java:622-640`):
- `Inventory`: ListTag of CompoundTags (`ItemStackWithSlot`):
  ```
  {
     "Slot": ByteTag,         // 0-8: Hotbar
                              // 9-35: Storage inventory
                              // 100-103: Armor (100=feet, 101=legs, 102=chest, 103=head)
                              // -106: Offhand
     "id": StringTag,         // e.g. "minecraft:iron_pickaxe"
     "count": IntTag,         // e.g. 1
     "components": CompoundTag// DataComponentPatch
  }
  ```
- `SelectedItemSlot`: IntTag (0 to 8).
- `EnderItems`: ListTag of CompoundTags (ender chest inventory, 27 slots).

---

## 8. Suggested Build Order in Small Vertical Slices

```
┌─────────────────────────────────────────────────────────────┐
│ Slice 1: ItemStack Model & 26.2 Data Components Wire Format │
│ (ItemStack, DataComponentMap, DataComponentPatch, Codecs)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 2: Player Inventory Storage & Hotbar Selection        │
│ (Inventory 36-slot list, hotbar scrolling, offhand swap)    │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 3: InventoryMenu & Container Slot Synchronization     │
│ (46-slot InventoryMenu, ContainerSetContent, SetSlot)       │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 4: Container Click Protocol & Transaction State       │
│ (ServerboundContainerClick, 7 ContainerInputs, stateId sync)│
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 5: Item Dropping (Q / Ctrl+Q) & Player Item Pickup    │
│ (ServerPlayer.drop, trajectory physics, take item packet)   │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ Slice 6: Player Inventory Persistence                       │
│ (playerdata/<UUID>.dat NBT serialization, roundtrip test)   │
└─────────────────────────────────────────────────────────────┘
```

### Slice 1: `ItemStack` Model and 26.2 Data Components Wire Format
- **Objective:** Pure data model for items, count clamping, and binary serialization.
- **Classes to Implement:**
  - `ItemStack` with `Holder<Item>`, count, and `PatchedDataComponentMap`.
  - `DataComponentPatch` and stream codec (positive count, negative count, typed component stream codecs).
  - `HashedStack` and `HashedPatchMap` hash calculation.
- **Testable Parity Check (`ItemStackCodecCheck.java`):**
  - Serialize empty stack -> assert single VarInt `0`.
  - Serialize stone x64 without components -> assert count 64, item ID, positiveCount 0, negativeCount 0.
  - Serialize damaged diamond sword with custom name -> assert exact positive count and component payload matches vanilla packet capture bit-for-bit.

### Slice 2: Player Inventory Storage and Hotbar Selection
- **Objective:** In-memory player storage, slot bounds, hotbar switching, and offhand swapping.
- **Classes to Implement:**
  - `Inventory` (36 `NonNullList<ItemStack>` items, selected slot, `EntityEquipment`).
  - Wire `ServerboundSetCarriedItemPacket` (`slotNum` 0 to 8) to `inventory.setSelectedSlot`.
  - Wire `ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND` to swap main hand and offhand.
- **Testable Parity Check (`PlayerInventorySelectCheck.java`):**
  - Set hotbar slot 3 with sword, slot 4 with bow.
  - Send carried item packet slot 4 -> assert `getSelectedItem()` returns bow.
  - Send swap offhand packet -> assert main hand has offhand item, offhand has bow.

### Slice 3: `InventoryMenu` and Container Slot Synchronization
- **Objective:** Implement the 46-slot menu mapping and outbound sync packets.
- **Classes to Implement:**
  - `InventoryMenu` mapping slots 0 to 45.
  - `ClientboundContainerSetContentPacket` (`0x14`).
  - `ClientboundContainerSetSlotPacket` (`0x16`).
  - `ClientboundSetHeldSlotPacket` (`0x51`).
- **Testable Parity Check (`ContainerContentSyncCheck.java`):**
  - Populate player inventory with known items across hotbar, armor, and storage.
  - Invoke `menu.sendAllDataToRemote()` -> assert packet 0x14 contains 46 items with hotbar at indices 36-44, armor at 5-8, and offhand at 45.
  - Modify slot 36 -> assert packet 0x16 emits container 0, slot 36, new item.

### Slice 4: Container Click Protocol and Transaction State
- **Objective:** Handle mouse clicks, item picking, dropping, dragging, and prediction recovery.
- **Classes to Implement:**
  - `ServerboundContainerClickPacket` parser.
  - `AbstractContainerMenu.doClick` implementing the 7 `ContainerInput` modes:
    - `PICKUP` (left/right click)
    - `QUICK_MOVE` (shift click transfer)
    - `SWAP` (number keys 1-9)
    - `THROW` (Q / Ctrl+Q)
    - `QUICK_CRAFT` (drag distribution)
    - `PICKUP_ALL` (double click)
  - `stateId` mismatch detection triggering `broadcastFullState()`.
- **Testable Parity Check (`ContainerClickTransactionCheck.java`):**
  - Left click slot 36 with empty cursor -> assert item transferred to cursor.
  - Left click slot 37 with carried item -> assert item deposited in slot 37.
  - Send packet with invalid `stateId` -> assert server rejects prediction and sends full `0x14` resync.

### Slice 5: Item Dropping (Q / Ctrl+Q) and Player Item Pickup
- **Objective:** Connect inventory actions to S14 `ItemEntity` physics.
- **Classes to Implement:**
  - `ServerPlayer.drop(boolean all)` calling `createItemStackToDrop`.
  - Trajectory vector calculation from player pitch/yaw with 40-tick pickup delay.
  - `ItemEntity.playerTouch` calling `inventory.add(itemStack)` and broadcasting `ClientboundTakeItemEntityPacket` (`0x68`).
  - Item merging within 0.5 blocks.
- **Testable Parity Check (`ItemDropPickupCheck.java`):**
  - Player with 64 iron ingots in slot 0 presses Q -> assert slot count is 63, `ItemEntity` spawned at player eye position with forward vector and pickup delay 40.
  - Advance time 40 ticks -> assert `playerTouch` picks up iron ingot, emits packet 0x68, and restores slot count to 64.

### Slice 6: Player Inventory Persistence
- **Objective:** Read and write inventory NBT in `playerdata/<UUID>.dat`.
- **Classes to Implement:**
  - `Inventory.save` and `Inventory.load` using `ItemStackWithSlot`.
  - Serializing `SelectedItemSlot` and `EnderItems`.
- **Testable Parity Check (`InventoryNbtRoundTripCheck.java`):**
  - Create full inventory with enchanted items, custom components, armor, and offhand.
  - Serialize to NBT compound tag.
  - Load into fresh `Inventory` instance -> assert all 36 items, 4 armor slots, offhand, and selected slot match exactly.

---

## 9. Summary of Gaps in Kestrel Today for G17

| Component | Existing Kestrel Code | Missing for G17 |
|---|---|---|
| **ItemStack** | `core/src/vanilla/java/dev/kestrel/worldgen/HeadlessEntityServerLevel.java` imports it, but Kestrel has no item system. | `ItemStack`, `DataComponentMap`, `DataComponentPatch`, `HashedStack`, and stream codecs. |
| **Player Inventory** | `KestrelPlayer.java` has no inventory fields. | `Inventory.java` with 36 slots, equipment mapping, hotbar selection. |
| **Inventory Menu** | None. | `InventoryMenu.java` 46-slot container, slot transfer logic (`quickMoveStack`). |
| **Networking Packets** | Zero inventory packets in `PacketIds.java` or `Outbound.java`. | `SetContainerContent` (`0x14`), `SetContainerSlot` (`0x16`), `SetHeldSlot` (`0x51`), `TakeItemEntity` (`0x68`), `ContainerClick` packet parser. |
| **Click Transactions** | None. | `ContainerInput` state machine, drag-crafting, shift-click transfer, `stateId` desync recovery. |
| **Item Dropping/Pickup** | None. | Player drop action (Q/Ctrl+Q), trajectory math, pickup collision detection, item merge loop. |
| **Persistence** | None. | Player NBT load/save for `Inventory`, `SelectedItemSlot`, `EnderItems`. |
