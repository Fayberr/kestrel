package dev.kestrel.net;

import com.mojang.serialization.Lifecycle;
import dev.kestrel.entity.KestrelPlayerEntity;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageScaling;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** G22 slice 1 gate for the player's own vanilla connection, damage and packets. */
public final class PlayerSelfPacketsCheck {
    private PlayerSelfPacketsCheck() {
    }

    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        codecScenario();
        DamageFixture damage = damageFixture();
        PlayerTrackingParityCheck.prepare(damage.access());
        liveScenario(damage);
        environmentalScenario(damage, "void", (h, k, v) -> {
            k.snapTo(0.5, -200.0, 0.5, 0.0F, 0.0F);
            v.snapTo(0.5, -200.0, 0.5, 0.0F, 0.0F);
            h.tick();
            environmentalState(k, v, "void tick");
            expect(k.getHealth() == 16.0F, "void damage health " + k.getHealth());
        });
        environmentalScenario(damage, "suffocation", (h, k, v) -> {
            h.loop.setBlock(new net.minecraft.core.BlockPos(0, 65, 0), Blocks.STONE.defaultBlockState(), 3);
            k.snapTo(0.5, 64.0, 0.5, 0.0F, 0.0F);
            v.snapTo(0.5, 64.0, 0.5, 0.0F, 0.0F);
            h.tick();
            environmentalState(k, v, "suffocation tick");
            expect(k.getHealth() == 19.0F, "suffocation health " + k.getHealth());
        });
        environmentalScenario(damage, "drowning", (h, k, v) -> {
            for (int y = 64; y <= 66; y++) {
                h.loop.setBlock(new net.minecraft.core.BlockPos(0, y, 0), Blocks.WATER.defaultBlockState(), 3);
            }
            k.snapTo(0.5, 64.0, 0.5, 0.0F, 0.0F);
            v.snapTo(0.5, 64.0, 0.5, 0.0F, 0.0F);
            k.setAirSupply(-19);
            v.setAirSupply(-19);
            expect(!k.level().getFluidState(new net.minecraft.core.BlockPos(0, 65, 0)).isEmpty(),
                    "drowning fixture has no live water");
            var probeChunk = k.level().getChunk(0, 0, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
            expect(probeChunk != null && probeChunk.getSections()[probeChunk.getSectionIndexFromSectionY(4)].hasFluid(),
                    "drowning fixture has no loaded fluid section");
            h.tick();
            environmentalState(k, v, "drowning damage tick");
            expect(k.getHealth() == 18.0F, "drowning health " + k.getHealth() + " air=" + k.getAirSupply()
                    + " eye=" + k.getEyeY() + " water=" + k.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            for (int i = 0; i < 3; i++) {
                h.tick();
                environmentalState(k, v, "drowning cooldown tick " + i);
            }
        });
        lethalSafetyScenario(damage);

        if (failures == 0) {
            System.out.println("PlayerSelfPacketsCheck: PASSED (" + checks + " checks, 10 scenarios)");
        } else {
            System.out.println("PlayerSelfPacketsCheck: FAILED (" + failures + " failures)");
            System.exit(1);
        }
    }

    private static void lethalSafetyScenario(DamageFixture fixture) throws Exception {
        PlayerTrackingParityCheck.World world = new PlayerTrackingParityCheck.World(loop -> {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                loop.setBlock(new net.minecraft.core.BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }, new net.minecraft.core.BlockPos(0, 64, 0));
        try (PlayerTrackingParityCheck.Harness h = new PlayerTrackingParityCheck.Harness("lethal-environment", world)) {
            h.server.setDatapack(fixture.packs());
            PlayerTrackingParityCheck.Client victim = h.connect("victim", 2, 0);
            h.join(victim);
            PlayerTrackingParityCheck.Client survivor = h.connect("survivor", 2, 0);
            h.join(survivor);
            var dying = h.server.bodies().body(victim.record).vanillaPlayer();
            var alive = h.server.bodies().body(survivor.record).vanillaPlayer();
            dying.setHealth(4.0F);
            dying.invulnerableTime = 0;
            dying.snapTo(0.5, -200.0, 0.5, 0.0F, 0.0F);
            long survivorTicks = h.server.bodies().bodyConnectionTicks(survivor.record);
            long victimTicks = h.server.bodies().bodyConnectionTicks(victim.record);
            long victimAttempts = h.server.bodies().bodyConnectionAttempts(victim.record);
            Throwable escaped = null;
            try {
                KestrelServer.runIteration(h.server, h.loop, h.sender);
            } catch (Throwable t) {
                escaped = t;
            }
            expect(escaped == null, "lethal environmental damage escaped iteration: " + escaped);
            expect(h.server.bodies().bodyConnectionTicks(survivor.record) == survivorTicks + 1,
                    "dead player skipped survivor tick");
            expect(dying.getHealth() == 0.0F, "lethal environmental hit did not finish death");
            expect(h.server.bodies().hasBody(victim.record), "death disconnected the player");

            // The round-2 safety net remains for a non-death connection fault.
            h.server.bodies().faultNextConnectionTick(victim.record);
            h.server.bodies().tickConnections();

            Wire.Inbound last = null;
            for (int i = 0; i < 100; i++) {
                last = victim.read();
                if (last.id() == PacketIds.PLAY_DISCONNECT) break;
            }
            expect(last != null && last.id() == PacketIds.PLAY_DISCONNECT,
                    "non-death fault stream did not end in disconnect");
            expect(last != null && java.util.Arrays.equals(last.payload(), Wire.disconnectNbt("Internal server error")),
                    "non-death fault disconnect reason was not Internal server error");

            long deadline = System.currentTimeMillis() + 10000;
            while (h.server.sessions.containsKey(victim.profile) && System.currentTimeMillis() < deadline) {
                Thread.sleep(1);
            }
            KestrelServer.runIteration(h.server, h.loop, h.sender);
            expect(h.server.bodies().bodyConnectionTicks(survivor.record) == survivorTicks + 3,
                    "server did not tick survivor after fault disconnect");
            expect(h.server.bodies().bodyConnectionTicks(victim.record) == -1
                            || h.server.bodies().bodyConnectionTicks(victim.record) == victimTicks,
                    "faulted player ticked again");
            expect(!h.server.bodies().hasBody(victim.record), "faulted player remained joined");
        }
    }

    @FunctionalInterface
    private interface EnvironmentalScript {
        void run(PlayerTrackingParityCheck.Harness h, net.minecraft.server.level.ServerPlayer kestrel,
                net.minecraft.server.level.ServerPlayer vanilla) throws Exception;
    }

    private static void environmentalScenario(DamageFixture fixture, String name,
            EnvironmentalScript script) throws Exception {
        PlayerTrackingParityCheck.World world = new PlayerTrackingParityCheck.World(loop -> {
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                loop.setBlock(new net.minecraft.core.BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }, new net.minecraft.core.BlockPos(0, 64, 0));
        try (PlayerTrackingParityCheck.Harness h = new PlayerTrackingParityCheck.Harness("environment-" + name, world)) {
            h.server.setDatapack(fixture.packs());
            PlayerTrackingParityCheck.Client client = h.connect(name, 2, 0);
            h.join(client);
            script.run(h, h.server.bodies().body(client.record).vanillaPlayer(), client.vp.player);
        }
    }

    private static void environmentalState(net.minecraft.server.level.ServerPlayer k,
            net.minecraft.server.level.ServerPlayer v, String where) {
        expect(k.getHealth() == v.getHealth(), where + " health " + k.getHealth() + "/" + v.getHealth());
        expect(k.invulnerableTime == v.invulnerableTime,
                where + " invulnerable " + k.invulnerableTime + "/" + v.invulnerableTime);
        expect(lastHurt(k) == lastHurt(v), where + " lastHurt " + lastHurt(k) + "/" + lastHurt(v));
        expect(k.getAirSupply() == v.getAirSupply(),
                where + " air " + k.getAirSupply() + "/" + v.getAirSupply());
    }

    private static void codecScenario() {
        net.minecraft.server.level.ServerPlayer player = dev.kestrel.entity.KestrelServerPlayer.inventoryOwner();
        expect(player.connection != null, "constructor-backed player has no connection");

        List<Outbound.Packet> encoded = VanillaPacketBridge.encode(new ClientboundSetHealthPacket(20.0F, 20, 5.0F));
        expect(encoded.size() == 1, "set health expanded to " + encoded.size() + " packets");
        if (encoded.size() == 1) {
            expect(encoded.getFirst().id() == 0x68, "set health id " + encoded.getFirst().id());
            expect(hex(encoded.getFirst().payload()).equals("41a000001440a00000"),
                    "set health payload " + hex(encoded.getFirst().payload()));
        }

        ClientboundDamageEventPacket packet = new ClientboundDamageEventPacket(300,
                Holder.direct(new DamageType("generic", DamageScaling.WHEN_CAUSED_BY_LIVING_NON_PLAYER, 0.1F)),
                -1, -1, java.util.Optional.empty());
        Outbound.Packet damage = EntityOutbound.damageEvent(packet, 18);
        expect(damage.id() == 0x19, "damage event id " + damage.id());
        expect(hex(damage.payload()).equals("ac0212000000"), "damage event payload " + hex(damage.payload()));
    }

    private static void liveScenario(DamageFixture fixture) throws Exception {
        PlayerTrackingParityCheck.World world = new PlayerTrackingParityCheck.World(loop -> {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) loop.setBlock(new net.minecraft.core.BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }, new net.minecraft.core.BlockPos(0, 64, 0));
        try (PlayerTrackingParityCheck.Harness h = new PlayerTrackingParityCheck.Harness("self-damage", world)) {
            h.server.setDatapack(fixture.packs());
            PlayerTrackingParityCheck.Client observer = h.connect("observer", 2, 0);
            h.join(observer);
            PlayerTrackingParityCheck.Client client = h.connect("damage", 2, 0);
            h.join(client);
            KestrelPlayerEntity body = h.server.bodies().body(client.record);
            var kestrel = body.vanillaPlayer();
            var vanilla = client.vp.player;

            long initialHealth = count(h.kestrelLog.get(client), PacketIds.PLAY_SET_HEALTH);
            expect(initialHealth == 1, "join health packets " + initialHealth);
            h.ticks(3);
            expect(count(h.kestrelLog.get(client), PacketIds.PLAY_SET_HEALTH) == 1,
                    "idle repeated the initial health packet");

            kestrel.setDeltaMovement(0.1, 0.0, 0.0);
            vanilla.setDeltaMovement(0.1, 0.0, 0.0);
            h.tick();
            expect(kestrel.getDeltaMovement().equals(vanilla.getDeltaMovement()),
                    "connection tick motion " + kestrel.getDeltaMovement() + "/" + vanilla.getDeltaMovement());

            int before = h.kestrelLog.get(client).size();
            int observerBefore = h.kestrelLog.get(observer).size();
            expect(hurt(kestrel, vanilla, h, fixture.source(), 4.0F), "fresh 4.0 hit was refused");
            h.tick();
            state(kestrel, vanilla, 16.0F, 19, 4.0F, "fresh hit");
            List<String> fresh = h.kestrelLog.get(client).subList(before, h.kestrelLog.get(client).size());
            expect(has(fresh, PacketIds.PLAY_DAMAGE_EVENT), "fresh hit omitted damage_event");
            expect(has(fresh, PacketIds.PLAY_HURT_ANIMATION), "fresh hit omitted hurt_animation");
            expect(has(fresh, PacketIds.PLAY_SET_HEALTH), "fresh hit omitted set_health");
            List<String> observed = h.kestrelLog.get(observer).subList(observerBefore,
                    h.kestrelLog.get(observer).size());
            expect(has(observed, PacketIds.PLAY_DAMAGE_EVENT), "observer omitted tracked damage_event");
            expect(!has(observed, PacketIds.PLAY_HURT_ANIMATION), "hurt_animation leaked to observer");
            expect(!has(observed, PacketIds.PLAY_SET_HEALTH), "set_health leaked to observer");

            before = h.kestrelLog.get(client).size();
            expect(!hurt(kestrel, vanilla, h, fixture.source(), 4.0F), "equal cooldown hit landed");
            h.tick();
            state(kestrel, vanilla, 16.0F, 18, 4.0F, "refused hit");
            expect(h.kestrelLog.get(client).size() == before, "refused hit emitted packets");

            before = h.kestrelLog.get(client).size();
            expect(hurt(kestrel, vanilla, h, fixture.source(), 7.0F), "larger cooldown hit was refused");
            h.tick();
            state(kestrel, vanilla, 13.0F, 17, 7.0F, "delta hit");
            List<String> delta = h.kestrelLog.get(client).subList(before, h.kestrelLog.get(client).size());
            expect(!has(delta, PacketIds.PLAY_DAMAGE_EVENT), "delta hit emitted damage_event");
            expect(has(delta, PacketIds.PLAY_SET_HEALTH), "delta hit omitted set_health");

            kestrel.invulnerableTime = 0;
            vanilla.invulnerableTime = 0;
            Throwable kestrelDeath = lethal(kestrel, (net.minecraft.server.level.ServerLevel) kestrel.level(),
                    fixture.source());
            Throwable vanillaDeath = lethal(vanilla, h.oracle.level, fixture.source());
            expect(kestrelDeath == null && vanillaDeath != null,
                    "death completion boundary differed: " + kestrelDeath + " / " + vanillaDeath);
            expect(kestrel.getHealth() == 0.0F && vanilla.getHealth() == 0.0F, "lethal health was not clamped to zero");
        }
    }

    private static boolean hurt(net.minecraft.server.level.ServerPlayer kestrel,
            net.minecraft.server.level.ServerPlayer vanilla, PlayerTrackingParityCheck.Harness h,
            DamageSource source, float amount) {
        boolean k = kestrel.hurtServer((net.minecraft.server.level.ServerLevel) kestrel.level(), source, amount);
        boolean v = vanilla.hurtServer(h.oracle.level, source, amount);
        expect(k == v, "hurt result kestrel=" + k + " vanilla=" + v);
        return k;
    }

    private static Throwable lethal(net.minecraft.server.level.ServerPlayer player,
            net.minecraft.server.level.ServerLevel level, DamageSource source) {
        try {
            player.hurtServer(level, source, 100.0F);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private static void state(net.minecraft.server.level.ServerPlayer k, net.minecraft.server.level.ServerPlayer v,
            float health, int invulnerable, float lastHurt, String where) {
        expect(k.getHealth() == health && v.getHealth() == health, where + " health " + k.getHealth() + "/" + v.getHealth());
        expect(k.invulnerableTime == invulnerable && v.invulnerableTime == invulnerable,
                where + " invulnerable " + k.invulnerableTime + "/" + v.invulnerableTime);
        float kh = lastHurt(k);
        float vh = lastHurt(v);
        expect(kh == lastHurt && vh == lastHurt, where + " lastHurt " + kh + "/" + vh);
        expect(k.getFoodData().getFoodLevel() == v.getFoodData().getFoodLevel(), where + " food");
        expect(k.getFoodData().getSaturationLevel() == v.getFoodData().getSaturationLevel(), where + " saturation");
        expect(k.tickCount == v.tickCount, where + " player tick count " + k.tickCount + "/" + v.tickCount);
        expect(k.getNoActionTime() == v.getNoActionTime(),
                where + " no-action time " + k.getNoActionTime() + "/" + v.getNoActionTime());
    }

    private static DamageFixture damageFixture() throws Exception {
        java.nio.file.Path data = java.nio.file.Path.of(System.getProperty("kestrel.datapack",
                java.nio.file.Path.of(System.getProperty("user.home"), "kestrel-run", "packdump", "data").toString()));
        List<String> fluidOrder = new ArrayList<>();
        for (int i = 0; i < BuiltInRegistries.FLUID.size(); i++) {
            fluidOrder.add(BuiltInRegistries.FLUID.get(i).orElseThrow().key().identifier().toString());
        }
        List<Datapack.RegistryEntries> packs = Datapack.load(data,
                List.of("minecraft:damage_type", "minecraft:fluid"), Map.of("minecraft:fluid", fluidOrder));
        Datapack.RegistryEntries served = packs.getFirst();
        RegistryAccess.Frozen access = PlayerBodies.damageRegistry(served);
        var generic = access.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(
                ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.parse("minecraft:generic")));
        return new DamageFixture(access, packs, served, new DamageSource(generic));
    }

    private static float lastHurt(net.minecraft.server.level.ServerPlayer player) {
        try {
            java.lang.reflect.Field field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("lastHurt");
            field.setAccessible(true);
            return field.getFloat(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private record DamageFixture(RegistryAccess.Frozen access, List<Datapack.RegistryEntries> packs,
            Datapack.RegistryEntries served, DamageSource source) { }

    private static long count(List<String> packets, int id) { return packets.stream().filter(p -> PlayerTrackingParityCheck.idOf(p) == id).count(); }
    private static boolean has(List<String> packets, int id) { return count(packets, id) != 0; }
    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private static void expect(boolean condition, String message) { checks++; if (!condition) { failures++; System.out.println("  - " + message); } }
}
