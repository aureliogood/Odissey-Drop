package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.models.MeteoriteChest;
import com.meteoritedrop.models.UnlockSession;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages tracking of all active meteorite chests, crater blocks, holograms, and unlocking sessions.
 */
public class ChestManager {

    public record BlockKey(UUID worldUid, int x, int y, int z) {
        public static BlockKey of(Location loc) {
            if (loc == null || loc.getWorld() == null) return null;
            return new BlockKey(loc.getWorld().getUID(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    private final MeteoriteDrop plugin;
    private final Map<BlockKey, MeteoriteChest> chestsByLocation = new ConcurrentHashMap<>();
    private final Map<UUID, MeteoriteChest> chestsByHologram = new ConcurrentHashMap<>();
    private final Set<MeteoriteChest> allActiveChests = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UnlockSession> activeSessions = new ConcurrentHashMap<>();

    private BukkitTask hologramTask;
    private boolean pulseToggle = false;
    private long animationTicks;

    public ChestManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        startHologramUpdateTask();
    }

    private void startHologramUpdateTask() {
        this.hologramTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateAllHolograms, 20L, 20L);
    }

    /**
     * Updates text on all active TextDisplay holograms according to each chest's state.
     */
    public void updateAllHolograms() {
        pulseToggle = !pulseToggle;
        animationTicks += 20;
        for (MeteoriteChest chest : allActiveChests) {
            updateHologram(chest, pulseToggle);
            Location base = chest.getDisplayBaseLocation();
            if (base != null && chest.getChestDisplayUuid() != null && !chest.isRestoring()) {
                Entity visual = base.getWorld().getEntity(chest.getChestDisplayUuid());
                if (visual instanceof BlockDisplay display && display.isValid()) {
                    double bob = Math.sin(animationTicks * 0.08 + chest.getChestLocation().getBlockX()) * 0.12;
                    Location animated = base.clone().add(0, bob, 0);
                    display.teleport(animated);
                    Entity hitbox = base.getWorld().getEntity(chest.getInteractionUuid());
                    if (hitbox != null && hitbox.isValid()) hitbox.teleport(animated);
                }
            }
        }
    }

    private void updateHologram(MeteoriteChest chest, boolean pulse) {
        if (chest.isRestoring() || chest.getState() == MeteoriteChest.ChestState.RESTORED) {
            return;
        }

        if (chest.getHologramUuid() == null || chest.getChestLocation() == null || chest.getChestLocation().getWorld() == null) {
            return;
        }

        Entity entity = chest.getChestLocation().getWorld().getEntity(chest.getHologramUuid());
        if (!(entity instanceof TextDisplay display) || !display.isValid()) {
            return;
        }

        String text;
        boolean isNormal = chest.getMeteorType() != null && chest.getMeteorType().isNormalDrop();
        String icon = isNormal ? "📦" : "☄";

        if (chest.isLocked()) {
            long remaining = chest.getRemainingLockSeconds();
            long minutes = remaining / 60;
            long seconds = remaining % 60;
            if (isNormal) {
                text = "§e" + icon + " §6[SUMINISTRO]\n§7Abre en: §c" + String.format("%02d:%02d", minutes, seconds);
            } else {
                text = "§c" + icon + " §4[SELLADO]\n§7Abre en: §c" + String.format("%02d:%02d", minutes, seconds);
            }
        } else if (chest.getCurrentSession() != null) {
            String playerName = chest.getCurrentSession().getPlayer().getName();
            text = "§e" + icon + " §6[ABRIENDO...]\n§e" + playerName + " §7está abriendo...";
        } else if (chest.getState() == MeteoriteChest.ChestState.UNLOCKED) {
            text = "§a" + icon + " §2[DESBLOQUEADO]\n§7¡Recoge el botín!";
        } else {
            // UNLOCKABLE: pulsing effect alternating each second
            if (isNormal) {
                text = "§a" + icon + " §2[¡SUMINISTRO LISTO!]\n§e¡Click derecho para abrir!";
            } else if (pulse) {
                text = "§a" + icon + " §2[LISTO!]\n§7¡Haz click para abrir!";
            } else {
                text = "§e" + icon + " §6[LISTO!]\n§7¡Haz click para abrir!";
            }
        }

        display.text(LegacyComponentSerializer.legacySection().deserialize(text));

        // Update Global BossBar
        net.kyori.adventure.bossbar.BossBar bossBar = chest.getGlobalBossBar();
        if (bossBar != null && !chest.isRestoring()) {
            String typeName = chest.getMeteorType() != null ? chest.getMeteorType().getDisplayName() : "Meteorito";
            int x = chest.getChestLocation().getBlockX();
            int z = chest.getChestLocation().getBlockZ();
            if (chest.isLocked()) {
                long remaining = chest.getRemainingLockSeconds();
                long minutes = remaining / 60;
                long seconds = remaining % 60;
                int totalLock = Math.max(1, chest.getConfiguredLockSeconds());
                float ratio = Math.max(0.0f, Math.min(1.0f, (float) remaining / (float) totalLock));
                bossBar.name(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&e⚠️ " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &c[SELLADO " + String.format("%02d:%02d", minutes, seconds) + "]"
                ));
                bossBar.progress(ratio);
            } else if (chest.getCurrentSession() != null) {
                bossBar.name(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&e☄ " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &e[Abriendo: " + chest.getCurrentSession().getPlayer().getName() + "]"
                ));
            } else if (chest.getState() == MeteoriteChest.ChestState.UNLOCKED) {
                bossBar.name(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&a" + icon + " " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &a[¡ABIERTO! Recoge el botín]"
                ));
                bossBar.progress(0.0f);
            } else {
                bossBar.name(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&a" + icon + " " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &a[¡LISTO PARA ABRIR!]"
                ));
                bossBar.progress(1.0f);
            }
        }
    }

    /**
     * Registers a newly spawned meteorite chest, its core block, its hologram, and its original terrain blocks.
     */
    public MeteoriteChest registerChest(Location chestLoc, Location coreLoc, UUID hologramUuid,
                                        MeteorType meteorType, Map<Location, org.bukkit.block.data.BlockData> originalBlocks) {
        return registerChest(chestLoc, coreLoc, hologramUuid, meteorType, originalBlocks, false);
    }

    public MeteoriteChest registerChest(Location chestLoc, Location coreLoc, UUID hologramUuid,
                                        MeteorType meteorType, Map<Location, org.bukkit.block.data.BlockData> originalBlocks,
                                        boolean partOfShower) {
        return registerChest(chestLoc, coreLoc, hologramUuid, null, meteorType, originalBlocks, partOfShower);
    }

    public MeteoriteChest registerChest(Location chestLoc, Location coreLoc, UUID hologramUuid, UUID chestDisplayUuid,
                                        MeteorType meteorType, Map<Location, org.bukkit.block.data.BlockData> originalBlocks,
                                        boolean partOfShower) {
        return registerChest(chestLoc, coreLoc, hologramUuid, chestDisplayUuid, null, meteorType, originalBlocks, partOfShower);
    }

    public MeteoriteChest registerChest(Location chestLoc, Location coreLoc, UUID hologramUuid, UUID chestDisplayUuid,
                                        UUID interactionUuid, MeteorType meteorType,
                                        Map<Location, org.bukkit.block.data.BlockData> originalBlocks,
                                        boolean partOfShower) {
        MeteoriteChest chest = new MeteoriteChest(plugin, chestLoc, coreLoc, hologramUuid, chestDisplayUuid,
                interactionUuid, meteorType, originalBlocks, partOfShower);
        allActiveChests.add(chest);

        BlockKey chestKey = BlockKey.of(chestLoc);
        if (chestKey != null) {
            chestsByLocation.put(chestKey, chest);
        }

        BlockKey coreKey = BlockKey.of(coreLoc);
        if (coreKey != null) {
            chestsByLocation.put(coreKey, chest);
        }

        if (hologramUuid != null) {
            chestsByHologram.put(hologramUuid, chest);
        }
        if (chestDisplayUuid != null) chestsByHologram.put(chestDisplayUuid, chest);
        if (interactionUuid != null) chestsByHologram.put(interactionUuid, chest);

        return chest;
    }

    /**
     * Checks if a given block location belongs to an active meteorite chest or its core block.
     */
    public boolean isMeteoriteChest(Location loc) {
        BlockKey key = BlockKey.of(loc);
        return key != null && chestsByLocation.containsKey(key);
    }

    /**
     * Retrieves the MeteoriteChest at the given location (chest or core block).
     */
    public MeteoriteChest getChestAt(Location loc) {
        BlockKey key = BlockKey.of(loc);
        return key != null ? chestsByLocation.get(key) : null;
    }

    /**
     * Retrieves the MeteoriteChest associated with a hologram TextDisplay UUID.
     */
    public MeteoriteChest getChestByHologram(UUID uuid) {
        return uuid != null ? chestsByHologram.get(uuid) : null;
    }

    public MeteoriteChest getChestByInventory(org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) return null;
        for (MeteoriteChest chest : allActiveChests) {
            if (chest.getLootInventory() == inventory) return chest;
        }
        return null;
    }

    /**
     * Checks whether a block is protected from being broken or exploded.
     * Only the chest block and the meteor core block directly above it are protected.
     * All other crater blocks (ores, terrain, etc.) can be broken normally to avoid dupe glitches.
     */
    public boolean isProtectedBlock(Location loc) {
        if (loc == null) return false;
        // Only the chest block and the core block on top of it are indestructible
        return isMeteoriteChest(loc);
    }

    public Collection<MeteoriteChest> getAllActiveChests() {
        return Collections.unmodifiableCollection(allActiveChests);
    }

    /**
     * Finds the nearest active meteorite chest to the given location in the same world.
     */
    public MeteoriteChest getNearestActiveChest(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        MeteoriteChest nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (MeteoriteChest chest : allActiveChests) {
            if (chest.getState() == MeteoriteChest.ChestState.RESTORED) continue;
            Location cLoc = chest.getChestLocation();
            if (cLoc == null || !loc.getWorld().equals(cLoc.getWorld())) continue;
            double distSq = loc.distanceSquared(cLoc);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = chest;
            }
        }
        return nearest;
    }

    /**
     * Begins an unlocking session for a player.
     */
    public void startUnlockSession(Player player, MeteoriteChest chest) {
        // If another player is already unlocking this chest, cancel their session first
        if (chest.getCurrentSession() != null) {
            chest.getCurrentSession().cancel(true);
        }

        // If this player already has an ongoing session elsewhere, cancel it silently
        cancelSession(player.getUniqueId(), false);

        int duration = chest.getMeteorType() != null ? chest.getMeteorType().getUnlockDurationSeconds() : 5;
        UnlockSession session = new UnlockSession(plugin, player, chest, duration);
        activeSessions.put(player.getUniqueId(), session);
        chest.setCurrentSession(session);
        session.start();

        // Immediately update hologram to show "ABRIENDO..."
        updateHologram(chest, pulseToggle);
    }

    /**
     * Cancels an active unlocking session for a player.
     */
    public void cancelSession(UUID playerUuid, boolean notifyPlayer) {
        UnlockSession session = activeSessions.remove(playerUuid);
        if (session != null) {
            MeteoriteChest chest = session.getChest();
            session.cancel(notifyPlayer);
            if (chest != null) {
                updateHologram(chest, pulseToggle);
            }
        }
    }

    /**
     * Removes an active session from the internal map.
     */
    public void removeSession(UUID playerUuid) {
        activeSessions.remove(playerUuid);
    }

    /**
     * Retrieves the active unlocking session for a player.
     */
    public UnlockSession getSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }

    /**
     * Checks whether a player is actively unlocking a chest.
     */
    public boolean hasActiveSession(UUID playerUuid) {
        return activeSessions.containsKey(playerUuid);
    }

    /**
     * Removes tracking of a chest when unlocked or cleaned up, making it a regular chest.
     */
    public void untrackChest(Location chestLoc) {
        MeteoriteChest chest = getChestAt(chestLoc);
        if (chest == null) {
            return;
        }

        allActiveChests.remove(chest);

        BlockKey chestKey = BlockKey.of(chest.getChestLocation());
        if (chestKey != null) {
            chestsByLocation.remove(chestKey);
        }

        BlockKey coreKey = BlockKey.of(chest.getCoreLocation());
        if (coreKey != null) {
            chestsByLocation.remove(coreKey);
        }

        if (chest.getHologramUuid() != null) {
            chestsByHologram.remove(chest.getHologramUuid());
        }
        if (chest.getChestDisplayUuid() != null) chestsByHologram.remove(chest.getChestDisplayUuid());
        if (chest.getInteractionUuid() != null) chestsByHologram.remove(chest.getInteractionUuid());
    }

    /**
     * Cleans up all active sessions, repeating tasks, and hologram entities on plugin disable.
     */
    public void cleanup() {
        if (hologramTask != null) {
            hologramTask.cancel();
            hologramTask = null;
        }

        // Cancel all active player sessions
        for (UnlockSession session : activeSessions.values()) {
            session.cancel(false);
        }
        activeSessions.clear();

        // Restore all active craters on shutdown to prevent world griefing
        for (MeteoriteChest chest : allActiveChests) {
            chest.removeHologram();
            chest.removeGlobalBossBar();
            chest.restoreCrater();
        }
        allActiveChests.clear();
        chestsByLocation.clear();
        chestsByHologram.clear();
    }
}
