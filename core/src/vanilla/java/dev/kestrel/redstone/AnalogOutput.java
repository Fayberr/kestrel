package dev.kestrel.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.CandleCakeBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CopperGolemStatueBlock;
import net.minecraft.world.level.block.DecoratedPotBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LavaCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code BlockState.getAnalogOutputSignal}, transcribed per block.
 *
 * <p>Vanilla's version takes a concrete {@code Level}, so it cannot be called
 * with the redstone engine. {@code BlockState.hasAnalogOutputSignal} takes no
 * level and is still asked of the real block; only the value is computed here.
 *
 * <p>Three groups:
 * <ul>
 * <li>Plain containers, whose override is exactly
 *     {@code AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos))}.
 *     The container instance comes from the loop's live block-entity store.</li>
 * <li>State-only overrides, which never touch the level beyond the state.</li>
 * <li>Everything else (chest, jukebox, lectern, command block, crafter,
 *     chiseled bookshelf, shelf, sculk sensor, creaking heart, detector rail)
 *     needs a block entity, an entity query or the double-chest lookup that
 *     Kestrel does not have yet. Those read 0, an explicit unported guard,
 *     so a comparator next to one in a live world stays quiet instead of
 *     throwing.</li>
 * </ul>
 */
public final class AnalogOutput {
    private AnalogOutput() {
    }

    /**
     * Blocks whose {@code getAnalogOutputSignal} is the plain container read:
     * {@code BarrelBlock}, {@code HopperBlock}, {@code DispenserBlock} (and
     * {@code DropperBlock}), {@code AbstractFurnaceBlock},
     * {@code BrewingStandBlock}, {@code DecoratedPotBlock},
     * {@code ShulkerBoxBlock}.
     */
    public static boolean isPlainContainer(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BarrelBlock
                || block instanceof HopperBlock
                || block instanceof DispenserBlock
                || block instanceof AbstractFurnaceBlock
                || block instanceof BrewingStandBlock
                || block instanceof DecoratedPotBlock
                || block instanceof ShulkerBoxBlock;
    }

    /** {@code BlockState.getAnalogOutputSignal(level, pos, direction)}. */
    static int getAnalogOutputSignal(VanillaRedstone level, BlockState state, BlockPos pos, Direction direction) {
        Block block = state.getBlock();
        if (isPlainContainer(state)) {
            return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
        }
        if (block instanceof BeehiveBlock) {
            // BeehiveBlock.getAnalogOutputSignal
            return state.getValue(BeehiveBlock.HONEY_LEVEL);
        }
        if (block instanceof CakeBlock) {
            // CakeBlock.getAnalogOutputSignal -> CakeBlock.getOutputSignal
            return CakeBlock.getOutputSignal(state.getValue(CakeBlock.BITES));
        }
        if (block instanceof CandleCakeBlock) {
            // CandleCakeBlock.getAnalogOutputSignal
            return CakeBlock.FULL_CAKE_SIGNAL;
        }
        if (block instanceof LayeredCauldronBlock) {
            // LayeredCauldronBlock.getAnalogOutputSignal
            return state.getValue(LayeredCauldronBlock.LEVEL);
        }
        if (block instanceof LavaCauldronBlock) {
            // LavaCauldronBlock.getAnalogOutputSignal
            return 3;
        }
        if (block instanceof ComposterBlock) {
            // ComposterBlock.getAnalogOutputSignal
            return state.getValue(ComposterBlock.LEVEL);
        }
        if (block instanceof EndPortalFrameBlock) {
            // EndPortalFrameBlock.getAnalogOutputSignal
            return state.getValue(EndPortalFrameBlock.HAS_EYE) ? 15 : 0;
        }
        if (block instanceof RespawnAnchorBlock) {
            // RespawnAnchorBlock.getAnalogOutputSignal -> getScaledChargeLevel(state, 15)
            return RespawnAnchorBlock.getScaledChargeLevel(state, 15);
        }
        if (block instanceof CopperGolemStatueBlock) {
            // CopperGolemStatueBlock.getAnalogOutputSignal
            return state.getValue(CopperGolemStatueBlock.POSE).ordinal() + 1;
        }
        if (block instanceof CopperBulbBlock) {
            // CopperBulbBlock.getAnalogOutputSignal reads level.getBlockState(pos)
            return level.getBlockState(pos).getValue(CopperBulbBlock.LIT) ? 15 : 0;
        }
        // Unported: needs a block entity, entity query or chest lookup Kestrel lacks.
        return 0;
    }
}
