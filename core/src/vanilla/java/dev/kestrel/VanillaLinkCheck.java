package dev.kestrel;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Compile-time proof that {@code core/} can see vanilla's real,
 * deobfuscated classes when built with {@code -PvanillaJar=...} (see
 * {@code preresearch/core-block-model-design.md}). Only included in the
 * build when that property is set (see {@code build.gradle}'s conditional
 * {@code sourceSets} block) - this file existing and compiling cleanly is
 * the actual verification, not a runtime check. Deliberately does not
 * instantiate anything (vanilla's static init assumes a running server
 * bootstrap this scaffold does not have yet); referencing the real
 * class/method names at the type level is enough to prove the classpath
 * wiring works.
 */
final class VanillaLinkCheck {
    private VanillaLinkCheck() {
    }

    @SuppressWarnings("unused")
    static Class<?> proveLinkage() {
        // Real vanilla method/field references, resolved at compile time
        // against the embedded server-26.2.jar. If this doesn't compile,
        // the -PvanillaJar wiring is broken.
        Class<BlockState> blockStateClass = BlockState.class;
        VoxelShape emptyShape = Shapes.empty();
        return blockStateClass.equals(emptyShape.getClass()) ? null : blockStateClass;
    }
}
