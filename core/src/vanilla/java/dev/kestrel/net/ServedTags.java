package dev.kestrel.net;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds the block tags Kestrel serves to clients onto the server's own block
 * holders, as vanilla's tag load does at startup
 * ({@code TagLoader} then {@code Holder.Reference.bindTags}). On the live
 * server the generator's vanilla resource load has normally bound them
 * already ({@link #blockTagsBound()}), and that binding is kept; this is the
 * fallback for a server without the live generator and for checks that boot
 * without a resource load. With no binding at all every block tag is empty,
 * so for example no tool is the correct tool for any block
 * ({@code Tool.Rule} matches a tag).
 *
 * <p>The served tags are vanilla's own data (the extracted vanilla datapack),
 * resolved to raw block ids in the vanilla network order
 * ({@code Datapack.tags}), so server and client agree on every membership.
 */
public final class ServedTags {
    private ServedTags() {
    }

    /** The registry-to-order map {@link Datapack#load(java.nio.file.Path, List, Map)} needs for block tags. */
    public static Map<String, List<String>> blockStaticOrder() {
        List<String> ids = new ArrayList<>(BuiltInRegistries.BLOCK.size());
        for (int i = 0; i < BuiltInRegistries.BLOCK.size(); i++) {
            ids.add(BuiltInRegistries.BLOCK.byId(i) == null ? null
                    : BuiltInRegistries.BLOCK.getKey(BuiltInRegistries.BLOCK.byId(i)).toString());
        }
        return Map.of("minecraft:block", ids);
    }

    /**
     * True when the block holders already carry tags: the live generator's
     * vanilla resource load ({@code WorldLoader.load}, which ends in
     * {@code ReloadableServerResources.updateComponentsAndStaticRegistryTags},
     * {@code WorldLoader.java:74}) binds vanilla's own block tags and item
     * components before the datapack is served. That binding is vanilla's and
     * is kept as is.
     */
    public static boolean blockTagsBound() {
        return net.minecraft.world.level.block.Blocks.STONE.builtInRegistryHolder().tags().findAny().isPresent();
    }

    /**
     * Bind every served block tag, unless {@link #blockTagsBound()}. Returns the number of tags bound (0 when
     * the packs carry no block tags, in which case nothing changes).
     */
    public static int bindBlockTags(List<Datapack.RegistryEntries> packs) {
        if (blockTagsBound()) {
            return 0;
        }
        Map<String, List<Integer>> tags = Datapack.tags(packs).getOrDefault("minecraft:block", Map.of());
        if (tags.isEmpty()) {
            return 0;
        }
        Map<Holder.Reference<Block>, Set<TagKey<Block>>> perHolder = new IdentityHashMap<>();
        for (Holder.Reference<Block> holder : BuiltInRegistries.BLOCK.listElements().toList()) {
            perHolder.put(holder, new HashSet<>());
        }
        for (Map.Entry<String, List<Integer>> tag : tags.entrySet()) {
            TagKey<Block> key = TagKey.create(Registries.BLOCK, Identifier.parse(tag.getKey()));
            for (int id : tag.getValue()) {
                BuiltInRegistries.BLOCK.get(id).ifPresent(holder -> perHolder.get(holder).add(key));
            }
        }
        try {
            Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
            bind.setAccessible(true);
            for (Map.Entry<Holder.Reference<Block>, Set<TagKey<Block>>> entry : perHolder.entrySet()) {
                bind.invoke(entry.getKey(), entry.getValue());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot bind served block tags", e);
        }
        return tags.size();
    }
}
