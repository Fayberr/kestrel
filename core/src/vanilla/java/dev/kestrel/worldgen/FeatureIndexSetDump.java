package dev.kestrel.worldgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Diagnostic: for one origin, print the placed-feature index set Kestrel
 * derives, and the possible-biome set it derives them from.
 *
 * <p>Vanilla's own set for an origin is exactly the rows its trace records in
 * feature-places.tsv, so this tool turns a block-level divergence into a
 * one-line set comparison, with the biome attribution that explains any
 * difference. It is a diagnostic, never a gate: it runs the step 7 feature
 * pass on a BIOMES-only grid, so placements themselves are meaningless here.
 *
 * <p>Args: seed originX originZ [step,step,...]
 */
public final class FeatureIndexSetDump {

    private FeatureIndexSetDump() {
    }

    private static String name(Holder<?> holder) {
        // This build's ResourceKey exposes no location(); its toString carries
        // the "minecraft:<path>" text, which is all this dump needs.
        return String.valueOf(holder.unwrapKey());
    }

    public static void main(String[] args) throws Exception {
        long seed = Long.parseLong(args[0]);
        int cx = Integer.parseInt(args[1]);
        int cz = Integer.parseInt(args[2]);
        List<Integer> wanted = new ArrayList<>();
        if (args.length > 3) {
            for (String part : args[3].split(",")) {
                wanted.add(Integer.parseInt(part.trim()));
            }
        }

        HeadlessNoise.Driver driver = HeadlessNoise.load(seed);
        Map<ChunkPos, ChunkAccess> quad =
                HeadlessNoise.prepareBiomeGrid(driver, cx, cz, (x, z) -> null, new HashMap<>());
        Map<Long, ChunkAccess> grid = new HashMap<>();
        List<ChunkPos> ordered = new ArrayList<>(quad.keySet());
        ordered.sort(Comparator.comparingInt(ChunkPos::x).thenComparingInt(ChunkPos::z));
        for (ChunkPos p : ordered) {
            grid.put(p.pack(), quad.get(p));
        }

        Map<Holder<Biome>, String> names = new LinkedHashMap<>();
        for (ChunkPos p : ordered) {
            for (LevelChunkSection section : quad.get(p).getSections()) {
                section.getBiomes().getAll(biome -> names.putIfAbsent(biome, name(biome)));
            }
        }
        List<Holder<Biome>> possible = new ArrayList<>(names.keySet());
        possible.retainAll(driver.generator().getBiomeSource().possibleBiomes());
        System.out.println("ORIGIN " + cx + "," + cz + " collected=" + names.size()
                + " afterRetain=" + possible.size());

        for (Holder<Biome> biome : possible) {
            StringBuilder out = new StringBuilder();
            var perStep = driver.generator().getBiomeGenerationSettings(biome).features();
            for (int step = 0; step < perStep.size(); step++) {
                if (!wanted.isEmpty() && !wanted.contains(step)) {
                    continue;
                }
                for (Holder<PlacedFeature> placed : perStep.get(step)) {
                    out.append(' ').append(step).append(':').append(name(placed));
                }
            }
            System.out.println("BIOME " + names.get(biome) + " ->" + out);
        }

        List<String> derived = new ArrayList<>();
        List<String> biomeReads = new ArrayList<>();
        HeadlessNoise.RegionView view =
                new HeadlessNoise.RegionView(driver, new ChunkPos(cx, cz), grid);
        view.beforeFeatureDebug = derived::add;
        view.biomeQueryDebug = (pos, biome) -> biomeReads.add("BIOMEREAD " + pos.getX()
                + "," + pos.getY() + "," + pos.getZ() + " -> " + name(biome));
        HeadlessNoise.features(driver, grid.get(ChunkPos.pack(cx, cz)), view);
        System.out.println("DERIVED count=" + derived.size());
        for (String line : derived) {
            System.out.println("DERIVED " + line);
        }
        System.out.println("BIOMEREAD count=" + biomeReads.size());
        for (String line : biomeReads) {
            System.out.println(line);
        }
    }
}
