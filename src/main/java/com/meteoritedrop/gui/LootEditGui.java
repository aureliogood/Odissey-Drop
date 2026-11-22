package com.meteoritedrop.gui;

import com.meteoritedrop.models.MeteorType;
import net.kyori.adventure.text.Component;
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

/**
 * GUI for inspecting, adding, and removing items from a MeteorType's loot table.
 */
public class LootEditGui implements InventoryHolder {

    private final MeteorType meteorType;
    private final Inventory inventory;

    public LootEditGui(MeteorType meteorType) {
        this.meteorType = meteorType;
        this.inventory = Bukkit.createInventory(
                this,
                54,
                LegacyComponentSerializer.legacyAmpersand().deserialize("&6Loot: &e" + meteorType.getDisplayName())
        );
        populate();
    }

    public void populate() {
        inventory.clear();

        ItemStack filler = createFillerItem();
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        List<ItemStack> loot = meteorType.getLoot();
        for (int i = 0; i < loot.size() && i < 45; i++) {
            ItemStack original = loot.get(i);
            if (original != null && !original.getType().isAir()) {
                ItemStack display = original.clone();
                ItemMeta meta = display.getItemMeta();
                if (meta != null) {
                    List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
                    lore.add(LegacyComponentSerializer.legacySection().deserialize("§cClick derecho para eliminar"));
                    meta.lore(lore);
                    display.setItemMeta(meta);
                }
                inventory.setItem(i, display);
            }
        }

        // Slot 49: GREEN_CONCRETE -> Agregar item en mano
        inventory.setItem(49, createButton(
                Material.GREEN_CONCRETE,
                "§aAgregar item en mano",
                List.of("§7Agrega el item que tienes en la", "§7mano principal a este loot")
        ));

        // Slot 50: RED_CONCRETE -> Limpiar loot
        inventory.setItem(50, createButton(
                Material.RED_CONCRETE,
                "§cLimpiar loot",
                List.of("§7Elimina todos los items de este loot")
        ));

        // Slot 53: ARROW -> Back to type edit menu
        inventory.setItem(53, createButton(
                Material.ARROW,
                "§7« Volver",
                List.of()
        ));
    }

    private ItemStack createButton(Material mat, String name, List<String> loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name));
            if (!loreLines.isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : loreLines) {
                    lore.add(LegacyComponentSerializer.legacySection().deserialize(line));
                }
                meta.lore(lore);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFillerItem() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.empty());
            item.setItemMeta(meta);
        }
        return item;
    }

    public MeteorType getMeteorType() {
        return meteorType;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
