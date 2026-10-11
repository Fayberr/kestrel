package dev.kestrel.net;

/**
 * Stage 3 numeric packet ids for 26.2 (protocol 776), read off the
 * registration orders (registration order IS the numeric id:
 * {@code ProtocolInfoBuilder.java:79-83}).
 *
 * <p>Handshake: {@code HandshakeProtocols.java:10-12}. Login:
 * {@code LoginProtocols.java:13-30}. Configuration:
 * {@code ConfigurationProtocols.java:33-69}. Play:
 * {@code GameProtocols.java:61-273} (clientbound index 0 is the bundle
 * delimiter, so every play clientbound id is shifted +1 versus older
 * tables that start at AddEntity=0).
 *
 * <p>Only the packets Kestrel sends or handles are named here. Anything
 * else is ignored (inbound) or never emitted (outbound).
 */
public final class PacketIds {

    private PacketIds() {
    }

    // ---- handshake serverbound ----
    public static final int HS_INTENTION = 0x00;

    // ---- status ----
    public static final int STATUS_REQUEST = 0x00;
    public static final int STATUS_PING = 0x01;
    public static final int STATUS_RESPONSE = 0x00;
    public static final int STATUS_PONG = 0x01;

    // ---- login ----
    public static final int LOGIN_HELLO = 0x00;
    public static final int LOGIN_ACK = 0x03;
    public static final int LOGIN_DISCONNECT = 0x00;
    public static final int LOGIN_FINISHED = 0x02;
    public static final int LOGIN_COMPRESSION = 0x03;
    /** Serverbound key exchange (login serverbound index 1, {@code LoginProtocols.java:16}). */
    public static final int LOGIN_KEY = 0x01;
    /** Clientbound hello asking for key exchange (login clientbound index 1, {@code :25}). */
    public static final int LOGIN_HELLO_OUT = 0x01;

    // ---- configuration ----
    public static final int CONFIG_CLIENT_INFO = 0x00;
    public static final int CONFIG_FINISH = 0x03;
    public static final int CONFIG_KEEPALIVE_IN = 0x04;
    public static final int CONFIG_SELECT_KNOWN = 0x07;
    public static final int CONFIG_CUSTOM_PAYLOAD = 0x01;
    public static final int CONFIG_DISCONNECT = 0x02;
    public static final int CONFIG_FINISH_OUT = 0x03;
    public static final int CONFIG_KEEPALIVE_OUT = 0x04;
    public static final int CONFIG_REGISTRY_DATA = 0x07;
    public static final int CONFIG_FEATURES = 0x0C;
    public static final int CONFIG_TAGS = 0x0D;
    public static final int CONFIG_SELECT_KNOWN_OUT = 0x0E;

