package dev.kestrel.net;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@code ServerLevel.destroyBlockProgress} ({@code ServerLevel.java:1024-1035}):
 * a {@code ClientboundBlockDestructionPacket(id, pos, progress)} to every
 * player of the server's list, in list order, that is in this level, is not
 * the breaker (by entity id) and whose squared distance from the block's
 * corner is under 1024 (32 blocks). The breaker's own client draws its own
 * cracks, so it never receives one.
 */
public final class BlockDestructionBroadcast {
    /** {@code ServerLevel.destroyBlockProgress}'s squared range. */
    static final double RANGE_SQR = 1024.0;

    private BlockDestructionBroadcast() {
    }

    /** One entry of the player list, as {@code destroyBlockProgress} reads it. */
    public interface Receiver {
        Object dimension();

        int entityId();

        double x();

        double y();

        double z();

        void send(Outbound.Packet packet);
    }

    public static void broadcast(List<? extends Receiver> players, Object dimension, int id, BlockPos pos,
            int progress) {
        Outbound.Packet packet = null;
        for (int i = 0; i < players.size(); i++) {
            Receiver player = players.get(i);
            if (player.dimension() == dimension && player.entityId() != id) {
                double xd = pos.getX() - player.x();
                double yd = pos.getY() - player.y();
                double zd = pos.getZ() - player.z();
                if (xd * xd + yd * yd + zd * zd < RANGE_SQR) {
                    if (packet == null) {
                        packet = Outbound.blockDestruction(id, pos.asLong(), progress);
                    }
                    player.send(packet);
                }
            }
        }
    }
}
