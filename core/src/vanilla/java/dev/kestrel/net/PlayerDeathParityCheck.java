package dev.kestrel.net;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** G22 slice 2 executable gate for death, the dying body and respawn. */
public final class PlayerDeathParityCheck {
    private static int checks;
    private static int failures;

    private PlayerDeathParityCheck() { }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Fixture fixture = fixture();
        PlayerTrackingParityCheck.prepare(fixture.access());

        scenario(fixture, false, false, "generic-drop");
        scenario(fixture, true, false, "keep-inventory");
        scenario(fixture, false, true, "hidden-death-message");
        aliveRespawnIgnored(fixture);
        dyingBodyRemoval(fixture);
        fullRespawn(fixture);
        rejoinBelowWorld(fixture);

        if (failures == 0) {
            System.out.println("PlayerDeathParityCheck: PASSED (" + checks + " checks, 7 scenarios)");
        } else {
            System.out.println("PlayerDeathParityCheck: FAILED (" + failures + " failures)");
            System.exit(1);
        }
    }

    private static void scenario(Fixture fixture, boolean keepInventory, boolean hideMessage, String name)
            throws Exception {
        try (Arena a = new Arena(fixture, name)) {
            a.h.loop.gameRules().set(GameRules.KEEP_INVENTORY, keepInventory, null);
            a.h.loop.gameRules().set(GameRules.SHOW_DEATH_MESSAGES, !hideMessage, null);
            a.h.setItem(a.victim, 0, new ItemStack(Items.DIAMOND, 3));
            int entitiesBefore = a.h.server.entityWorld().tickingCount();
            a.kill();
            List<String> own = a.victim.readTick(1001);
            expect(has(own, PacketIds.PLAY_COMBAT_KILL), name + " missing combat-kill packet");
            expect(a.player().getHealth() == 0.0F, name + " health " + a.player().getHealth());
            expect(!a.h.server.bodies().bodyClientLoaded(a.victim.record),
                    name + " did not mark client unloaded after death");
            expect(a.player().getInventory().getItem(0).isEmpty() != keepInventory,
                    name + " inventory keep=" + keepInventory);
            expect(a.h.server.entityWorld().tickingCount() == entitiesBefore + (keepInventory ? 0 : 1),
                    name + " item entity count");
            if (hideMessage) {
                String packet = own.stream().filter(p -> PlayerTrackingParityCheck.idOf(p) == PacketIds.PLAY_COMBAT_KILL)
                        .findFirst().orElse("");
                expect(packet.endsWith("00"), "SHOW_DEATH_MESSAGES=false did not send empty message");
            }
        }
    }

    private static void aliveRespawnIgnored(Fixture fixture) throws Exception {
        try (Arena a = new Arena(fixture, "alive-respawn")) {
            a.h.server.bodies().packet(a.victim.record, PacketIds.PLAY_CLIENT_COMMAND,
                    new Wire.Writer().vint(0).toBytes());
            List<String> own = a.victim.readTick(1002);
            expect(!has(own, PacketIds.PLAY_RESPAWN), "respawn while alive was accepted");
            expect(a.player().getHealth() == 20.0F, "alive respawn changed health");
        }
    }

    private static void dyingBodyRemoval(Fixture fixture) throws Exception {
        try (Arena a = new Arena(fixture, "death-time")) {
            a.kill();
            a.victim.readTick(1003);
            for (int i = 0; i < 19; i++) a.h.server.bodies().tickConnections();
            List<String> observer19 = a.observer.readTick(1004);
            expect(!has(observer19, PacketIds.PLAY_REMOVE_ENTITIES), "body removed before deathTime 20");
            a.h.server.bodies().tickConnections();
            List<String> observer20 = a.observer.readTick(1005);
            expect(has(observer20, PacketIds.PLAY_ENTITY_EVENT), "deathTime 20 missing event 60");
            expect(has(observer20, PacketIds.PLAY_REMOVE_ENTITIES), "deathTime 20 missing removal");
        }
    }

    private static void fullRespawn(Fixture fixture) throws Exception {
        try (Arena a = new Arena(fixture, "full-respawn")) {
            a.player().getFoodData().setFoodLevel(4);
            a.player().experienceLevel = 7;
            a.player().totalExperience = 91;
            a.kill();
            a.victim.readTick(1006);
            a.h.server.bodies().packet(a.victim.record, PacketIds.PLAY_CLIENT_COMMAND,
                    new Wire.Writer().vint(0).toBytes());
            List<String> own = a.victim.readTick(1007);
            int respawn = index(own, PacketIds.PLAY_RESPAWN);
            int position = index(own, PacketIds.PLAY_POSITION);
            int spawn = index(own, PacketIds.PLAY_SPAWN_POS);
            int difficulty = index(own, PacketIds.PLAY_CHANGE_DIFFICULTY);
            expect(respawn >= 0 && respawn < position && position < spawn && spawn < difficulty,
                    "respawn packet order " + own);
            expect(a.player().getHealth() == 20.0F, "respawn health");
            expect(a.player().getFoodData().getFoodLevel() == 20, "respawn food");
            expect(a.player().experienceLevel == 0 && a.player().totalExperience == 0, "respawn xp");
            expect(a.player().position().equals(new net.minecraft.world.phys.Vec3(0.5, 65.0, 0.5)),
                    "respawn position " + a.player().position());
        }
    }

    private static void rejoinBelowWorld(Fixture fixture) throws Exception {
        try (Arena a = new Arena(fixture, "below-world-cycle")) {
            a.player().setHealth(4.0F);
            a.player().invulnerableTime = 0;
            a.player().snapTo(0.5, -200.0, 0.5, 0.0F, 0.0F);
            a.h.server.bodies().tickConnections();
            expect(a.player().getHealth() == 0.0F, "below-world rejoin did not die");
            a.h.server.bodies().packet(a.victim.record, PacketIds.PLAY_CLIENT_COMMAND,
                    new Wire.Writer().vint(0).toBytes());
            expect(a.player().getHealth() == 20.0F && a.player().getY() == 65.0,
                    "below-world rejoin did not respawn");
            expect(a.h.server.bodies().hasBody(a.victim.record), "below-world cycle disconnected player");
        }
    }

    private static final class Arena implements AutoCloseable {
        final PlayerTrackingParityCheck.Harness h;
        final PlayerTrackingParityCheck.Client observer;
        final PlayerTrackingParityCheck.Client victim;
        final DamageSource source;

        Arena(Fixture fixture, String name) throws Exception {
            PlayerTrackingParityCheck.World world = new PlayerTrackingParityCheck.World(loop -> {
                for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                    loop.setBlock(new net.minecraft.core.BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            }, new net.minecraft.core.BlockPos(0, 64, 0));
            h = new PlayerTrackingParityCheck.Harness("death-" + name, world);
            h.server.setDatapack(fixture.packs());
            observer = h.connect("obs" + Integer.toUnsignedString(name.hashCode(), 36), 2, 0);
            h.join(observer);
            victim = h.connect("vic" + Integer.toUnsignedString(name.hashCode(), 36), 2, 0);
            h.join(victim);
            source = fixture.source();
        }

        net.minecraft.server.level.ServerPlayer player() {
            return h.server.bodies().body(victim.record).vanillaPlayer();
        }

        void kill() {
            player().invulnerableTime = 0;
            player().hurtServer((net.minecraft.server.level.ServerLevel) player().level(), source, 1000.0F);
        }

        @Override public void close() { h.close(); }
    }

    private static Fixture fixture() throws Exception {
        java.nio.file.Path data = java.nio.file.Path.of(System.getProperty("kestrel.datapack",
                java.nio.file.Path.of(System.getProperty("user.home"), "kestrel-run", "packdump", "data").toString()));
        List<String> fluidOrder = new ArrayList<>();
        for (int i = 0; i < BuiltInRegistries.FLUID.size(); i++)
            fluidOrder.add(BuiltInRegistries.FLUID.get(i).orElseThrow().key().identifier().toString());
        List<Datapack.RegistryEntries> packs = Datapack.load(data,
                List.of("minecraft:damage_type", "minecraft:fluid"), Map.of("minecraft:fluid", fluidOrder));
        RegistryAccess.Frozen access = PlayerBodies.damageRegistry(packs.getFirst());
        var generic = access.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(
                ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("minecraft:generic")));
        return new Fixture(access, packs, new DamageSource(generic));
    }

    private record Fixture(RegistryAccess.Frozen access, List<Datapack.RegistryEntries> packs, DamageSource source) { }
    private static boolean has(List<String> packets, int id) { return index(packets, id) >= 0; }
    private static int index(List<String> packets, int id) {
        for (int i = 0; i < packets.size(); i++) if (PlayerTrackingParityCheck.idOf(packets.get(i)) == id) return i;
        return -1;
    }
    private static void expect(boolean ok, String message) {
        checks++;
        if (!ok) { failures++; System.out.println("  - " + message); }
    }
}
