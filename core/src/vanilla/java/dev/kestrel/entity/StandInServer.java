package dev.kestrel.entity;

import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * The vanilla server and player list the stand-ins of one level share, as the network layer
 * sees them. Vanilla has one {@code PlayerList} whose {@code players} and {@code playersByUUID}
 * its own code iterates (selectors, {@code /list}, admin broadcasts, death messages); a
 * joined stand-in enters it where {@code PlayerList.placeNewPlayer} adds it and leaves where
 * {@code PlayerList.remove} removes it. Isolated gate stand-ins never enter it.
 *
 * <p>Configuration (settings, commands, operators) is stored per level and read when vanilla
 * asks, so it can be installed before or after the first stand-in exists.
 *
 * <p>Thread-safety: tick thread only, apart from {@link #configure}-style installs at boot.
 */
public final class StandInServer {
    private StandInServer() {
    }

    /** What the server answers from {@code server.properties}, read live. */
    public interface Settings {
        int maxPlayers();

        int opPermissionLevel();

        GameType gameMode();

        boolean forceGameMode();
    }

    static final class Config {
        volatile @Nullable Settings settings;
        volatile @Nullable Commands commands;
        volatile @Nullable ServerOpList ops;
        private @Nullable Commands unavailable;

        /** The installed commands, or an empty set: every command is then vanilla's unknown command. */
        Commands commands() {
            Commands installed = commands;
            if (installed != null) return installed;
            if (unavailable == null) unavailable = empty();
            return unavailable;
        }
    }

    private static final Map<EntityLevel, Config> CONFIGS = new WeakHashMap<>();

    static Config config(EntityLevel level) {
        synchronized (CONFIGS) {
            return CONFIGS.computeIfAbsent(level, ignored -> new Config());
        }
    }

    /** A {@code Commands} with a dispatcher that holds no command, built without registering any. */
    private static Commands empty() {
        try {
            Commands commands = (Commands) unsafe().allocateInstance(Commands.class);
            KestrelServerPlayer.put(commands, Commands.class, "dispatcher",
                    new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>());
            return commands;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build an empty command set", e);
        }
    }

    public static void setSettings(EntityLevel level, Settings settings) {
        config(level).settings = settings;
    }

    /** Install the boot {@code Commands}; {@code null} leaves commands unavailable. */
    public static void setCommands(EntityLevel level, @Nullable Commands commands) {
        config(level).commands = commands;
    }

    /** {@code PlayerList.ops}: the one list vanilla's {@code isOp} and {@code getProfilePermissions} read. */
    public static void setOps(EntityLevel level, ServerOpList ops) {
        Config config = config(level);
        config.ops = ops;
        VanillaEntityLevel adapter = VanillaEntityLevel.of(level);
        if (adapter.hasRuntime()) {
            try {
                KestrelServerPlayer.put(adapter.runtime().server().getPlayerList(),
                        net.minecraft.server.players.PlayerList.class, "ops", ops);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * {@code PlayerList.placeNewPlayer} (:204-206): {@code players.add} and {@code playersByUUID.put},
     * after the new player was sent the list of those already in.
     */
    public static void addToList(ServerPlayer player) {
        KestrelServerPlayer.Runtime runtime = runtimeOf(player);
        runtime.list().add(player);
        runtime.byUuid().put(player.getUUID(), player);
    }

    /**
     * {@code PlayerList.remove} (:325-336): the player leaves both lists, and the per-player
     * stats and advancements go with the entry that owned the id.
     */
    public static void removeFromList(ServerPlayer player) {
        KestrelServerPlayer.Runtime runtime = runtimeOf(player);
        runtime.list().remove(player);
        UUID id = player.getUUID();
        if (runtime.byUuid().get(id) == player) {
            runtime.byUuid().remove(id);
            runtime.server().players.stats.remove(id);
            runtime.server().players.advancements.remove(id);
        }
    }

    /** {@code ServerLevel.addNewPlayer} (:1939): the player enters the level's own list. */
    public static void addToLevel(ServerPlayer player) {
        runtimeOf(player).level().players().add(player);
    }

    /** {@code ServerLevel.removePlayerImmediately} (:1974): it leaves the level's list. */
    public static void removeFromLevel(ServerPlayer player) {
        runtimeOf(player).level().players().remove(player);
    }

    /**
     * {@code PlayerList.sendPlayerPermissionLevel} (:467-470): the permission entity event, then
     * the command tree this player may use.
     */
    public static void sendPlayerPermissionLevel(ServerPlayer player) {
        runtimeOf(player).server().getPlayerList().sendPlayerPermissionLevel(player);
    }

    /** The joined players, in {@code PlayerList.players} order. */
    public static List<ServerPlayer> joined(ServerPlayer anyStandIn) {
        return runtimeOf(anyStandIn).list();
    }

    private static KestrelServerPlayer.Runtime runtimeOf(ServerPlayer player) {
        return ((VanillaEntityLevel) player.level()).runtime();
    }

    private static sun.misc.Unsafe unsafe() throws ReflectiveOperationException {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
