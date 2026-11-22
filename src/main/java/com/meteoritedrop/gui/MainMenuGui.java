package com.meteoritedrop.gui;

import com.meteoritedrop.MeteoriteDrop;
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

import java.util.*;

/**
 * Main GUI menu displaying all available meteorite types for selection and configuration.
 */
public class MainMenuGui implements InventoryHolder {

    private final MeteoriteDrop plugin;
    private final Inventory inventory;
    private final Map<Integer, MeteorType> slotToType = new HashMap<>();

    public MainMenuGui(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(
                this,
                54,
                LegacyComponentSerializer.legacySection().deserialize("§6§lMeteoriteDrop §8— §eSelecciona Meteorito")
        );
        populate();
    }

    public void populate() {
        inventory.clear();
        slotToType.clear();

        ItemStack filler = createFillerItem();
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        Collection<MeteorType> types = plugin.getMeteorTypeManager().getTypes();
        int[] availableSlots = {21, 23, 20, 24, 22, 19, 25, 29, 31, 33, 30};

        int index = 0;
        for (MeteorType type : types) {
            if (index >= availableSlots.length) break;
            int slot = availableSlots[index++];
            slotToType.put(slot, type);
            inventory.setItem(slot, createTypeItem(type));
        }

        // Admin-only controls for distributing the tagged ritual components.
        if (plugin.getRitualManager() != null) {
            inventory.setItem(45, plugin.getRitualManager().createRitualAnchorItem());
            inventory.setItem(46, plugin.getRitualManager().createRitualFireChargeItem());
        }

        // Close button at slot 49
        ItemStack barrier = new ItemStack(Material.BARRIER);
        ItemMeta barrierMeta = barrier.getItemMeta();
        if (barrierMeta != null) {
            barrierMeta.displayName(LegacyComponentSerializer.legacySection().deserialize("§cCerrar"));
            barrier.setItemMeta(barrierMeta);
        }
        inventory.setItem(49, barrier);
    }

    private ItemStack createTypeItem(MeteorType type) {
        ItemStack item = new ItemStack(type.getIcon());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e" + type.getDisplayName()
            ));

            List<Component> lore = new ArrayList<>();
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Radio del cráter: §f" + type.getCraterRadius() + " bloques"));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Tiempo de espera: §f" + type.getLockDurationSeconds() + "s"));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Tiempo desbloqueo: §f" + type.getUnlockDurationSeconds() + "s"));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Caída desde el menú: §f" + type.getFallDurationSeconds() + "s"));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§7Items de loot: §f" + (type.getLoot() != null ? type.getLoot().size() : 0)));
            lore.add(LegacyComponentSerializer.legacySection().deserialize("§8» Click para editar"));

            meta.lore(lore);
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

    public MeteorType getMeteorTypeAt(int slot) {
        return slotToType.get(slot);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