    // ---- play ----
    public static final int PLAY_ACCEPT_TELEPORT = 0x00;
    /**
     * Serverbound chat packet (serverbound index 9 = 0x09, read off
     * {@code GameProtocols.SERVERBOUND_TEMPLATE.details().listPackets}
     * for minecraft:chat in the 26.2 server jar).
     */
    public static final int PLAY_CHAT = 0x09;
    /**
     * Serverbound chat command packet (serverbound index 7 = 0x07, read off
     * {@code GameProtocols.SERVERBOUND_TEMPLATE.details().listPackets}
     * for minecraft:chat_command in the 26.2 server jar).
     */
    public static final int PLAY_CHAT_COMMAND = 0x07;
    /**
     * Serverbound signed chat command packet (serverbound index 8 = 0x08,
     * read off {@code GameProtocols.SERVERBOUND_TEMPLATE.details().listPackets}
     * for minecraft:chat_command_signed in the 26.2 server jar).
     */
    public static final int PLAY_CHAT_COMMAND_SIGNED = 0x08;
    /**
     * Serverbound dig action (serverbound index 41 = 0x29, counted
     * off {@code GameProtocols.java:60-128} registration order).
     */
    public static final int PLAY_PLAYER_ACTION = 0x29;
    /** Serverbound set_carried_item, counted from the 26.2 registration list. */
    public static final int PLAY_SET_CARRIED_ITEM = 0x35;
    /**
     * Serverbound use-item-on (serverbound index 66 = 0x42, read off
     * {@code GameProtocols.SERVERBOUND_TEMPLATE.details().listPackets}
     * for minecraft:use_item_on in the 26.2 server jar).
     */
    public static final int PLAY_USE_ITEM_ON = 0x42;
    /**
     * Clientbound block-changed ack (clientbound index 4, same listing
     * off {@code CLIENTBOUND_TEMPLATE}).
     */
    public static final int PLAY_BLOCK_CHANGED_ACK = 0x04;
    /**
     * Clientbound block destruction (crack stage): clientbound index 5, the
     * entry after the ack in {@code GameProtocols}' clientbound template.
     * {@code BlockBreakingCheck} reads it off the live listing.
     */
    public static final int PLAY_BLOCK_DESTRUCTION = 0x05;
    /**
     * Clientbound single-block change (probed off the live vanilla
     * protocol details: 0x08).
     */
    public static final int PLAY_BLOCK_UPDATE = 0x08;
    public static final int PLAY_SECTION_BLOCKS_UPDATE = 0x54;
    /**
     * Clientbound level event: the 47th entry of {@code GameProtocols}'
     * clientbound template, after the bundle delimiter at 0.
     * {@code FallingBlockParityCheck} proves the bytes against vanilla's codec.
     */
    public static final int PLAY_LEVEL_EVENT = 0x2E;
    public static final int PLAY_CHUNK_BATCH_RECEIVED = 0x0B;
    public static final int PLAY_CLIENT_TICK_END = 0x0D;
    public static final int PLAY_KEEPALIVE_IN = 0x1C;
    public static final int PLAY_MOVE_POS = 0x1E;
    public static final int PLAY_MOVE_POSROT = 0x1F;
    public static final int PLAY_MOVE_ROT = 0x20;
    public static final int PLAY_MOVE_STATUS = 0x21;
    public static final int PLAY_PLAYER_LOADED = 0x2C;
    /** Serverbound client_command, registration index 12. */
    public static final int PLAY_CLIENT_COMMAND = 0x0C;

    public static final int PLAY_CHANGE_DIFFICULTY = 0x0A;
    public static final int PLAY_CONTAINER_CLICK = 0x12;
    public static final int PLAY_CONTAINER_CLOSE = 0x13;
    public static final int PLAY_CONTAINER_SET_CONTENT = 0x12;
    public static final int PLAY_CONTAINER_SET_SLOT = 0x14;
    public static final int PLAY_CHUNK_BATCH_FINISHED = 0x0B;
    public static final int PLAY_CHUNK_BATCH_START = 0x0C;
    public static final int PLAY_DISCONNECT = 0x20;
    public static final int PLAY_GAME_EVENT = 0x26;
    public static final int PLAY_INIT_BORDER = 0x2B;
    public static final int PLAY_KEEPALIVE_OUT = 0x2C;
    public static final int PLAY_CHUNK = 0x2D;
    public static final int PLAY_JOIN = 0x31;
    public static final int PLAY_ABILITIES = 0x40;
    public static final int PLAY_PLAYER_INFO = 0x46;
    public static final int PLAY_POSITION = 0x48;
    public static final int PLAY_CACHE_CENTER = 0x5E;
    /**
     * Clientbound set_chunk_cache_radius (clientbound index 95 = 0x5F),
     * verified locally against the 26.2 server jar via the same
     * registration-order probe {@link PlayShapeCheck#vanillaId} uses
     * (bytecode inspection of {@code GameProtocols.CLIENTBOUND_TEMPLATE}
     * on {@code net.minecraft.network.protocol.game.GamePacketTypes
     * .CLIENTBOUND_SET_CHUNK_CACHE_RADIUS}), not assumed from prior
     * investigation notes. Codec is a single VarInt radius
     * ({@code ClientboundSetChunkCacheRadiusPacket.class}).
     */
    public static final int PLAY_CACHE_RADIUS = 0x5F;
    /** Clientbound set_cursor_item (clientbound index 96 = 0x60). */
    public static final int PLAY_SET_CURSOR_ITEM = 0x60;
    public static final int PLAY_SPAWN_POS = 0x61;
    public static final int PLAY_HELD_SLOT = 0x69;
    /**
     * Clientbound set_time (clientbound index 113 = 0x71, listed off
     * {@code GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets}
     * in the 26.2 server jar; carries game time plus the world-clock
     * day time). The same listing gives game_event at 38 = 0x26.
     */
    public static final int PLAY_SET_TIME = 0x71;
    /**
     * Clientbound system chat packet (clientbound index 121 = 0x79, read
     * off {@code GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets}
     * for minecraft:system_chat in the 26.2 server jar).
     *
     * <p>Kestrel broadcasts chat via system_chat rather than player_chat (0x41)
     * or disguised_chat (0x21): Kestrel runs in offline mode without secure
     * chat enforcement (enforcesSecureChat=false). Offline clients do not sign
     * chat; system_chat requires no dynamic chat_type registry bindings or
     * signature chains, and vanilla itself broadcasts unsigned chat and system
     * messages via ClientboundSystemChatPacket without client warnings.
     */
    public static final int PLAY_SYSTEM_CHAT = 0x79;
    public static final int PLAY_RECIPES = 0x85;

