package dev.kestrel.harness;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-tick block-entity census for step 5's item 2: how many ticking block
 * entities exist, of which types, and what fraction satisfy their idle
 * predicate.
 *
 * <p><b>Why this exists alongside {@link WorldStateHasher}.</b> The tick
 * hash proves two worlds agree; it cannot say whether the sleeping step is
 * worth building. That question needs the population and the idle
 * fraction, per type, on a realistic corpus. This tracer walks the same
 * live-chunk set the hasher walks (same {@code getChunkNow} +
 * {@code updatingChunkMap} path - a chunk invisible to game logic is
 * invisible to the census too) and, for every loaded level, reads
 * {@code Level.blockEntityTickers} directly: that list IS the walked
 * sequence, so its size is the per-tick preamble cost and each entry's
 * type + idle state is the sleepable share.
 *
 * <p><b>What it logs</b> (block-entity-census.log, next to ticks.log,
 * exit phase only, one line per tick):
 * <pre>
 * # kestrel-harness block-entity census, schema_version=1
 * # tick_count,tickers,type=total/idle,...
 * 12,41,sign=9/9,furnace=2/1,...
 * </pre>
 * Cells after {@code tickers} are variable: one {@code name=total/idle}
 * cell per block-entity type actually present that tick, in sorted order
 * (see census-summarize.ps1). There is no fixed column layout and no
 * {@code other} bucket: unknown types are logged by name and counted
 * present-but-never-idle. The first header line declares the schema; the
 * second describes the cell shape. The idle predicates are the exact ones
 * from {@code preresearch/block-entity-tick-analysis.md} item 3, quoted in
 * each method's javadoc with the decompile line anchor.
 *
 * <p><b>Cost.</b> One extra walk over the ticker list per tick (a few
 * dozen entries on corpus worlds) plus one reflective field read per
 * entry for the idle check. No full-chunk rescan: the census runs after
 * the hasher in the same exit phase and reuses nothing from it (separate
 * Handles, no coupling), but its own walk is O(tickers), not O(blocks).
 *
 * <p>Deliberately reflection-only, same bootstrap-classloader constraint
 * as {@link WorldStateHasher} - ships in the boot jar alongside it.
 */
public final class BlockEntityCensus {

    private static final int SCHEMA_VERSION = 1;

    private static volatile java.io.BufferedWriter writer;
    private static volatile Handles handles;
    private static volatile boolean headerWritten = false;

    private BlockEntityCensus() {
    }

    public static synchronized void init(String censusPath) {
        try {
            writer = new java.io.BufferedWriter(new java.io.FileWriter(censusPath, /* append= */ false));
            writer.write("# kestrel-harness block-entity census, schema_version=" + SCHEMA_VERSION);
            writer.newLine();
            writer.write("# tick_count,tickers,total_N,idle_N per sleep-candidate type (see class javadoc)");
            writer.newLine();
            writer.flush();
            headerWritten = true;
        } catch (java.io.IOException e) {
            throw new RuntimeException("kestrel-harness: could not open block-entity census at " + censusPath, e);
        }
    }

    static synchronized void setOutputPath(String path) {
        if (headerWritten) {
            return;
        }
        init(path);
    }

    /** Called from TickLogger.onTick for the exit phase only. Never throws. */
    public static void traceExit(Object server, int tickCount) {
        java.io.BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        String line;
        try {
            line = buildLine(server, tickCount);
        } catch (Throwable t) {
            System.err.println("[kestrel-harness] block-entity census failed at tick " + tickCount + ": " + t);
            return;
        }
        synchronized (BlockEntityCensus.class) {
            try {
                w.write(line);
                w.newLine();
                // Flush per line like TickLogger: runs end via
                // Stop-Process -Force, so buffered rows would be lost.
                w.flush();
            } catch (java.io.IOException e) {
                System.err.println("[kestrel-harness] block-entity census write failed: " + e);
            }
        }
    }

    // ---- line building ----

