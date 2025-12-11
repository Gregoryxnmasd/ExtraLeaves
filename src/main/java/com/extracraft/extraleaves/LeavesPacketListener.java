package com.extracraft.extraleaves;

import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import org.bukkit.entity.Player;

/**
 * Listener de PacketEvents.
 *
 * Cuando el servidor manda:
 *  - BLOCK_CHANGE
 *  - MULTI_BLOCK_CHANGE
 *  - CHUNK_DATA
 * iniciamos un "burst" de repintado de hojas en LeafManager para ese jugador.
 */
public class LeavesPacketListener extends PacketListenerCommon {

    private final ExtraLeavesPlugin plugin;
    private final LeafManager leafManager;

    public LeavesPacketListener(ExtraLeavesPlugin plugin, LeafManager leafManager) {
        this.plugin = plugin;
        this.leafManager = leafManager;
    }

    // NO usamos @Override aquí para evitar problemas de firma con versiones concretas.
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();

        if (type != PacketType.Play.Server.BLOCK_CHANGE
                && type != PacketType.Play.Server.MULTI_BLOCK_CHANGE
                && type != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null || !player.isOnline()) {
            return;
        }

        try {
            // Burst de repintado hardcore: 12 ticks, radio 16
            leafManager.startVisualBurst(player, 12, 16);
        } catch (Exception ex) {
            plugin.getLogger().warning("[ExtraLeaves] Error en LeavesPacketListener: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }
}
