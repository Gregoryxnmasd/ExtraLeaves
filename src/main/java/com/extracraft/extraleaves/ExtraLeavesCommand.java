package com.extracraft.extraleaves;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Locale;
import java.util.logging.Level;

public class ExtraLeavesCommand implements CommandExecutor {

    private final ExtraLeavesPlugin plugin;
    private final LeafManager leafManager;
    private final PackGenerator packGenerator;

    public ExtraLeavesCommand(ExtraLeavesPlugin plugin, LeafManager leafManager, PackGenerator packGenerator) {
        this.plugin = plugin;
        this.leafManager = leafManager;
        this.packGenerator = packGenerator;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("extraleaves.use")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para usar este comando.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> handleReload(sender);
            case "pack" -> handlePack(sender);
            case "give" -> handleGive(sender, args);
            default -> sendHelp(sender, label);
        }

        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.GOLD + "ExtraLeaves comandos:");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " reload"
                + ChatColor.GRAY + " - Recarga la config y regenera el resourcepack");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " pack"
                + ChatColor.GRAY + " - Regenera solo el resourcepack");
        sender.sendMessage(ChatColor.YELLOW + "/" + label + " give <jugador> <leafId> [cantidad]"
                + ChatColor.GRAY + " - Da hojas custom");
    }

    private void handleReload(CommandSender sender) {
        long start = System.currentTimeMillis();
        sender.sendMessage(ChatColor.YELLOW + "[ExtraLeaves] Recargando config y hojas...");

        leafManager.reload();

        try {
            packGenerator.generatePack();
            long ms = System.currentTimeMillis() - start;
            sender.sendMessage(ChatColor.GREEN + "[ExtraLeaves] Recargado todo y resourcepack regenerado en " + ms + " ms.");
            sender.sendMessage(ChatColor.GRAY + "Ruta del pack: "
                    + packGenerator.getOutputFolder().toAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error al regenerar el resourcepack.", e);
            sender.sendMessage(ChatColor.RED + "[ExtraLeaves] Error al regenerar el resourcepack. Revisa la consola.");
        }
    }

    private void handlePack(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "[ExtraLeaves] Regenerando resourcepack...");
        try {
            packGenerator.generatePack();
            sender.sendMessage(ChatColor.GREEN + "[ExtraLeaves] Resourcepack regenerado.");
            sender.sendMessage(ChatColor.GRAY + "Ruta del pack: "
                    + packGenerator.getOutputFolder().toAbsolutePath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Error al regenerar el resourcepack.", e);
            sender.sendMessage(ChatColor.RED + "[ExtraLeaves] Error al regenerar el resourcepack. Revisa la consola.");
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Uso: /extraleaves give <jugador> <leafId> [cantidad]");
            return;
        }

        String playerName = args[1];
        String leafId = args[2];

        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Jugador no encontrado: " + playerName);
            return;
        }

        LeafType type = leafManager.find(leafId);
        if (type == null) {
            sender.sendMessage(ChatColor.RED + "Tipo de hoja desconocido: " + leafId);
            sender.sendMessage(ChatColor.GRAY + "Tipos disponibles: ");
            StringBuilder sb = new StringBuilder();
            for (LeafType t : leafManager.getAll()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(t.id());
            }
            sender.sendMessage(ChatColor.YELLOW + sb.toString());
            return;
        }

        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException ignored) {
                sender.sendMessage(ChatColor.RED + "Cantidad inválida, se usará 1.");
            }
        }

        if (amount <= 0) amount = 1;

        var item = leafManager.createLeafItem(type, amount);

        var inv = target.getInventory();
        var leftover = inv.addItem(item);
        if (!leftover.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }

        sender.sendMessage(ChatColor.GREEN + "Dadas " + amount + " hojas de tipo " + type.id()
                + " a " + target.getName() + ".");
        if (sender != target) {
            target.sendMessage(ChatColor.YELLOW + "Has recibido " + amount + " hojas de tipo "
                    + ChatColor.GREEN + type.displayName());
        }
    }
}
