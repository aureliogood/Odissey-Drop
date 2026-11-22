package com.meteoritedrop.gui;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Event listener handling user interactions across all MeteoriteDrop GUIs.
 */
public class GuiListener implements Listener {

    private final MeteoriteDrop plugin;

    public GuiListener(MeteoriteDrop plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof MainMenuGui || holder instanceof TypeEditGui || holder instanceof LootEditGui || holder instanceof AdminPanelGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof MainMenuGui || holder instanceof TypeEditGui || holder instanceof LootEditGui || holder instanceof AdminPanelGui)) {
            return;
        }

        // Always prevent moving items inside plugin GUIs
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Only process clicks within the top custom inventory
        if (event.getClickedInventory() != event.getInventory()) {
            return;
        }

        int slot = event.getSlot();

        if (holder instanceof MainMenuGui mainMenuGui) {
            handleMainMenuClick(player, mainMenuGui, slot);
        } else if (holder instanceof TypeEditGui typeEditGui) {
            handleTypeEditClick(player, typeEditGui, slot, event.isLeftClick(), event.isRightClick());
        } else if (holder instanceof LootEditGui lootEditGui) {
            handleLootEditClick(player, lootEditGui, slot, event.isRightClick());
        }
    }

    private void handleMainMenuClick(Player player, MainMenuGui gui, int slot) {
        if (slot == 45) {
            plugin.getRitualManager().giveRitualAnchor(player);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
            return;
        }
        if (slot == 46) {
            plugin.getRitualManager().giveRitualFireCharge(player);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.3f);
            return;
        }
        if (slot == 49) { // Close button
            player.closeInventory();
            return;
        }

        MeteorType type = gui.getMeteorTypeAt(slot);
        if (type != null) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
            plugin.getGuiManager().openTypeEdit(player, type);
        }
    }

    private void handleTypeEditClick(Player player, TypeEditGui gui, int slot, boolean left, boolean right) {
        MeteorType type = gui.getMeteorType();

        switch (slot) {
            case 10 -> { // Lock duration (CLOCK): Left +30s, Right -30s
                if (left) {
                    type.setLockDurationSeconds(type.getLockDurationSeconds() + 30);
                } else if (right) {
                    type.setLockDurationSeconds(Math.max(10, type.getLockDurationSeconds() - 30));
                }
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            }
            case 11 -> { // Unlock duration (HOPPER): Left +1s, Right -1s
                if (left) {
                    type.setUnlockDurationSeconds(type.getUnlockDurationSeconds() + 1);
                } else if (right) {
                    type.setUnlockDurationSeconds(Math.max(1, type.getUnlockDurationSeconds() - 1));
                }
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            }
            case 12 -> { // Crater radius (GRASS_BLOCK): Left +1, Right -1
                if (left) {
                    type.setCraterRadius(type.getCraterRadius() + 1);
                } else if (right) {
                    type.setCraterRadius(Math.max(2, type.getCraterRadius() - 1));
                }
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            }
            case 13 -> { // Crater depth (BEDROCK): Left +1, Right -1
                if (left) {
                    type.setCraterDepth(type.getCraterDepth() + 1);
                } else if (right) {
                    type.setCraterDepth(Math.max(1, type.getCraterDepth() - 1));
                }
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            }
            case 14 -> { // Fall duration, same 1-120 second range as /md drop
                if (left) type.setFallDurationSeconds(type.getFallDurationSeconds() + 1);
                else if (right) type.setFallDurationSeconds(type.getFallDurationSeconds() - 1);
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.sendMessage("§bTiempo de caída configurado: §f" + type.getFallDurationSeconds() + " segundos.");
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
            }
            case 22 -> { // CHEST -> Loot edit menu
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.2f);
                plugin.getGuiManager().openLootEdit(player, type);
            }
            case 31 -> { // LIME_CONCRETE -> Launch meteor at player's location
                long wait = plugin.getMeteorManager().acquireManualLaunchCooldown(player);
                if (wait > 0) { player.sendMessage("§cEspera " + wait + " segundos antes de lanzar otro evento."); return; }
                player.closeInventory();
                boolean launched = plugin.getMeteorManager().launchMeteor(type, player.getWorld(), player.getLocation().getBlockX(), player.getLocation().getBlockZ(), type.getFallDurationSeconds());
                if (launched) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&a[MeteoriteDrop] Meteorito &e" + type.getId() + " &alanzado en tu posición; caerá durante &e" + type.getFallDurationSeconds() + " segundos&a."
                    ));
                } else {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&cNo se pudo iniciar el meteorito."
                    ));
                }
            }
            case 49 -> { // ARROW -> Return to main menu
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
                plugin.getGuiManager().openMainMenu(player);
            }
        }
    }

    private void handleLootEditClick(Player player, LootEditGui gui, int slot, boolean right) {
        MeteorType type = gui.getMeteorType();

        if (slot >= 0 && slot < 45) {
            if (right && slot < type.getLoot().size()) {
                type.getLoot().remove(slot);
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.8f, 1.0f);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cItem eliminado del loot."));
            }
            return;
        }

        switch (slot) {
            case 49 -> { // GREEN_CONCRETE: Add item in hand
                ItemStack inHand = player.getInventory().getItemInMainHand();
                if (inHand.getType().isAir()) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&c¡Debes sostener un item en la mano principal!"
                    ));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                    return;
                }
                if (type.getLoot().size() >= 45) {
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&c¡El loot está lleno (máximo 45 items)!"
                    ));
                    return;
                }
                type.getLoot().add(inHand.clone());
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&a¡Item agregado al loot de &e" + type.getId() + "&a!"
                ));
            }
            case 50 -> { // RED_CONCRETE: Clear all loot
                type.getLoot().clear();
                plugin.getMeteorTypeManager().saveType(type);
                gui.populate();
                player.playSound(player.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.8f, 1.0f);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&c¡Loot limpiado para &e" + type.getId() + "&c!"
                ));
            }
            case 53 -> { // ARROW: Return to type edit menu
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.7f, 1.0f);
                plugin.getGuiManager().openTypeEdit(player, type);
            }
        }
    }
}
