package com.meteoritedrop.gui;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import org.bukkit.entity.Player;

/**
 * Facilitates opening and managing GUI navigation for players.
 */
public class GuiManager {

    private final MeteoriteDrop plugin;

    public GuiManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
    }

    public void openMainMenu(Player player) {
        if (player == null) return;
        MainMenuGui gui = new MainMenuGui(plugin);
        player.openInventory(gui.getInventory());
    }

    public void openTypeEdit(Player player, MeteorType type) {
        if (player == null || type == null) return;
        TypeEditGui gui = new TypeEditGui(type);
        player.openInventory(gui.getInventory());
    }

    public void openLootEdit(Player player, MeteorType type) {
        if (player == null || type == null) return;
        LootEditGui gui = new LootEditGui(type);
        player.openInventory(gui.getInventory());
    }

    public void openAdminPanel(Player player) {
        if (player != null) player.openInventory(new AdminPanelGui(plugin).getInventory());
    }

    public void openAdminSettings(Player player) {
        if (player != null) player.openInventory(new AdminSettingsGui(plugin).getInventory());
    }
}
