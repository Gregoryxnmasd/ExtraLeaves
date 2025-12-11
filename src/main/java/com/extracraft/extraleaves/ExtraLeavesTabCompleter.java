package com.extracraft.extraleaves;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ExtraLeavesTabCompleter implements TabCompleter {

    private final LeafManager leafManager;

    public ExtraLeavesTabCompleter(LeafManager leafManager) {
        this.leafManager = leafManager;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!sender.hasPermission("extraleaves.use")) {
            return completions;
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String opt : List.of("reload", "pack", "give")) {
                if (opt.startsWith(prefix)) {
                    completions.add(opt);
                }
            }
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
            return completions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (LeafType type : leafManager.getAll()) {
                String id = type.id();
                if (id.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    completions.add(id);
                }
            }
            return completions;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            for (String n : List.of("1", "8", "16", "32", "64")) {
                if (n.startsWith(args[3])) {
                    completions.add(n);
                }
            }
            return completions;
        }

        return completions;
    }
}
