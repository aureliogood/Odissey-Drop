package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Creates, identifies and consumes meteorite keys stored as PDC tagged items. */
public final class KeyManager {
    private final MeteoriteDrop plugin;private final NamespacedKey key;
    public KeyManager(MeteoriteDrop plugin){this.plugin=plugin;key=new NamespacedKey(plugin,"meteorite_key_type");}
    public boolean isRequired(String type){return plugin.getConfig().getBoolean("meteor-types."+type+".requires-key",false);}
    public ItemStack create(String type){
        String path="meteor-types."+type+".";Material material=Material.matchMaterial(plugin.getConfig().getString(path+"key-material","TRIPWIRE_HOOK"));if(material==null)material=Material.TRIPWIRE_HOOK;
        ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();if(meta!=null){meta.setDisplayName(plugin.getConfig().getString(path+"key-name","§5Llave de Meteorito: §d"+type));meta.getPersistentDataContainer().set(key,PersistentDataType.STRING,type.toLowerCase());meta.setLore(java.util.List.of("§7Abre un cofre del meteorito §f"+type,"§8Objeto vinculado a MeteoriteDrop"));item.setItemMeta(meta);}return item;
    }
    public boolean has(Player p,String type){return find(p,type)>=0;}
    public boolean consume(Player p,String type){int slot=find(p,type);if(slot<0)return false;ItemStack item=p.getInventory().getItem(slot);if(item.getAmount()>1)item.setAmount(item.getAmount()-1);else p.getInventory().setItem(slot,null);return true;}
    public void give(Player p,String type,int amount){ItemStack item=create(type);item.setAmount(Math.max(1,Math.min(64,amount)));p.getInventory().addItem(item).values().forEach(drop->p.getWorld().dropItemNaturally(p.getLocation(),drop));}
    private int find(Player p,String type){for(int i=0;i<p.getInventory().getSize();i++){ItemStack item=p.getInventory().getItem(i);if(item==null||!item.hasItemMeta())continue;String id=item.getItemMeta().getPersistentDataContainer().get(key,PersistentDataType.STRING);if(id!=null&&id.equalsIgnoreCase(type))return i;}return -1;}
}
