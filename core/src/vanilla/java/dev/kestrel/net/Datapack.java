package dev.kestrel.net;

import dev.kestrel.persist.Nbt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 3c vanilla datapack registry data: serves the real 26.2
 * datapack entries during configuration so the vanilla client can
 * load its registries.
 *
 * <p>Background, grounded in the 26.2 decompile plus the first
 * real-client join: Kestrel sent EMPTY entry lists for every
 * synchronized registry, assuming the client falls back to its
 * vanilla defaults. It does not: the config client loads registries
 * purely from the network stream and validates
 * ({@code RegistryDataLoader.java:135-164},
 * {@code SYNCHRONIZED_REGISTRIES}), so empty lists fail with
 * "Registry must be non-empty" for the 12 validated registries
 * (wolf/cat/cow/pig/chicken/frog/painting/zombie-nautilus variants
 * plus their sound variants). The client report named them exactly
 * (disconnect-2026-09-12_17.57.38-client.txt).
 *
 * <p>Vanilla's own rule ({@code SynchronizeRegistriesTask.java:54-63}):
 * entries the client already has from a negotiated known pack may be
 * sent WITHOUT contents (present=false, client loads from its local
 * pack); anything else must carry the full NBT value. Kestrel
 * requests the vanilla core pack
 * ({@code BuiltInPackSource.java:27}) and the 26.2 client accepts,
 * so vanilla-equivalent entries COULD skip contents. But skipping is
 * only valid when the bytes are byte-identical to the client's pack,
 * and Kestrel cannot prove that from here. So Kestrel sends every
 * entry WITH contents, converted once from the vanilla datapack JSON
 * ({@code data/<ns>/<registry>/<path>.json}) to network NBT.
 *
 * <p>Source of truth: the datapack inside the vanilla server jar
 * ({@code data/...}, 7310 JSON files). KestrelServer loads it at
 * startup from a directory (extract once next to the world or pass
 * the path); entries are pre-encoded to NBT bytes at load so the
 * per-join send is a memcpy.
 *
 * <p>JSON-to-NBT conversion: datapack JSON values map 1:1 except
 * that JSON has no int/float distinction and no byte type. Rule:
 * numbers with a fraction or exponent become TAG_Float/Double by
 * magnitude, whole numbers become TAG_Int unless outside int range
 * (then TAG_Long). This matches SNBT-ish datapack intent for the
 * registries served here: vanilla's own codecs decode ints from
 * any whole number and floats from any number, so the distinction
 * only matters for exactness, not acceptance.
 *
 * <p>Thread-safety: loaded once at startup on the main thread,
 * read-only afterwards (I/O threads only read).
 */
public final class Datapack {

    /**
     * One registry's pre-encoded entries plus its tag dir (null when the
     * datapack has no {@code tags/<regpath>} dir, e.g. worldgen/biome
     * tags live under {@code tags/worldgen/biome}). {@code staticOrder}
     * is non-null only for static (code-built, never synchronized)
     * registries: the vanilla network id order (protocol_id order from
     * the --reports Registry Dump), used solely to resolve tag member
     * ids for the tags wire packet. Null for synced registries (order
     * comes from served entries).
     */
    public record RegistryEntries(String key, List<Map.Entry<String, byte[]>> entries,
            Path tagDir, List<String> staticOrder) {
        public RegistryEntries(String key, List<Map.Entry<String, byte[]>> entries) {
            this(key, entries, null, null);
        }

        public RegistryEntries(String key, List<Map.Entry<String, byte[]>> entries,
                Path tagDir) {
            this(key, entries, tagDir, null);
        }
    }

    private Datapack() {
    }

    /**
     * Load every registry in {@code keys} from {@code dataDir} (a
     * directory holding {@code minecraft/<registry>/<path>.json}).
     * Returns entries in key order. Registries with zero JSON files
     * (biome, game_event, mob_effect, sound_event live in code, not
     * the datapack) come back empty, exactly like vanilla's own pack
     * step: vanilla ALSO sends zero entries for those
     * ({@code RegistrySynchronization.java:44-67} iterates the live
     * registry, which for server-built registries like sound_event
     * holds real entries, but the datapack contributes none).
     */
    /**
     * Static (code-built, never synchronized) registries whose TAGS
     * vanilla still sends on the wire
     * ({@code RegistrySynchronization.networkSafeRegistries} =
     * networked + STATIC layer). The tags packet references member ids
     * in vanilla network (protocol_id) order, which for these comes
     * from the --reports Registry Dump, not from any datapack. Ids are
     * passed in via {@code staticOrders} ({@code registry key ->
     * protocol_id-ordered entry ids}); KestrelServer builds it once
     * from the live BuiltInRegistries. Entries stay empty: values are
     * never synchronized for these.
     */
    /**
     * Static tag registries that actually appear in vanilla's tags
     * packet (live capture): block/item/fluid/entity/potion/poi plus
     * game_event. mob_effect and sound_effect have no tags on the wire
     * and stay out (an empty section would be dropped by vanilla's own
     * serializer anyway).
     */
    public static final List<String> STATIC_TAG_REGISTRIES = List.of(
            "minecraft:block",
            "minecraft:item",
            "minecraft:fluid",
            "minecraft:entity_type",
            "minecraft:potion",
            "minecraft:point_of_interest_type",
            "minecraft:game_event");

