package com.extracraft.extraleaves;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

public class ExtraLeavesPlugin extends JavaPlugin {

    private LeafManager leafManager;
    private PackGenerator packGenerator;

    @Override
    public void onEnable() {
        // Config + carpetas
        saveDefaultConfig();

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("No se pudo crear la carpeta de datos del plugin.");
        }

        File texturesDir = new File(getDataFolder(), "leaf_textures");
        if (!texturesDir.exists() && !texturesDir.mkdirs()) {
            getLogger().warning("No se pudo crear la carpeta leaf_textures.");
        }

        // Inicializar managers
        this.leafManager = new LeafManager(this);
        this.packGenerator = new PackGenerator(this, leafManager);

        // Comando /extraleaves
        PluginCommand cmd = getCommand("extraleaves");
        if (cmd != null) {
            cmd.setExecutor(new ExtraLeavesCommand(this, leafManager, packGenerator));
            cmd.setTabCompleter(new ExtraLeavesTabCompleter(leafManager));
        } else {
            getLogger().warning("No se encontró el comando /extraleaves en plugin.yml");
        }

        // Generar resourcepack
        try {
            packGenerator.generatePack();
            getLogger().info("Resourcepack de ExtraLeaves generado en: "
                    + packGenerator.getOutputFolder().toAbsolutePath());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "No se pudo generar el resourcepack de ExtraLeaves al iniciar.", e);
        }

        getLogger().info("ExtraLeaves habilitado. Bloque host: " + leafManager.getHostMaterial().name());
    }

    @Override
    public void onDisable() {
        getLogger().info("ExtraLeaves deshabilitado.");
    }

    public LeafManager getLeafManager() {
        return leafManager;
    }

    public PackGenerator getPackGenerator() {
        return packGenerator;
    }
}
