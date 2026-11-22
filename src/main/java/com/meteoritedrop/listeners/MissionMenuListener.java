package com.meteoritedrop.listeners;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.gui.MissionMenuHolder;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MissionMenuListener implements Listener {
    private final MeteoriteDrop plugin;
    public MissionMenuListener(MeteoriteDrop plugin){this.plugin=plugin;}
    @EventHandler(priority=EventPriority.HIGHEST) public void click(InventoryClickEvent e){
        if(!(e.getInventory().getHolder() instanceof MissionMenuHolder))return;
        e.setCancelled(true); if(e.getWhoClicked() instanceof Player p && e.getRawSlot()==4){p.playSound(p.getLocation(),Sound.UI_BUTTON_CLICK,.7f,1.2f);plugin.getMissionsManager().openMenu(p);}
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void drag(InventoryDragEvent e){if(e.getInventory().getHolder() instanceof MissionMenuHolder)e.setCancelled(true);}
}
