package dev.kestrel.net;

import net.minecraft.server.notifications.EmptyNotificationService;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.ServerOpListEntry;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The operator list: vanilla's own {@link ServerOpList} over an {@code ops.json} in the
 * server root, loaded the way a dedicated server loads it.
 *
 * <p>{@code DedicatedPlayerList.java:21-24} loads the list, then saves it, each step
 * logging a warning and carrying on when it fails ({@code :64-80}). So a missing file is
 * created holding {@code []}, a file that does not parse leaves the list empty (and the save
 * then replaces the file with {@code []}), and entries without a readable {@code uuid} are
 * skipped by {@code StoredUserList.load}. The format is vanilla's: {@code uuid},
 * {@code name}, {@code level} (0 to 4, default 0 when absent) and {@code bypassesPlayerLimit}.
 *
 * <p>An owner writes the {@code uuid} the login derives: offline mode takes
 * {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)} ({@link Wire#offlineUuid}), the
 * same id as {@code UUIDUtil.createOfflinePlayerUUID} that vanilla's offline login uses.
 */
final class OpList {
    private OpList() {
    }

    /** {@code PlayerList.OPLIST_FILE} ({@code PlayerList.java:108}), beside {@code server.properties}. */
    static Path file(Path root) {
        return root.resolve("ops.json");
    }

    /** Load the list from {@code ops.json} under {@code root}, as {@code DedicatedPlayerList} does at start. */
    static ServerOpList load(Path root) {
        ServerOpList ops = new ServerOpList(file(root).toFile(), new EmptyNotificationService());
        try {
            ops.load();
        } catch (Exception e) {
            System.out.println("[kestrel] Failed to load operators list: " + e);
        }
        try {
            ops.save();
        } catch (Exception e) {
            System.out.println("[kestrel] Failed to save operators list: " + e);
        }
        return ops;
    }

    /** An empty list that never touches the disk: the server before an {@code ops.json} is loaded, and gates. */
    static ServerOpList inMemory() {
        return new ServerOpList(new File("ops.json"), new EmptyNotificationService()) {
            @Override
            public void save() {
            }
        };
    }

    /** {@code PlayerList.isOp} for a server that is neither singleplayer nor allows commands for all. */
    static boolean isOp(ServerOpList ops, UUID id) {
        return ops.get(new NameAndId(id, "")) != null;
    }

    /** {@code PlayerList.op(nameAndId)} (:537-552) at {@code level}, without the permission resend. */
    static void op(ServerOpList ops, UUID id, String name,
            net.minecraft.server.permissions.LevelBasedPermissionSet permissions) {
        ops.add(new ServerOpListEntry(new NameAndId(id, name), permissions, false));
    }
}
