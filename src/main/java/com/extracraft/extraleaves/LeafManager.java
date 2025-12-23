package com.extracraft.extraleaves;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Maneja:
 *  - tipos de hojas (config)
 *  - items de hojas
 *  - registro de posiciones de hojas custom por chunk (para drops, memoria, etc.)
 *  - integración con Iris (escanear chunks)
 *  - drops al romper
 *  - estabilidad visual (bloqueo de physics + estado persistente)
 */
public class LeafManager implements Listener {

    private final ExtraLeavesPlugin plugin;

    private static final int RESKIN_VIEW_RADIUS = 32;
    private static final int MAX_RESENDS_PER_TICK = 800;

    private static final int PARTICLE_TICK_INTERVAL = 10;
    private static final int PARTICLE_CHUNK_RADIUS = 2;
    private static final int MAX_PARTICLES_PER_TICK = 40;
    private static final int MAX_PARTICLES_PER_PLAYER = 3;
    private static final double PARTICLE_PLAYER_RADIUS = 32.0;
    private static final double PARTICLE_PLAYER_RADIUS_SQUARED = PARTICLE_PLAYER_RADIUS * PARTICLE_PLAYER_RADIUS;
    private static final float PARTICLE_SIZE = 1.0f;

    // Bloque host real (Iris + plugin usan AZALEA_LEAVES)
    private final Material hostMaterial = Material.AZALEA_LEAVES;

    // Claves PDC
    private final NamespacedKey itemIdKey;
    private final NamespacedKey chunkDataKey;

    // Tipos de hojas desde config
    private final Map<String, LeafType> byId = new HashMap<>();
    private final Map<Integer, LeafType> byDistance = new HashMap<>();

    // ChunkKey -> (BlockKey -> LeafEntry)
    private final Map<ChunkKey, Map<BlockKey, LeafEntry>> leavesByChunk = new HashMap<>();

    // Drops al romper con la mano
    private static class HandDrop {
        final Material material;
        final int min;
        final int max;
        final double chance;

        HandDrop(Material material, int min, int max, double chance) {
            this.material = material;
            this.min = min;
            this.max = max;
            this.chance = chance;
        }
    }

    private final List<HandDrop> handDrops = new ArrayList<>();

    // Claves auxiliares para map
    private record ChunkKey(UUID worldId, int x, int z) {}
    private record BlockKey(int x, int y, int z) {}
    private record LeafEntry(LeafType type, boolean persistent) {}

    public LeafManager(ExtraLeavesPlugin plugin) {
        this.plugin = plugin;
        this.itemIdKey = new NamespacedKey(plugin, "leaf_id");
        this.chunkDataKey = new NamespacedKey(plugin, "leaf_blocks");

        loadConfigLeaves();
        loadHandDropsFromConfig();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Reconstruir datos persistidos (hojas colocadas antes del restart)
        Bukkit.getScheduler().runTask(plugin, this::rebuildLoadedChunks);

        // Reloj ligero para procesar la cola de repintados sin burst masivos
        Bukkit.getScheduler().runTaskTimer(plugin, this::processReskinQueue, 1L, 1L);

        // Partículas suaves de hojas cayendo (solo hojas colocadas)
        Bukkit.getScheduler().runTaskTimer(plugin, this::spawnLeafParticles, PARTICLE_TICK_INTERVAL, PARTICLE_TICK_INTERVAL);
    }

    // ==================== CONFIG ====================

    public Material getHostMaterial() {
        return hostMaterial;
    }

