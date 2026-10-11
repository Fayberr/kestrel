package dev.kestrel.net;

import dev.kestrel.item.ItemStackCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import com.mojang.datafixers.util.Pair;

/**
 * Outbound builders for the entity tracking packets (S14 slice 2), in the
 * same {@link Outbound.Packet} shape as every other Kestrel builder.
 *
 * <p>Reuse rule, as in {@code dev.kestrel.item.ItemStackCodec}: the framing
 * (which fields, in which order, with which primitive encoding) is written
 * here by hand from the 26.2 packet classes cited on each builder, so
 * {@code EntityTrackingCheck} can diff it byte for byte against vanilla's
 * own codecs. Leaf encodings that vanilla owns are delegated, never
 * re-derived: the low precision velocity ({@code Vec3.LP_STREAM_CODEC},
 * i.e. {@code LpVec3}), the entity type registry id, a synched data value
 * ({@link SynchedEntityData.DataValue#write}), the attribute holder id
 * ({@link Attribute#STREAM_CODEC}), and a modifier's identifier and
 * operation.
 *
 * <p>Leaves are written through a {@link RegistryFriendlyByteBuf} over the
 * built-in registries. That covers every built-in serializer and plain text
 * components. A data value whose serializer resolves a datapack registry
 * (painting or wolf variants, for example) would need the network registry
 * access instead; no entity Kestrel tracks today carries one.
 *
 * <p>Thread-safety: stateless apart from the frozen registry access.
 */
public final class EntityOutbound {

    private EntityOutbound() {
    }

