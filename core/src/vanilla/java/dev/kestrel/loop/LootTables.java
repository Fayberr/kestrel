package dev.kestrel.loop;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.world.level.storage.loot.LootDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The reloadable loot registries (predicates, item modifiers, loot tables)
 * as {@code ReloadableServerRegistries.reload} builds them
 * ({@code ReloadableServerRegistries.java:40-71}): each
 * {@link LootDataType}'s directory decoded with its codec through the
 * world's registry ops, registered with vanilla's registration info. Tag
 * loading and the validation pass are left out: vanilla ships no tags for
 * these registries, and its validation only logs.
 */
public final class LootTables {
    /** {@code ReloadableServerRegistries.DEFAULT_REGISTRATION_INFO}. */
    private static final RegistrationInfo REGISTRATION = new RegistrationInfo(Optional.empty(), Lifecycle.experimental());

    private LootTables() {
    }

    /** What {@code MinecraftServer.reloadableRegistries()} holds after a load. */
    public static ReloadableServerRegistries.Holder load(RegistryAccess access, ResourceManager resources) {
        RegistryOps<JsonElement> ops = access.createSerializationContext(JsonOps.INSTANCE);
        List<HolderLookup.RegistryLookup<?>> loaded = new ArrayList<>();
        LootDataType.values().forEach(type -> loaded.add(load(type, ops, resources)));
        Stream<HolderLookup.RegistryLookup<?>> context = access.registries()
                .map(entry -> (HolderLookup.RegistryLookup<?>) entry.value());
        return new ReloadableServerRegistries.Holder(
                HolderLookup.Provider.create(Stream.concat(context, loaded.stream())));
    }

    private static <T extends net.minecraft.world.level.storage.loot.Validatable> WritableRegistry<T> load(LootDataType<T> type, RegistryOps<JsonElement> ops,
            ResourceManager resources) {
        WritableRegistry<T> registry = new MappedRegistry<>(type.registryKey(), Lifecycle.experimental());
        Map<Identifier, T> elements = new HashMap<>();
        SimpleJsonResourceReloadListener.scanDirectory(resources, type.registryKey(), ops, type.codec(), elements);
        elements.forEach((id, element) -> registry.register(ResourceKey.create(type.registryKey(), id), element,
                REGISTRATION));
        registry.freeze();
        return registry;
    }
}
