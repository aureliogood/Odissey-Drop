package com.meteoritedrop.models;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;

/**
 * Represents an active channeling session where a player attempts to unlock a meteorite chest.
 */
public class UnlockSession {

    private final MeteoriteDrop plugin;
    private final Player player;
    private final MeteoriteChest chest;
    private final Location startLocation;
    private final int totalDurationSeconds;

    private BossBar bossBar;
    private BukkitTask task;
    private int elapsedSeconds;

    private boolean completed = false;
    private boolean cancelled = false;

    public UnlockSession(MeteoriteDrop plugin, Player player, MeteoriteChest chest, int durationSeconds) {
        this.plugin = plugin;
        this.player = player;
        this.chest = chest;
        this.startLocation = player.getLocation().clone();
        this.totalDurationSeconds = durationSeconds > 0 ? durationSeconds : 5;
        this.elapsedSeconds = 0;
    }

    /**
     * Begins the channeling session.
     */
    public void start() {
        // Create Adventure BossBar (PURPLE, NOTCHED_6)
        this.bossBar = BossBar.bossBar(
                LegacyComponentSerializer.legacyAmpersand().deserialize("&d☄ Abriendo meteorito..."),
                1.0f,
                BossBar.Color.PURPLE,
                BossBar.Overlay.NOTCHED_6
        );

        player.showBossBar(bossBar);
        player.sendMessage(plugin.getConfigManager().getUnlockStart());

        // Send initial progress at 0 seconds
        updateFeedback(0);

        // Schedule periodic task running every 20 ticks (1 second)
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        if (cancelled || completed) {
            return;
        }

        // Validate player is still online and valid
        if (!player.isOnline()) {
            cancel(false);
            return;
        }

        elapsedSeconds++;
        updateFeedback(elapsedSeconds);

        if (elapsedSeconds >= totalDurationSeconds) {
            complete();
        }
    }

