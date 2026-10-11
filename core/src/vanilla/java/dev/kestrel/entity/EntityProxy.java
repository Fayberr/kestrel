package dev.kestrel.entity;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * A real vanilla entity standing in for a {@link KestrelMovingEntity}
 * wherever vanilla code is handed an {@code Entity}: the collision context
 * ({@code CollisionContext.of(entity)}), {@code Block.fallOn},
 * {@code Block.stepOn} and the {@code Fallable} hooks. It has the vanilla
 * class the code tests for ({@code instanceof FallingBlockEntity},
 * {@code instanceof LivingEntity}), and it lives on the owner's
 * {@link VanillaEntityLevel}.
 *
 * <p>What vanilla reads through it comes from the owner: the id, whether it
 * is silent, the sneak flag (and so {@code isDescending},
 * {@code isSuppressingBounce} and {@code isSteppingCarefully}), the motion,
 * and fall damage. The position and fall distance are fields vanilla reads
 * directly, so {@link KestrelMovingEntity#proxy} copies them in before every
 * hand-out.
 */
interface EntityProxy {

    KestrelMovingEntity owner();

    /** Copy the owner's position and fall distance into the vanilla fields. */
    default void sync() {
        Entity self = (Entity) this;
        KestrelMovingEntity owner = owner();
        self.setPos(owner.position().x, owner.position().y, owner.position().z);
        self.fallDistance = owner.getFallDistance();
        if (owner.isRemoved() && !self.isRemoved()) {
            self.remove(Entity.RemovalReason.DISCARDED);
        }
    }

    /** The falling block's stand-in. */
    final class FallingBlock extends FallingBlockEntity implements EntityProxy {
        private final KestrelFallingBlockEntity owner;

        FallingBlock(VanillaEntityLevel level, KestrelFallingBlockEntity owner) {
            super(EntityTypes.FALLING_BLOCK, level);
            this.owner = owner;
            // The (type, level) constructor leaves it false; vanilla sets it in the spawning
            // constructor and in recreateFromPacket. Level.isUnobstructed reads it, so a
            // falling block in the cell stops a placement.
            this.blocksBuilding = true;
        }

        @Override public KestrelMovingEntity owner() { return owner; }
        @Override public int getId() { return owner.getId(); }
        @Override public boolean isSilent() { return owner.isSilent(); }
        @Override public boolean isShiftKeyDown() { return owner.isShiftKeyDown(); }
        @Override public Vec3 getDeltaMovement() { return owner.getDeltaMovement(); }
        @Override public void setDeltaMovement(Vec3 movement) { owner.setDeltaMovement(movement); }

        @Override
        public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource) {
            return owner.causeFallDamage(((VanillaEntityLevel) level()).target(), fallDistance, damageModifier);
        }

        @Override
        public void setHurtsEntities(float damagePerDistance, int damageMax) {
            owner.setHurtsEntities(damagePerDistance, damageMax);
        }
    }

    /** The item's stand-in. */
    final class Item extends ItemEntity implements EntityProxy {
        private final KestrelItemEntity owner;

        Item(VanillaEntityLevel level, KestrelItemEntity owner) {
            super(EntityTypes.ITEM, level);
            this.owner = owner;
        }

        @Override public KestrelItemEntity owner() { return owner; }
        @Override public int getId() { return owner.getId(); }
        @Override public boolean isSilent() { return owner.isSilent(); }
        @Override public boolean isShiftKeyDown() { return owner.isShiftKeyDown(); }
        @Override public Vec3 getDeltaMovement() { return owner.getDeltaMovement(); }
        @Override public void setDeltaMovement(Vec3 movement) { owner.setDeltaMovement(movement); }
        @Override public boolean isAlive() { return owner.isAlive(); }
        @Override public ItemStack getItem() { return owner.getItem(); }
        @Override public void setItem(ItemStack stack) { owner.setItem(stack); }
        @Override public void playerTouch(Player player) { owner.playerTouch(player); }

        @Override
        public @Nullable Entity getOwner() {
            UUID thrower = owner.getThrower();
            return thrower != null ? level().getPlayerByUUID(thrower) : null;
        }

        @Override
        public boolean causeFallDamage(double fallDistance, float damageModifier, DamageSource damageSource) {
            return owner.causeFallDamage(((VanillaEntityLevel) level()).target(), fallDistance, damageModifier);
        }
    }
}
