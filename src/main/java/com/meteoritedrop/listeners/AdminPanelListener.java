package com.meteoritedrop.listeners;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.gui.AdminPanelGui;
import com.meteoritedrop.gui.AdminSettingsGui;
import com.meteoritedrop.models.MeteoriteChest;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.entity.Player;

public final class AdminPanelListener implements Listener {
    private final MeteoriteDrop plugin;
    public AdminPanelListener(MeteoriteDrop plugin) { this.plugin = plugin; }
    @EventHandler(priority = EventPriority.HIGHEST) public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof AdminPanelGui || e.getInventory().getHolder() instanceof AdminSettingsGui) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void onClick(InventoryClickEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if(holder instanceof AdminSettingsGui){handleSettingsClick(e);return;}
        if (!(holder instanceof AdminPanelGui gui)) return;
        e.setCancelled(true); if (!(e.getWhoClicked() instanceof Player player) || e.getClickedInventory() != e.getInventory()) return;
        int slot=e.getSlot();
        if (slot==49) { player.closeInventory(); return; }
        if (slot==53) { plugin.getGuiManager().openAdminSettings(player); return; }
        if (slot==51 || slot==52) {
            String key=slot==51?"drop-protection.enabled":"drop-protection.worldguard";
            plugin.getConfig().set(key,!plugin.getConfig().getBoolean(key,true)); plugin.saveConfig();
            player.sendMessage("§a"+key+" ahora está "+(plugin.getConfig().getBoolean(key)?"activo":"inactivo")+".");
            player.openInventory(new AdminPanelGui(plugin).getInventory()); return;
        }
        MeteoriteChest chest=gui.chestAt(slot); if(chest==null || chest.getChestLocation()==null) return;
        if (e.isShiftClick()) {
            int delta=e.isRightClick()?-30:30;
            if (chest.adjustLockSeconds(delta)) player.sendMessage("§aTiempo de sellado ajustado: §f"+chest.getRemainingLockSeconds()+"s");
            else player.sendMessage("§eEste meteorito ya no está sellado.");
            player.openInventory(new AdminPanelGui(plugin).getInventory()); return;
        }
        if (e.isRightClick()) { chest.restoreCrater(); player.sendMessage("§cMeteorito cancelado; se está restaurando el terreno."); player.openInventory(new AdminPanelGui(plugin).getInventory()); return; }
        Location dest=chest.getChestLocation().clone().add(.5,1,.5); player.closeInventory(); player.teleport(dest);
    }

    private void handleSettingsClick(InventoryClickEvent e){
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player player)||e.getClickedInventory()!=e.getInventory())return;
        int slot=e.getSlot();
        if(slot==49){plugin.getGuiManager().openAdminPanel(player);return;}
        if(slot==53){player.closeInventory();return;}
        String key=switch(slot){case 30->"special-boss.enabled"; case 32->"special-boss.ability-enabled"; default->null;};
        if(key!=null){plugin.getConfig().set(key,!plugin.getConfig().getBoolean(key,true));plugin.saveConfig();}
        if(slot==34){int current=plugin.getConfig().getInt("special-boss.health",300);int next=current<250?300:current<400?500:200;plugin.getConfig().set("special-boss.health",next);plugin.saveConfig();}
        if(key!=null||slot==34){player.openInventory(new com.meteoritedrop.gui.AdminSettingsGui(plugin).getInventory());player.sendMessage("§aAjuste guardado en config.yml.");}
    }
}