    private static final RegistryAccess LEAF_ACCESS =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    /** Run one vanilla leaf encoder and return exactly the bytes it wrote. */
    private static byte[] leaf(Consumer<RegistryFriendlyByteBuf> encoder) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), LEAF_ACCESS);
        try {
            encoder.accept(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** {@code Vec3.LP_STREAM_CODEC} ({@code LpVec3.java}), a delegated leaf. */
    private static byte[] lpVec3(Vec3 v) {
        return leaf(buf -> Vec3.LP_STREAM_CODEC.encode(buf, v));
    }

    /** {@code ClientboundEntityEventPacket}: fixed int id, then event byte. */
    public static Outbound.Packet entityEvent(int id, int event) {
        Wire.Writer w = new Wire.Writer();
        w.i(id);
        w.b(event);
        return new Outbound.Packet(PacketIds.PLAY_ENTITY_EVENT, w.toBytes());
    }

    /**
     * {@code ClientboundDamageEventPacket}: entity, served damage-type raw id,
     * optional entity ids as {@code id + 1}, then the optional source position.
     * The caller supplies the raw id because damage types belong to the synced
     * datapack registry rather than the built-in registry access used by leaves.
     */
    public static Outbound.Packet damageEvent(ClientboundDamageEventPacket packet, int damageTypeId) {
        Wire.Writer w = new Wire.Writer();
        w.vint(packet.entityId());
        w.vint(damageTypeId);
        w.vint(packet.sourceCauseId() + 1);
        w.vint(packet.sourceDirectId() + 1);
        w.bool(packet.sourcePosition().isPresent());
        packet.sourcePosition().ifPresent(position -> {
            w.d(position.x());
            w.d(position.y());
            w.d(position.z());
        });
        return new Outbound.Packet(PacketIds.PLAY_DAMAGE_EVENT, w.toBytes());
    }

    /** {@code ClientboundSetEquipmentPacket}, preserving the supplied slot order. */
    public static Outbound.Packet setEquipment(int id, List<Pair<EquipmentSlot, ItemStack>> slots) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        for (int i = 0; i < slots.size(); i++) {
            Pair<EquipmentSlot, ItemStack> entry = slots.get(i);
            w.b(entry.getFirst().ordinal() | (i + 1 < slots.size() ? 0x80 : 0));
            w.bytes(leaf(buf -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, entry.getSecond())));
        }
        return new Outbound.Packet(PacketIds.PLAY_SET_EQUIPMENT, w.toBytes());
    }

    /**
     * Bundle delimiter (clientbound index 0, empty payload). A bundle goes
     * out as delimiter, members, delimiter, which is what vanilla's
     * {@code BundlerInfo.unbundlePacket} writes for a
     * {@code ClientboundBundlePacket} ({@code BundlerInfo.java:19-28}).
     */
    public static Outbound.Packet bundleDelimiter() {
        return new Outbound.Packet(PacketIds.PLAY_BUNDLE_DELIMITER, new byte[0]);
    }

    /** Wrap members in delimiters, the wire form of a bundle. */
    public static List<Outbound.Packet> bundle(List<Outbound.Packet> members) {
        List<Outbound.Packet> out = new ArrayList<>(members.size() + 2);
        out.add(bundleDelimiter());
        out.addAll(members);
        out.add(bundleDelimiter());
        return out;
    }

    /**
     * AddEntity ({@code ClientboundAddEntityPacket.java:109-121}): VarInt id,
     * UUID, VarInt type id, three doubles, LpVec3 movement, then xRot, yRot,
     * yHeadRot as packed bytes, then VarInt data.
     */
    public static Outbound.Packet addEntity(int id, UUID uuid, EntityType<?> type,
            double x, double y, double z, Vec3 movement,
            byte xRot, byte yRot, byte yHeadRot, int data) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.uuid(uuid);
        w.vint(BuiltInRegistries.ENTITY_TYPE.getId(type));
        w.d(x);
        w.d(y);
        w.d(z);
        w.bytes(lpVec3(movement));
        w.b(xRot);
        w.b(yRot);
        w.b(yHeadRot);
        w.vint(data);
        return new Outbound.Packet(PacketIds.PLAY_ADD_ENTITY, w.toBytes());
    }

    /** RemoveEntities ({@code ClientboundRemoveEntitiesPacket.java}, {@code writeIntIdList}). */
    public static Outbound.Packet removeEntities(int... ids) {
        Wire.Writer w = new Wire.Writer();
        w.vint(ids.length);
        for (int id : ids) {
            w.vint(id);
        }
        return new Outbound.Packet(PacketIds.PLAY_REMOVE_ENTITIES, w.toBytes());
    }

    /** TakeItemEntity ({@code ClientboundTakeItemEntityPacket.java}): itemId, playerId, amount. */
    public static Outbound.Packet takeItemEntity(int itemId, int playerId, int amount) {
        Wire.Writer w = new Wire.Writer();
        w.vint(itemId);
        w.vint(playerId);
        w.vint(amount);
        return new Outbound.Packet(PacketIds.PLAY_TAKE_ITEM_ENTITY, w.toBytes());
    }

    /**
     * SetEntityData ({@code ClientboundSetEntityDataPacket.java}): VarInt id,
     * each value as vanilla writes it (a delegated leaf), then the 255 end
     * marker. An item stack value (a dropped item's stack, S14 slice 3) is
     * framed here instead and its stack written by Kestrel's own
     * {@link ItemStackCodec}; see {@link #itemStackValue}.
     */
    public static Outbound.Packet setEntityData(int id, List<SynchedEntityData.DataValue<?>> values) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        for (SynchedEntityData.DataValue<?> value : values) {
            if (value.serializer() == EntityDataSerializers.ITEM_STACK) {
                w.bytes(itemStackValue(value.id(), (ItemStack) value.value()));
            } else {
                w.bytes(leaf(value::write));
            }
        }
        w.b(255);
        return new Outbound.Packet(PacketIds.PLAY_SET_ENTITY_DATA, w.toBytes());
    }

    /**
     * One item stack data value, {@code SynchedEntityData.DataValue.write}
     * ({@code SynchedEntityData.java:220-229}): the data id as a byte, the
     * serializer's registered id as a VarInt (a delegated leaf), then the
     * stack through {@link ItemStackCodec}, which mirrors the
     * {@code ItemStack.OPTIONAL_STREAM_CODEC} the {@code ITEM_STACK}
     * serializer uses ({@code EntityDataSerializers.java:62-71}).
     */
    static byte[] itemStackValue(int dataId, ItemStack stack) {
        return leaf(buf -> {
            buf.writeByte(dataId);
            buf.writeVarInt(EntityDataSerializers.getSerializedId(EntityDataSerializers.ITEM_STACK));
            ItemStackCodec.encode(buf, stack);
        });
    }

    /** MoveEntity.Pos: VarInt id, three shorts, onGround. */
    public static Outbound.Packet moveEntityPos(int id, short xa, short ya, short za, boolean onGround) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.s(xa);
        w.s(ya);
        w.s(za);
        w.bool(onGround);
        return new Outbound.Packet(PacketIds.PLAY_MOVE_ENTITY_POS, w.toBytes());
    }

    /** MoveEntity.PosRot: VarInt id, three shorts, yRot, xRot, onGround. */
    public static Outbound.Packet moveEntityPosRot(int id, short xa, short ya, short za,
            byte yRot, byte xRot, boolean onGround) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.s(xa);
        w.s(ya);
        w.s(za);
        w.b(yRot);
        w.b(xRot);
        w.bool(onGround);
        return new Outbound.Packet(PacketIds.PLAY_MOVE_ENTITY_POS_ROT, w.toBytes());
    }

    /** MoveEntity.Rot: VarInt id, yRot, xRot, onGround. */
    public static Outbound.Packet moveEntityRot(int id, byte yRot, byte xRot, boolean onGround) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.b(yRot);
        w.b(xRot);
        w.bool(onGround);
        return new Outbound.Packet(PacketIds.PLAY_MOVE_ENTITY_ROT, w.toBytes());
    }

    /**
     * EntityPositionSync ({@code ClientboundEntityPositionSyncPacket.java}):
     * VarInt id, then {@code PositionMoveRotation} (position as three
     * doubles, delta movement as three doubles, yRot float, xRot float), then
     * onGround.
     */
    public static Outbound.Packet entityPositionSync(int id, Vec3 position, Vec3 movement,
            float yRot, float xRot, boolean onGround) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.d(position.x);
        w.d(position.y);
        w.d(position.z);
        w.d(movement.x);
        w.d(movement.y);
        w.d(movement.z);
        w.f(yRot);
        w.f(xRot);
        w.bool(onGround);
        return new Outbound.Packet(PacketIds.PLAY_ENTITY_POSITION_SYNC, w.toBytes());
    }

    /** RotateHead ({@code ClientboundRotateHeadPacket.java}): VarInt id, packed yaw. */
    public static Outbound.Packet rotateHead(int id, byte yHeadRot) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.b(yHeadRot);
        return new Outbound.Packet(PacketIds.PLAY_ROTATE_HEAD, w.toBytes());
    }

    /** SetEntityMotion ({@code ClientboundSetEntityMotionPacket.java}): VarInt id, LpVec3. */
    public static Outbound.Packet setEntityMotion(int id, Vec3 movement) {
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.bytes(lpVec3(movement));
        return new Outbound.Packet(PacketIds.PLAY_SET_ENTITY_MOTION, w.toBytes());
    }

    /** SetPassengers ({@code ClientboundSetPassengersPacket.java}): VarInt vehicle, VarInt array. */
    public static Outbound.Packet setPassengers(int vehicle, int[] passengers) {
        Wire.Writer w = new Wire.Writer();
        w.vint(vehicle);
        w.vint(passengers.length);
        for (int p : passengers) {
            w.vint(p);
        }
        return new Outbound.Packet(PacketIds.PLAY_SET_PASSENGERS, w.toBytes());
    }

    /**
     * UpdateAttributes ({@code ClientboundUpdateAttributesPacket.java}):
     * VarInt id, VarInt count, then per attribute its holder id (leaf), base
     * double, VarInt modifier count, and per modifier its identifier (leaf),
     * amount double and operation (leaf). Snapshots are taken in the
     * iteration order of {@code values}, as the vanilla constructor does.
     */
    public static Outbound.Packet updateAttributes(int id, Collection<AttributeInstance> values) {
        if (values.size() > 128) {
            // ByteBufCodecs.list(128) refuses to encode more; so do we.
            throw new IllegalArgumentException("more than 128 attributes: " + values.size());
        }
        Wire.Writer w = new Wire.Writer();
        w.vint(id);
        w.vint(values.size());
        for (AttributeInstance instance : values) {
            Holder<Attribute> attribute = instance.getAttribute();
            w.bytes(leaf(buf -> Attribute.STREAM_CODEC.encode(buf, attribute)));
            w.d(instance.getBaseValue());
            Collection<AttributeModifier> modifiers = instance.getModifiers();
            w.vint(modifiers.size());
            for (AttributeModifier modifier : modifiers) {
                Identifier modifierId = modifier.id();
                w.bytes(leaf(buf -> Identifier.STREAM_CODEC.encode(buf, modifierId)));
                w.d(modifier.amount());
                w.bytes(leaf(buf -> AttributeModifier.Operation.STREAM_CODEC.encode(buf, modifier.operation())));
            }
        }
        return new Outbound.Packet(PacketIds.PLAY_UPDATE_ATTRIBUTES, w.toBytes());
    }
}
