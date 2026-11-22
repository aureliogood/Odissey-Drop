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
 * GUI for inspecting and editing properties of a specific MeteorType.
 */
public class TypeEditGui implements InventoryHolder {

    private final MeteorType meteorType;
    private final Inventory inventory;

    public TypeEditGui(MeteorType meteorType) {
        this.meteorType = meteorType;
        this.inventory = Bukkit.createInventory(
                this,
                54,
                LegacyComponentSerializer.legacyAmpersand().deserialize("&6Editando: &e" + meteorType.getDisplayName())
        );
        populate();
    }

    public void populate() {
        inventory.clear();

        ItemStack filler = createFillerItem();
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // Slot 10: CLOCK -> Lock duration
        inventory.setItem(10, createButton(
                Material.CLOCK,
                "§eTiempo de espera: §f" + meteorType.getLockDurationSeconds() + "s",
                List.of("§7Click izquierdo: §a+30s §7| §7Click derecho: §c-30s")
        ));

        // Slot 11: HOPPER -> Unlock duration
        inventory.setItem(11, createButton(
                Material.HOPPER,
                "§eTiempo desbloqueo: §f" + meteorType.getUnlockDurationSeconds() + "s",
                List.of("§7Click izq: §a+1s §7| §7Click der: §c-1s")
        ));

        // Slot 12: GRASS_BLOCK -> Crater radius
        inventory.setItem(12, createButton(
                Material.GRASS_BLOCK,
                "§eRadio del cráter: §f" + meteorType.getCraterRadius() + " bloques",
                List.of("§7Click izq: §a+1 §7| §7Click der: §c-1")
        ));

        // Slot 13: BEDROCK -> Crater depth
        inventory.setItem(13, createButton(
                Material.BEDROCK,
                "§eProfundidad del cráter: §f" + meteorType.getCraterDepth() + " bloques",
                List.of("§7Click izq: §a+1 §7| §7Click der: §c-1")
        ));

        // Slot 14: configure the default descent duration used by this menu's launch button.
        inventory.setItem(14, createButton(
                Material.CLOCK,
                "§bTiempo en el aire: §f" + meteorType.getFallDurationSeconds() + " segundos",
                List.of("§7Click izquierdo: §a+1 segundo", "§7Click derecho: §c-1 segundo", "§7Rango igual al comando: 1–120 segundos")
        ));

        // Slot 22: CHEST -> Edit rewards
        inventory.setItem(22, createButton(
                Material.CHEST,
                "§e§lEditar Recompensas",
                List.of("§7Gestiona el loot del meteorito", "§8» Click para abrir")
        ));

        // Slot 31: LIME_CONCRETE -> Launch this meteor
        inventory.setItem(31, createButton(
                Material.LIME_CONCRETE,
                "§a§lLanzar este meteorito",
                List.of("§7Lanza un meteorito de este tipo", "§7en tu posición actual", "§7Tiempo de caída: §f" + meteorType.getFallDurationSeconds() + " segundos")
        ));

        // Slot 49: ARROW -> Back to main menu
        inventory.setItem(49, createButton(
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
