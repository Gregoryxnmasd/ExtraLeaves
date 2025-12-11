package com.extracraft.extraleaves;

import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera un resourcepack en formato carpeta con:
 *
 *  - pack.mcmeta
 *  - assets/<namespace>/models/block/<leaf.id>.json
 *  - assets/<namespace>/models/item/<leaf.id>.json
 *  - assets/<namespace>/textures/block/<texture>.png
 *  - assets/minecraft/blockstates/azalea_leaves.json
 *  - assets/minecraft/models/item/azalea_leaves.json
 */
public class PackGenerator {

    private final ExtraLeavesPlugin plugin;
    private final LeafManager leafManager;
    private final String namespace;
    private final Path outputFolder;

    public PackGenerator(ExtraLeavesPlugin plugin, LeafManager leafManager) {
        this.plugin = plugin;
        this.leafManager = leafManager;

        FileConfiguration cfg = plugin.getConfig();
        this.namespace = cfg.getString("namespace", "extraleaves").toLowerCase();

        this.outputFolder = plugin.getDataFolder().toPath().resolve("resourcepack");
    }

    public Path getOutputFolder() {
        return outputFolder;
    }

    public void generatePack() throws IOException {
        deleteDirectoryIfExists(outputFolder);
        createDirectories();
        writePackMcmeta();
        writeLeafModelsAndTextures();
        writeAzaleaLeavesBlockstate();
        writeAzaleaLeavesItemModel();
    }

    private void deleteDirectoryIfExists(Path dir) throws IOException {
        if (!Files.exists(dir)) return;

        Files.walk(dir)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        plugin.getLogger().warning("No se pudo borrar " + path + ": " + e.getMessage());
                    }
                });
    }

    private void createDirectories() throws IOException {
        Files.createDirectories(outputFolder);

        Files.createDirectories(outputFolder.resolve("assets/minecraft/blockstates"));
        Files.createDirectories(outputFolder.resolve("assets/minecraft/models/item"));

        Files.createDirectories(outputFolder.resolve("assets/" + namespace + "/models/block"));
        Files.createDirectories(outputFolder.resolve("assets/" + namespace + "/models/item"));
        Files.createDirectories(outputFolder.resolve("assets/" + namespace + "/textures/block"));
    }

    private void writeString(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void writePackMcmeta() throws IOException {
        int packFormat = 34;

        String json = ""
                + "{\n"
                + "  \"pack\": {\n"
                + "    \"pack_format\": " + packFormat + ",\n"
                + "    \"description\": \"ExtraLeaves generated pack by " + plugin.getName() + "\"\n"
                + "  }\n"
                + "}\n";

        Path mcmeta = outputFolder.resolve("pack.mcmeta");
        writeString(mcmeta, json);
    }

    private void writeLeafModelsAndTextures() throws IOException {
        List<LeafType> leaves = leafManager.getAll();
        if (leaves.isEmpty()) {
            plugin.getLogger().warning("No hay hojas en LeafManager; no se generan modelos.");
            return;
        }

        Path texturesInputDir = plugin.getDataFolder().toPath().resolve("leaf_textures");
        Path texturesOutDir = outputFolder.resolve("assets/" + namespace + "/textures/block");
        Path modelsBlockDir = outputFolder.resolve("assets/" + namespace + "/models/block");
        Path modelsItemDir = outputFolder.resolve("assets/" + namespace + "/models/item");

        for (LeafType type : leaves) {
            String id = type.id();
            String textureName = type.texture();

            // Textura
            Path srcTexture = texturesInputDir.resolve(textureName + ".png");
            Path dstTexture = texturesOutDir.resolve(textureName + ".png");

            if (!Files.exists(srcTexture)) {
                plugin.getLogger().warning("No se encontró la textura para " + id
                        + " en " + srcTexture + ". Skipping texture copy.");
            } else {
                Files.createDirectories(dstTexture.getParent());
                Files.copy(srcTexture, dstTexture, StandardCopyOption.REPLACE_EXISTING);
            }

            // Modelo de bloque
            Path blockModelPath = modelsBlockDir.resolve(id + ".json");
            String blockModelJson = createBlockModelJson(textureName);
            writeString(blockModelPath, blockModelJson);

            // Modelo de item
            Path itemModelPath = modelsItemDir.resolve(id + ".json");
            String itemModelJson = createItemModelJson(id);
            writeString(itemModelPath, itemModelJson);
        }
    }

    private String createBlockModelJson(String textureName) {
        return ""
                + "{\n"
                + "  \"parent\": \"minecraft:block/cube_all\",\n"
                + "  \"textures\": {\n"
                + "    \"all\": \"" + namespace + ":block/" + textureName + "\"\n"
                + "  }\n"
                + "}\n";
    }

    private String createItemModelJson(String leafId) {
        return ""
                + "{\n"
                + "  \"parent\": \"" + namespace + ":block/" + leafId + "\"\n"
                + "}\n";
    }

    private void writeAzaleaLeavesBlockstate() throws IOException {
        Map<Integer, String> distanceToModel = new HashMap<>();
        for (LeafType type : leafManager.getAll()) {
            distanceToModel.put(type.distanceId(), namespace + ":block/" + type.id());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"variants\": {\n");

        boolean first = true;

        for (int distance = 1; distance <= 7; distance++) {
            for (boolean persistent : new boolean[]{false, true}) {
                for (boolean waterlogged : new boolean[]{false, true}) {

                    String key = String.format(
                            "    \"distance=%d,persistent=%s,waterlogged=%s\"",
                            distance,
                            persistent ? "true" : "false",
                            waterlogged ? "true" : "false"
                    );

                    String model = distanceToModel.get(distance);
                    String modelValue;
                    if (model != null && persistent && !waterlogged) {
                        modelValue = model;
                    } else {
                        modelValue = "minecraft:block/azalea_leaves";
                    }

                    if (!first) {
                        sb.append(",\n");
                    } else {
                        sb.append("\n");
                        first = false;
                    }

                    sb.append(key).append(": {\n");
                    sb.append("      \"model\": \"").append(modelValue).append("\"\n");
                    sb.append("    }");
                }
            }
        }

        sb.append("\n  }\n");
        sb.append("}\n");

        Path blockstatePath = outputFolder.resolve("assets/minecraft/blockstates/azalea_leaves.json");
        writeString(blockstatePath, sb.toString());
    }

    private void writeAzaleaLeavesItemModel() throws IOException {
        List<LeafType> leaves = leafManager.getAll();
        Path itemModelPath = outputFolder.resolve("assets/minecraft/models/item/azalea_leaves.json");

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"parent\": \"minecraft:item/generated\",\n");
        sb.append("  \"textures\": {\n");
        sb.append("    \"layer0\": \"minecraft:block/azalea_leaves\"\n");
        sb.append("  }");

        boolean anyOverride = false;
        for (LeafType type : leaves) {
            if (type.customModelData() <= 0) continue;
            if (!anyOverride) {
                sb.append(",\n");
                sb.append("  \"overrides\": [\n");
                anyOverride = true;
            } else {
                sb.append(",\n");
            }

            sb.append("    {\n");
            sb.append("      \"predicate\": { \"custom_model_data\": ").append(type.customModelData()).append(" },\n");
            sb.append("      \"model\": \"").append(namespace).append(":item/").append(type.id()).append("\"\n");
            sb.append("    }");
        }

        if (anyOverride) {
            sb.append("\n  ]\n");
        } else {
            sb.append("\n");
        }

        sb.append("}\n");

        writeString(itemModelPath, sb.toString());
    }
}