    private void loadConfigLeaves() {
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("leaves");

        if (sec == null) {
            plugin.getLogger().warning("No hay sección 'leaves' en config.yml");
            return;
        }

        for (String key : sec.getKeys(false)) {
            ConfigurationSection leafSec = sec.getConfigurationSection(key);
            if (leafSec == null) continue;

            String id = leafSec.getString("id", key);
            String displayName = leafSec.getString("display-name", id);
            String texture = leafSec.getString("texture", id);
            int distanceId = leafSec.getInt("distance-id", 2);
            int customModelData = leafSec.getInt("custom-model-data", 0);
            Color particleColor = parseParticleColor(leafSec.getString("particle-color", "#FFFFFF"), id);
            int particleAmount = Math.max(0, leafSec.getInt("particle-amount", 1));

            if (distanceId < 1 || distanceId > 7) {
                plugin.getLogger().warning("distance-id inválido en " + id + " (1..7). Se ignora.");
                continue;
            }
            if (byDistance.containsKey(distanceId)) {
                plugin.getLogger().warning("distance-id " + distanceId + " duplicado, se ignora " + id);
                continue;
            }

            BlockData visual = Material.AZALEA_LEAVES.createBlockData();
            if (!(visual instanceof Leaves leaves)) {
                plugin.getLogger().severe("AZALEA_LEAVES no es Leaves, no se puede usar.");
                continue;
            }
            // Estado "visual" que mandaremos al cliente
            leaves.setPersistent(true);
            leaves.setWaterlogged(false);
            leaves.setDistance(distanceId);

            LeafType type = new LeafType(
                    id.toLowerCase(Locale.ROOT),
                    displayName,
                    distanceId,
                    texture,
                    customModelData,
                    visual,
                    particleColor,
                    particleAmount
            );

            byId.put(type.id(), type);
            byDistance.put(distanceId, type);
        }

        plugin.getLogger().info("ExtraLeaves: cargados " + byId.size() + " tipos de hojas.");
    }

    private Color parseParticleColor(String raw, String leafId) {
        if (raw == null || raw.isBlank()) {
            return Color.WHITE;
        }

        String trimmed = raw.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }

        if (trimmed.matches("(?i)[0-9a-f]{6}")) {
            int rgb = Integer.parseInt(trimmed, 16);
            return Color.fromRGB(rgb);
        }

        String[] parts = trimmed.split(",");
        if (parts.length == 3) {
            try {
                int r = clampColorComponent(Integer.parseInt(parts[0].trim()));
                int g = clampColorComponent(Integer.parseInt(parts[1].trim()));
                int b = clampColorComponent(Integer.parseInt(parts[2].trim()));
                return Color.fromRGB(r, g, b);
            } catch (NumberFormatException ignored) {
                // fallback below
            }
        }