    /**
     * Tag dir for a registry key: {@code tags/<regpath>} for normal
     * registries, but {@code tags/<lastname>} for static ones whose key
     * has no tags subdir of its own (block/item/fluid/... live flat
     * under {@code tags/item/}, not {@code tags/minecraft/item/}).
     * Tries the full regpath first, then the last segment.
     */
    static Path tagDirFor(Path dataDir, String key) {
        String[] parts = key.split(":", 2);
        // Full regpath first (tags/worldgen/biome for
        // minecraft:worldgen/biome; tags/item for minecraft:item).
        Path full = dataDir.resolve(parts[0]).resolve("tags").resolve(parts[1]);
        if (Files.isDirectory(full)) {
            return full;
        }
        // Last segment fallback (never currently hit, kept for safety).
        String regPath = parts[1];
        int slash = regPath.lastIndexOf('/');
        if (slash >= 0) {
            Path sub = dataDir.resolve(parts[0]).resolve("tags")
                    .resolve(regPath.substring(slash + 1));
            if (Files.isDirectory(sub)) {
                return sub;
            }
        }
        return null;
    }

    public static List<RegistryEntries> load(Path dataDir, List<String> keys) throws IOException {
        return load(dataDir, keys, Map.of());
    }

    public static List<RegistryEntries> load(Path dataDir, List<String> keys,
            Map<String, List<String>> staticOrders) throws IOException {
        List<RegistryEntries> out = new ArrayList<>(keys.size() + STATIC_TAG_REGISTRIES.size());
        for (String key : keys) {
            out.add(new RegistryEntries(key, loadRegistry(dataDir, key),
                    tagDirFor(dataDir, key)));
        }
        // Static tag-only registries: no entries, but tag dirs plus
        // the vanilla network order so tags resolve to the same ids
        // the client's local registries use.
        for (String key : STATIC_TAG_REGISTRIES) {
            Path tagDir = tagDirFor(dataDir, key);
            List<String> order = staticOrders.get(key);
            if (tagDir == null || order == null || order.isEmpty()) {
                continue;
            }
            out.add(new RegistryEntries(key, List.of(), tagDir, List.copyOf(order)));
        }
        inlineKnownTags(dataDir, out);
        return out;
    }

    /**
     * Rewrite tag-referencing entry fields to inline id lists the
     * config client can resolve WITHOUT the referenced registry's
     * tags. Background: entries like sulfur_cube_archetype carry
     * {@code "items": "#minecraft:sulfur_cube_archetype/bouncy"}
     * (a tag in the ITEM registry, never synchronized), and the
     * client's bare context fails them with "Missing tag". The
     * rewrite expands the tag file to a JSON list of member ids when
     * every member resolves to a plain (non-#) id; entries whose
     * tags need recursive expansion are left untouched (client
     * behavior unchanged for those).
     */
    static void inlineKnownTags(Path dataDir, List<RegistryEntries> packs) throws IOException {
        for (int i = 0; i < packs.size(); i++) {
            RegistryEntries re = packs.get(i);
            // Only the three ALWAYS_FULL registries need this (they
            // are the only ones served WITH contents on the
            // negotiated path); ids-only entries carry no values.
            if (!re.key().equals("minecraft:sulfur_cube_archetype")
                    && !re.key().equals("minecraft:dimension_type")
                    && !re.key().equals("minecraft:enchantment")) {
                continue;
            }
            List<Map.Entry<String, byte[]>> rewritten = new ArrayList<>(re.entries().size());
            boolean changed = false;
            for (Map.Entry<String, byte[]> e : re.entries()) {
                Nbt.Tag inlined = inlineEntryTags(dataDir, e.getValue());
                if (inlined != null) {
                    rewritten.add(Map.entry(e.getKey(), Nbt.encodeAnyTag(inlined)));
                    changed = true;
                } else {
                    rewritten.add(e);
                }
            }
            if (changed) {
                packs.set(i, new RegistryEntries(re.key(), List.copyOf(rewritten), re.tagDir()));
            }
        }
    }

