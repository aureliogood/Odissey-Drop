package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.utils.AtomicYamlStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.*;

/** Persists original terrain before impacts so unfinished craters can be rolled back after a crash. */
public final class CraterRecoveryManager {
    private final MeteoriteDrop plugin;
    private final File file;
    private YamlConfiguration data;
    public CraterRecoveryManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "crater-recovery.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }
    private String key(Location loc) { return loc.getWorld().getUID() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ(); }
    public synchronized void saveSnapshot(Location chest, Map<Location, BlockData> blocks) {
        if (chest == null || chest.getWorld() == null || blocks == null || blocks.isEmpty()) return;
        String k = key(chest); String path = "craters." + k;
        data.set(path + ".world", chest.getWorld().getName());
        data.set(path + ".world-uuid", chest.getWorld().getUID().toString());
        data.set(path + ".blocks", null);
        List<String> entries = new ArrayList<>(blocks.size());
        for (Map.Entry<Location, BlockData> e : blocks.entrySet()) {
            Location l=e.getKey(); BlockData b=e.getValue();
            if (l.getWorld()!=null && b!=null) entries.add(l.getBlockX()+","+l.getBlockY()+","+l.getBlockZ()+"|"+b.getAsString());
        }
        data.set(path + ".blocks", entries); save();
    }
    public synchronized void removeSnapshot(Location chest) {
        if (chest == null || chest.getWorld() == null) return;
        data.set("craters." + key(chest), null); save();
    }
    public synchronized int restorePending() {
        if (!data.isConfigurationSection("craters")) return 0;
        int restored=0; Set<String> keys=new HashSet<>(data.getConfigurationSection("craters").getKeys(false));
        for (String key: keys) {
            String path="craters."+key; World world=null;
            try { String uid=data.getString(path+".world-uuid", ""); if(!uid.isBlank()) world=Bukkit.getWorld(UUID.fromString(uid)); } catch(IllegalArgumentException ignored) {}
            if(world==null) world=Bukkit.getWorld(data.getString(path+".world", ""));
            if(world==null) { plugin.getLogger().warning("Crater recovery deferred: world unavailable for record " + key); continue; }
            boolean ok=true;
            for(String entry:data.getStringList(path+".blocks")) try {
                int sep=entry.indexOf('|'); String[] xyz=entry.substring(0,sep).split(",");
                int x=Integer.parseInt(xyz[0]), y=Integer.parseInt(xyz[1]), z=Integer.parseInt(xyz[2]);
                world.getChunkAt(x >> 4,z >> 4).load(true);
                world.getBlockAt(x,y,z).setBlockData(Bukkit.createBlockData(entry.substring(sep+1)),false);
            } catch(Exception ex) { ok=false; plugin.getLogger().warning("Could not restore a block in crater record " + key + ": " + ex.getMessage()); }
            if(ok) { data.set(path,null); restored++; }
        }
        save();
        if(restored>0) plugin.getLogger().warning("Recovered terrain for " + restored + " unfinished meteor crater(s) after startup.");
        return restored;
    }
    private synchronized void save() { try { AtomicYamlStorage.save(data,file); } catch(IOException e) { plugin.getLogger().severe("Could not save crater-recovery.yml: " + e.getMessage()); } }
}
