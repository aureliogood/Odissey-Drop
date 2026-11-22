package com.meteoritedrop.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.HashMap;
import java.util.Map;

public final class MissionMenuHolder implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer,String> missions = new HashMap<>();
    public MissionMenuHolder(String title) { inventory=Bukkit.createInventory(this,54,title); }
    public Inventory getInventory(){ return inventory; }
    public void setMission(int slot,String id){missions.put(slot,id);}
}
