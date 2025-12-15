package com.extracraft.extraleaves;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
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
 *  - repintado visual (sendAllVisualsAround + bursts hardcodeados)
 */
public class LeafManager implements Listener {

    private final ExtraLeavesPlugin plugin;

    private static final int RESKIN_VIEW_RADIUS = 32;
    private static final int MAX_RESENDS_PER_TICK = 800;

    // Bloque host real (Iris + plugin usan AZALEA_LEAVES)
    private final Material hostMaterial = Material.AZALEA_LEAVES;

    // Claves PDC
    private final NamespacedKey itemIdKey;
    private final NamespacedKey chunkDataKey;

    // Tipos de hojas desde config
    private final Map<String, LeafType> byId = new HashMap<>();
    private final Map<Integer, LeafType> byDistance = new HashMap<>();

    // ChunkKey -> (BlockPos -> LeafType)
    private final Map<ChunkKey, Map<BlockPos, LeafType>> leavesByChunk = new HashMap<>();

    // Cola de posiciones que necesitan repintado repetido durante unos ticks
    private final Map<BlockKey, Integer> reskinQueue = new HashMap<>();

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

    // Tareas de bursts visuales por jugador
    private final Map<UUID, Integer> visualBurstTasks = new HashMap<>();

    // Bursts de estabilidad local tras roturas (para evitar flickering prolongado)
    private static class StabilityBurst {
        int taskId;
        int remaining;
    }

    private record StabilityKey(UUID worldId, int x, int y, int z, int radius) {}

    private final Map<StabilityKey, StabilityBurst> stabilityBursts = new HashMap<>();

