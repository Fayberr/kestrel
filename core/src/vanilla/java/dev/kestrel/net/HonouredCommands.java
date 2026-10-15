package dev.kestrel.net;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.ExecutionCommandSource;

import java.util.HashSet;
import java.util.Set;

/**
 * The commands Kestrel can honour. Vanilla's whole command set parses on the stand-in, and
 * many of its commands would report success while changing nothing real in Kestrel (the
 * stand-in is not where the world, the clock or the inventory live). So the boot
 * {@code Commands}' dispatcher is replaced by one that holds only these roots: the tree
 * sent to clients, parsing, execution and suggestions all read that one dispatcher, so a
 * command outside it reads as vanilla's own unknown command and is absent from autocomplete.
 *
 * <p>The dispatcher is a private final field of {@code Commands} ({@code Commands.java:175}),
 * read by {@code sendCommands}, {@code performCommand} and {@code getDispatcher}; replacing
 * it through reflection is the one place every user of it is covered without copying vanilla's
 * handlers. The kept roots appear in the order vanilla registered them
 * ({@code Commands.java:177-243}), because the packet lists nodes in child order.
 *
 * <p>{@code help} captures the dispatcher it was registered on ({@code HelpCommand.java:19}),
 * so it is registered again on the kept one and lists only what exists here.
 */
final class HonouredCommands {
    private HonouredCommands() {
    }

    /** Roots with no redirect: nothing else has to exist for them. */
    static final Set<String> ROOTS = Set.of("gamemode", "help", "list");

    static void restrict(Commands commands) {
        restrict(commands, ROOTS);
    }

    static void restrict(Commands commands, Set<String> roots) {
        CommandDispatcher<CommandSourceStack> full = commands.getDispatcher();
        CommandDispatcher<CommandSourceStack> kept = new CommandDispatcher<>();
        Set<String> found = new HashSet<>();
        for (CommandNode<CommandSourceStack> child : full.getRoot().getChildren()) {
            if (!roots.contains(child.getName())) {
                continue;
            }
            found.add(child.getName());
            if (child.getName().equals("help")) {
                net.minecraft.server.commands.HelpCommand.register(kept);
            } else {
                kept.getRoot().addChild(child);
            }
        }
        if (!found.equals(roots)) {
            throw new IllegalStateException("vanilla registers none of " + new HashSet<>(roots).stream().filter(n -> !found.contains(n)).toList());
        }
        checkRedirects(kept.getRoot(), new HashSet<>(), reachable(kept.getRoot(), new HashSet<>()));
        // Commands.java:243, the last line of the constructor.
        kept.setConsumer(ExecutionCommandSource.resultConsumer());
        try {
            java.lang.reflect.Field field = Commands.class.getDeclaredField("dispatcher");
            field.setAccessible(true);
            field.set(commands, kept);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot restrict the command set", e);
        }
    }

    private static Set<CommandNode<CommandSourceStack>> reachable(CommandNode<CommandSourceStack> node,
            Set<CommandNode<CommandSourceStack>> seen) {
        if (seen.add(node)) {
            for (CommandNode<CommandSourceStack> child : node.getChildren()) {
                reachable(child, seen);
            }
        }
        return seen;
    }

    /** A redirect out of the kept tree would send clients to a node they were never given. */
    private static void checkRedirects(CommandNode<CommandSourceStack> node,
            Set<CommandNode<CommandSourceStack>> visited, Set<CommandNode<CommandSourceStack>> kept) {
        if (!visited.add(node)) {
            return;
        }
        if (node.getRedirect() != null && !kept.contains(node.getRedirect())) {
            throw new IllegalStateException("command " + node.getName() + " redirects outside the honoured set");
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            checkRedirects(child, visited, kept);
        }
    }
}
