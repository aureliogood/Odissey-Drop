package com.meteoritedrop.utils;

import com.meteoritedrop.MeteoriteDrop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Utility for creating and identifying the Cosmic Compass radar tool.
 */
public final class CompassUtil {

    private static final String KEY = "cosmic_compass";

    private CompassUtil() {}

    public static ItemStack createCosmicCompass(MeteoriteDrop plugin) {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacySection().deserialize("§6§lBrújula Cósmica"));
            List<Component> lore = List.of(
                    LegacyComponentSerializer.legacySection().deserialize("§7Sintonizada con las fluctuaciones gravitacionales."),
                    LegacyComponentSerializer.legacySection().deserialize("§7Apunta hacia el meteorito activo más cercano."),
                    LegacyComponentSerializer.legacySection().deserialize("§eClick derecho para escanear la distancia exacta.")
            );
            meta.lore(lore);

            NamespacedKey nKey = new NamespacedKey(plugin, KEY);
            meta.getPersistentDataContainer().set(nKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isCosmicCompass(ItemStack item, MeteoriteDrop plugin) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        NamespacedKey nKey = new NamespacedKey(plugin, KEY);
        return meta.getPersistentDataContainer().has(nKey, PersistentDataType.BYTE);
    }
}