    // Claves auxiliares para map
    private record ChunkKey(UUID worldId, int x, int z) {}
    private record BlockPos(int x, int y, int z) {}
    private record BlockKey(UUID worldId, int x, int y, int z) {}

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
        Bukkit.getScheduler().runTaskTimer(plugin, this::processReskinQueue, 1L, 2L);
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
                    visual
            );

            byId.put(type.id(), type);
            byDistance.put(distanceId, type);
        }

        plugin.getLogger().info("ExtraLeaves: cargados " + byId.size() + " tipos de hojas.");
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

    private BlockPos blockPos(Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }

    private Map<BlockPos, LeafType> getOrCreateChunkMap(ChunkKey key) {
        return leavesByChunk.computeIfAbsent(key, k -> new HashMap<>());
    }

    private void setLeafAt(World world, int x, int y, int z, LeafType type) {
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        ChunkKey key = chunkKey(chunk);
        Map<BlockPos, LeafType> map = getOrCreateChunkMap(key);
        map.put(new BlockPos(x, y, z), type);
        saveChunkData(chunk, map);
    }

    private void registerLeafAt(Block block, LeafType type) {
        setLeafAt(block.getWorld(), block.getX(), block.getY(), block.getZ(), type);
    }

    private void unregisterLeafAt(Block block) {
        Chunk chunk = block.getChunk();
        ChunkKey key = chunkKey(chunk);
        Map<BlockPos, LeafType> map = leavesByChunk.get(key);
        if (map == null) return;
        map.remove(blockPos(block));
        saveChunkData(chunk, map);
    }

    private void saveChunkData(Chunk chunk, Map<BlockPos, LeafType> map) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();

        if (map == null || map.isEmpty()) {
            pdc.remove(chunkDataKey);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<BlockPos, LeafType> e : map.entrySet()) {
            BlockPos pos = e.getKey();
            LeafType type = e.getValue();
            sb.append(pos.x()).append(",")
                    .append(pos.y()).append(",")
                    .append(pos.z()).append(":")
                    .append(type.id()).append(";");
        }

        pdc.set(chunkDataKey, PersistentDataType.STRING, sb.toString());
    }

    /**
     * Solo desde el mapa (sin autodetección).
     */
    public LeafType getLeafAt(World world, int x, int y, int z) {
        Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
        ChunkKey key = chunkKey(chunk);
        Map<BlockPos, LeafType> map = leavesByChunk.get(key);
        if (map == null) return null;
        return map.get(new BlockPos(x, y, z));
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
            // Aseguramos persistent=true
            if (data instanceof Leaves leaves) {
                if (!leaves.isPersistent()) {
                    leaves.setPersistent(true);
                    block.setBlockData(leaves, false);
                }
            }
            setLeafAt(world, x, y, z, type);
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
        block.setType(hostMaterial, false);

        BlockData data = block.getBlockData();
        if (data instanceof Leaves leaves) {
            leaves.setPersistent(true);
            block.setBlockData(leaves, false);
        }

        registerLeafAt(block, type);

        Player p = event.getPlayer();
        p.sendBlockChange(block.getLocation(), type.visualData());
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
        boolean silk = tool.containsEnchantment(Enchantment.SILK_TOUCH);

        event.setDropItems(false);

        unregisterLeafAt(block);

        if (shears) {
            world.dropItemNaturally(block.getLocation(), createLeafItem(type, 1));
        } else {
            dropHandLoot(world, block.getLocation());
        }

        // Estabilización optimizada: repintamos solo las hojas registradas cercanas y
        // las mantenemos en la cola durante unos ticks para cubrir los recalculos de distance.
        org.bukkit.Location center = block.getLocation();
        repaintHostLeavesAround(center, 14, null);
        queueRegisteredLeavesAround(center, 14, 18);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        ChunkKey key = chunkKey(chunk);

        Map<BlockPos, LeafType> map = leavesByChunk.get(key);
        if (map == null) {
            map = new HashMap<>();
            leavesByChunk.put(key, map);
        }

        // 1) Cargar datos persistidos (hojas colocadas por jugadores / sesiones anteriores)
        map.clear();
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        String raw = pdc.get(chunkDataKey, PersistentDataType.STRING);

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

                BlockPos pos = new BlockPos(x, y, z);
                map.put(pos, type);
            }
        }

        // 2) Escanear SIEMPRE el chunk del mundo para detectar hojas de Iris (azalea)
        scanChunkForHostLeaves(chunk, map);

        // 3) Guardar el mapa completo (jugadores + Iris)
        saveChunkData(chunk, map);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        // Opcional limpiar leavesByChunk, pero no es obligatorio.
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Nada más entrar, burst de repintado para evitar ver azaleas durante la carga inicial
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            startVisualBurst(player, 20, 16);
        }, 20L);
    }

    // ==================== ESCANEO DE CHUNK (IRIS) ====================

    /**
     * Escanea un chunk para encontrar AZALEA_LEAVES de Iris y registrarlas si
     * aún no están en el mapa (no pisa hojas ya registradas por jugadores).
     */
    private void scanChunkForHostLeaves(Chunk chunk, Map<BlockPos, LeafType> map) {
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

                    BlockPos pos = new BlockPos(x, y, z);
                    if (map.containsKey(pos)) {
                        // Ya hay una hoja registrada aquí (probablemente de jugador)
                        continue;
                    }

                    BlockData data = block.getBlockData();
                    LeafType type = null;

                    if (data instanceof Leaves leaves) {
                        type = byDistance.get(leaves.getDistance());
                        if (!leaves.isPersistent()) {
                            leaves.setPersistent(true);
                            block.setBlockData(leaves, false);
                        }
                    }

                    if (type == null && !byId.isEmpty()) {
                        type = byId.values().iterator().next();
                    }

                    if (type != null) {
                        map.put(pos, type);
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

    private void queueReskin(Block block, int durationTicks) {
        queueReskin(block.getWorld(), block.getX(), block.getY(), block.getZ(), durationTicks);
    }

    private void queueReskin(World world, int x, int y, int z, int durationTicks) {
        if (durationTicks <= 0 || world == null) return;
        BlockKey key = new BlockKey(world.getUID(), x, y, z);
        reskinQueue.merge(key, durationTicks, Math::max);
    }

    private void queueRegisteredLeavesAround(org.bukkit.Location center, int radius, int durationTicks) {
        World world = center.getWorld();
        if (world == null || radius <= 0 || durationTicks <= 0) return;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int r = radius;
        int rSq = r * r;
        int minY = Math.max(world.getMinHeight(), cy - r);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + r);

        int minChunkX = (cx - r) >> 4;
        int maxChunkX = (cx + r) >> 4;
        int minChunkZ = (cz - r) >> 4;
        int maxChunkZ = (cz + r) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Map<BlockPos, LeafType> chunkMap = leavesByChunk.get(new ChunkKey(world.getUID(), chunkX, chunkZ));
                if (chunkMap == null || chunkMap.isEmpty()) continue;

                for (BlockPos pos : chunkMap.keySet()) {
                    int dx = pos.x() - cx;
                    int dz = pos.z() - cz;
                    if (dx * dx + dz * dz > rSq) continue;
                    if (pos.y() < minY || pos.y() > maxY) continue;

                    queueReskin(world, pos.x(), pos.y(), pos.z(), durationTicks);
                }
            }
        }
    }

    // ==================== VISUALES ====================

    /**
     * HARDCORE:
     * Escanea TODOS los bloques en un radio alrededor del jugador,
     * y cualquier AZALEA_LEAVES se fuerza a su textura custom correspondiente.
     *
     * - Usa getOrDetectLeafAt => si no estaba registrada, la detecta por distance o le asigna una.
     */
    public void sendAllVisualsAround(Player player, int radius) {
        repaintHostLeavesAround(player.getLocation(), radius, player);
    }

    /**
     * Tras una rotura forzada por Iris, Mojang recalcula distances durante varios ticks
     * y puede mandar múltiples block-changes vanilla. Este estabilizador reaplica la
     * textura custom cada "intervalTicks" mientras dure la ventana de "durationTicks".
     */
    private void repaintHostLeavesAround(org.bukkit.Location center, int radius, Player onlyPlayer) {
        World world = center.getWorld();
        if (world == null) return;

        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        int r = radius;
        int rSq = r * r;

        int yRadius = Math.min(r, 16);
        int minY = Math.max(world.getMinHeight(), cy - yRadius);
        int maxY = Math.min(world.getMaxHeight() - 1, cy + yRadius);

        List<Player> viewers;
        if (onlyPlayer != null) {
            if (!onlyPlayer.isOnline()) return;
            viewers = Collections.singletonList(onlyPlayer);
        } else {
            viewers = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                double dx = p.getLocation().getX() - center.getX();
                double dz = p.getLocation().getZ() - center.getZ();
                if (dx * dx + dz * dz <= rSq) {
                    viewers.add(p);
                }
            }
            if (viewers.isEmpty()) return;
        }

        int minChunkX = (cx - r) >> 4;
        int maxChunkX = (cx + r) >> 4;
        int minChunkZ = (cz - r) >> 4;
        int maxChunkZ = (cz + r) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Map<BlockPos, LeafType> chunkMap = leavesByChunk.get(new ChunkKey(world.getUID(), chunkX, chunkZ));
                if (chunkMap == null || chunkMap.isEmpty()) continue;

                for (Map.Entry<BlockPos, LeafType> entry : chunkMap.entrySet()) {
                    BlockPos pos = entry.getKey();
                    int dx = pos.x() - cx;
                    int dz = pos.z() - cz;
                    if (dx * dx + dz * dz > rSq) continue;
                    if (pos.y() < minY || pos.y() > maxY) continue;

                    Block block = world.getBlockAt(pos.x(), pos.y(), pos.z());
                    if (block.getType() != hostMaterial) continue;

                    sendVisual(block, entry.getValue(), viewers);
                }
            }
        }
    }

    /**
     * Inicia un "burst" visual para un jugador:
     * durante 'ticks' repite sendAllVisualsAround cada tick para evitar que
     * los recalculos de distance de Mojang dejen las hojas como azalea.
     */
    public void startVisualBurst(Player player, int ticks, int radius) {
        if (ticks <= 0) return;
        UUID uuid = player.getUniqueId();

        // Cancelar burst anterior si existe
        Integer oldTask = visualBurstTasks.remove(uuid);
        if (oldTask != null) {
            Bukkit.getScheduler().cancelTask(oldTask);
        }

        int[] holder = new int[1];
        holder[0] = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
            int remaining = ticks;

            @Override
            public void run() {
                if (!player.isOnline() || remaining-- <= 0) {
                    Bukkit.getScheduler().cancelTask(holder[0]);
                    visualBurstTasks.remove(uuid);
                    return;
                }
                sendAllVisualsAround(player, radius);
            }
        }, 0L, 1L); // delay 0L para repintar lo antes posible

        visualBurstTasks.put(uuid, holder[0]);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeafPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != hostMaterial) return;

        LeafType type = getOrDetectLeafAt(block.getWorld(), block.getX(), block.getY(), block.getZ());
        if (type == null) return;

        queueReskin(block, 12);
        sendVisual(block, type, getNearbyViewers(block.getLocation(), RESKIN_VIEW_RADIUS));
    }

    private List<Player> getNearbyViewers(org.bukkit.Location center, int radius) {
        World world = center.getWorld();
        if (world == null) return Collections.emptyList();

        int rSq = radius * radius;
        List<Player> viewers = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (!player.isOnline()) continue;
            double dx = player.getLocation().getX() - center.getX();
            double dz = player.getLocation().getZ() - center.getZ();
            if (dx * dx + dz * dz <= rSq) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    private void sendVisual(Block block, LeafType type, Collection<Player> viewers) {
        if (viewers == null || viewers.isEmpty()) return;
        org.bukkit.Location loc = block.getLocation();
        for (Player viewer : viewers) {
            if (viewer.isOnline()) {
                viewer.sendBlockChange(loc, type.visualData());
            }
        }
    }

    private void processReskinQueue() {
        if (reskinQueue.isEmpty()) return;

        int processed = 0;
        Iterator<Map.Entry<BlockKey, Integer>> it = reskinQueue.entrySet().iterator();
        while (it.hasNext() && processed < MAX_RESENDS_PER_TICK) {
            Map.Entry<BlockKey, Integer> entry = it.next();
            BlockKey key = entry.getKey();
            World world = Bukkit.getWorld(key.worldId());
            if (world == null) {
                it.remove();
                continue;
            }

            Block block = world.getBlockAt(key.x(), key.y(), key.z());
            if (block.getType() != hostMaterial) {
                it.remove();
                continue;
            }

            LeafType type = getOrDetectLeafAt(world, key.x(), key.y(), key.z());
            if (type == null) {
                it.remove();
                continue;
            }

            sendVisual(block, type, getNearbyViewers(block.getLocation(), RESKIN_VIEW_RADIUS));

            int remaining = entry.getValue() - 2; // reloj cada 2 ticks
            if (remaining <= 0) {
                it.remove();
            } else {
                entry.setValue(remaining);
            }

            processed++;
        }
    }

    private void rebuildLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkLoadEvent fake = new ChunkLoadEvent(chunk, false);
                onChunkLoad(fake);
            }
        }
    }
}
