package dev.kestrel.persist;

import net.minecraft.world.level.block.state.BlockState;

/**
 * G2 step-2 section census for the import path
 * ({@code preresearch/g2-memory-design.md} 1.4 item 4): what the corpus
 * region files hold, counted while {@link WorldStore#importWorld} reads
 * them, so the memory decision can be read off the air-section share.
 *
 * <p>Counts are per section TAG as found on disc. A tag is in range when
 * its world section Y lies inside the level's build range; vanilla also
 * writes light-only tags one outside it (design 0.2), counted separately
 * and never decoded. Palette buckets follow {@link SectionCodec#bitsFor}:
 * 1, 2-16, 17-32, 33-64, 65-128, 129-256, more than 256.
 *
 * <p>"Air" matches vanilla's {@code LevelChunkSection.hasOnlyAir}
 * (non-empty count uses {@code BlockState.isAir}), so cave/void air count
 * as air cells. {@code airOnly} is stricter: palette exactly
 * {@code [minecraft:air]}, the tag shape vanilla writes for an empty
 * section.
 *
 * <p>The offline counterpart is {@code harness/tools/g2-region.py census}:
 * the same fields from the same files, parsed independently. The two
 * lines must agree exactly.
 *
 * <p>Thread-safety: none; one import on one thread.
 */
public final class ImportCensus {

    /** Bucket labels, index = {@link #bucket}. */
    public static final String[] BUCKETS = {"1", "2-16", "17-32", "33-64", "65-128", "129-256", ">256"};

    public long regions;
    public long chunks;
    /** Chunks present on disc but outside the import filter. */
    public long chunksSkipped;
    /** Imported chunks whose Status is not minecraft:full. */
    public long chunksNotFull;
    public long tags;
    public long tagsLightOnly;
    /** In-range tags without block_states (nothing to decode). */
    public long tagsNoStates;
    public long sectionsDecoded;
    public long airOnly;
    public long singleNonAir;
    public final long[] paletteBuckets = new long[BUCKETS.length];
    public long nonAirCells;
    /** Store sections after restore (filled by the importer). */
    public long sectionsMaterialized;

    /** Bucket index for a palette size, mirroring SectionCodec.bitsFor thresholds. */
    static int bucket(int paletteSize) {
        int bits = SectionCodec.bitsFor(paletteSize);
        return switch (bits) {
            case 0 -> 0;
            case 4 -> 1;
            case 5 -> 2;
            case 6 -> 3;
            case 7 -> 4;
            case 8 -> 5;
            default -> 6;
        };
    }

    /** Record one in-range section skipped as air-only before decode (step 2b). */
    void sectionAirOnly(int paletteSize) {
        sectionsDecoded++;
        paletteBuckets[bucket(paletteSize)]++;
        airOnly++;
    }

    /** Record one decoded in-range section. */
    void section(int paletteSize, BlockState[] cells) {
        sectionsDecoded++;
        paletteBuckets[bucket(paletteSize)]++;
        if (paletteSize == 1) {
            if (cells[0].getBlock() == net.minecraft.world.level.block.Blocks.AIR) {
                airOnly++;
            } else {
                singleNonAir++;
            }
        }
        for (BlockState st : cells) {
            if (!st.isAir()) {
                nonAirCells++;
            }
        }
    }

    /** Record one compact section without inflating a dense cell array. */
    void compactSection(SectionCodec.CompactSection section) {
        int paletteSize = section.palette().length;
        sectionsDecoded++;
        paletteBuckets[bucket(paletteSize)]++;
        if (paletteSize == 1) {
            if (section.palette()[0].getBlock() == net.minecraft.world.level.block.Blocks.AIR) {
                airOnly++;
            } else {
                singleNonAir++;
            }
        }
        for (int i = 0; i < SectionCodec.CELLS; i++) {
            if (!SectionCodec.compactCell(section, i).isAir()) {
                nonAirCells++;
            }
        }
    }

    /** One grep-able line, stable field order (the offline tool prints the same). */
    public String line() {
        StringBuilder sb = new StringBuilder("census");
        sb.append(" regions=").append(regions)
                .append(" chunks=").append(chunks)
                .append(" chunksSkipped=").append(chunksSkipped)
                .append(" chunksNotFull=").append(chunksNotFull)
                .append(" tags=").append(tags)
                .append(" tagsLightOnly=").append(tagsLightOnly)
                .append(" tagsNoStates=").append(tagsNoStates)
                .append(" sectionsDecoded=").append(sectionsDecoded)
                .append(" airOnly=").append(airOnly)
                .append(" singleNonAir=").append(singleNonAir)
                .append(" palette=");
        for (int i = 0; i < BUCKETS.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(BUCKETS[i]).append(':').append(paletteBuckets[i]);
        }
        sb.append(" nonAirCells=").append(nonAirCells);
        return sb.toString();
    }

    /** Import-side extras the offline tool cannot know (store state). */
    public String storeLine() {
        double perChunk = chunks == 0 ? 0 : (double) sectionsMaterialized / chunks;
        double airShare = sectionsDecoded == 0 ? 0 : (double) airOnly / sectionsDecoded;
        return String.format(java.util.Locale.ROOT,
                "census-store sectionsMaterialized=%d perChunk=%.2f airOnlyShare=%.4f",
                sectionsMaterialized, perChunk, airShare);
    }
}
