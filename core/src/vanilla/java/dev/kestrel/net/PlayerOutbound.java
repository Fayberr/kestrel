package dev.kestrel.net;

import java.util.List;
import java.util.UUID;

/**
 * Clientbound play packets about players (G15 slice 1): the tab list
 * entries and the swing animation. Byte layout written by hand from the
 * 26.2 packet classes cited on each builder; {@code PlayerTrackingParityCheck}
 * diffs every one against vanilla's own codec.
 *
 * <p>Thread-safety: stateless.
 */
public final class PlayerOutbound {

    private PlayerOutbound() {
    }

    /** {@code ClientboundPlayerInfoUpdatePacket.Action} ordinals, the bit each sets in the action set. */
    static final int ADD_PLAYER = 0;
    static final int INITIALIZE_CHAT = 1;
    static final int UPDATE_GAME_MODE = 2;
    static final int UPDATE_LISTED = 3;
    static final int UPDATE_LATENCY = 4;
    static final int UPDATE_DISPLAY_NAME = 5;
    static final int UPDATE_LIST_ORDER = 6;
    static final int UPDATE_HAT = 7;

    /** One player's tab list row: {@code ClientboundPlayerInfoUpdatePacket.Entry(ServerPlayer)}. */
    public record Entry(UUID profile, String name, List<Outbound.ProfileProperty> properties, int latency,
            int gameMode, boolean showHat) {
    }

    /**
     * {@code ClientboundPlayerInfoUpdatePacket.createPlayerInitializing}
     * ({@code :43-55}): every action but none, written by {@code write}
     * ({@code :72-81}): the action set as a fixed bit set of the eight
     * actions (one byte), the entry count, then per entry the UUID and each
     * action's field in ordinal order. The entry's values are those of
     * {@code Entry(ServerPlayer)} ({@code :158-170}): listed, no display
     * name, list order 0 and no chat session until the client sends one.
     */
    public static Outbound.Packet playerInfoInitializing(List<Entry> entries) {
        Wire.Writer w = new Wire.Writer();
        w.b(0xFF);
        w.vint(entries.size());
        for (Entry e : entries) {
            w.uuid(e.profile());
            // ADD_PLAYER: ByteBufCodecs.PLAYER_NAME, then GAME_PROFILE_PROPERTIES.
            w.utf(e.name());
            w.vint(e.properties().size());
            for (Outbound.ProfileProperty p : e.properties()) {
                w.utf(p.name());
                w.utf(p.value());
                if (p.signature() != null) {
                    w.bool(true);
                    w.utf(p.signature());
                } else {
                    w.bool(false);
                }
            }
            w.bool(false); // INITIALIZE_CHAT: writeNullable, no session
            w.vint(e.gameMode()); // UPDATE_GAME_MODE
            w.bool(true); // UPDATE_LISTED
            w.vint(e.latency()); // UPDATE_LATENCY
            w.bool(false); // UPDATE_DISPLAY_NAME: writeNullable, no name
            w.vint(0); // UPDATE_LIST_ORDER
            w.bool(e.showHat()); // UPDATE_HAT
        }
        return new Outbound.Packet(PacketIds.PLAY_PLAYER_INFO, w.toBytes());
    }

    /**
     * {@code new ClientboundPlayerInfoUpdatePacket(UPDATE_HAT, player)}, which
     * {@code handleClientInformation} broadcasts when the hat layer flips
     * ({@code ServerGamePacketListenerImpl.java:2081-2083}).
     */
    public static Outbound.Packet playerInfoHat(UUID profile, boolean showHat) {
        Wire.Writer w = new Wire.Writer();
        w.b(1 << UPDATE_HAT);
        w.vint(1);
        w.uuid(profile);
        w.bool(showHat);
        return new Outbound.Packet(PacketIds.PLAY_PLAYER_INFO, w.toBytes());
    }

    /** {@code ClientboundPlayerInfoRemovePacket} ({@code :20-22}): a list of profile UUIDs. */
    public static Outbound.Packet playerInfoRemove(List<UUID> profiles) {
        Wire.Writer w = new Wire.Writer();
        w.vint(profiles.size());
        for (UUID id : profiles) {
            w.uuid(id);
        }
        return new Outbound.Packet(PacketIds.PLAY_PLAYER_INFO_REMOVE, w.toBytes());
    }

    /**
     * {@code ClientboundAnimatePacket} ({@code :31-34}): VarInt entity id,
     * unsigned byte action (0 swing main hand, 3 swing off hand).
     */
    public static Outbound.Packet animate(int entityId, int action) {
        Wire.Writer w = new Wire.Writer();
        w.vint(entityId);
        w.b(action);
        return new Outbound.Packet(PacketIds.PLAY_ANIMATE, w.toBytes());
    }
}
