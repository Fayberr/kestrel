package dev.kestrel.entity;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongPredicate;

/**
 * The entities of one level (S14 slice 3): the part of vanilla's
 * {@code ServerLevel} that adds entities, keeps the tick list and runs the
 * entity phase of the tick, over slice 1's {@link EntitySectionManager}
 * (storage) and slice 2's {@link EntityTracker} (clients).
 *
 * <p>{@link #addFreshEntity} and {@link #spawnItem} are the spawn entry
 * point the real sources will call (block drops in G19, dropping a held
 * item in G17). {@link #tickEntities} is the body of the entity phase;
 * {@code TickLoop} decides when it runs.
 *
 * <p>Explicit approximations, because Kestrel has no chunk ticket or
 * {@code Visibility} lifecycle yet (slice 5) and never unloads a chunk:
 * every chunk an entity is in is treated as {@code ENTITY_TICKING}, so an
 * added entity is at once tracked and ticking, as vanilla's is inside
 * simulation distance; and {@code inEntityTickingRange} is a predicate the
 * owner supplies, true everywhere on the live server. There is no tick
 * rate manager, so no entity is ever frozen.
 *
 * <p>Thread-safety: tick thread only.
 */
public final class EntityWorld {

    private final EntityLevel level;
    private final @Nullable EntityTracker tracker;
    private final EntitySectionManager<KestrelEntity> sections = new EntitySectionManager<>(KestrelEntity.class);
    /** {@code EntityTickList.active}: the same fastutil map, so the tick order is insertion order. */
    private final Int2ObjectLinkedOpenHashMap<KestrelEntity> tickList = new Int2ObjectLinkedOpenHashMap<>();
    private LongPredicate inEntityTickingRange = chunk -> true;
    /**
     * Where a new entity's own random comes from: vanilla's field initialiser
     * {@code RandomSource.create()} ({@code Entity.java:253}). The gate hands
     * in the state vanilla's twin started from, so the UUID, the item's yaw,
     * {@code bobOffs} and drop motion can be compared raw.
     */
    private java.util.function.Supplier<RandomSource> entityRandoms = RandomSource::create;

    /**
     * @param tracker the clients' tracker, or null for a world nobody
     *     watches (the gate's physics runs)
     */
    public EntityWorld(EntityLevel level, @Nullable EntityTracker tracker) {
        this.level = level;
        this.tracker = tracker;
    }

    public EntityLevel level() {
        return level;
    }

    /** {@code DistanceManager.inEntityTickingRange}, keyed by packed chunk position. */
    public void setInEntityTickingRange(LongPredicate inEntityTickingRange) {
        this.inEntityTickingRange = inEntityTickingRange;
    }

    /** Replace the source of new entities' randoms (the gate only). */
    void setEntityRandoms(java.util.function.Supplier<RandomSource> entityRandoms) {
        this.entityRandoms = entityRandoms;
    }

    // ---- the spawn entry point ----

    /**
     * {@code ServerLevel.addFreshEntity} ({@code addEntity}): a removed
     * entity or a duplicate UUID is refused; otherwise the entity is
     * stored, starts ticking and starts being tracked, in the order
     * {@code PersistentEntitySectionManager.addEntity} runs the callbacks.
     */
    public boolean addFreshEntity(KestrelEntity entity) {
        if (entity.isRemoved()) {
            return false;
        }
        markChunkTicking(entity);
        if (!sections.addNewEntity(entity)) {
            return false;
        }
        level.entityAdded(entity);
        entity.setRemovalHook(this::onRemoved);
        if (entity instanceof KestrelFallingBlockEntity falling) falling.attach(this);
        if (entity instanceof KestrelItemEntity item) item.attach(this);
        tickList.put(entity.getId(), entity);
        if (tracker != null) {
            tracker.addEntity(entity);
        }
        return true;
    }

    /**
     * {@code new ItemEntity(level, x, y, z, stack)} ({@code ItemEntity.java:63-68})
     * then {@code addFreshEntity}: the random initial motion vanilla gives a
     * drop, from the entity's own random. Returns null when the add was
     * refused (an empty stack is still added, and discards itself on its
     * first tick, as in vanilla).
     */
    public @Nullable KestrelItemEntity spawnItem(double x, double y, double z, ItemStack stack) {
        KestrelItemEntity item = createItem(x, y, z, stack);
        RandomSource random = item.getRandom();
        item.setDeltaMovement(new Vec3(random.nextDouble() * 0.2 - 0.1, 0.2, random.nextDouble() * 0.2 - 0.1));
        return addFreshEntity(item) ? item : null;
    }