    /**
     * Parse one pre-encoded entry, expand any string field that is a
     * same-datapack tag reference into a string list, and return the
     * new tag (null when nothing changed). Only touches TAG_String
     * values starting with '#'; everything else passes through
     * untouched.
     */
    static Nbt.Tag inlineEntryTags(Path dataDir, byte[] encoded) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(encoded));
        int type = in.readUnsignedByte();
        if (type != Nbt.TAG_COMPOUND) {
            return null;
        }
        Nbt.Tag parsed = readAnyCompoundBody(in);
        if (!(parsed instanceof Nbt.Tag.TCompound root)) {
            return null;
        }
        if (!rewriteCompound(dataDir, root, new java.util.HashSet<>())) {
            return null;
        }
        return root;
    }

    /** Read a compound body (no type byte, no name) from the stream. */
    private static Nbt.Tag readAnyCompoundBody(java.io.DataInput in) throws IOException {
        Map<String, Nbt.Tag> entries = new LinkedHashMap<>();
        while (true) {
            int id = in.readUnsignedByte();
            if (id == Nbt.TAG_END) {
                return new Nbt.Tag.TCompound(entries);
            }
            int nlen = in.readUnsignedShort();
            byte[] nb = new byte[nlen];
            in.readFully(nb);
            String name = new String(nb, StandardCharsets.UTF_8);
            entries.put(name, readAnyPayload(id, in));
        }
    }

    private static Nbt.Tag readAnyPayload(int id, java.io.DataInput in) throws IOException {
        return switch (id) {
            case Nbt.TAG_BYTE -> new Nbt.Tag.TByte(in.readByte());
            case Nbt.TAG_SHORT -> new Nbt.Tag.TShort(in.readShort());
            case Nbt.TAG_INT -> new Nbt.Tag.TInt(in.readInt());
            case Nbt.TAG_LONG -> new Nbt.Tag.TLong(in.readLong());
            case Nbt.TAG_FLOAT -> new Nbt.Tag.TFloat(in.readFloat());
            case Nbt.TAG_DOUBLE -> new Nbt.Tag.TDouble(in.readDouble());
            case Nbt.TAG_STRING -> {
                int n = in.readUnsignedShort();
                byte[] b = new byte[n];
                in.readFully(b);
                yield new Nbt.Tag.TString(new String(b, StandardCharsets.UTF_8));
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                int n = in.readInt();
                byte[] v = new byte[n];
                in.readFully(v);
                yield new Nbt.Tag.TByteArray(v);
            }
            case Nbt.TAG_INT_ARRAY -> {
                int n = in.readInt();
                int[] v = new int[n];
                for (int i = 0; i < n; i++) {
                    v[i] = in.readInt();
                }
                yield new Nbt.Tag.TIntArray(v);
            }
            case Nbt.TAG_LONG_ARRAY -> {
                int n = in.readInt();
                long[] v = new long[n];
                for (int i = 0; i < n; i++) {
                    v[i] = in.readLong();
                }
                yield new Nbt.Tag.TLongArray(v);
            }
            case Nbt.TAG_LIST -> {
                int elem = in.readUnsignedByte();
                int n = in.readInt();
                List<Nbt.Tag> items = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    items.add(readAnyPayload(elem, in));
                }
                yield new Nbt.Tag.TList(elem, items);
            }
            case Nbt.TAG_COMPOUND -> readAnyCompoundBody(in);
            default -> throw new IOException("bad tag id " + id);
        };
    }

    /**
     * Walk a compound: every string value starting with '#' that
     * resolves (via the datapack tag files) to a flat list of plain
     * ids becomes a TAG_List of strings. Returns true when anything
     * changed. Nested compounds and lists recurse.
     */
    private static boolean rewriteCompound(Path dataDir, Nbt.Tag.TCompound root,
            java.util.Set<String> seen) {
        boolean changed = false;
        for (Map.Entry<String, Nbt.Tag> e : new ArrayList<>(root.entries().entrySet())) {
            Nbt.Tag v = e.getValue();
            if (v instanceof Nbt.Tag.TString s && s.value().startsWith("#")) {
                List<String> flat = flatTagIds(dataDir, s.value().substring(1), seen);
                if (flat != null) {
                    List<Nbt.Tag> items = new ArrayList<>(flat.size());
                    for (String id : flat) {
                        items.add(new Nbt.Tag.TString(id));
                    }
                    root.entries().put(e.getKey(),
                            new Nbt.Tag.TList(Nbt.TAG_STRING, items));
                    changed = true;
                }
            } else if (v instanceof Nbt.Tag.TCompound c) {
                changed |= rewriteCompound(dataDir, c, seen);
            } else if (v instanceof Nbt.Tag.TList l) {
                for (Nbt.Tag item : l.items()) {
                    if (item instanceof Nbt.Tag.TCompound c) {
                        changed |= rewriteCompound(dataDir, c, seen);
                    }
                }
            }
        }
        return changed;
    }

    /**
     * Expand a tag id (no '#') to a flat list of plain member ids
     * using the datapack tag files. Returns null when any member is
     * itself a tag reference (leave those entries untouched).
     */
    private static List<String> flatTagIds(Path dataDir, String tagId,
            java.util.Set<String> seen) {
        if (!seen.add(tagId)) {
            return null;
        }
        try {
            String[] parts = tagId.split(":", 2);
            if (parts.length != 2) {
                return null;
            }
            // Find the tag file: tags live under
            // data/<ns>/tags/<registry>/<path>.json where <registry>
            // is the tag's OWN registry (item, block, ...), not the
            // entry's registry. Scan all tags dirs for a path match.
            Path tagsRoot = dataDir.resolve(parts[0]).resolve("tags");
            if (!Files.isDirectory(tagsRoot)) {
                return null;
            }
            List<Path> hits = new ArrayList<>();
            try (var stream = Files.walk(tagsRoot)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(hits::add);
            }
            Path match = null;
            for (Path h : hits) {
                String rel = tagsRoot.relativize(h).toString().replace('\\', '/');
                // rel is <registry>/<path>.json; the tag id is
                // <ns>:<path> regardless of which registry dir holds
                // it (HolderSetCodec resolves in the ENTRY's
                // registry context... see below: match by suffix).
                String idPath = rel.substring(rel.indexOf('/') + 1,
                        rel.length() - ".json".length());
                if (idPath.equals(parts[1])) {
                    match = h;
                    break;
                }
            }
            if (match == null) {
                return null;
            }
            Nbt.Tag parsed = JsonNbt.parse(Files.readString(match, StandardCharsets.UTF_8));
            if (!(parsed instanceof Nbt.Tag.TCompound root
                    && root.entries().get("values") instanceof Nbt.Tag.TList list)) {
                return null;
            }
            List<String> out = new ArrayList<>();
            for (Nbt.Tag t : list.items()) {
                if (!(t instanceof Nbt.Tag.TString s)) {
                    return null;
                }
                if (s.value().startsWith("#")) {
                    List<String> sub = flatTagIds(dataDir, s.value().substring(1), seen);
                    if (sub == null) {
                        return null;
                    }
                    out.addAll(sub);
                } else {
                    out.add(s.value());
                }
            }
            return out;
        } catch (IOException ex) {
            return null;
        } finally {
            seen.remove(tagId);
        }
    }

    /**
     * Tags for the loaded registries: {@code registry key -> tag id ->
     * sorted raw-zero-based ids}. Built from the datapack tag JSON
     * ({@code data/<ns>/tags/<regpath>/<tag>.json}, {@code values[]}
     * of entry ids, {@code #}-prefixed values reference other tags in
     * the same registry and are expanded). Unknown ids are dropped
     * (datapack may name entries from packs Kestrel does not serve).
     * Registries with no tags map to an empty map; the sender skips
     * empty payloads like vanilla
     * ({@code TagNetworkSerialization.java:24-27}).
     *
     * <p>Memoized per {@code packs} instance: the datapack is read-only
     * after boot, and recomputing walks and parses every tag file
     * (~75-115ms on the Pi, per join; see
     * preresearch/join-latency-path-analysis.md fix 3). The returned maps
     * are shared across callers and unmodifiable.
     */
    public static Map<String, Map<String, List<Integer>>> tags(
            List<RegistryEntries> packs) {
        synchronized (TAGS_LOCK) {
            if (tagsFor != packs || tagsCache == null) {
                tagsCache = computeTags(packs);
                tagsFor = packs;
            }
            return tagsCache;
        }
    }

    private static final Object TAGS_LOCK = new Object();
    /** Identity of the packs list {@link #tagsCache} was computed from. */
    private static List<RegistryEntries> tagsFor;
    private static Map<String, Map<String, List<Integer>>> tagsCache;

    private static Map<String, Map<String, List<Integer>>> computeTags(
            List<RegistryEntries> packs) {
        Map<String, Map<String, List<Integer>>> out = new LinkedHashMap<>();
        if (packs == null) {
            return out;
        }
        for (RegistryEntries re : packs) {
            // Static registries resolve member ids through the vanilla
            // network order (protocol_id order), NOT served entries
            // (they serve none). Synced registries use served order.
            Map<String, Integer> order = new LinkedHashMap<>();
            if (re.staticOrder() != null) {
                for (int i = 0; i < re.staticOrder().size(); i++) {
                    order.put(re.staticOrder().get(i), i);
                }
            } else {
                for (int i = 0; i < re.entries().size(); i++) {
                    order.put(re.entries().get(i).getKey(), i);
                }
            }
            Map<String, List<Integer>> regTags = new LinkedHashMap<>();
            // Static registries' tag files live directly under the
            // tags dir (tags/item/*.json): there is no tags/<name>
            // subdir, so tagDirFor returns null. Fall back to the flat
            // tags/<lastname> dir (e.g. tags/item for minecraft:item).
            Path tagDir = re.tagDir;
            if (tagDir != null && Files.isDirectory(tagDir)) {
                List<Path> files = new ArrayList<>();
                try (var stream = Files.walk(tagDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(files::add);
                } catch (IOException e) {
                    continue;
                }
                for (Path f : files) {
                    String rel = tagDir.relativize(f).toString().replace('\\', '/');
                    // Nested tag files (pattern_item/<name>.json) belong
                    // to a namespaced tag id: the PARENT dir is the tag
                    // name, the file is one value. Flat files are the
                    // tag itself. Disambiguate by depth: files directly
                    // under tagDir are tags; deeper files are grouped.
                    String tagId;
                    String[] segs = rel.split("/");
                    String ns = re.key().split(":", 2)[0];
                    if (segs.length == 1) {
                        tagId = ns + ":" + rel.substring(0, rel.length() - ".json".length());
                    } else {
                        continue; // grouped below
                    }
                    try {
                        String json = Files.readString(f, StandardCharsets.UTF_8);
                        Nbt.Tag parsed = JsonNbt.parse(json);
                        if (!(parsed instanceof Nbt.Tag.TCompound root)) {
                            continue;
                        }
                        Nbt.Tag raw = root.entries().get("values");
                        if (!(raw instanceof Nbt.Tag.TList list)) {
                            continue;
                        }
                        java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
                        expandTagValues(list, tagId, re, order, packs, ids, new java.util.HashSet<>());
                        // Empty tags MUST still be sent (as empty id
                        // lists): vanilla binds every tag file it knows
                        // from the pack set, and a missing tag section
                        // freezes as "Unbound tags" (seen live: dialog
                        // pause_screen_additions/quick_actions are
                        // empty in the datapack but required).
                        regTags.put(tagId, List.copyOf(ids));
                    } catch (IOException ignored) {
                        // One bad tag file skips; the registry still loads.
                    }
                }
            }
            // Namespaced tags: files in SUBDIRS are tags whose id
            // includes the subdir path (banner pattern_item/creeper ->
            // tag "minecraft:pattern_item/creeper", per
            // BannerPatternTags.java:9-18). Each file's values expand
            // normally (plain ids plus #-refs).
            if (tagDir == null) {
                out.put(re.key(), java.util.Collections.unmodifiableMap(regTags));
                continue;
            }
            try (var stream = Files.walk(tagDir)) {
                for (Path f : (Iterable<Path>) stream
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()::iterator) {
                    String rel = tagDir.relativize(f).toString().replace('\\', '/');
                    if (!rel.contains("/")) {
                        continue; // flat files handled above
                    }
                    String ns = re.key().split(":", 2)[0];
                    String tagId = ns + ":" + rel.substring(0, rel.length() - ".json".length());
                    try {
                        Nbt.Tag pv = JsonNbt.parse(Files.readString(f, StandardCharsets.UTF_8));
                        if (pv instanceof Nbt.Tag.TCompound pr
                                && pr.entries().get("values") instanceof Nbt.Tag.TList pl) {
                            java.util.LinkedHashSet<Integer> ids = new java.util.LinkedHashSet<>();
                            expandTagValues(pl, tagId, re, order, packs, ids,
                                    new java.util.HashSet<>());
                            regTags.put(tagId, List.copyOf(ids));
                        }
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException ignored) {
            }
            out.put(re.key(), java.util.Collections.unmodifiableMap(regTags));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * Expand a tag values list: plain ids plus #-references to
     * same-registry tags. Order is file order, NOT sorted: vanilla
     * serializes tag member lists in datapack file order (proven by
     * live capture: is_fall is [fall, ender_pearl, stalagmite] =
     * [10, 8, 39], while sorted would be [8, 10, 39]). The client
     * binds these positionally-sensitive lists as-is.
     */
    private static void expandTagValues(Nbt.Tag.TList list, String selfId,
            RegistryEntries re, Map<String, Integer> order,
            List<RegistryEntries> packs, java.util.LinkedHashSet<Integer> ids,
            java.util.Set<String> seen) {
        for (Nbt.Tag t : list.items()) {
            if (!(t instanceof Nbt.Tag.TString s)) {
                continue;
            }
            String v = s.value();
            if (v.startsWith("#")) {
                String ref = v.substring(1);
                if (!seen.add(ref)) {
                    continue;
                }
                String[] refParts = ref.split(":", 2);
                // Cross-registry reference (e.g. item tag referencing a
                // block tag): resolve against THAT registry's order, not
                // ours. Same-registry refs keep using our order.
                // NOTE: a same-namespace '#' (e.g. "#minecraft:logs"
                // inside an item tag) may ALSO be cross-registry (block
                // tag file included by name): only keep our order when
                // the tag file actually exists in OUR tag dir.
                Map<String, Integer> refOrder = order;
                if (refParts.length == 2) {
                    boolean inOurs = re.tagDir != null && Files.isRegularFile(
                            re.tagDir.resolve(refParts[1] + ".json"));
                    if (!inOurs) {
                        Map<String, Integer> other = orderOf(packs, refParts[0],
                                refParts[1]);
                        if (other != null) {
                            refOrder = other;
                        }
                    }
                }
                List<String> refValues = readTagFile(re, ref, packs);
                if (refValues != null) {
                    for (String rv : refValues) {
                        if (rv.startsWith("#")) {
                            expandTagValues(new Nbt.Tag.TList(Nbt.TAG_STRING,
                                    List.of((Nbt.Tag) new Nbt.Tag.TString(rv))),
                                    selfId, re, refOrder, packs, ids, seen);
                        } else {
                            Integer id = refOrder.get(rv);
                            if (id != null) {
                                ids.add(id);
                            }
                        }
                    }
                }
                seen.remove(ref);
            } else {
                Integer id = order.get(v);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
    }

    /**
     * Order map ({@code entry id -> network id}) for the registry that
     * owns a tag path: {@code namespace} selects the registry by tag
     * dir match, falling back to the serving registry. Cross-registry
     * example: an item tag referencing {@code minecraft:stone} resolves
     * through the block order, not the item order.
     */
    private static Map<String, Integer> orderOf(List<RegistryEntries> packs,
            String namespace, String tagPath) {
        // tagPath like "stone" or "enchantable/fire_aspect": the owning
        // registry is the one whose tagDir holds this file. Search order:
        // prefer a registry whose KEY namespace matches, but only if it
        // actually holds the file; otherwise first holder wins (covers
        // "#minecraft:logs" inside item tags -> block tags/logs.json).
        Map<String, Integer> fallback = null;
        for (RegistryEntries cand : packs) {
            if (cand.tagDir == null) {
                continue;
            }
            if (Files.isRegularFile(cand.tagDir.resolve(tagPath + ".json"))) {
                String candNs = cand.key().split(":", 2)[0];
                if (candNs.equals(namespace) || fallback == null) {
                    Map<String, Integer> order = new LinkedHashMap<>();
                    if (cand.staticOrder() != null) {
                        for (int i = 0; i < cand.staticOrder().size(); i++) {
                            order.put(cand.staticOrder().get(i), i);
                        }
                    } else {
                        for (int i = 0; i < cand.entries().size(); i++) {
                            order.put(cand.entries().get(i).getKey(), i);
                        }
                    }
                    if (candNs.equals(namespace)) {
                        return order;
                    }
                    fallback = order;
                }
            }
        }
        return fallback;
    }

    /** Read another tag file's values as raw strings (for #-expansion). */
    private static List<String> readTagFile(RegistryEntries re, String tagId) {
        return readTagFile(re, tagId, null);
    }

    private static List<String> readTagFile(RegistryEntries re, String tagId,
            List<RegistryEntries> packs) {
        String[] parts = tagId.split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        // Owning registry's tag dir first (cross-registry refs live in
        // THEIR registry's tags dir, e.g. block tags referenced from an
        // item tag file).
        Path f = null;
        if (packs != null) {
            for (RegistryEntries cand : packs) {
                if (cand.tagDir == null) {
                    continue;
                }
                Path c = cand.tagDir.resolve(parts[1] + ".json");
                if (Files.isRegularFile(c)) {
                    f = c;
                    break;
                }
            }
        } else if (re.tagDir != null) {
            f = re.tagDir.resolve(parts[1] + ".json");
        }
        if (f == null || !Files.isRegularFile(f)) {
            return null;
        }
        try {
            Nbt.Tag parsed = JsonNbt.parse(Files.readString(f, StandardCharsets.UTF_8));
            if (parsed instanceof Nbt.Tag.TCompound root
                    && root.entries().get("values") instanceof Nbt.Tag.TList list) {
                List<String> out = new ArrayList<>();
                for (Nbt.Tag t : list.items()) {
                    if (t instanceof Nbt.Tag.TString s) {
                        out.add(s.value());
                    }
                }
                return out;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Load one registry: every {@code <path>.json} under its dir. */
    static List<Map.Entry<String, byte[]>> loadRegistry(Path dataDir, String key) throws IOException {
        // Key "minecraft:foo/bar" maps to dir "minecraft/foo/bar";
        // entry "minecraft:path/to/x" is the file "path/to/x.json".
        String[] parts = key.split(":", 2);
        String ns = parts[0];
        String regPath = parts[1];
        Path dir = dataDir.resolve(ns).resolve(regPath);
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return entries;
        }
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
        }
        for (Path f : files) {
            String rel = dir.relativize(f).toString().replace('\\', '/');
            String id = ns + ":" + rel.substring(0, rel.length() - ".json".length());
            String json = Files.readString(f, StandardCharsets.UTF_8);
            Nbt.Tag tag = JsonNbt.parse(json);
            if (!(tag instanceof Nbt.Tag.TCompound compound)) {
                throw new IOException("datapack entry is not an object: " + f);
            }
            entries.add(Map.entry(id, Nbt.encodeAnyTag(compound)));
        }
        return entries;
    }

    // ---- minimal JSON parser (datapack subset) ----

    /**
     * Minimal JSON reader for datapack files: objects, arrays,
     * strings (with escapes incl. unicode escapes and surrogates), numbers,
     * true/false/null (null becomes an absent marker the caller
     * drops). Duplicate keys: last wins (vanilla Gson behavior).
     */
    public static final class JsonNbt {
        private final String s;
        private int pos;

        private JsonNbt(String s) {
            this.s = s;
        }

        public static Nbt.Tag parse(String json) throws IOException {
            JsonNbt p = new JsonNbt(json);
            Nbt.Tag v = p.value();
            p.ws();
            if (p.pos != p.s.length()) {
                throw new IOException("trailing bytes after JSON value");
            }
            return v;
        }

        /** Sentinel for JSON null (caller drops null object fields). */
        static final Nbt.Tag JSON_NULL = new Nbt.Tag.TByte((byte) 0);

        private Nbt.Tag value() throws IOException {
            ws();
            if (pos >= s.length()) {
                throw new IOException("truncated JSON");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> new Nbt.Tag.TString(string());
                case 't' -> {
                    expect("true");
                    yield new Nbt.Tag.TByte((byte) 1);
                }
                case 'f' -> {
                    expect("false");
                    yield new Nbt.Tag.TByte((byte) 0);
                }
                case 'n' -> {
                    expect("null");
                    yield JSON_NULL;
                }
                default -> number();
            };
        }

        private Nbt.Tag.TCompound object() throws IOException {
            pos++; // {
            Map<String, Nbt.Tag> map = new LinkedHashMap<>();
            ws();
            if (peek('}')) {
                pos++;
                return new Nbt.Tag.TCompound(map);
            }
            while (true) {
                ws();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new IOException("expected string key at " + pos);
                }
                String key = string();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IOException("expected : at " + pos);
                }
                pos++;
                Nbt.Tag v = value();
                if (v != JSON_NULL) {
                    map.put(key, v);
                }
                ws();
                if (pos >= s.length()) {
                    throw new IOException("truncated JSON object");
                }
                char d = s.charAt(pos++);
                if (d == '}') {
                    return new Nbt.Tag.TCompound(map);
                }
                if (d != ',') {
                    throw new IOException("expected , or } at " + (pos - 1));
                }
            }
        }

        private Nbt.Tag.TList array() throws IOException {
            pos++; // [
            List<Nbt.Tag> items = new ArrayList<>();
            ws();
            if (peek(']')) {
                pos++;
                return new Nbt.Tag.TList(Nbt.TAG_END, items);
            }
            int elem = -1;
            boolean mixedInt = false;
            while (true) {
                Nbt.Tag v = value();
                if (v == JSON_NULL) {
                    throw new IOException("null in JSON array");
                }
                int id = v.id();
                if (elem < 0) {
                    elem = id;
                } else if (elem != id) {
                    // JSON has one number type: mixed int/float/long (or
                    // true/false bytes among ints) widen to the broadest
                    // seen so far. Anything else stays an error.
                    int wide = widen(elem, id);
                    if (wide < 0) {
                        throw new IOException("heterogeneous JSON array at " + pos);
                    }
                    elem = wide;
                    mixedInt = true;
                }
                items.add(v);
                ws();
                if (pos >= s.length()) {
                    throw new IOException("truncated JSON array");
                }
                char d = s.charAt(pos++);
                if (d == ']') {
                    if (mixedInt) {
                        items = widenAll(items, elem);
                    }
                    return new Nbt.Tag.TList(elem, items);
                }
                if (d != ',') {
                    throw new IOException("expected , or ] at " + (pos - 1));
                }
            }
        }

        /** Widening order for JSON numbers: byte < int < long < float < double. */
        private static int rank(int id) {
            return switch (id) {
                case Nbt.TAG_BYTE -> 0;
                case Nbt.TAG_INT -> 1;
                case Nbt.TAG_LONG -> 2;
                case Nbt.TAG_FLOAT -> 3;
                case Nbt.TAG_DOUBLE -> 4;
                default -> -1;
            };
        }

        private static int widen(int a, int b) {
            int ra = rank(a);
            int rb = rank(b);
            if (ra < 0 || rb < 0) {
                return -1;
            }
            return ra >= rb ? a : b;
        }

        private static List<Nbt.Tag> widenAll(List<Nbt.Tag> items, int target) {
            List<Nbt.Tag> out = new ArrayList<>(items.size());
            for (Nbt.Tag t : items) {
                out.add(widenOne(t, target));
            }
            return out;
        }

        private static Nbt.Tag widenOne(Nbt.Tag t, int target) {
            if (t.id() == target) {
                return t;
            }
            long whole = switch (t) {
                case Nbt.Tag.TByte b -> b.value();
                case Nbt.Tag.TInt i -> i.value();
                case Nbt.Tag.TLong l -> l.value();
                default -> 0;
            };
            double frac = (t instanceof Nbt.Tag.TFloat f) ? f.value()
                    : (t instanceof Nbt.Tag.TDouble d) ? d.value() : whole;
            return switch (target) {
                case Nbt.TAG_INT -> new Nbt.Tag.TInt((int) whole);
                case Nbt.TAG_LONG -> new Nbt.Tag.TLong(whole);
                case Nbt.TAG_FLOAT -> new Nbt.Tag.TFloat((float) frac);
                case Nbt.TAG_DOUBLE -> new Nbt.Tag.TDouble(frac);
                default -> t;
            };
        }

        private String string() throws IOException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new IOException("truncated JSON string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IOException("truncated JSON escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IOException("truncated unicode escape");
                            }
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            if (Character.isHighSurrogate((char) cp) && pos + 6 <= s.length()
                                    && s.charAt(pos) == '\\' && s.charAt(pos + 1) == 'u') {
                                int lo = Integer.parseInt(s.substring(pos + 2, pos + 6), 16);
                                if (Character.isLowSurrogate((char) lo)) {
                                    pos += 6;
                                    sb.appendCodePoint(Character.toCodePoint((char) cp, (char) lo));
                                    continue;
                                }
                            }
                            sb.append((char) cp);
                        }
                        default -> throw new IOException("bad JSON escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Nbt.Tag number() throws IOException {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            boolean frac = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                frac = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                frac = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String raw = s.substring(start, pos);
            if (raw.isEmpty() || raw.equals("-")) {
                throw new IOException("bad JSON number at " + start);
            }
            try {
                if (frac) {
                    double d = Double.parseDouble(raw);
                    if (!Double.isFinite(d)) {
                        throw new IOException("non-finite JSON number " + raw);
                    }
                    float f = (float) d;
                    if ((double) f == d) {
                        return new Nbt.Tag.TFloat(f);
                    }
                    return new Nbt.Tag.TDouble(d);
                }
                try {
                    return new Nbt.Tag.TInt(Integer.parseInt(raw));
                } catch (NumberFormatException e) {
                    return new Nbt.Tag.TLong(Long.parseLong(raw));
                }
            } catch (NumberFormatException e) {
                throw new IOException("bad JSON number " + raw, e);
            }
        }

        private void expect(String word) throws IOException {
            if (!s.startsWith(word, pos)) {
                throw new IOException("expected " + word + " at " + pos);
            }
            pos += word.length();
        }

        private boolean peek(char c) {
            return pos < s.length() && s.charAt(pos) == c;
        }

        private void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }
    }
}