        plugin.getLogger().warning("particle-color inválido para " + leafId + ": " + raw + " (usando blanco).");
        return Color.WHITE;
    }

    private int clampColorComponent(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private void loadHandDropsFromConfig() {
        handDrops.clear();

        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("hand-drops");
        if (sec == null) {
            plugin.getLogger().info("No hay sección 'hand-drops' (sin loots especiales).");
            return;
        }

        for (String key : sec.getKeys(false)) {
            ConfigurationSection dropSec = sec.getConfigurationSection(key);
            if (dropSec == null) continue;

            String matName = dropSec.getString("material");
            if (matName == null) continue;

            Material mat = Material.matchMaterial(matName);
            if (mat == null) {
                plugin.getLogger().warning("Material inválido en hand-drops: " + matName);
                continue;
            }

            int min = dropSec.getInt("min", 1);
            int max = dropSec.getInt("max", min);
            double chance = dropSec.getDouble("chance", 0.0);

            if (chance <= 0.0) continue;
            if (min <= 0 || max < min) continue;

            handDrops.add(new HandDrop(mat, min, max, chance));
        }

        plugin.getLogger().info("ExtraLeaves: cargados " + handDrops.size() + " drops de mano.");
    }

    public void reload() {
        byId.clear();
        byDistance.clear();
        leavesByChunk.clear();
        handDrops.clear();

        plugin.reloadConfig();
        loadConfigLeaves();
        loadHandDropsFromConfig();
        rebuildLoadedChunks();
    }

    public List<LeafType> getAll() {
        return List.copyOf(byId.values());
    }

    public LeafType find(String id) {
        if (id == null) return null;
        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    // ==================== ITEMS ====================

    public ItemStack createLeafItem(LeafType type, int amount) {
        ItemStack item = new ItemStack(hostMaterial, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', type.displayName()));
        meta.getPersistentDataContainer().set(itemIdKey, PersistentDataType.STRING, type.id());

        if (type.customModelData() > 0) {
            meta.setCustomModelData(type.customModelData());
        }

        item.setItemMeta(meta);
        return item;
    }

    private LeafType getLeafTypeFromItem(ItemStack item) {
        if (item == null) return null;
        if (item.getType() != hostMaterial) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String id = meta.getPersistentDataContainer().get(itemIdKey, PersistentDataType.STRING);
        if (id == null) return null;

        return byId.get(id.toLowerCase(Locale.ROOT));
    }

    // ==================== MAPA DE HOJAS ====================

    private ChunkKey chunkKey(Chunk chunk) {
        return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
    }

    private BlockKey blockPos(Block block) {
        return new BlockKey(block.getX(), block.getY(), block.getZ());
    }


    private Map<BlockKey, LeafEntry> getChunkMap(Chunk chunk) {
        ChunkKey key = chunkKey(chunk);
        Map<BlockKey, LeafEntry> existing = leavesByChunk.get(key);
        if (existing != null) {
            return existing;
        }

        Map<BlockKey, LeafEntry> loaded = loadChunkData(chunk);
        leavesByChunk.put(key, loaded);
        return loaded;
    }

    private void setLeafAt(Block block, LeafType type, boolean persistent, boolean savePersistentChanges) {
        Chunk chunk = block.getChunk();
        Map<BlockKey, LeafEntry> map = getChunkMap(chunk);
        BlockKey pos = blockPos(block);

        LeafEntry previous = map.put(pos, new LeafEntry(type, persistent));
        applyLeafState(block, type);

        if (!savePersistentChanges) {
            return;
        }

        boolean previousPersistent = previous != null && previous.persistent();
        if (persistent || previousPersistent) {
            saveChunkData(chunk, map);
        }
    }

    private void registerLeafAt(Block block, LeafType type) {
        setLeafAt(block, type, true, true);
    }

    private void unregisterLeafAt(Block block) {
        Chunk chunk = block.getChunk();
        Map<BlockKey, LeafEntry> map = leavesByChunk.get(chunkKey(chunk));
        if (map == null) return;

        LeafEntry removed = map.remove(blockPos(block));
        if (removed != null && removed.persistent()) {
            saveChunkData(chunk, map);
        }
    }

    private void saveChunkData(Chunk chunk, Map<BlockKey, LeafEntry> map) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();

        if (map == null) {
            pdc.remove(chunkDataKey);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BlockKey, LeafEntry> e : map.entrySet()) {
            LeafEntry entry = e.getValue();
            if (!entry.persistent()) continue;

            BlockKey pos = e.getKey();
            sb.append(pos.x()).append(",")
                    .append(pos.y()).append(",")
                    .append(pos.z()).append(":")
                    .append(entry.type().id()).append(";");
        }

        if (sb.length() == 0) {
            pdc.remove(chunkDataKey);
        } else {
            pdc.set(chunkDataKey, PersistentDataType.STRING, sb.toString());
        }
    }

    /**
     * Solo desde el mapa (sin autodetección).
     */
    public LeafType getLeafAt(World world, int x, int y, int z) {
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        Map<BlockKey, LeafEntry> map = getChunkMap(chunk);
        LeafEntry entry = map.get(new BlockKey(x, y, z));
        return entry == null ? null : entry.type();
    }

    /**
     * Devuelve el tipo de hoja o lo detecta y registra si es una azalea host.
     *  - Se usa como fallback si no estaba en el mapa (ej: hojas de Iris).
     */
    public LeafType getOrDetectLeafAt(World world, int x, int y, int z) {
        LeafType type = getLeafAt(world, x, y, z);
        if (type != null) return type;

        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != hostMaterial) {
            return null;
        }

        BlockData data = block.getBlockData();
        if (data instanceof Leaves leaves) {
            // Mapeamos por distance-id (para hojas generadas por Iris)
            type = byDistance.get(leaves.getDistance());
        }

        if (type == null && !byId.isEmpty()) {
            // Fallback: primera hoja definida en config
            type = byId.values().iterator().next();
        }

        if (type != null) {
            setLeafAt(block, type, false, false);
        }

        return type;
    }

    // ==================== EVENTOS ====================

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack inHand = event.getItemInHand();
        LeafType type = getLeafTypeFromItem(inHand);
        if (type == null) return;

        Block block = event.getBlockPlaced();
        BlockData visual = type.visualData().clone();
        block.setBlockData(visual, false);

        registerLeafAt(block, type);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != hostMaterial) return;

        World world = block.getWorld();
        int bx = block.getX();
        int by = block.getY();
        int bz = block.getZ();

        LeafType type = getOrDetectLeafAt(world, bx, by, bz);
        if (type == null) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        boolean shears = (tool.getType() == Material.SHEARS);

        event.setDropItems(false);

        unregisterLeafAt(block);

        if (shears) {
            world.dropItemNaturally(block.getLocation(), createLeafItem(type, 1));
        } else {
            dropHandLoot(world, block.getLocation());
        }

    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkKey key = chunkKey(chunk);

        Map<BlockKey, LeafEntry> map = loadChunkData(chunk);
        leavesByChunk.put(key, map);

        // Detectar hojas de Iris sin persistirlas
        scanChunkForHostLeaves(chunk, map);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        leavesByChunk.remove(chunkKey(event.getChunk()));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Nada que hacer: los bloques ya están en el estado correcto y no necesitan "reskins".
    }

    // ==================== ESCANEO DE CHUNK (IRIS) ====================

    /**
     * Escanea un chunk para encontrar AZALEA_LEAVES de Iris y registrarlas si
     * aún no están en el mapa (no pisa hojas ya registradas por jugadores).
     */
    private void scanChunkForHostLeaves(Chunk chunk, Map<BlockKey, LeafEntry> map) {
        World world = chunk.getWorld();

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int y = minY; y < maxY; y++) {
                    int x = baseX + dx;
                    int z = baseZ + dz;

                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() != hostMaterial) continue;

                    BlockKey pos = new BlockKey(x, y, z);
                    if (map.containsKey(pos)) {
                        // Ya hay una hoja registrada aquí (probablemente de jugador)
                        continue;
                    }

                    BlockData data = block.getBlockData();
                    LeafType type = null;

                    if (data instanceof Leaves leaves) {
                        type = byDistance.get(leaves.getDistance());
                    }

                    if (type == null && !byId.isEmpty()) {
                        type = byId.values().iterator().next();
                    }

                    if (type != null) {
                        map.put(pos, new LeafEntry(type, false));
                        applyLeafState(block, type);
                    }
                }
            }
        }
    }

    // ==================== DROPS MANO ====================

    private void dropHandLoot(World world, org.bukkit.Location loc) {
        if (handDrops.isEmpty()) return;

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (HandDrop def : handDrops) {
            if (rnd.nextDouble() <= def.chance) {
                int amount;
                if (def.min == def.max) {
                    amount = def.min;
                } else {
                    amount = def.min + rnd.nextInt(def.max - def.min + 1);
                }
                world.dropItemNaturally(loc, new ItemStack(def.material, amount));
            }
        }
    }

    


    private void applyLeafState(Block block, LeafType type) {
        if (type == null) return;

        if (block.getType() != hostMaterial) {
            block.setType(hostMaterial, false);
        }

        BlockData data = block.getBlockData();
        if (!(data instanceof Leaves leaves)) return;

        boolean changed = false;

        if (leaves.getDistance() != type.distanceId()) {
            leaves.setDistance(type.distanceId());
            changed = true;
        }
        if (!leaves.isPersistent()) {
            leaves.setPersistent(true);
            changed = true;
        }
        if (leaves.isWaterlogged()) {
            leaves.setWaterlogged(false);
            changed = true;
        }

        if (changed) {
            block.setBlockData(leaves, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeafPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != hostMaterial) return;

        LeafType type = getOrDetectLeafAt(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (type == null) return;

        applyLeafState(block, type);
        event.setCancelled(true);
    }

    private void rebuildLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                leavesByChunk.remove(chunkKey(chunk));
                ChunkLoadEvent fake = new ChunkLoadEvent(chunk, false);
                onChunkLoad(fake);
            }
        }
    }

    private Map<BlockKey, LeafEntry> loadChunkData(Chunk chunk) {
        Map<BlockKey, LeafEntry> map = new HashMap<>();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String raw = pdc.get(chunkDataKey, PersistentDataType.STRING);

        int skipped = 0;

        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(";");
            for (String part : parts) {
                if (part.isEmpty()) continue;
                String[] pv = part.split(":");
                if (pv.length != 2) continue;

                String[] coords = pv[0].split(",");
                if (coords.length != 3) continue;

                int x, y, z;
                try {
                    x = Integer.parseInt(coords[0]);
                    y = Integer.parseInt(coords[1]);
                    z = Integer.parseInt(coords[2]);
                } catch (NumberFormatException ex) {
                    continue;
                }

                String id = pv[1];
                LeafType type = byId.get(id);
                if (type == null) continue;

                BlockKey pos = new BlockKey(x, y, z);
                if ((pos.x() >> 4) != chunk.getX() || (pos.z() >> 4) != chunk.getZ()) {
                    skipped++;
                    continue;
                }

                map.put(pos, new LeafEntry(type, true));
                applyLeafState(chunk.getWorld().getBlockAt(pos.x(), pos.y(), pos.z()), type);
            }
        }

        if (skipped > 0) {
            plugin.getLogger().warning("Ignoradas " + skipped + " hojas persistidas fuera del chunk " + chunk.getX() + "," + chunk.getZ());
        }

        return map;
    }

    /**
     * Fallback manual refresher usado por comandos o depuración externa.
     * Reaplica el estado custom a todas las hojas rastreadas en chunks cargados.
     */
    public void processReskinQueue() {
        for (Map.Entry<ChunkKey, Map<BlockKey, LeafEntry>> entry : leavesByChunk.entrySet()) {
            ChunkKey chunkKey = entry.getKey();
            World world = Bukkit.getWorld(chunkKey.worldId());
            if (world == null) continue;

            if (!world.isChunkLoaded(chunkKey.x(), chunkKey.z())) {
                continue;
            }

            Chunk chunk = world.getChunkAt(chunkKey.x(), chunkKey.z());
            Map<BlockKey, LeafEntry> blocks = entry.getValue();
            for (Map.Entry<BlockKey, LeafEntry> blockEntry : blocks.entrySet()) {
                BlockKey pos = blockEntry.getKey();
                LeafEntry leafEntry = blockEntry.getValue();
                applyLeafState(world.getBlockAt(pos.x(), pos.y(), pos.z()), leafEntry.type());
            }
        }
    }

    private void spawnLeafParticles() {
        if (leavesByChunk.isEmpty()) {
            return;
        }

        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) {
            return;
        }

        int remaining = MAX_PARTICLES_PER_TICK;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (Player player : players) {
            if (remaining <= 0) {
                break;
            }
            if (!player.isOnline() || player.isDead()) {
                continue;
            }

            Location playerLoc = player.getLocation();
            World world = playerLoc.getWorld();
            if (world == null) continue;

            int baseChunkX = playerLoc.getBlockX() >> 4;
            int baseChunkZ = playerLoc.getBlockZ() >> 4;

            int perPlayer = Math.min(MAX_PARTICLES_PER_PLAYER, remaining);
            for (int attempt = 0; attempt < perPlayer; attempt++) {
                int cx = baseChunkX + rnd.nextInt(-PARTICLE_CHUNK_RADIUS, PARTICLE_CHUNK_RADIUS + 1);
                int cz = baseChunkZ + rnd.nextInt(-PARTICLE_CHUNK_RADIUS, PARTICLE_CHUNK_RADIUS + 1);
                Map<BlockKey, LeafEntry> map = leavesByChunk.get(new ChunkKey(world.getUID(), cx, cz));
                if (map == null || map.isEmpty()) {
                    continue;
                }

                Map.Entry<BlockKey, LeafEntry> selection = pickRandomLeaf(map, rnd);
                if (selection == null) {
                    continue;
                }

                LeafEntry entry = selection.getValue();
                if (!entry.persistent()) {
                    continue;
                }

                BlockKey pos = selection.getKey();
                if (playerLoc.distanceSquared(new Location(world, pos.x() + 0.5, pos.y() + 0.5, pos.z() + 0.5))
                        > PARTICLE_PLAYER_RADIUS_SQUARED) {
                    continue;
                }

                Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                if (block.getType() != hostMaterial) {
                    continue;
                }

                int amount = Math.min(entry.type().particleAmount(), remaining);
                if (amount <= 0) {
                    continue;
                }

                for (int i = 0; i < amount; i++) {
                    spawnLeafParticle(player, entry.type(), pos, rnd);
                }
                remaining -= amount;
                if (remaining <= 0) {
                    break;
                }
            }
        }
    }

    private Map.Entry<BlockKey, LeafEntry> pickRandomLeaf(Map<BlockKey, LeafEntry> map, ThreadLocalRandom rnd) {
        if (map.isEmpty()) return null;
        int target = rnd.nextInt(map.size());
        int index = 0;
        for (Map.Entry<BlockKey, LeafEntry> entry : map.entrySet()) {
            if (index++ == target) {
                return entry;
            }
        }
        return null;
    }

    private void spawnLeafParticle(Player player, LeafType type, BlockKey pos, ThreadLocalRandom rnd) {
        Color color = type.particleColor();
        Particle.DustOptions dust = new Particle.DustOptions(color, PARTICLE_SIZE);

        double x = pos.x() + 0.25 + rnd.nextDouble() * 0.5;
        double y = pos.y() + 0.2 + rnd.nextDouble() * 0.6;
        double z = pos.z() + 0.25 + rnd.nextDouble() * 0.5;

        player.spawnParticle(
                Particle.DUST,
                x,
                y,
                z,
                1,
                0.05,
                0.1,
                0.05,
                0.0,
                dust
        );
    }
}