    private static String buildLine(Object server, int tickCount) throws ReflectiveOperationException {
        Handles h = handles(server);
        // type-name -> [total, idle], sorted for deterministic columns.
        Map<String, long[]> counts = new TreeMap<>();
        int tickers = 0;
        for (Object level : (Iterable<?>) h.getAllLevels.invoke(server)) {
            List<?> list = (List<?>) h.blockEntityTickersField.get(level);
            // Defensive copy: the walk below does reflective reads per
            // entry, and a concurrent structural change mid-tick would
            // otherwise throw. The copy is O(tickers), same as the walk.
            List<?> snapshot = new ArrayList<>(list);
            tickers += snapshot.size();
            for (Object ticker : snapshot) {
                Object be;
                try {
                    be = unwrapBlockEntity(ticker, h.wrapperTickerField, h.boundBlockEntityField);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
                Object type = h.blockEntityGetType.invoke(be);
                Object key = h.registryGetKey.invoke(h.blockEntityTypeRegistry, type);
                String name = shortName(key.toString());
                long[] c = counts.computeIfAbsent(name, k -> new long[2]);
                c[0]++;
                if (isIdle(h, name, be)) {
                    c[1]++;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(tickCount).append(',').append(tickers);
        for (Map.Entry<String, long[]> e : counts.entrySet()) {
            sb.append(',').append(e.getKey()).append('=').append(e.getValue()[0])
              .append('/').append(e.getValue()[1]);
        }
        return sb.toString();
    }

    private static String shortName(String key) {
        int slash = key.indexOf('/');
        String path = slash >= 0 ? key.substring(slash + 1) : key;
        int colon = path.indexOf(':');
        return colon >= 0 ? path.substring(colon + 1) : path;
    }

    // ---- idle predicates (one per sleep-candidate type) ----

    /**
     * Dispatches to the per-type predicate. Unknown types return false:
     * no predicate claimed, never counted idle.
     */
    private static boolean isIdle(Handles h, String typeName, Object be) {
        try {
            switch (typeName) {
                case "furnace":
                case "blast_furnace":
                case "smoker":
                    return isFurnaceIdle(h, be);
                case "brewing_stand":
                    return isBrewingStandIdle(h, be);
                case "beehive":
                    return isBeehiveIdle(h, be);
                case "campfire":
                    return isCampfireIdle(h, be);
                case "crafter":
                    return isCrafterIdle(h, be);
                case "sculk_catalyst":
                    return isSculkCatalystIdle(h, be);
                case "sculk_sensor":
                case "sculk_shrieker":
                    return isVibrationIdle(h, be);
                case "sign":
                    return isSignIdle(h, be);
                case "shulker_box":
                    return isShulkerIdle(h, be);
                case "hopper":
                    return isHopperIdle(h, be);
                default:
                    return false;
            }
        } catch (Throwable t) {
            // One unreadable entry must not lose the whole tick's census.
            // Counted present-but-not-idle, which is the conservative side.
            return false;
        }
    }

    /**
     * {@code litTimeRemaining == 0 && cookingTimer == 0 &&
     * (items[0].isEmpty() || items[1].isEmpty())}
     * (AbstractFurnaceBlockEntity.serverTick, :139).
     */
    private static boolean isFurnaceIdle(Handles h, Object be) throws ReflectiveOperationException {
        int lit = (Integer) h.furnaceLitField.get(be);
        int timer = (Integer) h.furnaceTimerField.get(be);
        if (lit != 0 || timer != 0) {
            return false;
        }
        List<?> items = (List<?>) h.furnaceItemsField.get(be);
        return ((Boolean) h.stackIsEmpty.invoke(items.get(0)))
            || ((Boolean) h.stackIsEmpty.invoke(items.get(1)));
    }

    /**
     * {@code brewTime == 0 && !(fuel <= 0 && fuel-stack is brewing fuel) &&
     * !isBrewable && potion bits unchanged}
     * (BrewingStandBlockEntity.serverTick, :97). The potion-bits clause
     * needs an allocation + recipe check, so the census uses the cheap
     * sufficient prefix: a stand that fails the prefix is brewing; one
     * that passes it MIGHT still brew next tick (bits changed), which
     * only errs toward counting idle, never toward missing work. The
     * line therefore over-reports brewing-stand idleness by the
     * bits-changed share; recorded here so the reader discounts it.
     */
    private static boolean isBrewingStandIdle(Handles h, Object be) throws ReflectiveOperationException {
        int brewTime = (Integer) h.brewBrewTimeField.get(be);
        if (brewTime != 0) {
            return false;
        }
        int fuel = (Integer) h.brewFuelField.get(be);
        List<?> items = (List<?>) h.brewItemsField.get(be);
        Object fuelStack = items.get(4);
        if (fuel <= 0 && isBrewingFuel(h, fuelStack)) {
            return false;
        }
        return !(Boolean) h.brewIsBrewable.invoke(null, potionBrewing(h, be), items);
    }

    /** {@code stored.isEmpty()} (BeehiveBlockEntity.serverTick, :284). */
    private static boolean isBeehiveIdle(Handles h, Object be) throws ReflectiveOperationException {
        List<?> stored = (List<?>) h.beeStoredField.get(be);
        return stored.isEmpty();
    }

    /**
     * LIT: all four item slots empty (cookTick loop never sets changed).
     * Unlit: every cookingProgress slot == 0 (cooldownTick never sets
     * changed). LIT read from the block entity's cached block state.
     */
    private static boolean isCampfireIdle(Handles h, Object be) throws ReflectiveOperationException {
        Object state = h.beGetBlockState.invoke(be);
        Object litProp = h.campfireLitProp;
        boolean lit = (Boolean) h.blockStateGetValue.invoke(state, litProp);
        if (lit) {
            List<?> items = (List<?>) h.campfireItemsField.get(be);
            for (Object stack : items) {
                if (!((Boolean) h.stackIsEmpty.invoke(stack))) {
                    return false;
                }
            }
            return true;
        }
        int[] progress = (int[]) h.campfireProgressField.get(be);
        for (int p : progress) {
            if (p != 0) {
                return false;
            }
        }
        return true;
    }

    /** {@code craftingTicksRemaining <= 0} (CrafterBlockEntity.serverTick, :237). */
    private static boolean isCrafterIdle(Handles h, Object be) throws ReflectiveOperationException {
        return ((Integer) h.crafterTicksField.get(be)) <= 0;
    }

    /**
     * {@code sculkSpreader.cursors.isEmpty()} (SculkSpreader.updateCursors,
     * :141 opens with the emptiness check). Path: BE -> catalystListener
     * -> sculkSpreader -> cursors.
     */
    private static boolean isSculkCatalystIdle(Handles h, Object be) throws ReflectiveOperationException {
        Object listener = h.catalystListenerField.get(be);
        Object spreader = h.catalystSpreaderField.get(listener);
        List<?> cursors = (List<?>) h.spreaderCursorsField.get(spreader);
        return cursors.isEmpty();
    }

    /**
     * {@code data.currentVibration == null} (VibrationSystem.Ticker.tick).
     * The selection-strategy clause (no queued candidate) is folded in via
     * {@code chosenCandidate(gameTime).isEmpty()}: a candidate whose tick
     * is not yet due still wakes the tick, so only "no candidate at all"
     * counts as idle.
     */
    private static boolean isVibrationIdle(Handles h, Object be) throws ReflectiveOperationException {
        Object data = h.vibrationDataFor(be).invoke(be);
        Object current = h.vibrationCurrentField.get(data);
        if (current != null) {
            return false;
        }
        Object selector = h.vibrationSelectorGetter.invoke(data);
        Object level = h.beGetLevel.invoke(be);
        long gameTime = (Long) h.levelGetGameTime.invoke(level);
        Object chosen = h.selectorChosen.invoke(selector, gameTime);
        return ((java.util.Optional<?>) chosen).isEmpty();
    }

    /** {@code playerWhoMayEdit == null} (SignBlockEntity.tick, :265). */
    private static boolean isSignIdle(Handles h, Object be) throws ReflectiveOperationException {
        return h.signEditorField.get(be) == null;
    }

    /**
     * {@code animationStatus == CLOSED && progress == progressOld == 0}
     * (ShulkerBoxBlockEntity.updateAnimation CLOSED branch). Enum compared
     * by name so no class reference is needed.
     */
    private static boolean isShulkerIdle(Handles h, Object be) throws ReflectiveOperationException {
        Object status = h.shulkerStatusField.get(be);
        if (!"CLOSED".equals(((Enum<?>) status).name())) {
            return false;
        }
        float progress = (Float) h.shulkerProgressField.get(be);
        float progressOld = (Float) h.shulkerProgressOldField.get(be);
        return progress == 0.0F && progressOld == 0.0F;
    }

    /**
     * Hopper idleness is NOT decided here: step 2's HopperState owns the
     * gating, and its sleep-eligibility is a cross-tick memo, not a
     * single-field read. Counted present, never idle, so the census never
     * over-claims the step-5 share for hoppers.
     */
    private static boolean isHopperIdle(Handles h, Object be) {
        return false;
    }

    // ---- reflective handles ----

    private static final class Handles {
        final Method getAllLevels;
        final Field blockEntityTickersField;
        final Field wrapperTickerField;
        final Field boundBlockEntityField;
        final Method blockEntityGetType;
        final Method registryGetKey;
        final Object blockEntityTypeRegistry;
        final Method stackIsEmpty;
        // furnace
        final Field furnaceLitField;
        final Field furnaceTimerField;
        final Field furnaceItemsField;
        // brewing stand
        final Field brewBrewTimeField;
        final Field brewFuelField;
        final Field brewItemsField;
        final Method brewIsBrewable;
        final Method potionBrewingGetter;
        final Object brewingFuelTag;
        final Method stackIsTag;
        // beehive
        final Field beeStoredField;
        // campfire
        final Method beGetBlockState;
        final Method blockStateGetValue;
        final Object campfireLitProp;
        final Field campfireItemsField;
        final Field campfireProgressField;
        // crafter
        final Field crafterTicksField;
        // sculk catalyst
        final Field catalystListenerField;
        final Field catalystSpreaderField;
        final Field spreaderCursorsField;
        // vibration (sensor + shrieker)
        final Field vibrationCurrentField;
        final Method vibrationSelectorGetter;
        final Method beGetLevel;
        final Method levelGetGameTime;
        final Method selectorChosen;
        // sign
        final Field signEditorField;
        // shulker
        final Field shulkerStatusField;
        final Field shulkerProgressField;
        final Field shulkerProgressOldField;

        Handles(Object server) throws ReflectiveOperationException {
            ClassLoader cl = server.getClass().getClassLoader();
            Class<?> serverClass = server.getClass();
            Class<?> levelClass = cls(cl, "net.minecraft.world.level.Level");
            Class<?> beClass = cls(cl, "net.minecraft.world.level.block.entity.BlockEntity");
            Class<?> tickerClass = cls(cl, "net.minecraft.world.level.block.entity.TickingBlockEntity");
            Class<?> registryClass = cls(cl, "net.minecraft.core.Registry");
            Class<?> builtInRegistriesClass = cls(cl, "net.minecraft.core.registries.BuiltInRegistries");
            Class<?> stackClass = cls(cl, "net.minecraft.world.item.ItemStack");

            getAllLevels = method(serverClass, "getAllLevels");
            blockEntityTickersField = declaredField(levelClass, "blockEntityTickers");
            // Entries are RebindableTickingBlockEntityWrapper (private inner
            // class of LevelChunk): unwrap via its TickingBlockEntity field
            // to the BoundTickingBlockEntity, then that one's BlockEntity
            // field. Both found by type, not name (see findUnwrapFields).
            Field[] unwrap = findUnwrapFields(cl);
            wrapperTickerField = unwrap[0];
            boundBlockEntityField = unwrap[1];
            blockEntityGetType = method(beClass, "getType");
            registryGetKey = method(registryClass, "getKey", Object.class);
            blockEntityTypeRegistry = field(builtInRegistriesClass, "BLOCK_ENTITY_TYPE").get(null);
            stackIsEmpty = method(stackClass, "isEmpty");

            Class<?> furnaceClass = cls(cl, "net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity");
            furnaceLitField = declaredField(furnaceClass, "litTimeRemaining");
            furnaceTimerField = declaredField(furnaceClass, "cookingTimer");
            furnaceItemsField = declaredField(furnaceClass, "items");

            Class<?> brewClass = cls(cl, "net.minecraft.world.level.block.entity.BrewingStandBlockEntity");
            brewBrewTimeField = declaredField(brewClass, "brewTime");
            brewFuelField = declaredField(brewClass, "fuel");
            brewItemsField = declaredField(brewClass, "items");
            brewIsBrewable = findStatic(brewClass, "isBrewable");
            Class<?> levelClass2 = levelClass;
            potionBrewingGetter = findMethod(levelClass2, "potionBrewing");
            Class<?> itemTagsClass = cls(cl, "net.minecraft.tags.ItemTags");
            brewingFuelTag = field(itemTagsClass, "BREWING_FUEL").get(null);
            // ItemStack.is(TagKey) is declared on the TypedInstance
            // superinterface, not on ItemStack itself: resolve by walk.
            stackIsTag = findMethod(stackClass, "is", cls(cl, "net.minecraft.tags.TagKey"));

            Class<?> beeClass = cls(cl, "net.minecraft.world.level.block.entity.BeehiveBlockEntity");
            beeStoredField = declaredField(beeClass, "stored");

            beGetBlockState = findMethod(beClass, "getBlockState");
            Class<?> stateClass = cls(cl, "net.minecraft.world.level.block.state.BlockState");
            // getValue(Property) is declared on the StateHolder
            // superclass, not on BlockState itself: resolve by walk.
            blockStateGetValue = findMethod(stateClass, "getValue",
                cls(cl, "net.minecraft.world.level.block.state.properties.Property"));
            Class<?> campfireClass = cls(cl, "net.minecraft.world.level.block.entity.CampfireBlockEntity");
            Class<?> campfireBlockClass = cls(cl, "net.minecraft.world.level.block.CampfireBlock");
            campfireLitProp = field(campfireBlockClass, "LIT").get(null);
            campfireItemsField = declaredField(campfireClass, "items");
            campfireProgressField = declaredField(campfireClass, "cookingProgress");

            Class<?> crafterClass = cls(cl, "net.minecraft.world.level.block.entity.CrafterBlockEntity");
            crafterTicksField = declaredField(crafterClass, "craftingTicksRemaining");

            Class<?> catalystClass = cls(cl, "net.minecraft.world.level.block.entity.SculkCatalystBlockEntity");
            catalystListenerField = declaredField(catalystClass, "catalystListener");
            Class<?> listenerClass = cls(cl, "net.minecraft.world.level.block.entity.SculkCatalystBlockEntity$CatalystListener");
            catalystSpreaderField = declaredField(listenerClass, "sculkSpreader");
            Class<?> spreaderClass = cls(cl, "net.minecraft.world.level.block.SculkSpreader");
            spreaderCursorsField = declaredField(spreaderClass, "cursors");

            Class<?> vibDataClass = cls(cl, "net.minecraft.world.level.gameevent.vibrations.VibrationSystem$Data");
            Class<?> vibSelectorClass = cls(cl, "net.minecraft.world.level.gameevent.vibrations.VibrationSelector");
            vibrationCurrentField = declaredField(vibDataClass, "currentVibration");
            vibrationSelectorGetter = findMethod(vibDataClass, "getSelectionStrategy");
            selectorChosen = findMethod(vibSelectorClass, "chosenCandidate", long.class);
            beGetLevel = findMethod(beClass, "getLevel");
            // getGameTime() is a default method on the LevelAccessor
            // interface, not on Level itself: resolve by walk.
            levelGetGameTime = findMethod(levelClass, "getGameTime");
            // Sensor and shrieker both expose getVibrationData(); resolved
            // per entry via vibrationDataFor (two holder classes, one name).

            Class<?> signClass = cls(cl, "net.minecraft.world.level.block.entity.SignBlockEntity");
            signEditorField = declaredField(signClass, "playerWhoMayEdit");

            Class<?> shulkerClass = cls(cl, "net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity");
            shulkerStatusField = declaredField(shulkerClass, "animationStatus");
            shulkerProgressField = declaredField(shulkerClass, "progress");
            shulkerProgressOldField = declaredField(shulkerClass, "progressOld");
        }



        Method vibrationDataFor(Object be) throws ReflectiveOperationException {
            Method m = be.getClass().getMethod("getVibrationData");
            m.setAccessible(true);
            return m;
        }
    }

    // Per-entry helpers needing Handles state live here (not in Handles)
    // so the constructor stays a straight field list.
    private static Object potionBrewing(Handles h, Object be) throws ReflectiveOperationException {
        Object level = h.beGetLevel.invoke(be);
        return h.potionBrewingGetter.invoke(level);
    }

    private static boolean isBrewingFuel(Handles h, Object stack) throws ReflectiveOperationException {
        return (Boolean) h.stackIsTag.invoke(stack, h.brewingFuelTag);
    }

    /**
     * Unwraps one entry of {@code Level.blockEntityTickers} to its
     * underlying {@code BlockEntity}. Entries are
     * {@code RebindableTickingBlockEntityWrapper} (private inner class of
     * {@code LevelChunk}): it holds a private {@code TickingBlockEntity}
     * field pointing at the real {@code BoundTickingBlockEntity}, which in
     * turn holds the private {@code BlockEntity} field. Two hops, both
     * found by type enumeration rather than by name so a wrapper rename
     * fails loudly instead of silently censusing nothing.
     */
    private static Object unwrapBlockEntity(Object ticker,
            Field wrapperField, Field boundField)
            throws IllegalAccessException {
        Object bound = wrapperField.get(ticker);
        return boundField.get(bound);
    }

    private static Field[] findUnwrapFields(ClassLoader cl) throws ReflectiveOperationException {
        Class<?> tickerIface = Class.forName("net.minecraft.world.level.block.entity.TickingBlockEntity", true, cl);
        Class<?> beClass = Class.forName("net.minecraft.world.level.block.entity.BlockEntity", true, cl);
        Class<?> chunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk", true, cl);
        Field wrapperField = null;
        Field boundField = null;
        for (Class<?> inner : chunkClass.getDeclaredClasses()) {
            for (Field f : inner.getDeclaredFields()) {
                if (f.getType() == tickerIface) {
                    wrapperField = f;
                } else if (beClass.isAssignableFrom(f.getType())) {
                    boundField = f;
                }
            }
        }
        if (wrapperField == null || boundField == null) {
            throw new NoSuchFieldException(
                "ticker wrapper/bound fields not found in LevelChunk inner classes");
        }
        wrapperField.setAccessible(true);
        boundField.setAccessible(true);
        return new Field[] { wrapperField, boundField };
    }

    private static Method findStatic(Class<?> c, String name) throws NoSuchMethodException {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static volatile Handles handlesCache;

    private static Handles handles(Object server) throws ReflectiveOperationException {
        Handles h = handlesCache;
        if (h == null) {
            synchronized (BlockEntityCensus.class) {
                h = handlesCache;
                if (h == null) {
                    h = new Handles(server);
                    handlesCache = h;
                }
            }
        }
        return h;
    }

    private static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    private static Method method(Class<?> c, String name, Class<?>... params) throws NoSuchMethodException {
        Method m = c.getMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    /**
     * Like {@link #method}, but walks superclasses and interfaces instead
     * of only the named class. Needed for methods declared above the
     * concrete class: interface defaults ({@code Level.getGameTime} lives
     * on {@code LevelAccessor}), superclass members ({@code
     * BlockState.getValue} lives on {@code StateHolder}), and interface
     * members ({@code ItemStack.is(TagKey)} lives on
     * {@code TypedInstance}).
     */
    private static Method findMethod(Class<?> c, String name, Class<?>... params)
            throws NoSuchMethodException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                Method m = cur.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // fall through to the interface walk below
            }
            for (Class<?> iface : cur.getInterfaces()) {
                Method m = findMethodInInterface(iface, name, params);
                if (m != null) {
                    return m;
                }
            }
            cur = cur.getSuperclass();
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static Method findMethodInInterface(Class<?> iface, String name, Class<?>[] params) {
        for (Class<?> cur = iface; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException e) {
                // continue with superinterfaces
            }
            for (Class<?> parent : cur.getInterfaces()) {
                Method m = findMethodInInterface(parent, name, params);
                if (m != null) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getField(name);
        f.setAccessible(true);
        return f;
    }

    private static Field declaredField(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