    /** {@code new ItemEntity(level, x, y, z, stack, dx, dy, dz)} ({@code :70-84}) then {@code addFreshEntity}. */
    public @Nullable KestrelItemEntity spawnItem(double x, double y, double z, ItemStack stack,
            double deltaX, double deltaY, double deltaZ) {
        KestrelItemEntity item = createItem(x, y, z, stack);
        item.setDeltaMovement(new Vec3(deltaX, deltaY, deltaZ));
        return addFreshEntity(item) ? item : null;
    }

    private final java.util.Set<net.minecraft.world.entity.EntityType<?>> unbuiltSpawns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Bridge for entities constructed by vanilla {@code Block.dropResources}. */
    public boolean addFreshVanillaEntity(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof ItemEntity item) {
            Vec3 motion = item.getDeltaMovement();
            KestrelItemEntity spawned = spawnItem(item.getX(), item.getY(), item.getZ(), item.getItem(),
                    motion.x, motion.y, motion.z);
            if (spawned != null) {
                spawned.setPickUpDelay(vanillaPickupDelay(item));
                spawned.setThrower(vanillaThrower(item));
                spawned.setTarget(vanillaTarget(item));
            }
            return spawned != null;
        }
        // Not built yet, so not spawned: XP orbs (movement, merging and pickup
        // are G26) and mobs such as an infested block's silverfish (G44). The
        // break itself completes; a fault here would cost the player's session.
        if (unbuiltSpawns.add(entity.getType())) {
            System.out.println("[kestrel] not spawned (not built yet): "
                    + net.minecraft.world.entity.EntityType.getKey(entity.getType()));
        }
        return false;
    }

    private static int vanillaPickupDelay(ItemEntity item) {
        try {
            java.lang.reflect.Field field = ItemEntity.class.getDeclaredField("pickupDelay");
            field.setAccessible(true);
            return field.getInt(item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read vanilla item pickup delay", e);
        }
    }

    private static @Nullable UUID vanillaThrower(ItemEntity item) {
        try {
            java.lang.reflect.Field field = ItemEntity.class.getDeclaredField("thrower");
            field.setAccessible(true);
            Object ref = field.get(item);
            if (ref instanceof net.minecraft.world.entity.EntityReference<?> entityRef) {
                return entityRef.getUUID();
            }
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read vanilla item thrower", e);
        }
    }

    private static @Nullable UUID vanillaTarget(ItemEntity item) {
        try {
            java.lang.reflect.Field field = ItemEntity.class.getDeclaredField("target");
            field.setAccessible(true);
            return (UUID) field.get(item);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read vanilla item target", e);
        }
    }

    /**
     * The construction half, shared by both spawns: a fresh random, the
     * UUID drawn from it ({@code Entity.java:253}, {@code :287}), the id
     * from the shared minter, then position and stack.
     */
    private KestrelItemEntity createItem(double x, double y, double z, ItemStack stack) {
        RandomSource random = entityRandoms.get();
        UUID uuid = Mth.createInsecureUUID(random);
        KestrelItemEntity item = new KestrelItemEntity(EntityIds.claim(), uuid, random);
        item.setPos(x, y, z);
        item.setItem(stack);
        return item;
    }

    /**
     * {@code FallingBlockEntity.fall} ({@code FallingBlockEntity.java:93-104}):
     * build the entity, write the block's fluid (or air) through the live
     * {@code setBlock}, then add the entity.
     */
    public @Nullable KestrelFallingBlockEntity spawnFalling(BlockPos pos, BlockState state) {
        KestrelFallingBlockEntity entity = KestrelFallingBlockEntity.create(pos, state, entityRandoms.get());
        level.setBlock(pos, state.getFluidState().createLegacyBlock(), 3);
        return addFreshEntity(entity) ? entity : null;
    }

    /**
     * The body of {@code FallingBlock.tick} once the block is free below
     * ({@code FallingBlock.java:49-53}): {@code FallingBlockEntity.fall},
     * then {@code this.falling(entity)}.
     */
    public void fallingBlockTick(BlockPos pos, BlockState state) {
        KestrelFallingBlockEntity entity = spawnFalling(pos, state);
        if (entity != null) {
            entity.falling(level, state.getBlock());
        }
    }

    @Nullable KestrelItemEntity spawnFallingDrop(KestrelFallingBlockEntity source, net.minecraft.world.level.block.Block block) {
        KestrelItemEntity item = spawnItem(source.position().x, source.position().y, source.position().z,
                new ItemStack(block));
        if (item != null) item.setDefaultPickUpDelay();
        return item;
    }

    // ---- removal ----

    /** {@code Entity.remove(reason)} from outside the entity's own tick. */
    public void removeEntity(KestrelEntity entity, net.minecraft.world.entity.Entity.RemovalReason reason) {
        entity.setRemoved(reason);
    }

    /**
     * The rest of {@code PersistentEntitySectionManager.Callback.onRemove}
     * after the storage forgot the entity: stop ticking, stop tracking (the
     * tracker tells every watcher at once, as {@code ChunkMap.removeEntity}
     * does), and release the id.
     */
    private void onRemoved(KestrelEntity entity) {
        tickList.remove(entity.getId());
        if (tracker != null) {
            tracker.removeEntity(entity);
        }
        EntityIds.release(entity.getId());
    }

    // ---- the entity phase ----

    /**
     * The entity block of {@code ServerLevel.tick}, {@code ServerLevel.java:430-446},
     * for each entity in the tick list as it stood when the pass began
     * ({@code EntityTickList.forEach} iterates a map that later adds and
     * removals do not touch): skip it if removed; {@code checkDespawn};
     * then, inside entity ticking range, skip a passenger whose vehicle is
     * alive and still carries it, dismount one whose vehicle is not, and
     * tick it under the crash guard.
     */
    public void tickEntities() {
        List<KestrelEntity> pass = new ArrayList<>(tickList.values());
        for (KestrelEntity entity : pass) {
            if (entity.isRemoved()) {
                continue;
            }
            // :434 tickRateManager.isEntityFrozen: no tick rate manager, never frozen.
            entity.checkDespawn();
            if (inEntityTickingRange.test(ChunkPos.pack(entity.chunkX(), entity.chunkZ()))) {
                KestrelEntity vehicle = entity.getVehicle();
                if (vehicle != null) {
                    if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                        continue;
                    }
                    entity.stopRiding();
                }
                guardEntityTick(entity);
            }
        }
    }

    /** {@code Level.guardEntityTick} ({@code Level.java:556-565}): a throw becomes a crash report. */
    private void guardEntityTick(KestrelEntity entity) {
        try {
            tickNonPassenger(entity);
        } catch (Throwable t) {
            CrashReport report = CrashReport.forThrowable(t, "Ticking entity");
            CrashReportCategory category = report.addCategory("Entity being ticked");
            category.setDetail("Entity", entity::toString);
            throw new ReportedException(report);
        }
        if (!entity.isRemoved()) {
            markChunkTicking(entity);
        }
    }

    /** {@code ServerLevel.tickNonPassenger} ({@code ServerLevel.java:824-836}). */
    private void tickNonPassenger(KestrelEntity entity) {
        entity.setOldPosAndRot();
        entity.tickCount++;
        entity.tick(level);
        for (KestrelEntity passenger : entity.getPassengers()) {
            tickPassenger(entity, passenger);
        }
    }

    /**
     * {@code ServerLevel.tickPassenger} ({@code :838-854}). {@code rideTick}
     * is reduced to its motion reset and tick; the vehicle's
     * {@code positionRider} belongs to the riding types (slice 6).
     */
    private void tickPassenger(KestrelEntity vehicle, KestrelEntity entity) {
        if (entity.isRemoved() || entity.getVehicle() != vehicle) {
            entity.stopRiding();
        } else if (tickList.containsKey(entity.getId())) {
            entity.setOldPosAndRot();
            entity.tickCount++;
            entity.setDeltaMovement(Vec3.ZERO);
            entity.tick(level);
            for (KestrelEntity passenger : entity.getPassengers()) {
                tickPassenger(entity, passenger);
            }
        }
    }

    /** See the class comment: a chunk an entity is in counts as entity ticking. */
    private void markChunkTicking(KestrelEntity entity) {
        long chunk = ChunkPos.pack(entity.chunkX(), entity.chunkZ());
        if (sections.chunkStatus(chunk) != EntityVisibility.TICKING) {
            sections.updateChunkStatus(chunk, FullChunkStatus.ENTITY_TICKING);
        }
    }

    // ---- views ----

    /** Live entity by id, or null. */
    public @Nullable KestrelEntity getEntity(int id) {
        return sections.getEntity(id);
    }

    public int tickingCount() {
        return tickList.size();
    }

    public boolean isTicking(int id) {
        return tickList.containsKey(id);
    }

    public EntitySectionManager<KestrelEntity> sections() {
        return sections;
    }

    public @Nullable EntityTracker tracker() {
        return tracker;
    }

    List<KestrelEntity> entitiesSnapshot() { return new ArrayList<>(tickList.values()); }
}
