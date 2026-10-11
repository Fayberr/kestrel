package dev.kestrel.net;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 3 outbound packet builders: every clientbound packet Kestrel
 * sends, hand-encoded per the 26.2 decompile. Each builder returns the
 * id + payload pair; framing and compression happen in
 * {@link NetServer} via {@link Wire#frame}.
 *
 * <p>Config phase (grounded in
 * {@code ServerConfigurationPacketListenerImpl.java:86-104} and the
 * task codecs): brand ({@code BrandPayload.java:8-16}), enabled
 * features ({@code ClientboundUpdateEnabledFeaturesPacket.java:16-22}),
 * select-known-packs ({@code ClientboundSelectKnownPacks.java:12-14},
 * {@code KnownPack.java:9-11}), registry data
 * ({@code ClientboundRegistryDataPacket.java:19-25},
 * {@code RegistrySynchronization.java:91-99}), update tags
 * ({@code ClientboundUpdateTagsPacket.java:12-28}), finish
 * configuration (zero bytes,
 * {@code ClientboundFinishConfigurationPacket.java:9-10}), keepalive
 * (single long, {@code ClientboundKeepAlivePacket.java:18-24}).
 *
 * <p>Play phase (grounded in {@code PlayerList.java:146-221} and the
 * packet codecs): join ({@code ClientboundLoginPacket.java:32-62},
 * {@code CommonPlayerSpawnInfo.java:27-53}), difficulty
 * ({@code ClientboundChangeDifficultyPacket.java:11-17}), abilities
 * ({@code ClientboundPlayerAbilitiesPacket.java:33-64}), held slot,
 * recipes (empty), game event (byte + float,
 * {@code ClientboundGameEventPacket.java:41-49}), init border (8
 * fields, {@code ClientboundInitializeBorderPacket.java:22-53}),
 * spawn position ({@code LevelData.java:33-52}), player position
 * (VarInt id + PositionMoveRotation,
 * {@code ClientboundPlayerPositionPacket.java:12-21}), player info
 * ({@code ClientboundPlayerInfoUpdatePacket.java:43-129}), set time,
 * cache center (2 VarInts), chunk batch start (empty) / finished
 * (VarInt), level chunk with light (below).
 *
 * <p>Chunk data (grounded in
 * {@code ClientboundLevelChunkPacketData.java:50-67} and
 * {@code ClientboundLightUpdatePacketData.java:50-66}): heightmap map
 * (empty: single zero VarInt), VarInt byte-length + raw section bytes,
 * block-entity list (empty for Stage 3: BE bodies are stubs), then
 * light data (full-bright sky, see chunk()). Section bytes per
 * {@code LevelChunkSection.write} ({@code :184-189}):
 * nonEmptyCount short, fluidCount short, states container, biomes
 * container; container per {@code PalettedContainer.Data.write}
 * ({@code PalettedContainer.java:338-342}): bits byte, palette
 * (single VarInt global id for bits-0, else count VarInt + VarInt ids),
 * raw longs with NO length prefix.
 *
 * <p>Thread-safety: stateless builders. Called on the tick thread
 * (join burst, chunk stream) and the I/O thread (keepalive replies
 * are inbound-driven; disconnects originate either side).
 */
public final class Outbound {

    private Outbound() {
    }

    /** One outbound packet: numeric id plus payload bytes. */
    public record Packet(int id, byte[] payload) {
    }

    // ---- status ----

    /**
     * StatusResponse(status as JSON string). Vanilla reads this packet as lenient JSON
     * ({@code ClientboundStatusResponsePacket.java:15-19}).
     */
    public static Packet statusResponse(String json) {
        return new Packet(PacketIds.STATUS_RESPONSE, new Wire.Writer().utf(json).toBytes());
    }

    /**
     * PongResponse(time). Mirrors the client's ping timestamp
     * ({@code ClientboundPongResponsePacket.java:8-12}).
     */
    public static Packet statusPong(long time) {
        return new Packet(PacketIds.STATUS_PONG, new Wire.Writer().l(time).toBytes());
    }

    // ---- login ----

    /** LoginCompression(threshold). */
    public static Packet loginCompression(int threshold) {
        return new Packet(PacketIds.LOGIN_COMPRESSION, new Wire.Writer().vint(threshold).toBytes());
    }

    /** LoginFinished(profile uuid, name, zero properties; session id). */
    public static Packet loginFinished(UUID profile, String name) {
        return loginFinished(profile, name, List.of());
    }

    /**
     * LoginFinished with Mojang profile properties (skins). Properties
     * codec grounded in {@code ByteBufCodecs.java:209-238}: VarInt count,
     * then per property UTF name (max 64), UTF value (max 32767),
     * nullable UTF signature (max 1024). Vanilla mints a random
     * connection session id; Kestrel reuses the profile uuid, which the
     * client accepts anywhere a uuid is expected.
     */
    public static Packet loginFinished(UUID profile, String name, List<ProfileProperty> props) {
        Wire.Writer w = new Wire.Writer();
        w.uuid(profile);
        w.utf(name);
        w.vint(props.size());
        for (ProfileProperty p : props) {
            w.utf(p.name());
            w.utf(p.value());
            if (p.signature() == null) {
                w.bool(false);
            } else {
                w.bool(true);
                w.utf(p.signature());
            }
        }
        w.uuid(profile);
        return new Packet(PacketIds.LOGIN_FINISHED, w.toBytes());
    }

    /** One Mojang profile property (skin/capes): name, value, optional signature. */
    public record ProfileProperty(String name, String value, String signature) {
    }

    /**
     * ClientboundHello for online mode: empty server id, X.509 RSA
     * public key, 4-byte challenge, shouldAuthenticate true
     * ({@code ClientboundHelloPacket.java:34-38}).
     */
    public static Packet loginHello(byte[] publicKey, byte[] challenge) {
        Wire.Writer w = new Wire.Writer();
        w.utf("");
        w.byteArray(publicKey);
        w.byteArray(challenge);
        w.bool(true);
        return new Packet(PacketIds.LOGIN_HELLO_OUT, w.toBytes());
    }

    /**
     * LoginDisconnect(reason as JSON text component). Vanilla reads
     * this packet as lenient JSON
     * ({@code ClientboundLoginDisconnectPacket.java:18-21}), NOT NBT:
     * the old NBT framing left 20 trailing bytes the client rejected.
     */
    public static Packet loginDisconnect(String reason) {
        return new Packet(PacketIds.LOGIN_DISCONNECT, Wire.disconnectJson(reason));
    }

    // ---- configuration ----

    /** Brand: id minecraft:brand + UTF brand. */
    public static Packet brand(String brand) {
        Wire.Writer w = new Wire.Writer();
        w.utf("minecraft:brand");
        w.utf(brand);
        return new Packet(PacketIds.CONFIG_CUSTOM_PAYLOAD, w.toBytes());
    }

    /** UpdateEnabledFeatures([minecraft:vanilla]). */
    public static Packet enabledFeatures() {
        Wire.Writer w = new Wire.Writer();
        w.vint(1);
        w.utf("minecraft:vanilla");
        return new Packet(PacketIds.CONFIG_FEATURES, w.toBytes());
    }

    /** SelectKnownPacks(requested packs). */
    public static Packet selectKnownPacks(List<String[]> packs) {
        Wire.Writer w = new Wire.Writer();
        w.vint(packs.size());
        for (String[] p : packs) {
            w.utf(p[0]).utf(p[1]).utf(p[2]);
        }
        return new Packet(PacketIds.CONFIG_SELECT_KNOWN_OUT, w.toBytes());
    }

    /**
     * RegistryData with ids only (present=false for every entry). The
     * client loads values from its local copy of the negotiated pack
     * (SynchronizeRegistriesTask sends this when accepted packs equal
     * requested packs). Ids still come from our datapack so order and
     * membership match what tags reference.
     */
    public static Packet registryDataIdsOnly(String registryKey,
            List<Map.Entry<String, byte[]>> entries) {
        Wire.Writer w = new Wire.Writer();
        w.utf(registryKey);
        w.vint(entries.size());
        for (Map.Entry<String, byte[]> e : entries) {
            w.utf(e.getKey());
            w.bool(false);
        }
        return new Packet(PacketIds.CONFIG_REGISTRY_DATA, w.toBytes());
    }

    /** RegistryData(registry key, entries with present flags). */
    public static Packet registryData(String registryKey, List<Map.Entry<String, byte[]>> entries) {
        Wire.Writer w = new Wire.Writer();
        w.utf(registryKey);
        w.vint(entries.size());
        for (Map.Entry<String, byte[]> e : entries) {
            w.utf(e.getKey());
            if (e.getValue() == null) {
                w.bool(false);
            } else {
                w.bool(true);
                w.bytes(e.getValue());
            }
        }
        return new Packet(PacketIds.CONFIG_REGISTRY_DATA, w.toBytes());
    }

    /** UpdateTags(empty outer map, kept for offline tests without a datapack). */
    public static Packet updateTags() {
        return updateTags(Map.of());
    }

    /**
     * UpdateTags(registry key -> tag id -> raw-zero-based ids). Wire
     * shape per {@code ClientboundUpdateTagsPacket.java:28-30} (map of
     * registry key -> payload) and
     * {@code TagNetworkSerialization.java:67-69} (payload is a map of
     * tag id -> VarInt count + VarInt ids). Registries with empty tag
     * maps are skipped, like vanilla's serializeTagsToNetwork
     * ({@code TagNetworkSerialization.java:24-27}).
     */
    public static Packet updateTags(Map<String, Map<String, List<Integer>>> tags) {
        Wire.Writer w = new Wire.Writer();
        int present = 0;
        for (var e : tags.values()) {
            if (!e.isEmpty()) {
                present++;
            }
        }
        w.vint(present);
        for (Map.Entry<String, Map<String, List<Integer>>> e : tags.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            w.utf(e.getKey());
            w.vint(e.getValue().size());
            for (Map.Entry<String, List<Integer>> t : e.getValue().entrySet()) {
                w.utf(t.getKey());
                w.vint(t.getValue().size());
                for (int id : t.getValue()) {
                    w.vint(id);
                }
            }
        }
        return new Packet(PacketIds.CONFIG_TAGS, w.toBytes());
    }

    /** FinishConfiguration (zero bytes). */
    public static Packet finishConfiguration() {
        return new Packet(PacketIds.CONFIG_FINISH_OUT, new byte[0]);
    }

    /** Config KeepAlive(id). */
    public static Packet configKeepAlive(long id) {
        return new Packet(PacketIds.CONFIG_KEEPALIVE_OUT,
                new Wire.Writer().l(id).toBytes());
    }

    /**
     * Config/play Disconnect(reason as NBT Component). The shared
     * config/play disconnect packet carries an NBT Component
     * ({@code ClientboundDisconnectPacket.java:11-12}).
     */
    public static Packet configDisconnect(String reason) {
        return new Packet(PacketIds.CONFIG_DISCONNECT, Wire.disconnectNbt(reason));
    }

    // ---- play ----

    /**
     * Join Game. Levels lists minecraft:overworld only (Stage 3:
     * overworld only). viewDistance matches the radius Kestrel
     * actually streams/enforces for this player
     * ({@link KestrelPlayer#viewDistance}, negotiated from the
     * client's ClientInformation and clamped 2..16 in
     * {@code NetServer}'s CONFIG_CLIENT_INFO handler): advertising a
     * hardcoded 10 here regardless of that value is exactly the
     * advertised/streamed mismatch that produced the invisible-chunk
     * render-boundary bug (client trusts this join field for its
     * effective render distance, independent of the render-distance
     * option it was configured with). CommonPlayerSpawnInfo:
     * dimensionType as REFERENCE id 0 (single byte 0x01: id+1,
     * {@code ByteBufCodecs.java:592-613}), dimension key, seed,
     * gameType byte 0 (survival), prevGameType byte -1, debug/flat
     * false, no death location, portalCooldown 0, seaLevel 63.
     */
    public static Packet join(int playerId, int maxPlayers, long seed, int viewDistance) {
        return join(playerId, maxPlayers, seed, viewDistance, 10, false, 0);
    }

    public static Packet join(int playerId, int maxPlayers, long seed, int viewDistance,
            int simulationDistance, boolean hardcore, int gameType) {
        Wire.Writer w = new Wire.Writer();
        w.i(playerId);
        w.bool(hardcore);
        w.vint(1);
        w.utf("minecraft:overworld");
        w.vint(maxPlayers);
        w.vint(viewDistance); // chunkRadius: matches ChunkSender's actual streamed radius
        w.vint(simulationDistance);
        w.bool(false); // reducedDebugInfo
        w.bool(true); // showDeathScreen
        w.bool(false); // doLimitedCrafting
        w.vint(0); // dimensionType: holderRegistry = raw id 0 (overworld)
        w.utf("minecraft:overworld");
        w.l(seed);
        w.b(gameType);
        w.b(0xFF); // previousGameType absent (-1)
        w.bool(false); // isDebug
        w.bool(false); // isFlat
        w.bool(false); // lastDeathLocation absent
        w.vint(0); // portalCooldown
        w.vint(63); // seaLevel
        w.bool(false); // onlineMode (offline)
        w.bool(false); // enforcesSecureChat
        return new Packet(PacketIds.PLAY_JOIN, w.toBytes());
    }

    /** ClientboundRespawnPacket for the one overworld survival level, dataToKeep = 0. */
    public static Packet respawn(long seed, net.minecraft.core.GlobalPos lastDeathLocation) {
        Wire.Writer w = new Wire.Writer();
        w.vint(0); // overworld dimension-type holder raw id
        w.utf("minecraft:overworld");
        w.l(seed);
        w.b(0).b(0xFF); // survival, no previous game type
        w.bool(false).bool(false); // debug, flat
        w.bool(lastDeathLocation != null);
        if (lastDeathLocation != null) {
            w.utf(lastDeathLocation.dimension().identifier().toString());
            w.l(lastDeathLocation.pos().asLong());
        }
        w.vint(0).vint(63); // portal cooldown, sea level
        w.b(0); // keep no entity data
        return new Packet(PacketIds.PLAY_RESPAWN, w.toBytes());
    }

    /** ChangeDifficulty(normal=2, unlocked). */
    public static Packet difficulty() {
        return difficulty(2);
    }

    /** ChangeDifficulty for a specific difficulty ID (0 peaceful, 1 easy, 2 normal, 3 hard). */
    public static Packet difficulty(int difficultyId) {
        Wire.Writer w = new Wire.Writer();
        w.vint(difficultyId);
        w.bool(false);
        return new Packet(PacketIds.PLAY_CHANGE_DIFFICULTY, w.toBytes());
    }

    /** PlayerAbilities: survival flags clear + default speeds. */
    public static Packet abilities() {
        Wire.Writer w = new Wire.Writer();
        // Cleared flags are correct for survival. allow-flight in
        // server.properties disables the anti-fly check on the server side, it
        // does not set the may-fly bit, so this stays 0 for every game mode
        // Kestrel supports today.
        w.b(0);
        w.f(0.05f);
        w.f(0.1f);
        return new Packet(PacketIds.PLAY_ABILITIES, w.toBytes());
    }

    /** SetHeldSlot(0). */
    public static Packet heldSlot() {
        return heldSlot(0);
    }

    /** SetHeldSlot for an explicit hotbar slot (G4 restores the saved INVENTORY_STUB slot). */
    public static Packet heldSlot(int slot) {
        return new Packet(PacketIds.PLAY_HELD_SLOT, new Wire.Writer().vint(slot).toBytes());
    }

    /** UpdateRecipes: empty (count 0 + empty ingredient list). */
    public static Packet recipes() {
        Wire.Writer w = new Wire.Writer();
        w.vint(0); // recipe count
        w.vint(0); // stonecutter/ingredient count
        return new Packet(PacketIds.PLAY_RECIPES, w.toBytes());
    }

    /** GameEvent(id byte + value float). */
    public static Packet gameEvent(int id, float value) {
        Wire.Writer w = new Wire.Writer();
        w.b(id);
        w.f(value);
        return new Packet(PacketIds.PLAY_GAME_EVENT, w.toBytes());
    }

    /** InitializeBorder: 60000000 center/size defaults, zero warnings. */
    public static Packet initBorder() {
        Wire.Writer w = new Wire.Writer();
        w.d(0).d(0).d(59999968).d(59999968);
        w.bytes(Wire.varLong(0));
        w.vint(29999984).vint(5).vint(15);
        return new Packet(PacketIds.PLAY_INIT_BORDER, w.toBytes());
    }

    /** SetDefaultSpawnPosition(GlobalPos overworld + block + yaw/pitch). */
    public static Packet spawnPos(BlockPos pos) {
        Wire.Writer w = new Wire.Writer();
        w.utf("minecraft:overworld");
        w.l(pos.asLong());
        w.f(0).f(0);
        return new Packet(PacketIds.PLAY_SPAWN_POS, w.toBytes());
    }

    /**
     * PlayerPosition teleport: VarInt id + PositionMoveRotation (pos
     * Vec3, delta Vec3 zero, yRot/xRot) + relatives bit set as INT.
     * The relatives set uses Relative.SET_STREAM_CODEC =
     * ByteBufCodecs.INT (fixed 4-byte int, NOT a varint): an empty
     * set is 4 zero bytes. Sending a varint 0 here shifts the frame
     * and the real client fails "Failed to decode packet
     * clientbound/minecraft:player_position" (seen live 2026-09-12).
     */
    public static Packet position(int teleportId, double x, double y, double z,
            float yaw, float pitch) {
        Wire.Writer w = new Wire.Writer();
        w.vint(teleportId);
        w.d(x).d(y).d(z);
        w.d(0).d(0).d(0);
        w.f(yaw).f(pitch);
        w.i(0); // empty relatives as fixed int
        return new Packet(PacketIds.PLAY_POSITION, w.toBytes());
    }

    /**
     * PlayerInfoUpdate initializing, minimal: actions {ADD_PLAYER,
     * UPDATE_LISTED} (mask 0x09) + name + zero properties + listed.
     *
     * <p>G5: Player visibility (spawning other player entities or syncing player
     * lists/tab list between sessions) is DEFERRED: do not implement
     * peer player-info/list packets yet.
     */
    public static Packet playerInfo(UUID profile, String name) {
        Wire.Writer w = new Wire.Writer();
        w.b(0x09);
        w.vint(1);
        w.uuid(profile);
        w.utf(name);
        w.vint(0); // zero properties
        w.bool(true); // listed
        return new Packet(PacketIds.PLAY_PLAYER_INFO, w.toBytes());
    }

    /**
     * Periodic SetTime broadcast per vanilla {@code MinecraftServer.forceGameTimeSynchronization}
     * ({@code :1179}): sends LONG gameTime with an EMPTY clock map (count 0), relying on client
     * extrapolation between syncs.
     */
    public static Packet setTime(long gameTime) {
        Wire.Writer w = new Wire.Writer();
        w.l(gameTime);
        w.vint(0); // empty clock map
        return new Packet(PacketIds.PLAY_SET_TIME, w.toBytes());
    }

    /**
     * Clock modification SetTime broadcast per vanilla {@code ServerClockManager.modifyClock}
     * ({@code :115}, e.g. /time set): sends LONG gameTime and exactly the one modified clock
     * (overworld, registry id 0).
     */
    public static Packet setTime(long gameTime, long dayTime) {
        Wire.Writer w = new Wire.Writer();
        w.l(gameTime);
        w.vint(1); // one clock update: overworld (world_clock id 0)
        w.vint(0);
        w.vlong(dayTime); // totalTicks
        w.f(0.0f); // partialTick
        w.f(1.0f); // rate
        return new Packet(PacketIds.PLAY_SET_TIME, w.toBytes());
    }

    /**
     * Full sync SetTime packet per vanilla {@code ServerClockManager.createFullSyncPacket}
     * ({@code :130}), sent on player join ({@code PlayerList.sendLevelInfo:654}): sends
     * LONG gameTime and all registered clocks (overworld id 0, the_end id 1).
     */
    public static Packet setTimeFull(long gameTime, long overworldTicks, long theEndTicks) {
        Wire.Writer w = new Wire.Writer();
        w.l(gameTime);
        w.vint(2); // two clock updates: overworld (0) and the_end (1)
        w.vint(0); // overworld
        w.vlong(overworldTicks);
        w.f(0.0f);
        w.f(1.0f);
        w.vint(1); // the_end
        w.vlong(theEndTicks);
        w.f(0.0f);
        w.f(1.0f);
        return new Packet(PacketIds.PLAY_SET_TIME, w.toBytes());
    }

    /**
     * GameEvent START_RAINING (1, param 0). Vanilla sends it when
     * isRaining flips on and on join while raining
     * ({@code ServerLevel.advanceWeatherCycle},
     * {@code PlayerList.sendLevelInfo}).
     */
    public static Packet startRaining() {
        return gameEvent(PacketIds.EVENT_START_RAINING, 0.0f);
    }

    /** GameEvent STOP_RAINING (2, param 0), sent when isRaining flips off. */
    public static Packet stopRaining() {
        return gameEvent(PacketIds.EVENT_STOP_RAINING, 0.0f);
    }

    /** GameEvent RAIN_LEVEL_CHANGE (7, param = rain level 0..1), sent each tick the level moves. */
    public static Packet rainLevel(float level) {
        return gameEvent(PacketIds.EVENT_RAIN_LEVEL_CHANGE, level);
    }

    /** SetChunkCacheCenter(x, z). */
    public static Packet cacheCenter(int x, int z) {
        Wire.Writer w = new Wire.Writer();
        w.vint(x).vint(z);
        return new Packet(PacketIds.PLAY_CACHE_CENTER, w.toBytes());
    }

    /**
     * SetChunkCacheRadius(radius). Single VarInt, verified against the
     * 26.2 {@code ClientboundSetChunkCacheRadiusPacket} codec (see
     * {@link PacketIds#PLAY_CACHE_RADIUS}). Sent with the same radius
     * as {@link #join}'s chunkRadius so the client's chunk cache never
     * disagrees with what the login packet already advertised. The
     * separate simulationDistance remains 10.
     */
    public static Packet cacheRadius(int radius) {
        return new Packet(PacketIds.PLAY_CACHE_RADIUS, new Wire.Writer().vint(radius).toBytes());
    }

    /** ChunkBatchStart (empty). */
    public static Packet batchStart() {
        return new Packet(PacketIds.PLAY_CHUNK_BATCH_START, new byte[0]);
    }

    /** ChunkBatchFinished(count). */
    public static Packet batchFinished(int count) {
        return new Packet(PacketIds.PLAY_CHUNK_BATCH_FINISHED,
                new Wire.Writer().vint(count).toBytes());
    }

    /** Play KeepAlive(id). */
    public static Packet playKeepAlive(long id) {
        return new Packet(PacketIds.PLAY_KEEPALIVE_OUT,
                new Wire.Writer().l(id).toBytes());
    }

    /**
     * BlockUpdate(pos packed long + state id varint). Pos uses
     * BlockPos.asLong packing, state is the global BLOCK_STATE_REGISTRY
     * id ({@code ClientboundBlockUpdatePacket.java:18-24}).
     */
    public static Packet blockUpdate(long posLong, int stateId) {
        Wire.Writer w = new Wire.Writer();
        w.l(posLong);
        w.vint(stateId);
        return new Packet(PacketIds.PLAY_BLOCK_UPDATE, w.toBytes());
    }

    /**
     * SectionBlocksUpdate: section pos long, count varint, then per change
     * varlong {@code stateId << 12 | sectionRelativePos}
     * ({@code ClientboundSectionBlocksUpdatePacket.write}).
     */
    public static Packet sectionBlocksUpdate(long sectionPos, short[] positions, int[] stateIds) {
        Wire.Writer w = new Wire.Writer();
        w.l(sectionPos);
        w.vint(positions.length);
        for (int i = 0; i < positions.length; i++) {
            w.vlong((long) stateIds[i] << 12 | positions[i]);
        }
        return new Packet(PacketIds.PLAY_SECTION_BLOCKS_UPDATE, w.toBytes());
    }

    /**
     * BlockDestruction(breaker entity id varint, pos long, stage unsigned
     * byte) ({@code ClientboundBlockDestructionPacket.write}); a stage of -1
     * goes out as 0xFF, which the client reads as "no crack".
     */
    public static Packet blockDestruction(int entityId, long posLong, int progress) {
        Wire.Writer w = new Wire.Writer();
        w.vint(entityId);
        w.l(posLong);
        w.b(progress);
        return new Packet(PacketIds.PLAY_BLOCK_DESTRUCTION, w.toBytes());
    }

    /**
     * LevelEvent(type int, pos long, data int, global boolean)
     * ({@code ClientboundLevelEventPacket.write}).
     */
    public static Packet levelEvent(int type, long posLong, int data, boolean globalEvent) {
        Wire.Writer w = new Wire.Writer();
        w.i(type);
        w.l(posLong);
        w.i(data);
        w.bool(globalEvent);
        return new Packet(PacketIds.PLAY_LEVEL_EVENT, w.toBytes());
    }

    /**
     * BlockChangedAck(sequence varint). Ends the client's block
     * prediction up to that sequence, sent after the use-item-on is
     * handled ({@code ServerGamePacketListenerImpl.ackBlockChangesUpTo}).
     */
    public static Packet blockChangedAck(int sequence) {
        return new Packet(PacketIds.PLAY_BLOCK_CHANGED_ACK,
                new Wire.Writer().vint(sequence).toBytes());
    }

    /**
     * SystemChat(message, overlay=false). Formats as a plain NBT StringTag
     * Component (type 8, 2-byte big-endian short length, UTF-8 payload,
     * then boolean overlay false) matching vanilla's TRUSTED_STREAM_CODEC.
     *
     * <p>Standard vanilla chat format for player broadcasts: {@code "<Sender> message"}.
     */
    public static Packet systemChat(String text) {
        return systemChat(text, false);
    }

    /**
     * SystemChat(message, overlay).
     *
     * @param text formatted chat or system message (e.g. {@code "<Player> hello"})
     * @param overlay true for action bar, false for chat HUD
     */
    public static Packet systemChat(String text, boolean overlay) {
        byte[] nbt = Wire.disconnectNbt(text);
        Wire.Writer w = new Wire.Writer();
        w.bytes(nbt);
        w.bool(overlay);
        return new Packet(PacketIds.PLAY_SYSTEM_CHAT, w.toBytes());
    }

    /** Play Disconnect(reason). */
    public static Packet playDisconnect(String reason) {
        return new Packet(PacketIds.PLAY_DISCONNECT, Wire.disconnectNbt(reason));
    }

    /**
     * LevelChunkWithLight: int x, int z, chunk data, light data.
     * Sections encode in slot order (slot 0 first) with the two shorts
     * (nonEmpty, fluid) + states + biomes per section. Heightmaps empty,
     * block entities empty (Stage 3).
     *
     * <p>Light is FULL BRIGHT, not empty: every light section claims
     * full sky light (15 nibbles = 0xFF...F). The old all-empty light
     * (6 zero bytes) told the client "no light data anywhere", which
     * renders every chunk fully dark; the spawn chunk only looked lit
     * because the client keeps its own default light there (seen live
     * 2026-09-12: 1 lit chunk, rest black). Vanilla's own full-bright
     * path is the same shape: sky/block masks set, empty-masks clear,
     * one 2048-byte 0xFF layer per section.
     *
     * @param lightSections number of light sections (chunk sections + 2
     *         for the above/below light-only fringes)
     */
    public static Packet chunk(int x, int z, List<byte[]> sections, int lightSections) {
        Wire.Writer light = new Wire.Writer();
        java.util.BitSet full = new java.util.BitSet(lightSections);
        full.set(0, lightSections);
        light.bitSet(full); // skyYMask: all sections lit
        light.bitSet(new java.util.BitSet()); // blockYMask: none
        light.bitSet(new java.util.BitSet()); // emptySkyYMask: none empty
        light.bitSet(new java.util.BitSet()); // emptyBlockYMask: none declared
        light.vint(lightSections); // sky layers
        byte[] bright = new byte[2048];
        java.util.Arrays.fill(bright, (byte) 0xFF);
        for (int i = 0; i < lightSections; i++) {
            light.byteArray(bright);
        }
        light.vint(0); // block layers: none
        return chunk(x, z, sections, light.toBytes());
    }

    /**
     * LevelChunkWithLight using an exact pre-serialized Vanilla
     * ClientboundLightUpdatePacketData payload.
     */
    public static Packet chunk(int x, int z, List<byte[]> sections, byte[] lightData) {
        if (lightData == null || lightData.length == 0) {
            throw new IllegalArgumentException("light payload must be non-empty");
        }
        Wire.Writer w = new Wire.Writer();
        w.i(x).i(z);
        w.vint(0); // empty heightmap map
        Wire.Writer body = new Wire.Writer();
        for (byte[] section : sections) {
            body.bytes(section);
        }
        w.byteArray(body.toBytes());
        w.vint(0); // empty block entities
        w.bytes(lightData);
        return new Packet(PacketIds.PLAY_CHUNK, w.toBytes());
    }

    /**
     * Encode one 16x16x16 section to network bytes: nonEmptyCount
     * short, fluidCount short, states container, biomes container.
     * Palette strategy mirrors the disc rules (1 state: bits 0 +
     * single global id; 2-16: bits 4; 17-256: bits 5-8; more: global
     * bits with zero palette bytes). Data longs are packed
     * SimpleBitStorage order with NO length prefix.
     */
    public static byte[] sectionBytes(BlockState[] cells, int nonEmpty, int fluid,
            BlockState air, int plainsBiomeId) {
        Wire.Writer biome = new Wire.Writer();
        biome.b(0);
        biome.vint(plainsBiomeId);
        return sectionBytes(cells, nonEmpty, fluid, air, biome.toBytes());
    }

    public static byte[] sectionBytes(BlockState[] cells, int nonEmpty, int fluid,
            BlockState air, byte[] biomeContainer) {
        if (biomeContainer == null || biomeContainer.length == 0) {
            throw new IllegalArgumentException("biome container must be non-empty");
        }
        Map<BlockState, Integer> ids = new java.util.LinkedHashMap<>();
        List<Integer> globalIds = new ArrayList<>();
        int[] raw = new int[4096];
        for (int i = 0; i < 4096; i++) {
            BlockState st = cells[i] != null ? cells[i] : air;
            Integer id = ids.get(st);
            if (id == null) {
                id = globalIds.size();
                ids.put(st, id);
                globalIds.add(Block.getId(st));
            }
            raw[i] = id;
        }
        int bits = bitsFor(globalIds.size());
        Wire.Writer w = new Wire.Writer();
        w.s(nonEmpty);
        w.s(fluid);
        w.b(bits);
        if (bits == 0) {
            w.vint(globalIds.get(0));
        } else if (bits < 9) {
            w.vint(globalIds.size());
            for (int id : globalIds) {
                w.vint(id);
            }
            long[] data = pack(raw, bits);
            for (long v : data) {
                w.l(v);
            }
        } else {
            // Global palette: zero palette bytes, raw global ids.
            long[] data = packGlobal(raw, globalIds, bits);
            for (long v : data) {
                w.l(v);
            }
        }
        w.bytes(biomeContainer);
        return w.toBytes();
    }

    /**
     * Encode an all-air section without materialising its 4096 cells.
     *
     * <p>Byte-identical to {@link #sectionBytes} called with an array of
     * 4096 air states: that path derives a one-entry palette, so
     * {@code bitsFor(1)} is 0, no data longs are emitted, and both counts
     * are zero. Absent sections are ~45% of a column
     * ({@code getHeight() >> 4} slots vs materialised sections), so the
     * sender would otherwise allocate a {@code BlockState[4096]} and run
     * 8192 loop iterations per slot to rebuild a constant.
     */
    public static byte[] airSectionBytes(BlockState air, int plainsBiomeId) {
        Wire.Writer biome = new Wire.Writer();
        biome.b(0);
        biome.vint(plainsBiomeId);
        return airSectionBytes(air, biome.toBytes());
    }

    public static byte[] airSectionBytes(BlockState air, byte[] biomeContainer) {
        if (biomeContainer == null || biomeContainer.length == 0) {
            throw new IllegalArgumentException("biome container must be non-empty");
        }
        Wire.Writer w = new Wire.Writer();
        w.s(0);
        w.s(0);
        w.b(0);
        w.vint(Block.getId(air));
        w.bytes(biomeContainer);
        return w.toBytes();
    }

    static int bitsFor(int paletteSize) {
        if (paletteSize <= 1) {
            return 0;
        }
        if (paletteSize <= 16) {
            return 4;
        }
        if (paletteSize <= 32) {
            return 5;
        }
        if (paletteSize <= 64) {
            return 6;
        }
        if (paletteSize <= 128) {
            return 7;
        }
        if (paletteSize <= 256) {
            return 8;
        }
        return 32 - Integer.numberOfLeadingZeros(
                Block.BLOCK_STATE_REGISTRY.size() - 1);
    }

    static long[] pack(int[] raw, int bits) {
        int perLong = 64 / bits;
        long[] data = new long[(4096 + perLong - 1) / perLong];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < 4096; i++) {
            data[i / perLong] |= (raw[i] & mask) << ((i % perLong) * bits);
        }
        return data;
    }

    static long[] packGlobal(int[] raw, List<Integer> globalIds, int bits) {
        int perLong = 64 / bits;
        long[] data = new long[(4096 + perLong - 1) / perLong];
        long mask = bits == 64 ? -1L : (1L << bits) - 1;
        for (int i = 0; i < 4096; i++) {
            data[i / perLong] |= (globalIds.get(raw[i]) & mask) << ((i % perLong) * bits);
        }
        return data;
    }
}
