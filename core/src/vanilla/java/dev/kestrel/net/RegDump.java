package dev.kestrel.net;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dumps this JVM's entire block-state registry as a stable, diffable
 * table: one line per global network state id, in id order, as
 *
 * <pre>id&lt;TAB&gt;blockKey&lt;TAB&gt;stateDefinitionString</pre>
 *
 * <p>Purpose (trapdoor/block-state rendering investigation): the client resolves a chunk
 * section palette entry to a model purely by this global state id, so
 * if the CLIENT's table at a given id names a different block than the
 * SERVER's table does, every affected block renders as the wrong model
 * no matter how correct the wire bytes are. Running this on the server
 * and the same-format dump on the client, then diffing, shows exactly
 * which ids disagree and by how much (a constant offset implies a
 * registry-order difference, scattered mismatches imply something
 * else).
 *
 * <p>Runs fully offline against the vanilla jar on the classpath, the
 * same way {@link NetCheck} does. Bootstrap is required before touching
 * any vanilla registry.
 */
public final class RegDump {
    private RegDump() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        StringBuilder sb = new StringBuilder(1 << 20);
        int count = 0;
        for (BlockState state : (Iterable<BlockState>) Block.BLOCK_STATE_REGISTRY) {
            int id = Block.getId(state);
            String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).toString();
            // BlockState.toString() only prints the block (Block{minecraft:x}),
            // NOT the property values, and trapdoor variants differ ONLY by
            // properties (waterlogged/facing/half/open/powered). Emit the
            // properties explicitly, name-sorted, so the dump is genuinely
            // state-level and the client/server diff can distinguish e.g.
            // "copper trapdoor, waterlogged=true" from "...=false".
            java.util.TreeMap<String, String> props = new java.util.TreeMap<>();
            for (var pv : state.getValues().toList()) {
                props.put(pv.property().getName(), pv.valueName());
            }
            StringBuilder ps = new StringBuilder();
            for (var e : props.entrySet()) {
                if (ps.length() > 0) {
                    ps.append(',');
                }
                ps.append(e.getKey()).append('=').append(e.getValue());
            }
            sb.append(id).append('\t').append(key).append('\t')
                    .append(ps).append('\n');
            count++;
        }
        System.out.print("# RegDump minecraft=" + SharedConstants.getCurrentVersion().name()
                + " states=" + count + " blocks="
                + net.minecraft.core.registries.BuiltInRegistries.BLOCK.size() + "\n");
        System.out.print(sb);
        System.out.println("# RegDump complete lines=" + count);
    }
}
