package com.detraly.analytics.command;

import java.util.List;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class DetralyCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN = "detraly.admin";

    private final CommandCore core;

    public DetralyCommand(CommandCore core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        core.execute(new BukkitActor(sender), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return core.complete(new BukkitActor(sender), args);
    }

    private static final class BukkitActor implements CommandActor {
        private final CommandSender sender;

        BukkitActor(CommandSender sender) {
            this.sender = sender;
        }

        @Override
        public boolean isConsole() {
            return sender instanceof ConsoleCommandSender;
        }

        @Override
        public UUID playerId() {
            return sender instanceof Player p ? p.getUniqueId() : null;
        }

        @Override
        public boolean isAdmin() {
            return sender.hasPermission(ADMIN);
        }

        @Override
        public void send(Segment... parts) {
            StringBuilder out = new StringBuilder();
            for (Segment part : parts) {
                out.append(color(part.tone())).append(part.text());
            }
            sender.sendMessage(out.toString());
        }

        private static String color(Tone tone) {
            switch (tone) {
                case BRAND:
                    return ChatColor.GOLD.toString();
                case GOOD:
                    return ChatColor.GREEN.toString();
                case BAD:
                    return ChatColor.RED.toString();
                case WARN:
                    return ChatColor.YELLOW.toString();
                case MUTED:
                    return ChatColor.GRAY.toString();
                default:
                    return ChatColor.RESET.toString();
            }
        }
    }
}
