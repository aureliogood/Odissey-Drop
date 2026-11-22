package com.meteoritedrop.models;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/** Identifies the virtual inventory belonging to one meteorite drop. */
public final class LootInventoryHolder implements InventoryHolder {
    private Inventory inventory;
    @Override public @NotNull Inventory getInventory() { return inventory; }
    public void setInventory(Inventory inventory) { this.inventory = inventory; }
}
