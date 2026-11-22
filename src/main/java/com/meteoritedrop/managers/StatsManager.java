package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.utils.AtomicYamlStorage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;

/**
 * Manages persisting and querying meteorite looter statistics, season rankings,
 * and monthly/seasonal resets with automated champion rewards.
 */
public class StatsManager {

    public record LooterEntry(UUID uuid, String name, int looted) {}

    private final MeteoriteDrop plugin;
    private final File statsFile;
    private FileConfiguration statsConfig;

    public StatsManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.statsFile = new File(plugin.getDataFolder(), "stats.yml");
        loadStats();
    }

    public synchronized void loadStats() {
        if (!statsFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                statsFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create stats.yml", e);
            }
        }
        this.statsConfig = YamlConfiguration.loadConfiguration(statsFile);
        if (!statsConfig.contains("current-season")) {
            statsConfig.set("current-season", 1);
            saveStats();
        }
    }

    public synchronized void saveStats() {
        if (statsConfig == null || statsFile == null) return;
        try {
            AtomicYamlStorage.save((YamlConfiguration) statsConfig, statsFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml", e);
        }
    }

    public synchronized int getCaptureCount(UUID uuid) {
        if (uuid == null || statsConfig == null) return 0;
        String id=uuid.toString();
        return statsConfig.getInt("global-stats."+id+".looted", statsConfig.getInt("looter-stats."+id+".looted",0));
    }

    public synchronized boolean isTeamEventActive(){return statsConfig.getBoolean("team-event.active",false);}
    public synchronized void startTeamEvent(CommandSender sender){statsConfig.set("team-event.active",true);statsConfig.set("team-event.scores",null);saveStats();Bukkit.broadcastMessage("§b⚔ ¡Competencia por equipos iniciada! Las capturas se suman a los equipos del scoreboard.");}
    public synchronized void stopTeamEvent(CommandSender sender){
        var section=statsConfig.getConfigurationSection("team-event.scores");String winner="";int score=0;
        if(section!=null)for(String name:section.getKeys(false)){int value=section.getInt(name+".looted",0);if(value>score){score=value;winner=name;}}
        statsConfig.set("team-event.active",false);saveStats();
        if(winner.isBlank())Bukkit.broadcastMessage("§7La competencia por equipos terminó sin capturas.");
        else {
            Bukkit.broadcastMessage("§b⚔ Equipo ganador: §f"+winner+" §7con §e"+score+" capturas.");
            double prize=plugin.getConfig().getDouble("team-event.prize-per-player",0);
            if(prize>0&&plugin.getEconomyManager()!=null&&plugin.getEconomyManager().isAvailable())for(Player p:Bukkit.getOnlinePlayers()){org.bukkit.scoreboard.Team team=p.getScoreboard().getEntryTeam(p.getName());if(team!=null&&team.getName().equalsIgnoreCase(winner)){plugin.getEconomyManager().deposit(p,prize);p.sendMessage("§aTu equipo ganó; recibiste "+plugin.getEconomyManager().format(prize)+".");}}
        }
    }
    public synchronized Map<String,Integer> getTeamCaptureCounts() {
        Map<String,Integer> result=new HashMap<>();
        String path=isTeamEventActive()?"team-event.scores":"team-stats";
        ConfigurationSection section=statsConfig.getConfigurationSection(path);
        if(section!=null)for(String key:section.getKeys(false))result.put(key,section.getInt(key+".looted",0));
        return result;
    }
    public int getCurrentSeason() {
        return statsConfig != null ? statsConfig.getInt("current-season", 1) : 1;
    }

    /**
     * Records a successful meteorite chest unlock for a player for both current season and all-time.
     */
    public synchronized void recordLoot(Player player) {
        if (player == null) return;
        String uuidStr = player.getUniqueId().toString();
        int season = getCurrentSeason();

        // Season stats
        String seasonPath = "seasons.season-" + season + ".looters." + uuidStr;
        int currentSeasonLooted = statsConfig.getInt(seasonPath + ".looted", 0);
        statsConfig.set(seasonPath + ".name", player.getName());
        statsConfig.set(seasonPath + ".looted", currentSeasonLooted + 1);

        // Global stats
        String globalPath = "global-stats." + uuidStr;
        int currentGlobalLooted = statsConfig.getInt(globalPath + ".looted", 0);
        statsConfig.set(globalPath + ".name", player.getName());
        statsConfig.set(globalPath + ".looted", currentGlobalLooted + 1);

        if(isTeamEventActive() && player.getScoreboard().getEntryTeam(player.getName())!=null){String tn=player.getScoreboard().getEntryTeam(player.getName()).getName();String tp="team-event.scores."+tn;statsConfig.set(tp+".name",tn);statsConfig.set(tp+".looted",statsConfig.getInt(tp+".looted",0)+1);}
        org.bukkit.scoreboard.Team playerTeam = player.getScoreboard().getEntryTeam(player.getName());
        if (playerTeam != null) {
            String teamPath = "team-stats." + playerTeam.getName();
            statsConfig.set(teamPath + ".name", playerTeam.getName());
            statsConfig.set(teamPath + ".looted", statsConfig.getInt(teamPath + ".looted", 0) + 1);
        }

        // Backwards compatibility legacy section
        statsConfig.set("looter-stats." + uuidStr + ".name", player.getName());
        statsConfig.set("looter-stats." + uuidStr + ".looted", currentGlobalLooted + 1);

        saveStats();
    }

    /**
     * Returns the top looters of the CURRENT SEASON sorted in descending order.
     */
    public synchronized List<LooterEntry> getTopLooters(int limit) {
        int season = getCurrentSeason();
        String seasonPath = "seasons.season-" + season + ".looters";
        ConfigurationSection section = statsConfig.getConfigurationSection(seasonPath);
        if (section == null) {
            // Fallback to legacy looter-stats if no season yet
            section = statsConfig.getConfigurationSection("looter-stats");
        }
        return parseSection(section, limit);
    }

    /**
     * Returns all-time global top looters.
     */
    public synchronized List<LooterEntry> getGlobalTopLooters(int limit) {
        ConfigurationSection section = statsConfig.getConfigurationSection("global-stats");
        if (section == null) {
            section = statsConfig.getConfigurationSection("looter-stats");
        }
        return parseSection(section, limit);
    }

    private List<LooterEntry> parseSection(ConfigurationSection section, int limit) {
        List<LooterEntry> entries = new ArrayList<>();
        if (section == null) return entries;

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String name = section.getString(key + ".name", "Desconocido");
                int looted = section.getInt(key + ".looted", 0);
                entries.add(new LooterEntry(uuid, name, looted));
            } catch (IllegalArgumentException ignored) {
            }
        }

        entries.sort((a, b) -> Integer.compare(b.looted(), a.looted()));

        if (limit > 0 && entries.size() > limit) {
            return entries.subList(0, limit);
        }
        return entries;
    }

    /**
     * Resets the current season, crowns the champion with rewards, and advances to the next season.
     */
    public synchronized boolean resetSeason(CommandSender sender) {
        int currentSeason = getCurrentSeason();
        List<LooterEntry> top = getTopLooters(1);

        String winnerName = "Nadie";
        int winnerLooted = 0;
        UUID winnerUuid = null;

        if (!top.isEmpty() && top.get(0).looted() > 0) {
            LooterEntry champion = top.get(0);
            winnerName = champion.name();
            winnerLooted = champion.looted();
            winnerUuid = champion.uuid();
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        statsConfig.set("seasons.season-" + currentSeason + ".ended-at", now);
        statsConfig.set("seasons.season-" + currentSeason + ".champion-name", winnerName);
        statsConfig.set("seasons.season-" + currentSeason + ".champion-looted", winnerLooted);

        // Vault prize for the champion
        double prizeMoney = plugin.getConfig().getDouble("season-reward-money", 5000.0);
        if (winnerUuid != null && prizeMoney > 0 && plugin.getEconomyManager() != null && plugin.getEconomyManager().isAvailable()) {
            Player champPlayer = Bukkit.getPlayer(winnerUuid);
            if (champPlayer != null && champPlayer.isOnline()) {
                plugin.getEconomyManager().deposit(champPlayer, prizeMoney);
                champPlayer.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&6[MeteoriteDrop] &e¡Felicidades! Has ganado &a" + plugin.getEconomyManager().format(prizeMoney) +
                    " &epor ser el Campeón de la Temporada " + currentSeason + "!"
                ));
            }
        }

        int nextSeason = currentSeason + 1;
        statsConfig.set("current-season", nextSeason);
        saveStats();

        // Broadcast to entire server
        String broadcastMsg = "&8&m--------------------------------------------------\n" +
                "&6&l🏆 ¡FINAL DE TEMPORADA " + currentSeason + "! 🏆\n" +
                "&eCampeón: &a" + winnerName + " &7con &6" + winnerLooted + " &7meteoritos saqueados.\n" +
                "&b¡La Temporada " + nextSeason + " ha comenzado oficialmente!\n" +
                "&8&m--------------------------------------------------";
        Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(broadcastMsg));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
            p.showTitle(Title.title(
                LegacyComponentSerializer.legacySection().deserialize("§6§lTEMPORADA " + nextSeason),
                LegacyComponentSerializer.legacySection().deserialize("§e¡El ranking ha sido reiniciado!"),
                Title.Times.times(java.time.Duration.ofMillis(400), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(800))
            ));
        }

        if (plugin.getDiscordManager() != null) plugin.getDiscordManager().sendSeasonEnded(currentSeason, winnerName, winnerLooted);

        if (sender != null) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&a[MeteoriteDrop] Temporada " + currentSeason + " finalizada. Nueva temporada activa: &e" + nextSeason
            ));
        }
        return true;
    }
}
