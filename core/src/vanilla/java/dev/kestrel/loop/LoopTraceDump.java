package dev.kestrel.loop;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Dumps the loop's per-tick POWER trace over the S + T arms in
 * power-trace.log format, for diffing against the vanilla baseline
 * ({@code preresearch/baselines/power-trace-vanilla-26.2-2026-09-12.log}).
 * Not part of the proof battery; a developer tool. Usage:
 * {@code LoopTraceDump <ticks>}.
 */
public final class LoopTraceDump {
    private LoopTraceDump() {
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 30;

        // S arm: 41-wire line + powered lever (mirrors TickLoopCheck).
        TickLoop loop = TickLoopCheck.buildPoweredLine(-20, 20, -4);
        // T arm: torch-powered 10-wire line, offset so ids do not collide.
        for (int x = -2; x <= 13; x++) {
            loop.setBlock(new BlockPos(x, 0, 8), Blocks.STONE.defaultBlockState(), 3);
        }
        for (int x = 1; x <= 10; x++) {
            loop.setBlock(new BlockPos(x, 1, 8), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
        loop.setBlock(new BlockPos(0, 0, 8), Blocks.STONE.defaultBlockState(), 3);
        loop.setBlock(new BlockPos(0, 1, 8), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        TickLoopCheck.resolveWires(loop, -20, 20, -4);
        TickLoopCheck.resolveWires(loop, 1, 10, 8);
        loop.updatePowerAt(new BlockPos(-20, 1, -4));
        loop.updatePowerAt(new BlockPos(1, 1, 8));
        loop.drainDispatch();

        loop.discoverWires();
        for (int i = 0; i < ticks; i++) {
            loop.tick();
        }
        for (String line : loop.powerTrace()) {
            System.out.println(line);
        }
    }
}