    // ---- play clientbound: entity tracking (S14 slice 2) ----
    // Read off GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets in the
    // 26.2 server jar. EntityTrackingCheck re-derives every one of these at
    // runtime from vanilla's registered PacketType and fails on any drift.
    /** Bundle delimiter: clientbound index 0, brackets a bundle on the wire. */
    public static final int PLAY_BUNDLE_DELIMITER = 0x00;
    public static final int PLAY_ADD_ENTITY = 0x01;
    public static final int PLAY_DAMAGE_EVENT = 0x19;
    public static final int PLAY_HURT_ANIMATION = 0x2A;
    public static final int PLAY_SET_HEALTH = 0x68;
    public static final int PLAY_ENTITY_POSITION_SYNC = 0x23;
    public static final int PLAY_ENTITY_EVENT = 0x22;
    public static final int PLAY_MOVE_ENTITY_POS = 0x35;
    public static final int PLAY_MOVE_ENTITY_POS_ROT = 0x36;
    public static final int PLAY_MOVE_ENTITY_ROT = 0x38;
    public static final int PLAY_REMOVE_ENTITIES = 0x4D;
    public static final int PLAY_RESPAWN = 0x52;
    public static final int PLAY_ROTATE_HEAD = 0x53;
    public static final int PLAY_SET_ENTITY_DATA = 0x63;
    public static final int PLAY_SET_ENTITY_MOTION = 0x65;
    public static final int PLAY_SET_EQUIPMENT = 0x66;
    public static final int PLAY_SET_PASSENGERS = 0x6B;
    public static final int PLAY_UPDATE_ATTRIBUTES = 0x83;
    /** Clientbound take item entity (clientbound index 124 = 0x7C). */
    public static final int PLAY_TAKE_ITEM_ENTITY = 0x7C;

    // ---- play: player bodies (G15 slice 1) ----
    // Read off GameProtocols' templates in the 26.2 server jar;
    // PlayerTrackingParityCheck re-derives each at runtime and fails on drift.
    /** Clientbound: the swing animation. */
    public static final int PLAY_ANIMATE = 0x02;
    /** Clientbound: tab list removal. */
    public static final int PLAY_PLAYER_INFO_REMOVE = 0x45;
    public static final int PLAY_COMBAT_KILL = 0x44;
    /** Serverbound: ClientInformation again, in play. */
    public static final int PLAY_CLIENT_INFORMATION = 0x0E;
    /** Serverbound: start/stop sprinting (and riding, sleeping, gliding actions). */
    public static final int PLAY_PLAYER_COMMAND = 0x2A;
    /** Serverbound: the movement keys, shift among them. */
    public static final int PLAY_PLAYER_INPUT = 0x2B;
    /** Serverbound: an arm swing. */
    public static final int PLAY_SWING = 0x3F;

    // ---- game event ids (ClientboundGameEventPacket.java:15-28) ----
    /** Weather events: START_RAINING 1, STOP_RAINING 2, RAIN_LEVEL_CHANGE 7. */
    public static final int EVENT_START_RAINING = 1;
    public static final int EVENT_STOP_RAINING = 2;
    public static final int EVENT_RAIN_LEVEL_CHANGE = 7;
    public static final int EVENT_LEVEL_CHUNKS_LOAD_START = 13;

    // ---- play phases ----
    /** Handshake next-state values ({@code ClientIntent.java:8-15}). */
    public static final int INTENT_STATUS = 1;
    public static final int INTENT_LOGIN = 2;
}
