package com.meteoritedrop.listeners;

import com.meteoritedrop.MeteoriteDrop;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Handles placement, charging, protection, and refund delivery for tagged ritual items. */
public final class RitualListener implements Listener {
    private final MeteoriteDrop plugin;

    public RitualListener(MeteoriteDrop plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRitualAnchorPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.RESPAWN_ANCHOR
                && plugin.getRitualManager().isRitualAnchorItem(event.getItemInHand())) {
            plugin.getRitualManager().registerPlacedAnchor(event.getBlockPlaced().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRitualAnchorBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.RESPAWN_ANCHOR) return;
        if (plugin.getRitualManager().isAnchorPending(block.getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cNo puedes romper el ancla mientras el ritual está contando.");
            return;
        }
        plugin.getRitualManager().unregisterAnchor(block.getLocation());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onRitualCharge(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack held = event.getItem();
        boolean taggedCharge = plugin.getRitualManager().isRitualFireChargeItem(held);
        Block clicked = event.getClickedBlock();
        boolean isAnchor = clicked != null && clicked.getType() == Material.RESPAWN_ANCHOR;
        boolean taggedAnchor = isAnchor && plugin.getRitualManager().isRegisteredAnchor(clicked.getLocation());
        boolean vanillaFireChargeAtCustomAnchor = isAnchor && taggedAnchor && held != null && held.getType() == Material.FIRE_CHARGE && !taggedCharge;

        if (!taggedCharge && !vanillaFireChargeAtCustomAnchor) return;
        event.setCancelled(true); // The special item is ritual-only and must never launch a fireball.

        Player player = event.getPlayer();
        if (!isAnchor) {
            if (event.getAction() == Action.RIGHT_CLICK_BLOCK) player.sendMessage("§cLa carga ígnea ritual solo funciona sobre el ancla ritual especial.");
            return;
        }
        if (!taggedAnchor) {
            player.sendMessage("§cEse es un ancla normal. Usa el ancla especial entregada por MeteoriteDrop.");
            return;
        }
        if (!taggedCharge) {
            player.sendMessage("§cDebes usar la carga ígnea especial entregada por MeteoriteDrop.");
            return;
        }

        String result = plugin.getRitualManager().tryInvoke(player, clicked.getLocation());
        player.sendMessage(result);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getRitualManager().deliverPendingRefund(event.getPlayer());
    }
}
