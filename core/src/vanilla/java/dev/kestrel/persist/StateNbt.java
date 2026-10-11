package dev.kestrel.persist;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Stage 2 BlockState to palette-entry codec: BlockState to
 * {@code {Name, Properties}} and back, mirroring vanilla's
 * {@code StateHolder.codec} ({@code StateHolder.java:150-161}).
 *
 * <p>On-disc form per entry: a compound with string {@code Name} (the
 * block's registry id, e.g. {@code minecraft:redstone_wire}) plus an
 * optional compound {@code Properties} mapping property name to its
 * serialized value string. Singleton states (no properties) carry no
 * {@code Properties} tag; multi-property states carry every property.
 * This is exactly what vanilla's CODEC emits, which is why a
 * Kestrel-written palette parses under real vanilla NBT ops.
 *
 * <p>Property values serialize via {@code Property.getName(value)}
 * (lowercase canonical form: NORTH/SOUTH ints, axis names): the same
 * function vanilla's codec uses on write. On read, the value is resolved
 * through {@code Property.getValue(name)} wrapped in Optional: unknown
 * names decode as empty, and the whole state falls back to the block
 * default, matching vanilla's lenient property recovery.
 *
 * <p>Thread-safety: stateless. Single tick-thread use like the rest of
 * the package.
 */
public final class StateNbt {

    private StateNbt() {
    }

    /** Encode one BlockState to its palette-entry compound. */
    public static Nbt.Tag.TCompound encode(BlockState state) {
        Nbt.Tag.TCompound out = Nbt.compound();
        Block block = state.getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        Nbt.putString(out, "Name", id == null ? "minecraft:air" : id.toString());
        Collection<Property<?>> props = state.getProperties();
        if (!props.isEmpty()) {
            Nbt.Tag.TCompound p = Nbt.compound();
            List<Property<?>> sorted = new ArrayList<>(props);
            sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (Property<?> prop : sorted) {
                Nbt.putString(p, prop.getName(), serializeValue(state, prop));
            }
            Nbt.put(out, "Properties", p);
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String serializeValue(BlockState state, Property<?> prop) {
        Property raw = prop;
        return raw.getName(state.getValue(raw));
    }

    /**
     * Decode one palette-entry compound to a BlockState. Unknown block ids
     * fall back to air; unknown property values fall back to the block
     * default; partially-known properties apply the known subset.
     */
    public static BlockState decode(Nbt.Tag.TCompound tag) throws Nbt.NbtException {
        String name = Nbt.getString(tag, "Name");
        Identifier id = Identifier.tryParse(name);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) {
            return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        }
        BlockState state = block.defaultBlockState();
        Nbt.Tag propsTag = Nbt.getOrNull(tag, "Properties");
        if (!(propsTag instanceof Nbt.Tag.TCompound props)) {
            return state;
        }
        for (Property<?> prop : state.getProperties()) {
            Nbt.Tag v = props.entries().get(prop.getName());
            if (v instanceof Nbt.Tag.TString s) {
                state = applyOrDefault(state, prop, s.value());
            }
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyOrDefault(BlockState state, Property<?> prop, String name) {
        Property raw = prop;
        java.util.Optional<?> opt = raw.getValue(name);
        if (opt.isPresent()) {
            return state.setValue(raw, (Comparable) opt.get());
        }
        return state;
    }
}
