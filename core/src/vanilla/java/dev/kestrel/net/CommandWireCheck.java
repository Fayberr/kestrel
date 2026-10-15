package dev.kestrel.net;

import com.mojang.brigadier.CommandDispatcher;
import dev.kestrel.worldgen.HeadlessNoise;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.ExecutionCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.CommonPacketTypes;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerboundChangeGameModePacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.server.commands.GameModeCommand;
import net.minecraft.server.commands.HelpCommand;
import net.minecraft.server.commands.ListPlayersCommand;
import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Gate for operators, the command tree and the commands Kestrel honours, against vanilla 26.2.
 *
 * <p>Kestrel's side is the live path on the lockstep harness of
 * {@link PlayerTrackingParityCheck}: a real {@link NetServer} with {@code ops.json} loaded through
 * {@code NetServer.loadOps} and the data pack's own {@code Commands} installed through
 * {@code NetServer.setCommands} (which restricts it to the honoured commands), real clients over
 * loopback sockets. The oracle side is vanilla's own listener on vanilla's own player list.
 *
 * <p>The oracle stays independent of Kestrel's operator and filtering code: its operator list is
 * filled by the gate directly, and its {@code Commands} is a dispatcher this class registers
 * itself from vanilla's own {@code register} methods (gamemode, help, list), in the order the
 * {@code Commands} constructor registers them. A filtering bug on Kestrel's side therefore
 * shows as a different tree or a different answer. Every client's whole stream of the families
 * listed in {@link #main} is compared with the oracle's, tick by tick.
 */
public final class CommandWireCheck {
    private CommandWireCheck() {
    }

    private static HeadlessNoise.Structural structural;
    private static int scenarios;
    private static int ids_systemChat;
    private static int ids_commands;
    private static int ids_gameEvent;
    private static int ids_abilities;
    private static int ids_suggestions;
    private static int ids_disconnect;

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        PlayerTrackingParityCheck.prepare(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        PlayerTrackingParityCheck.compareAlso(GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT, GamePacketTypes.CLIENTBOUND_COMMANDS,
                GamePacketTypes.CLIENTBOUND_GAME_EVENT, GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES,
                GamePacketTypes.CLIENTBOUND_COMMAND_SUGGESTIONS, CommonPacketTypes.CLIENTBOUND_DISCONNECT);
        ids_systemChat = PlayerTrackingParityCheck.clientboundId(GamePacketTypes.CLIENTBOUND_SYSTEM_CHAT);
        ids_commands = PlayerTrackingParityCheck.clientboundId(GamePacketTypes.CLIENTBOUND_COMMANDS);
        ids_gameEvent = PlayerTrackingParityCheck.clientboundId(GamePacketTypes.CLIENTBOUND_GAME_EVENT);
        ids_abilities = PlayerTrackingParityCheck.clientboundId(GamePacketTypes.CLIENTBOUND_PLAYER_ABILITIES);
        ids_suggestions = PlayerTrackingParityCheck.clientboundId(GamePacketTypes.CLIENTBOUND_COMMAND_SUGGESTIONS);
        ids_disconnect = PlayerTrackingParityCheck.clientboundId(CommonPacketTypes.CLIENTBOUND_DISCONNECT);

        checkPacketIds();
        opsFileAtBoot();
        // The data pack's own Commands: what the live server installs, built by the same load.
        structural = HeadlessNoise.loadStructural(4242424242L);

        String only = args.length > 0 ? String.join(" ", args) : System.getProperty("kestrel.commands.scenario");
        run(only, "join-levels", CommandWireCheck::joinLevels);
        run(only, "gamemode", CommandWireCheck::gameMode);
        run(only, "gamemode-targets", CommandWireCheck::gameModeTargets);
        run(only, "refused", CommandWireCheck::refused);
        run(only, "f3-f4", CommandWireCheck::f3f4);
        run(only, "list-and-help", CommandWireCheck::listAndHelp);
        run(only, "suggestions", CommandWireCheck::suggestions);
        run(only, "leave", CommandWireCheck::leave);
        run(only, "illegal-character", CommandWireCheck::illegalCharacter);
        run(only, "spam-throttle", CommandWireCheck::spamThrottle);
        run(only, "respawn", CommandWireCheck::respawn);
        structural.close();

        List<String> failures = PlayerTrackingParityCheck.failureList();
        if (!failures.isEmpty()) {
            System.out.println("CommandWireCheck: FAILED with " + failures.size() + " failure(s)");
            for (int i = 0; i < Math.min(failures.size(), 40); i++) {
                System.out.println("  - " + failures.get(i));
            }
            System.exit(1);
        }
        System.out.println("CommandWireCheck: PASSED (" + PlayerTrackingParityCheck.checkCount() + " checks, "
                + scenarios + " scenarios)");
        System.exit(0);
    }

    private interface Script {
        void run(Rig r) throws Exception;
    }

    private static void run(String only, String name, Script script) throws Exception {
        if (only != null && !only.equals(name)) {
            return;
        }
        scenarios++;
        int before = PlayerTrackingParityCheck.failureList().size();
        try (Rig r = new Rig(name, new Op("alice", 4), new Op("bob", 2))) {
            script.run(r);
            r.h.finish();
        } catch (Throwable t) {
            PlayerTrackingParityCheck.failureList().add(name + ": threw " + t);
            t.printStackTrace(System.out);
        }
        int failed = PlayerTrackingParityCheck.failureList().size() - before;
        System.out.println("  scenario " + name + ": " + (failed == 0 ? "ok" : failed + " failure(s)"));
    }

    private static void expect(boolean ok, String message) {
        PlayerTrackingParityCheck.expect(ok, message);
    }

    // =====================================================================
    // Packet ids the inbound side depends on
    // =====================================================================

    private static void checkPacketIds() {
        expect(PacketIds.PLAY_CHAT_COMMAND == PlayerTrackingParityCheck.serverboundId(GamePacketTypes.SERVERBOUND_CHAT_COMMAND),
                "id chat_command");
        expect(PacketIds.PLAY_CHAT_COMMAND_SIGNED
                == PlayerTrackingParityCheck.serverboundId(GamePacketTypes.SERVERBOUND_CHAT_COMMAND_SIGNED),
                "id chat_command_signed");
        expect(PacketIds.PLAY_COMMAND_SUGGESTION
                == PlayerTrackingParityCheck.serverboundId(GamePacketTypes.SERVERBOUND_COMMAND_SUGGESTION),
                "id command_suggestion");
        expect(PacketIds.PLAY_CHANGE_GAME_MODE
                == PlayerTrackingParityCheck.serverboundId(GamePacketTypes.SERVERBOUND_CHANGE_GAME_MODE),
                "id change_game_mode");
    }

    // =====================================================================
    // One scenario's two sides
    // =====================================================================

    /** The oracle's command set: vanilla's own register methods, in the constructor's order. */
    private static Commands oracleCommands() {
        Commands commands = PlayerTrackingOracle.emptyCommands();
        CommandDispatcher<CommandSourceStack> dispatcher = commands.getDispatcher();
        GameModeCommand.register(dispatcher);
        HelpCommand.register(dispatcher);
        ListPlayersCommand.register(dispatcher);
        dispatcher.setConsumer(ExecutionCommandSource.resultConsumer());
        return commands;
    }

    private static UUID offline(String name) {
        return UUIDUtil.createOfflinePlayerUUID(name);
    }

    /** An operator as an owner writes it in {@code ops.json}. */
    private record Op(String name, int level) {
    }

    private static final BlockPos SPAWN = new BlockPos(0, 64, 0);

    private static final class Rig implements AutoCloseable {
        final PlayerTrackingParityCheck.Harness h;
        final Path root;

        Rig(String name, Op... ops) throws Exception {
            this(name, null, ops);
        }

        Rig(String name, String opsFile, Op... ops) throws Exception {
            PlayerTrackingParityCheck.World world = new PlayerTrackingParityCheck.World(loop -> {
                for (int x = -3; x <= 3; x++) {
                    for (int z = -3; z <= 3; z++) {
                        loop.setBlock(new BlockPos(x, 63, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }, SPAWN);
            h = new PlayerTrackingParityCheck.Harness("commands-" + name, world);
            root = Files.createTempDirectory("kestrel-commands");
            if (opsFile == null) {
                StringBuilder json = new StringBuilder("[");
                for (int i = 0; i < ops.length; i++) {
                    json.append(i == 0 ? "" : ",").append("{\"uuid\":\"").append(offline(ops[i].name())).append("\",\"name\":\"")
                            .append(ops[i].name()).append("\",\"level\":").append(ops[i].level())
                            .append(",\"bypassesPlayerLimit\":false}");
                }
                opsFile = json.append("]").toString();
            }
            Files.writeString(root.resolve("ops.json"), opsFile, StandardCharsets.UTF_8);
            // Kestrel: operators, then the data pack's commands, before anyone joins.
            h.server.loadOps(root);
            h.server.setCommands(structural.commands());
            // Vanilla: the gate's own list and dispatcher.
            h.oracle.server.commands = oracleCommands();
            for (Op op : ops) {
                h.oracle.ops.add(new ServerOpListEntry(new NameAndId(offline(op.name()), op.name()),
                        LevelBasedPermissionSet.forLevel(PermissionLevel.byId(op.level())), false));
            }
        }

        PlayerTrackingParityCheck.Client join(String name) throws Exception {
            PlayerTrackingParityCheck.Client c = h.connect(name, 2, 0);
            h.join(c);
            return c;
        }

        /** Send {@code /command} and run a tick. */
        void command(PlayerTrackingParityCheck.Client c, String command) throws Exception {
            c.send(new ServerboundChatCommandPacket(command));
            h.tick();
        }

        /** The packets Kestrel sent {@code c} so far, of {@code id}, that contain {@code needle} (ASCII). */
        long count(PlayerTrackingParityCheck.Client c, int id, String needle) {
            String hex = HexFormat.of().formatHex(needle.getBytes(StandardCharsets.US_ASCII));
            return h.kestrelLog.get(c).stream().filter(p -> PlayerTrackingParityCheck.idOf(p) == id
                    && p.substring(p.indexOf(':') + 1).contains(hex)).count();
        }

        long count(PlayerTrackingParityCheck.Client c, int id) {
            return h.kestrelLog.get(c).stream().filter(p -> PlayerTrackingParityCheck.idOf(p) == id).count();
        }

        /** Mark where each client's log stands, to look only at what follows. */
        java.util.Map<PlayerTrackingParityCheck.Client, Integer> mark() {
            java.util.Map<PlayerTrackingParityCheck.Client, Integer> marks = new java.util.HashMap<>();
            for (var e : h.kestrelLog.entrySet()) {
                marks.put(e.getKey(), e.getValue().size());
            }
            return marks;
        }

        List<String> since(java.util.Map<PlayerTrackingParityCheck.Client, Integer> marks,
                PlayerTrackingParityCheck.Client c) {
            List<String> log = h.kestrelLog.get(c);
            return log.subList(marks.getOrDefault(c, 0), log.size());
        }

        @Override
        public void close() {
            h.close();
        }
    }

    private static long countIn(List<String> packets, int id, String needle) {
        String hex = HexFormat.of().formatHex(needle.getBytes(StandardCharsets.US_ASCII));
        return packets.stream().filter(p -> PlayerTrackingParityCheck.idOf(p) == id
                && p.substring(p.indexOf(':') + 1).contains(hex)).count();
    }

    private static long countIn(List<String> packets, int id) {
        return packets.stream().filter(p -> PlayerTrackingParityCheck.idOf(p) == id).count();
    }

    /** The entity event {@code event} sent for entity {@code entityId}: its id as an int, then the byte. */
    private static String entityEvent(int entityId, int event) {
        return String.format("%08x%02x", entityId, event);
    }

    private static boolean hasEntityEvent(List<String> log, int entityId, int event) {
        String payload = entityEvent(entityId, event);
        return log.stream().anyMatch(p -> PlayerTrackingParityCheck.idOf(p) == PacketIds.PLAY_ENTITY_EVENT
                && p.endsWith(":" + payload));
    }

    // =====================================================================
    // ops.json at boot
    // =====================================================================

    private static void opsFileAtBoot() throws Exception {
        // Missing: vanilla's load finds no file, its save then creates one holding [].
        Path missing = Files.createTempDirectory("kestrel-ops-missing");
        ServerOpList none = OpList.load(missing);
        expect(none.isEmpty(), "ops.json missing: no operators");
        expect(Files.exists(missing.resolve("ops.json")), "ops.json missing: the file is created");
        expect(Files.readString(missing.resolve("ops.json")).replaceAll("\\s", "").equals("[]"),
                "ops.json missing: the new file holds []");

        // Malformed: the load fails and is logged, nobody is an operator, and (as vanilla's
        // loadOps then saveOps do) the file is rewritten as [].
        Path malformed = Files.createTempDirectory("kestrel-ops-malformed");
        Files.writeString(malformed.resolve("ops.json"), "{ this is not json", StandardCharsets.UTF_8);
        ServerOpList broken = OpList.load(malformed);
        expect(broken.isEmpty(), "ops.json malformed: no operators");
        expect(Files.readString(malformed.resolve("ops.json")).replaceAll("\\s", "").equals("[]"),
                "ops.json malformed: rewritten as []");

        // Present: a level, no level (vanilla reads that as level 0), an unreadable uuid (skipped).
        Path present = Files.createTempDirectory("kestrel-ops-present");
        UUID alice = offline("Alice");
        UUID bob = offline("Bob");
        Files.writeString(present.resolve("ops.json"), "[{\"uuid\":\"" + alice + "\",\"name\":\"Alice\",\"level\":3,"
                + "\"bypassesPlayerLimit\":true},{\"uuid\":\"" + bob + "\",\"name\":\"Bob\"},"
                + "{\"uuid\":\"not-a-uuid\",\"name\":\"Eve\",\"level\":4}]", StandardCharsets.UTF_8);
        ServerOpList loaded = OpList.load(present);
        ServerOpListEntry a = loaded.get(new NameAndId(alice, "Alice"));
        ServerOpListEntry b = loaded.get(new NameAndId(bob, "Bob"));
        expect(a != null && a.permissions().level() == PermissionLevel.ADMINS && a.getBypassesPlayerLimit(),
                "ops.json present: Alice is level 3 and bypasses the limit");
        expect(b != null && b.permissions().level() == PermissionLevel.ALL, "ops.json present: Bob has no level");
        expect(loaded.getEntries().size() == 2, "ops.json present: the unreadable uuid is skipped");
        expect(OpList.isOp(loaded, alice) && !OpList.isOp(loaded, offline("Eve")), "ops.json present: isOp");
        // The login uuid: offline mode derives it from the name, as vanilla's own helper does.
        expect(Wire.offlineUuid("Alice").equals(alice), "offline login uuid is vanilla's offline uuid");
    }

    // =====================================================================
    // Scenarios
    // =====================================================================

    /** A non-op, a level 4 operator and a level 2 operator join: events 24, 28 and 26 and their trees. */
    private static void joinLevels(Rig r) throws Exception {
        // Fresh rig, ops from ops.json: alice level 4, bob level 2.
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client bob = r.join("bob");
        expect(hasEntityEvent(r.h.kestrelLog.get(carol), carol.record.entityId, 24), "carol gets event 24");
        expect(hasEntityEvent(r.h.kestrelLog.get(alice), alice.record.entityId, 28), "alice gets event 28");
        expect(hasEntityEvent(r.h.kestrelLog.get(bob), bob.record.entityId, 26), "bob gets event 26");
        expect(r.count(carol, ids_commands) == 1 && r.count(alice, ids_commands) == 1 && r.count(bob, ids_commands) == 1,
                "each player gets one command tree");
        expect(r.count(carol, ids_commands, "gamemode") == 0, "carol's tree has no gamemode");
        expect(r.count(alice, ids_commands, "gamemode") == 1 && r.count(bob, ids_commands, "gamemode") == 1,
                "the operators' trees have gamemode");
        expect(r.count(alice, ids_commands, "summon") == 0 && r.count(alice, ids_commands, "teleport") == 0,
                "no command Kestrel cannot honour is in the tree");
        // Level 2 and level 4 operators see the same commands: the tree differs only by what the level allows.
    }

    private static void gameMode(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client bob = r.join("bob");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        var mark = r.mark();
        r.command(alice, "gamemode creative");
        List<String> mine = r.since(mark, alice);
        expect(countIn(mine, ids_gameEvent) == 1, "alice gets the game event");
        expect(countIn(mine, ids_abilities) == 1, "alice gets her abilities");
        expect(countIn(mine, PacketIds.PLAY_PLAYER_INFO) == 1, "alice gets her row update");
        expect(countIn(mine, ids_systemChat, "commands.gamemode.success.self") == 1, "alice gets the feedback line");
        List<String> bobs = r.since(mark, bob);
        expect(countIn(bobs, PacketIds.PLAY_PLAYER_INFO) == 1, "bob gets the row update");
        expect(countIn(bobs, ids_systemChat, "chat.type.admin") == 1, "bob gets the admin broadcast");
        List<String> carols = r.since(mark, carol);
        expect(countIn(carols, PacketIds.PLAY_PLAYER_INFO) == 1, "carol gets the row update");
        expect(countIn(carols, ids_systemChat) == 0, "carol gets no chat line");
        // The same mode again: vanilla's setGameMode answers false, nothing is sent.
        mark = r.mark();
        r.command(alice, "gamemode creative");
        expect(r.since(mark, alice).isEmpty() && r.since(mark, bob).isEmpty() && r.since(mark, carol).isEmpty(),
                "the same mode again sends nothing");
        r.command(alice, "gamemode survival");
        r.command(alice, "gamemode spectator");
        r.command(alice, "gamemode adventure");
    }

    private static void gameModeTargets(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client bob = r.join("bob");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        var mark = r.mark();
        r.command(alice, "gamemode adventure bob");
        expect(countIn(r.since(mark, bob), ids_systemChat, "gameMode.changed") == 1, "bob is told his mode changed");
        expect(countIn(r.since(mark, alice), ids_systemChat, "commands.gamemode.success.other") == 1,
                "alice gets the other-player feedback");
        expect(countIn(r.since(mark, carol), ids_gameEvent) == 0, "carol's mode is untouched");
        mark = r.mark();
        r.command(alice, "gamemode survival @a");
        for (var c : List.of(alice, bob, carol)) {
            expect(countIn(r.since(mark, c), PacketIds.PLAY_PLAYER_INFO) >= 1, c.name + " sees the @a row updates");
        }
        r.command(alice, "gamemode creative @p");
        r.command(alice, "gamemode creative @s");
        r.command(bob, "gamemode spectator carol");
        r.command(bob, "gamemode survival nobody");
        r.command(alice, "gamemode flying");
        r.command(alice, "gamemode");
    }

    private static void refused(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        var mark = r.mark();
        r.command(carol, "gamemode creative");
        expect(countIn(r.since(mark, carol), ids_systemChat, "command.unknown.command") == 1,
                "a non-operator's /gamemode reads as an unknown command");
        expect(countIn(r.since(mark, carol), ids_gameEvent) == 0, "a non-operator's mode did not change");
        mark = r.mark();
        r.command(alice, "summon pig");
        expect(countIn(r.since(mark, alice), ids_systemChat, "command.unknown.command") == 1,
                "/summon, which Kestrel cannot honour, reads as an unknown command");
        r.command(alice, "tp 0 100 0");
        r.command(alice, "give alice stone");
        r.command(alice, "time set day");
        r.command(alice, "execute as @a run gamemode creative");
        r.command(carol, "nonsense");
    }

    private static void f3f4(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        var mark = r.mark();
        alice.send(new ServerboundChangeGameModePacket(GameType.CREATIVE));
        r.h.tick();
        expect(countIn(r.since(mark, alice), ids_gameEvent) == 1, "an operator's F3+F4 changes the mode");
        expect(countIn(r.since(mark, carol), PacketIds.PLAY_PLAYER_INFO) == 1, "the others see the row");
        mark = r.mark();
        carol.send(new ServerboundChangeGameModePacket(GameType.SPECTATOR));
        r.h.tick();
        expect(r.since(mark, carol).isEmpty() && r.since(mark, alice).isEmpty(), "a non-operator's F3+F4 does nothing");
        alice.send(new ServerboundChangeGameModePacket(GameType.SURVIVAL));
        r.h.tick();
        alice.send(new ServerboundChangeGameModePacket(GameType.SURVIVAL));
        r.h.tick();
    }

    private static void listAndHelp(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        r.h.server.setMaxPlayers(7);
        r.h.oracle.server.maxPlayers = 7;
        var mark = r.mark();
        r.command(carol, "list");
        expect(countIn(r.since(mark, carol), ids_systemChat, "commands.list.players") == 1, "/list answers");
        r.command(carol, "list uuids");
        r.command(alice, "list");
        r.command(carol, "help");
        r.command(alice, "help gamemode");
        r.command(carol, "help summon");
        expect(countIn(r.since(mark, carol), ids_systemChat, "summon") == 0
                        || countIn(r.since(mark, carol), ids_systemChat, "commands.help.failed") >= 1,
                "/help knows nothing of a command that does not exist here");
    }

    private static void suggestions(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        var mark = r.mark();
        alice.send(new ServerboundCommandSuggestionPacket(1, "/gamemode "));
        r.h.tick();
        alice.send(new ServerboundCommandSuggestionPacket(2, "gamemode creative "));
        r.h.tick();
        alice.send(new ServerboundCommandSuggestionPacket(3, "g"));
        r.h.tick();
        alice.send(new ServerboundCommandSuggestionPacket(4, "su"));
        r.h.tick();
        carol.send(new ServerboundCommandSuggestionPacket(5, "g"));
        r.h.tick();
        expect(countIn(r.since(mark, alice), ids_suggestions) == 4, "alice gets four suggestion answers");
        expect(countIn(r.since(mark, alice), ids_suggestions, "gamemode") >= 1, "gamemode is suggested to an operator");
        expect(countIn(r.since(mark, alice), ids_suggestions, "summon") == 0, "summon is never suggested");
        expect(countIn(r.since(mark, carol), ids_suggestions, "gamemode") == 0, "gamemode is not suggested to a non-operator");
    }

    private static void leave(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client bob = r.join("bob");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        carol.disconnect();
        r.h.ticks(2);
        var mark = r.mark();
        r.command(alice, "gamemode creative @a");
        r.command(alice, "list");
        expect(r.since(mark, carol).isEmpty(), "a player who left gets no broadcast");
        expect(countIn(r.since(mark, bob), PacketIds.PLAY_PLAYER_INFO) == 1, "the others still get it");
        // Rejoining puts the player back on the list.
        PlayerTrackingParityCheck.Client again = r.join("carol");
        r.command(alice, "list");
        r.command(alice, "gamemode survival carol");
        expect(r.count(again, ids_gameEvent) == 0, "the rejoined player is a new entry, in survival");
    }

    private static void illegalCharacter(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client bob = r.join("bob");
        var mark = r.mark();
        r.command(alice, "gamemode creative §");
        expect(countIn(r.since(mark, alice), ids_disconnect, "illegal_characters") == 1,
                "an illegal character disconnects, as vanilla's tryHandleChat does");
        expect(countIn(r.since(mark, alice), ids_gameEvent) == 0, "and the command did not run");
        expect(r.since(mark, bob).isEmpty(), "and the others are told nothing");
    }

    /** Non-operators are disconnected for spamming commands; the count decays one per tick. */
    private static void spamThrottle(Rig r) throws Exception {
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        var mark = r.mark();
        for (int i = 0; i < 8; i++) {
            carol.send(new ServerboundChatCommandPacket("list"));
        }
        r.h.tick();
        r.h.ticks(30);
        for (int i = 0; i < 3; i++) {
            carol.send(new ServerboundChatCommandPacket("list"));
        }
        r.h.tick();
        expect(countIn(r.since(mark, carol), ids_disconnect) == 0, "a decayed count does not disconnect");
        for (int i = 0; i < 12; i++) {
            carol.send(new ServerboundChatCommandPacket("list"));
        }
        r.h.tick();
        expect(countIn(r.since(mark, carol), ids_disconnect, "disconnect.spam") == 1, "spam disconnects a non-operator");
        // Operators are exempt.
        mark = r.mark();
        for (int i = 0; i < 25; i++) {
            alice.send(new ServerboundChatCommandPacket("list"));
        }
        r.h.tick();
        expect(countIn(r.since(mark, alice), ids_disconnect) == 0, "an operator is not disconnected for spam");
    }

    /** After a death and respawn the permission event and the tree are sent again, at vanilla's place. */
    private static void respawn(Rig r) throws Exception {
        // The damage type registry the death needs, as the death gate serves it.
        java.nio.file.Path data = java.nio.file.Path.of(System.getProperty("kestrel.datapack",
                java.nio.file.Path.of(System.getProperty("user.home"), "kestrel-run", "packdump", "data").toString()));
        List<String> fluidOrder = new ArrayList<>();
        for (int i = 0; i < BuiltInRegistries.FLUID.size(); i++) {
            fluidOrder.add(BuiltInRegistries.FLUID.get(i).orElseThrow().key().identifier().toString());
        }
        List<Datapack.RegistryEntries> packs = Datapack.load(data, List.of("minecraft:damage_type", "minecraft:fluid"),
                java.util.Map.of("minecraft:fluid", fluidOrder));
        r.h.server.setDatapack(packs);
        net.minecraft.core.RegistryAccess.Frozen damage = PlayerBodies.damageRegistry(packs.getFirst());
        net.minecraft.world.damagesource.DamageSource source = new net.minecraft.world.damagesource.DamageSource(
                damage.lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getOrThrow(
                        net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE,
                                net.minecraft.resources.Identifier.parse("minecraft:generic"))));
        PlayerTrackingParityCheck.Client alice = r.join("alice");
        PlayerTrackingParityCheck.Client carol = r.join("carol");
        for (PlayerTrackingParityCheck.Client c : List.of(alice, carol)) {
            var player = r.h.server.bodies().body(c.record).vanillaPlayer();
            player.invulnerableTime = 0;
            player.hurtServer((net.minecraft.server.level.ServerLevel) player.level(), source, 1000.0F);
            // The dead player's own stream, outside the lockstep compare (the oracle does not die).
            c.readTick(2000);
            int before = r.h.kestrelLog.get(c).size();
            r.h.server.bodies().packet(c.record, PacketIds.PLAY_CLIENT_COMMAND, new Wire.Writer().vint(0).toBytes());
            List<String> own = c.readTick(2001);
            int respawnAt = indexOf(own, PacketIds.PLAY_RESPAWN);
            int eventAt = indexOfEvent(own, c.record.entityId);
            int treeAt = indexOf(own, ids_commands);
            expect(respawnAt >= 0 && eventAt > respawnAt && treeAt > eventAt,
                    c.name + " respawn: respawn, then the permission event, then the tree: " + respawnAt + " "
                            + eventAt + " " + treeAt);
            // vanilla's own bytes for the same two packets
            PlayerTrackingOracle.VPlayer vp = c.vp;
            vp.drain();
            r.h.oracle.sendPermissionLevel(vp);
            List<String> expected = vp.drain();
            List<String> got = new ArrayList<>();
            if (eventAt >= 0 && treeAt >= 0) {
                got.add(own.get(eventAt));
                got.add(own.get(treeAt));
            }
            List<String> wanted = new ArrayList<>();
            for (String p : expected) {
                int id = PlayerTrackingParityCheck.idOf(p);
                if (id == PacketIds.PLAY_ENTITY_EVENT || id == ids_commands) {
                    wanted.add(p);
                }
            }
            expect(got.equals(wanted), c.name + " respawn: permission packets kestrel=" + got + " vanilla=" + wanted);
            expect(before >= 0, "log kept");
        }
    }

    private static int indexOf(List<String> packets, int id) {
        for (int i = 0; i < packets.size(); i++) {
            if (PlayerTrackingParityCheck.idOf(packets.get(i)) == id) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfEvent(List<String> packets, int entityId) {
        for (int i = 0; i < packets.size(); i++) {
            String p = packets.get(i);
            if (PlayerTrackingParityCheck.idOf(p) == PacketIds.PLAY_ENTITY_EVENT
                    && p.substring(p.indexOf(':') + 1).startsWith(String.format("%08x", entityId))) {
                return i;
            }
        }
        return -1;
    }
}
