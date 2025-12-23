package com.extracraft.extraleaves;

import org.bukkit.Color;
import org.bukkit.block.data.BlockData;

/**
 * Representa un tipo de hoja custom.
 *
 * Campos:
 *  - id: identificador interno (ej. "lavender_leaves")
 *  - displayName: nombre con colores (&a, &f, etc.) para el item
 *  - distanceId: valor 1..7 que usamos como "código" en el blockstate de azalea_leaves
 *  - texture: nombre de la textura (archivo PNG sin la extensión)
 *  - customModelData: valor para el item en el inventario (para overrides de modelo)
 *  - visualData: BlockData que se enviará al cliente (PacketEvents o sendBlockChange)
 *  - particleColor: color de las partículas de hojas cayendo
 *  - particleAmount: cantidad de partículas por tick cuando se elige esta hoja
 */
public record LeafType(
        String id,
        String displayName,
        int distanceId,
        String texture,
        int customModelData,
        BlockData visualData,
        Color particleColor,
        int particleAmount
) {
}
