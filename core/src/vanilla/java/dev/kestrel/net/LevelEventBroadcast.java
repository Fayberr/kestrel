package dev.kestrel.net;

import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * {@code ServerLevel.levelEvent} ({@code ServerLevel.java:1109-1122}) over
 * {@code PlayerList.broadcast} ({@code PlayerList.java:604-624}): a
 * {@code ClientboundLevelEventPacket(type, pos, data, false)} to every player
 * in list order, except the source player, in the same dimension, whose
 * squared distance from the event's block coordinates is under 64 * 64.
 */
public final class LevelEventBroadcast {
    /** {@code ServerLevel.levelEvent}'s range. */
    static final double RANGE = 64.0;

    private LevelEventBroadcast() {
    }

    /** One entry of the player list, as {@code broadcast} reads it. */
    public interface Receiver {
        Object dimension();

        double x();

        double y();

        double z();

        void send(Outbound.Packet packet);
    }

    public static void broadcast(List<? extends Receiver> players, @Nullable Receiver except, Object dimension,
            int type, BlockPos pos, int data) {
        Outbound.Packet packet = Outbound.levelEvent(type, pos.asLong(), data, false);
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        for (int i = 0; i < players.size(); i++) {
            Receiver player = players.get(i);
            if (player != except && player.dimension() == dimension) {
                double xd = x - player.x();
                double yd = y - player.y();
                double zd = z - player.z();
                if (xd * xd + yd * yd + zd * zd < RANGE * RANGE) {
                    player.send(packet);
                }
            }
        }
    }
}
