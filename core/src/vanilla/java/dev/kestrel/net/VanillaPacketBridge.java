package dev.kestrel.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.GameProtocols;

import java.util.ArrayList;
import java.util.List;

/** Converts a vanilla clientbound play packet into Kestrel's existing wire shape. */
public final class VanillaPacketBridge {
    private static final ProtocolInfo<ClientGamePacketListener> CLIENTBOUND =
            GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)));

    private VanillaPacketBridge() {
    }

    /** Encode one packet, splitting a vanilla bundle into its on-wire delimiter sequence. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List<Outbound.Packet> encode(Packet<?> packet) {
        List<Outbound.Packet> result = new ArrayList<>();
        CLIENTBOUND.bundlerInfo().unbundlePacket(packet, member -> {
            ByteBuf buf = Unpooled.buffer();
            try {
                CLIENTBOUND.codec().encode(buf, (Packet) member);
                FriendlyByteBuf friendly = new FriendlyByteBuf(buf);
                int id = friendly.readVarInt();
                byte[] payload = new byte[friendly.readableBytes()];
                friendly.readBytes(payload);
                result.add(new Outbound.Packet(id, payload));
            } finally {
                buf.release();
            }
        });
        return result;
    }
}
