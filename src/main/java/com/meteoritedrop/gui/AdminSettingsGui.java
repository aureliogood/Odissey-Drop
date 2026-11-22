package com.meteoritedrop.gui;

import com.meteoritedrop.MeteoriteDrop;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** In-game controls for ambience and special meteor bosses. */
public final class AdminSettingsGui implements InventoryHolder {
    private final Inventory inventory;
    public AdminSettingsGui(MeteoriteDrop plugin){
        inventory=Bukkit.createInventory(this,54, LegacyComponentSerializer.legacySection().deserialize("§5§lMeteoriteDrop §8| §dAjustes"));
        for(int i=0;i<54;i++)set(i,Material.GRAY_STAINED_GLASS_PANE," ",List.of());
        set(10,Material.CLOCK,"§aClima y hora del mundo",List.of("§7Los meteoritos no cambian el clima", "§7ni la hora del mundo."));
        toggle(30,Material.RAVAGER_SPAWN_EGG,"Jefes especiales","special-boss.enabled",plugin);
        toggle(32,Material.BLAZE_POWDER,"Habilidad de área","special-boss.ability-enabled",plugin);
        int health=plugin.getConfig().getInt("special-boss.health",300);
        set(34,Material.GOLDEN_APPLE,"§cVida del jefe: §f"+health,List.of("§7Click: cambiar entre 200, 300 y 500 puntos"));
        set(49,Material.ARROW,"§aVolver al panel",List.of());
        set(53,Material.BARRIER,"§cCerrar",List.of());
    }
    private void toggle(int slot,Material icon,String label,String path,MeteoriteDrop plugin){
        boolean enabled=plugin.getConfig().getBoolean(path,true);
        set(slot,icon,"§e"+label+": "+(enabled?"§aACTIVO":"§cINACTIVO"),List.of("§7Click para alternar","§8Se guarda en config.yml"));
    }
    private void set(int slot,Material material,String name,List<String> lore){
        ItemStack item=new ItemStack(material); ItemMeta meta=item.getItemMeta();
        if(meta!=null){meta.setDisplayName(name);meta.setLore(lore);item.setItemMeta(meta);} inventory.setItem(slot,item);
    }
    @Override public @NotNull Inventory getInventory(){return inventory;}
}
