package com.meteoritedrop.gui;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteoriteChest;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** Admin dashboard for active meteorites and protection switches. */
public final class AdminPanelGui implements InventoryHolder {
    private final Inventory inventory;
    private final List<MeteoriteChest> chests = new ArrayList<>();

    public AdminPanelGui(MeteoriteDrop plugin) {
        inventory = Bukkit.createInventory(this, 54, LegacyComponentSerializer.legacySection().deserialize("§4§lMeteoriteDrop §8| §cAdministración"));
        int slot = 0;
        for (MeteoriteChest chest : plugin.getChestManager().getAllActiveChests()) {
            if (slot >= 45) break;
            if (chest.getChestLocation() == null || chest.getState() == MeteoriteChest.ChestState.RESTORED) continue;
            chests.add(chest);
            Material icon = chest.getMeteorType() == null ? Material.CHEST : chest.getMeteorType().getIcon();
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e" + (chest.getMeteorType() == null ? "Meteorito" : chest.getMeteorType().getDisplayName()));
                var loc = chest.getChestLocation();
                meta.setLore(List.of("§7Mundo: §f" + loc.getWorld().getName(), "§7X: §f" + loc.getBlockX() + " §7Y: §f" + loc.getBlockY() + " §7Z: §f" + loc.getBlockZ(), "§7Estado: §f" + chest.getState(), "§7Sellado: §f" + chest.getRemainingLockSeconds() + "s", "", "§aClick: teletransportarte", "§bShift + click: +30s sellado", "§eShift + click derecho: -30s sellado", "§cClick derecho: cancelar y restaurar"));
                item.setItemMeta(meta);
            }
            inventory.setItem(slot++, item);
        }
        set(inventory, 49, Material.BARRIER, "§cCerrar", List.of());
        set(inventory, 51, Material.REDSTONE_TORCH, "§cProtección de spawn: " + (plugin.getConfig().getBoolean("drop-protection.enabled", true) ? "§aACTIVA" : "§cINACTIVA"), List.of("§7Click para alternar; se guarda en config.yml"));
        set(inventory, 52, Material.SHIELD, "§cWorldGuard: " + (plugin.getConfig().getBoolean("drop-protection.worldguard", true) ? "§aACTIVO" : "§cINACTIVO"), List.of("§7Click para alternar; se guarda en config.yml"));
        set(inventory, 53, Material.COMPARATOR, "§dAjustes del evento", List.of("§7Efectos de entorno","§7Jefes especiales","§eClick para abrir"));
        if (chests.isEmpty()) set(inventory, 22, Material.PAPER, "§7No hay meteoritos activos", List.of());
    }
    private static void set(Inventory inv, int slot, Material type, String name, List<String> lore) {
        ItemStack item = new ItemStack(type); ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); meta.setLore(lore); item.setItemMeta(meta); }
        inv.setItem(slot, item);
    }
    public MeteoriteChest chestAt(int slot) { return slot >= 0 && slot < chests.size() ? chests.get(slot) : null; }
    @Override public @NotNull Inventory getInventory() { return inventory; }
}