    private void updateFeedback(int elapsed) {
        // BossBar progress: 1.0 down to 0.0 over total duration
        float remainingRatio = Math.max(0.0f, Math.min(1.0f, (float) (totalDurationSeconds - elapsed) / (float) totalDurationSeconds));
        bossBar.progress(remainingRatio);

        // Actionbar: [████░░░░░░] {elapsed}/5s
        String bar = buildProgressBar(elapsed, totalDurationSeconds, 10);
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&aAbriendo... [" + bar + "] " + elapsed + "/" + totalDurationSeconds + "s"
        ));

        // Soft chest open sound with subtle pitch increase
        Location soundLoc = chest.getChestLocation() != null ? chest.getChestLocation() : player.getLocation();
        float pitch = 1.0f + (elapsed * 0.08f);
        player.playSound(soundLoc, Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.5f, pitch);
    }

    /**
     * Builds a 10-character progress bar string with filled ('█') and empty ('░') slots.
     */
    public static String buildProgressBar(int elapsed, int total, int length) {
        if (total <= 0) total = 5;
        int filled = Math.min(length, Math.max(0, (elapsed * length) / total));
        int empty = length - filled;
        return "█".repeat(filled) + "░".repeat(empty);
    }

    /**
     * Interrupts and cancels this session.
     *
     * @param notifyPlayer whether to send interruption message & sound to the player.
     */
    public synchronized void cancel(boolean notifyPlayer) {
        if (cancelled || completed) {
            return;
        }
        cancelled = true;

        if (task != null) {
            task.cancel();
            task = null;
        }

        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }

        if (notifyPlayer && player.isOnline()) {
            player.sendMessage(plugin.getConfigManager().getUnlockInterrupted());
            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.7f, 1.0f);
            player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize("&c¡Interrumpido!"));

            // Consolation XP is limited per player and stored in their persistent data,
            // so canceling unlocks repeatedly cannot be used as an XP farm.
            int consolationXp = plugin.getConfig().getInt("consolation-xp", 10);
            NamespacedKey cooldownKey = new NamespacedKey(plugin, "consolation_xp_next_at");
            long configuredCooldown = plugin.getConfig().getLong("consolation-xp-cooldown-seconds", 3600L);
            long cooldownMillis = Math.max(60L, Math.min(2_592_000L, configuredCooldown)) * 1000L;
            long now = System.currentTimeMillis();
            long nextAllowed = player.getPersistentDataContainer().getOrDefault(cooldownKey, PersistentDataType.LONG, 0L);
            if (consolationXp > 0 && now >= nextAllowed) {
                player.getPersistentDataContainer().set(cooldownKey, PersistentDataType.LONG, now + cooldownMillis);
                player.giveExp(consolationXp);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&8[MeteoriteDrop] &7Recibiste &b" + consolationXp + " XP &7de consolación por intentarlo."
                ));
            }
        }

        chest.setCurrentSession(null);
        plugin.getChestManager().removeSession(player.getUniqueId());
    }

    /**
     * Successfully completes the unlock process.
     */
    public synchronized void complete() {
        if (cancelled || completed) {
            return;
        }
        if (player.isOnline() && chest.getMeteorType() != null && plugin.getKeyManager().isRequired(chest.getMeteorType().getId()) && !plugin.getKeyManager().consume(player, chest.getMeteorType().getId())) {
            player.sendMessage("§cNecesitas conservar la llave hasta terminar de abrir el cofre.");
            cancel(false);
            return;
        }
        completed = true;

        if (task != null) {
            task.cancel();
            task = null;
        }

        if (bossBar != null) {
            player.hideBossBar(bossBar);
        }

        if (player.isOnline()) {
            // Special effect for cursed meteor type
            String meteorTypeId = chest.getMeteorType() != null ? chest.getMeteorType().getId().toLowerCase() : "";
            if (meteorTypeId.contains("cursed") || meteorTypeId.contains("maldito")) {
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.WITHER, 160, 1, false, true, true));
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(
                    org.bukkit.potion.PotionEffectType.BLINDNESS, 100, 0, false, true, true));
                player.sendMessage(LegacyComponentSerializer.legacySection().deserialize(
                    "§4☠ ¡La maldición del meteorito te ha afectado! Sobrevive para reclamar tu botín..."));
            }

            player.sendMessage(plugin.getConfigManager().getUnlockComplete());
            player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize("&a¡Cofre abierto con éxito!"));

            if (chest.getMeteorType() != null && chest.getMeteorType().getId().equalsIgnoreCase("meteoriteritual")) plugin.getRitualManager().release(chest.getChestLocation());

            // Record looter stats
            if (plugin.getStatsManager() != null) {
                plugin.getStatsManager().recordLoot(player);
                if (plugin.getMissionsManager() != null) plugin.getMissionsManager().recordCapture(player);
            }
            if (plugin.getMeteorEventManager() != null) plugin.getMeteorEventManager().rewardContributors(chest, player);

            // Record winner in drop history
            if (plugin.getDropHistoryManager() != null) {
                Location chestLoc2 = chest.getChestLocation();
                if (chestLoc2 != null) {
                    plugin.getDropHistoryManager().setWinner(chestLoc2, player.getName());
                }
            }

            // Economy reward (Vault)
            if (plugin.getEconomyManager() != null && plugin.getEconomyManager().isAvailable()) {
                double reward = chest.getMeteorType() != null ? chest.getMeteorType().getRewardMoney() : 0;
                if (reward > 0) {
                    plugin.getEconomyManager().deposit(player, reward);
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&6[MeteoriteDrop] &e¡Recibiste &a" + plugin.getEconomyManager().format(reward) + "&e por abrir el meteorito!"
                    ));
                }
            }

            // XP reward
            int xp = chest.getMeteorType() != null ? chest.getMeteorType().getRewardXp() : 0;
            if (xp > 0) {
                player.giveExp(xp);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&6[MeteoriteDrop] &e¡Recibiste &b" + xp + " puntos de experiencia&e!"
                ));
            }

            // Show victory title to looter
            Title winTitle = Title.title(
                    LegacyComponentSerializer.legacySection().deserialize("§6§l¡VICTORIA!"),
                    LegacyComponentSerializer.legacySection().deserialize("§eHas abierto el cofre del meteorito"),
                    Title.Times.times(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofMillis(3000),
                            java.time.Duration.ofMillis(1000)
                    )
            );
            player.showTitle(winTitle);

            // Broadcast victory announcement to whole server
            String typeName = chest.getMeteorType() != null ? chest.getMeteorType().getDisplayName() : "Meteorito";
            String victoryBroadcast = "§8§m--------------------------------------------------\n" +
                    "§6👑 §e§l¡BOTÍN RECLAMADO! §6👑\n" +
                    "§f¡El jugador §a" + player.getName() + " §fha conquistado el §e" + typeName + "§f!\n" +
                    "§7¡Felicidades al superviviente!\n" +
                    "§8§m--------------------------------------------------";
            PlayerBroadcast.send(LegacyComponentSerializer.legacySection().deserialize(victoryBroadcast));
            if (plugin.getDiscordManager() != null && chest.getChestLocation() != null) plugin.getDiscordManager().sendLootClaimed(player.getName(), typeName, chest.getChestLocation().getBlockX(), chest.getChestLocation().getBlockZ());

            // Play triumphant celebration sound to all players
            Sound victorySound=Sound.UI_TOAST_CHALLENGE_COMPLETE;
            try{victorySound=Sound.valueOf(plugin.getConfig().getString("event-announcements.victory-sound","UI_TOAST_CHALLENGE_COMPLETE").toUpperCase(java.util.Locale.ROOT));}catch(IllegalArgumentException ignored){}
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), victorySound, SoundCategory.MASTER, 1.0f, 1.0f);

            // Open the chest inventory for the player
            Location chestLoc = chest.getChestLocation();
            if (chestLoc != null && chestLoc.getWorld() != null) {
                player.openInventory(chest.getLootInventory());
                if (chest.getLootInventory().isEmpty()) {
                    chest.startRestorationCountdown(plugin.getConfigManager().getCraterRestoreDelaySeconds());
                }
            }
        }

        // Notify chest it is unlocked and schedule safety cleanup
        chest.setCurrentSession(null);
        chest.onChestUnlocked();

        plugin.getChestManager().removeSession(player.getUniqueId());
    }

    public Player getPlayer() {
        return player;
    }

    public MeteoriteChest getChest() {
        return chest;
    }

    public Location getStartLocation() {
        return startLocation;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isCompleted() {
        return completed;
    }
}
