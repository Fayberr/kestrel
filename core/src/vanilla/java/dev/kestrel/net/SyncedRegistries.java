package dev.kestrel.net;

import java.util.List;

/**
 * Stage 3 synchronized-registry keys: EXACTLY vanilla's
 * {@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES} in vanilla order,
 * all 29 of them.
 *
 * <p>Order matters for the tag step (tags reference ids by position
 * within each registry's entry list), and completeness matters:
 * registries missing from the stream but present in the load set
 * freeze with unbound values/tags (seen live: dialog
 * pause_screen_additions/quick_actions, world_clock
 * overworld/the_end). {@code test_instance} has no datapack dir
 * (code-built) and loads empty, like vanilla's own stream.
 *
 * <p>EXCESS matters just as much, and is far harder to notice. Until
 * 2026-09-15 this list also carried {@code minecraft:game_event},
 * {@code minecraft:mob_effect} and {@code minecraft:sound_event},
 * which vanilla does NOT synchronize. The client owns those three as
 * code-built static registries, so Kestrel was sending three
 * RegistryData packets with ZERO entries for registries the client
 * already has, which reads on the wire as "these registries are
 * empty". A stock client drops them without a word, because
 * {@code RegistryDataCollector.loadRegistries} only ever looks up the
 * keys in SYNCHRONIZED_REGISTRIES, so the bug was invisible in normal
 * play and only showed up in a byte-for-byte diff of the whole
 * configuration phase against the real 26.2 server jar.
 *
 * <p>{@code NetCheck.checkSyncedRegistryKeys} now pins this list to
 * the jar's own list, so it cannot drift again in either direction.
 * Do not add a key here without a matching entry in vanilla's list.
 */
public final class SyncedRegistries {

    private SyncedRegistries() {
    }

    /** Registry keys in vanilla's sync order (order is cosmetic here). */
    public static final List<String> KEYS = List.of(
            "minecraft:worldgen/biome",
            "minecraft:chat_type",
            "minecraft:trim_pattern",
            "minecraft:trim_material",
            "minecraft:wolf_variant",
            "minecraft:wolf_sound_variant",
            "minecraft:pig_variant",
            "minecraft:pig_sound_variant",
            "minecraft:frog_variant",
            "minecraft:cat_variant",
            "minecraft:cat_sound_variant",
            "minecraft:cow_sound_variant",
            "minecraft:cow_variant",
            "minecraft:chicken_sound_variant",
            "minecraft:chicken_variant",
            "minecraft:zombie_nautilus_variant",
            "minecraft:painting_variant",
            "minecraft:sulfur_cube_archetype",
            "minecraft:dimension_type",
            "minecraft:damage_type",
            "minecraft:banner_pattern",
            "minecraft:enchantment",
            "minecraft:jukebox_song",
            "minecraft:instrument",
            "minecraft:test_environment",
            "minecraft:test_instance",
            "minecraft:dialog",
            "minecraft:world_clock",
            "minecraft:timeline");
}
