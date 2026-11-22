package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.gui.MissionMenuHolder;
import com.meteoritedrop.utils.AtomicYamlStorage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.logging.Level;

/** Permanent, daily and weekly capture missions with editable rewards and persistent progress. */
public final class MissionsManager {
    private record Mission(String id,String period,String title,Material icon,int target,double money,int xp,String objective,List<String> lore) {}
    private final MeteoriteDrop plugin;
    private final File definitionsFile,progressFile;
    private YamlConfiguration definitions,progress;
    private final List<Mission> missions=new ArrayList<>();
    public MissionsManager(MeteoriteDrop plugin){this.plugin=plugin;definitionsFile=new File(plugin.getDataFolder(),"missions.yml");progressFile=new File(plugin.getDataFolder(),"mission-progress.yml");if(!definitionsFile.exists())plugin.saveResource("missions.yml",false);mergeMissingDefaults();definitions=YamlConfiguration.loadConfiguration(definitionsFile);progress=YamlConfiguration.loadConfiguration(progressFile);loadDefinitions();}
    public synchronized void reload(){mergeMissingDefaults();definitions=YamlConfiguration.loadConfiguration(definitionsFile);progress=YamlConfiguration.loadConfiguration(progressFile);loadDefinitions();}
    private void mergeMissingDefaults(){try(InputStreamReader reader=new InputStreamReader(plugin.getResource("missions.yml"),StandardCharsets.UTF_8)){YamlConfiguration defaults=YamlConfiguration.loadConfiguration(reader);YamlConfiguration current=YamlConfiguration.loadConfiguration(definitionsFile);boolean changed=false;for(String path:defaults.getKeys(true))if(!defaults.isConfigurationSection(path)&&!current.isSet(path)){current.set(path,defaults.get(path));changed=true;}if(changed)AtomicYamlStorage.save(current,definitionsFile);}catch(Exception e){plugin.getLogger().warning("No se pudieron agregar nuevas misiones predeterminadas: "+e.getMessage());}}
    private void loadDefinitions(){missions.clear();loadGroup("missions","permanent");loadGroup("daily-missions","daily");loadGroup("weekly-missions","weekly");}
    private void loadGroup(String path,String period){ConfigurationSection section=definitions.getConfigurationSection(path);if(section==null)return;for(String id:section.getKeys(false)){ConfigurationSection m=section.getConfigurationSection(id);if(m==null||!m.getBoolean("enabled",true))continue;Material icon=Material.matchMaterial(m.getString("icon","NETHER_STAR"));if(icon==null)icon=Material.NETHER_STAR;missions.add(new Mission(id,period,m.getString("name",id),icon,Math.max(1,m.getInt("target",1)),Math.max(0,m.getDouble("rewards.money",0)),Math.max(0,m.getInt("rewards.xp",0)),m.getString("objective","captures").toLowerCase(Locale.ROOT),m.getStringList("description")));}}
    public synchronized void openMenu(Player player){
        MissionMenuHolder holder=new MissionMenuHolder("§5Misiones de Meteoritos");for(int i=0;i<54;i++)holder.getInventory().setItem(i,item(Material.GRAY_STAINED_GLASS_PANE," ",List.of()));
        int[] permanent={10,12,14,16,22},daily={27,29,31,33,35},weekly={45,47,49,51,53};
        holder.getInventory().setItem(4,item(Material.BOOK,"§eMisiones y recompensas",List.of("§7Abre cofres de meteorito para avanzar.","§7Las diarias se reinician cada día.","§7Las semanales se reinician cada lunes.")));
        holder.getInventory().setItem(19,item(Material.CLOCK,"§bMisiones diarias",List.of("§7Se reinician a medianoche, hora del servidor.")));
        holder.getInventory().setItem(37,item(Material.PAPER,"§dMisiones semanales",List.of("§7Se reinician cada lunes, hora del servidor.")));
        int p=0,d=0,w=0;boolean changed=false;
        for(Mission m:missions){if(resetCycleIfNeeded(player,m.period()))changed=true;int slot; if(m.period().equals("daily")){if(d>=daily.length)continue;slot=daily[d++];}else if(m.period().equals("weekly")){if(w>=weekly.length)continue;slot=weekly[w++];}else{if(p>=permanent.length)continue;slot=permanent[p++];}
            String path=missionPath(player,m);int current=Math.min(m.target(),progress.getInt(path+".progress",0));boolean done=progress.getBoolean(path+".completed",false)||current>=m.target();List<String> lore=new ArrayList<>();lore.add("§7Progreso: §f"+current+"§7/§f"+m.target());lore.add(done?"§aCompletada":"§eEn curso");lore.add("");m.lore().forEach(line->lore.add("§7"+line));if(m.money()>0)lore.add("§6Dinero: "+(plugin.getEconomyManager()!=null&&plugin.getEconomyManager().isAvailable()?plugin.getEconomyManager().format(m.money()):String.format(Locale.US,"$%.2f",m.money())));if(m.xp()>0)lore.add("§bExperiencia: "+m.xp()+" XP");holder.getInventory().setItem(slot,item(done?Material.LIME_STAINED_GLASS_PANE:m.icon(),m.title(),lore));
        }
        if(changed)saveProgress();player.openInventory(holder.getInventory());
    }
    private ItemStack item(Material material,String name,List<String> lore){ItemStack stack=new ItemStack(material);ItemMeta meta=stack.getItemMeta();if(meta!=null){meta.setDisplayName(name);meta.setLore(lore);stack.setItemMeta(meta);}return stack;}
    public synchronized void recordCapture(Player player){recordObjective(player,"captures");}
    public synchronized void recordObjective(Player player,String objective){
        if(player==null)return;
        boolean changed=false;
        for(Mission mission:missions){
            if(!mission.objective().equalsIgnoreCase(objective))continue;
            if(resetCycleIfNeeded(player,mission.period()))changed=true;
            String path=missionPath(player,mission);
            if(progress.getBoolean(path+".completed",false))continue;
            int next=Math.min(mission.target(),progress.getInt(path+".progress",0)+1);
            progress.set(path+".progress",next);
            changed=true;
            if(next>=mission.target()){
                progress.set(path+".completed",true);
                // Persist the one-time claim before paying it. This favors preventing
                // duplicate payouts after a crash; if persistence fails, do not pay yet.
                if(saveProgress()){
                    payReward(player,mission);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&6[MeteoriteDrop] &a¡Misión completada: &e"+mission.title()+"&a!"));
                    changed=false;
                }else{
                    progress.set(path+".completed",false);
                    plugin.getLogger().severe("Mission reward for '"+mission.id()+"' was withheld because mission-progress.yml could not be saved.");
                }
            }
        }
        if(changed)saveProgress();
    }
    private boolean resetCycleIfNeeded(Player player,String period){if(period.equals("permanent"))return false;String key=cycleKey(period),path="players."+player.getUniqueId()+".periods."+period;if(key.equals(progress.getString(path,"")))return false;progress.set("players."+player.getUniqueId()+".missions."+period,null);progress.set(path,key);return true;}
    private String cycleKey(String period){LocalDate now=LocalDate.now();if(period.equals("daily"))return now.toString();WeekFields iso=WeekFields.ISO;return now.get(iso.weekBasedYear())+"-W"+String.format("%02d",now.get(iso.weekOfWeekBasedYear()));}
    private String missionPath(Player player,Mission mission){return "players."+player.getUniqueId()+".missions."+mission.period()+"."+mission.id();}
    private void payReward(Player player,Mission mission){if(mission.money()>0){if(plugin.getEconomyManager()!=null&&plugin.getEconomyManager().isAvailable()){plugin.getEconomyManager().deposit(player,mission.money());player.sendMessage("§6Recompensa: "+plugin.getEconomyManager().format(mission.money()));}else player.sendMessage("§eDinero no entregado: instala Vault y una economía.");}if(mission.xp()>0){player.giveExp(mission.xp());player.sendMessage("§bRecompensa: "+mission.xp()+" XP");}}
    private synchronized boolean saveProgress(){try{AtomicYamlStorage.save(progress,progressFile);return true;}catch(IOException e){plugin.getLogger().log(Level.SEVERE,"No se pudo guardar mission-progress.yml",e);return false;}}
}
