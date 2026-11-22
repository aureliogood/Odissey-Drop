package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Manages recurring scheduled automatic meteorite drops, warning broadcasts, and countdowns.
 */
public class AutoDropManager {

    private final MeteoriteDrop plugin;

    private boolean enabled;
    private int intervalMinutes;
    private int minPlayers;
    private List<Integer> broadcastWarnings = new ArrayList<>();
    private List<String> worlds = new ArrayList<>();
    private int randomRadius;
    private boolean fixedScheduleEnabled;
    private List<String> scheduledTimes = new ArrayList<>();
    private List<String> scheduledDays = new ArrayList<>();
    private String lastScheduledMinute = "";

    private int remainingSeconds;
    private BukkitTask task;

    public AutoDropManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        loadConfig();
        startTask();
    }

    public synchronized void loadConfig() {
        FileConfiguration config = plugin.getConfig();

        this.enabled = config.getBoolean("auto-drop.enabled", true);
        int configuredInterval = config.getInt("auto-drop.interval-minutes", 90);
        this.intervalMinutes = Math.max(1, Math.min(43200, configuredInterval));
        if (this.intervalMinutes != configuredInterval) plugin.getLogger().warning("auto-drop.interval-minutes must be between 1 and 43200; it was clamped.");

        int configuredMinPlayers = config.getInt("auto-drop.min-players", 1);
        this.minPlayers = Math.max(0, Math.min(1000, configuredMinPlayers));
        if (this.minPlayers != configuredMinPlayers) plugin.getLogger().warning("auto-drop.min-players must be between 0 and 1000; it was clamped.");
        this.broadcastWarnings = config.getIntegerList("auto-drop.broadcast-warnings").stream()
                .filter(minutes -> minutes > 0 && minutes < intervalMinutes).distinct().sorted(Comparator.reverseOrder()).toList();
        if (this.broadcastWarnings.isEmpty()) {
            this.broadcastWarnings = Arrays.asList(10, 5, 1);
        }

        this.worlds = config.getStringList("auto-drop.worlds");
        if (this.worlds.isEmpty()) {
            this.worlds = Collections.singletonList("world");
        }

        this.fixedScheduleEnabled = config.getBoolean("auto-drop.schedule.enabled", false);
        this.scheduledTimes = config.getStringList("auto-drop.schedule.times");
        this.scheduledDays = config.getStringList("auto-drop.schedule.days").stream().map(String::toUpperCase).toList();
        int configuredRadius = config.getInt("auto-drop.random-radius", 800);
        this.randomRadius = Math.max(1, Math.min(100000, configuredRadius));
        if (this.randomRadius != configuredRadius) plugin.getLogger().warning("auto-drop.random-radius must be between 1 and 100000; it was clamped.");

        // Reset or clamp remaining seconds if changed
        if (this.remainingSeconds <= 0 || this.remainingSeconds > (this.intervalMinutes * 60)) {
            this.remainingSeconds = this.intervalMinutes * 60;
        }
    }

    public void startTask() {
        if (task != null) {
            task.cancel();
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        if (!enabled) return;
        if (fixedScheduleEnabled) {
            checkFixedSchedule();
            return;
        }
        remainingSeconds--;
        if (remainingSeconds > 0 && remainingSeconds % 60 == 0) {
            int minutes = remainingSeconds / 60;
            if (broadcastWarnings.contains(minutes)) broadcastWarning(minutes);
        }
        if (remainingSeconds <= 0) {
            remainingSeconds = intervalMinutes * 60;
            triggerAutoDrop();
        }
    }
    private void checkFixedSchedule() {
        if (scheduledTimes.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();
        String minute = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        if (minute.equals(lastScheduledMinute) || !scheduledTimes.contains(now.format(DateTimeFormatter.ofPattern("HH:mm")))) return;
        if (!scheduledDays.isEmpty() && !scheduledDays.contains(now.getDayOfWeek().name())) return;
        lastScheduledMinute = minute;
        int chance = Math.max(0, Math.min(100, plugin.getConfig().getInt("auto-drop.schedule.chance-percent", 100)));
        if (ThreadLocalRandom.current().nextInt(100) >= chance) { plugin.getLogger().info("[AutoDrop] Scheduled event skipped by configured probability."); return; }
        PlayerBroadcast.send(LegacyComponentSerializer.legacySection().deserialize("§6☄ ¡Evento programado de meteoritos!"));
        triggerAutoDrop();
    }

    private void broadcastWarning(int minutes) {
        String msg = "§8§m--------------------------------------------------\n" +
                "§e☄ §6[EVENTO AUTOMÁTICO] §7¡Un meteorito caerá en §e" + minutes + " minutos§7!\n" +
                "§8§m--------------------------------------------------";
        PlayerBroadcast.send(LegacyComponentSerializer.legacySection().deserialize(msg));

        // Notify Discord channel
        if (plugin.getDiscordManager() != null) {
            plugin.getDiscordManager().sendAutoDropWarning(minutes);
        }
    }

    private void triggerAutoDrop() {
        int online = Bukkit.getOnlinePlayers().size();
        if (online < minPlayers) {
            plugin.getLogger().info("[AutoDrop] Automatic meteorite drop skipped: insufficient players online (" +
                    online + "/" + minPlayers + "). Postponing for next cycle.");
            return;
        }

        World world = null;
        if (!worlds.isEmpty()) {
            String worldName = worlds.get(ThreadLocalRandom.current().nextInt(worlds.size()));
            world = Bukkit.getWorld(worldName);
        }
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            plugin.getLogger().warning("[AutoDrop] Could not find any valid world to drop meteorite.");
            return;
        }

        MeteorType type = plugin.getMeteorTypeManager().getRandomType();
        if (type == null) {
            plugin.getLogger().warning("[AutoDrop] No meteorite types available for automatic drop.");
            return;
        }
        int targetX = 0, targetZ = 0;
        boolean safe = false;
        for (int attempt = 0; attempt < 30 && !safe; attempt++) {
            targetX = world.getSpawnLocation().getBlockX() + ThreadLocalRandom.current().nextInt(-randomRadius, randomRadius + 1);
            targetZ = world.getSpawnLocation().getBlockZ() + ThreadLocalRandom.current().nextInt(-randomRadius, randomRadius + 1);
            safe = plugin.getProtectionManager() == null || !plugin.getProtectionManager().isProtected(
                    new org.bukkit.Location(world, targetX, world.getSpawnLocation().getY(), targetZ), type.getCraterRadius());
        }
        if (!safe) {
            plugin.getLogger().warning("[AutoDrop] Could not find a safe impact position.");
            return;
        }
        boolean success = plugin.getMeteorManager().launchMeteor(type, world, targetX, targetZ);
        if (success) {
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public void cleanup() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
