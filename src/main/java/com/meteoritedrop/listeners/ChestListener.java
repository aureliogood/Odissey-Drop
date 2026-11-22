package com.meteoritedrop.listeners;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteoriteChest;
import com.meteoritedrop.models.UnlockSession;
import com.meteoritedrop.utils.CompassUtil;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to player interactions, movement, damage, and world block events related to meteorites.
 */
public class ChestListener implements Listener {

    private final MeteoriteDrop plugin;
    private final Map<UUID, Long> warzoneNotified = new ConcurrentHashMap<>();

    public ChestListener(MeteoriteDrop plugin) {
        this.plugin = plugin;
        startCompassTrackingTask();
    }

    private void startCompassTrackingTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                ItemStack main = p.getInventory().getItemInMainHand();
                ItemStack off = p.getInventory().getItemInOffHand();
                if (CompassUtil.isCosmicCompass(main, plugin) || CompassUtil.isCosmicCompass(off, plugin)) {
                    MeteoriteChest nearest = plugin.getChestManager().getNearestActiveChest(p.getLocation());
                    if (nearest != null) {
                        p.setCompassTarget(nearest.getChestLocation());
                    }
                }
            }
        }, 20L, 20L);
    }

    /**
     * Interacting with the chest or the meteorite core block.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }

        Location loc = clicked.getLocation();
        if (!plugin.getChestManager().isMeteoriteChest(loc)) {
            return;
        }

        // Cancel default block interactions
        event.setCancelled(true);

        Player player = event.getPlayer();
        MeteoriteChest chest = plugin.getChestManager().getChestAt(loc);
        if (chest == null) {
            return;
        }

        handleChestInteraction(player, chest);
    }

    /**
     * Interacting with the TextDisplay hologram floating above the chest.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        handleEntityInteraction(event.getPlayer(), event.getRightClicked(), event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntityGeneric(PlayerInteractEntityEvent event) {
        if (event instanceof PlayerInteractAtEntityEvent) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        handleEntityInteraction(event.getPlayer(), event.getRightClicked(), event);
    }

    private void handleEntityInteraction(Player player, Entity entity, org.bukkit.event.Cancellable cancellable) {
        if (entity == null) return;

        MeteoriteChest chest = plugin.getChestManager().getChestByHologram(entity.getUniqueId());
        if (chest == null && entity.getScoreboardTags().contains("meteoritedrop_hologram")) {
            chest = plugin.getChestManager().getChestAt(entity.getLocation().getBlock().getLocation());
        }

        if (chest != null) {
            cancellable.setCancelled(true);
            handleChestInteraction(player, chest);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onCosmicCompass(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND || (event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        if(!CompassUtil.isCosmicCompass(event.getItem(),plugin))return;
        event.setCancelled(true);Player player=event.getPlayer();MeteoriteChest nearest=plugin.getChestManager().getNearestActiveChest(player.getLocation());
        if(nearest==null){player.sendMessage("§7No hay meteoritos activos que rastrear.");return;}
        Location target=nearest.getChestLocation();double distance=Math.sqrt(player.getLocation().distanceSquared(target));
        player.setCompassTarget(target);player.sendMessage("§6La brújula apunta al meteorito en X: §e"+target.getBlockX()+" §6Z: §e"+target.getBlockZ()+" §8("+Math.round(distance)+" bloques)");
    }
    private void handleChestInteraction(Player player, MeteoriteChest chest) {
        if (plugin.getMeteorEventManager() != null && !plugin.getMeteorEventManager().canUnlock(chest, player)) return;
        if (chest.isLocked()) {
            player.sendMessage(plugin.getConfigManager().getChestLocked(chest.getRemainingLockSeconds()));
            Location soundLoc = chest.getChestLocation() != null ? chest.getChestLocation() : player.getLocation();
            player.playSound(soundLoc, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 1.0f, 1.0f);
            return;
        }

        // If chest is already unlocked or restoring, allow direct looting
        if (chest.getState() == MeteoriteChest.ChestState.UNLOCKED || chest.isRestoring()) {
            player.openInventory(chest.getLootInventory());
            return;
        }

        if(chest.getMeteorType()!=null && plugin.getKeyManager().isRequired(chest.getMeteorType().getId()) && !plugin.getKeyManager().has(player,chest.getMeteorType().getId())) {
            player.sendMessage("§cNecesitas la llave de este meteorito para abrirlo. Puedes comprarla con /md shop key " + chest.getMeteorType().getId() + "");
            return;
        }

        // NORMAL DROP: instant open on first click! No 5-second waiting channeling
        if (chest.getMeteorType() != null && chest.getMeteorType().isNormalDrop()) {
            handleNormalChestOpen(player, chest);
            return;
        }

        // Chest is UNLOCKABLE! Start or interrupt unlock channel
        plugin.getChestManager().startUnlockSession(player, chest);
    }

    private void handleNormalChestOpen(Player player, MeteoriteChest chest) {
        if (chest.getMeteorType() != null && plugin.getKeyManager().isRequired(chest.getMeteorType().getId())
                && !plugin.getKeyManager().consume(player, chest.getMeteorType().getId())) {
            player.sendMessage("§cNecesitas una llave de este meteorito.");
            return;
        }
        chest.setState(MeteoriteChest.ChestState.UNLOCKED);
        Location chestLoc = chest.getChestLocation();
        if (chestLoc != null) {
            player.openInventory(chest.getLootInventory());

            // If already empty right at opening, start crater restoration
            if (chest.getLootInventory().isEmpty()) {
                chest.startRestorationCountdown(plugin.getConfigManager().getCraterRestoreDelaySeconds());
            }
        }

        // Sound and title for the first looter
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0f, 1.0f);
        player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&a¡Has sido el primero en abrir el suministro!"));
        if (plugin.getDiscordManager() != null && chestLoc != null) plugin.getDiscordManager().sendLootClaimed(player.getName(), "Suministro Normal", chestLoc.getBlockX(), chestLoc.getBlockZ());

        player.showTitle(Title.title(
            LegacyComponentSerializer.legacySection().deserialize("§6§l¡SUMINISTRO RECLAMADO!"),
            LegacyComponentSerializer.legacySection().deserialize("§eHas abierto el cofre de suministros"),
            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(800))
        ));

        // Stats & Drop History recording
        if (plugin.getStatsManager() != null) {
            plugin.getStatsManager().recordLoot(player);
                if (plugin.getMissionsManager() != null) plugin.getMissionsManager().recordCapture(player);
        }
        if (plugin.getDropHistoryManager() != null && chestLoc != null) {
            plugin.getDropHistoryManager().setWinner(chestLoc, player.getName());
        }

        // Economy reward
        if (plugin.getEconomyManager() != null && plugin.getEconomyManager().isAvailable()) {
            double money = chest.getMeteorType().getRewardMoney();
            if (money > 0) {
                plugin.getEconomyManager().deposit(player, money);
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&6[Suministro] &e¡Recibiste &a" + plugin.getEconomyManager().format(money) + "&e de recompensa!"
                ));
            }
        }
        int xp = chest.getMeteorType().getRewardXp();
        if (xp > 0) {
            player.giveExp(xp);
            player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6[Suministro] &e¡Recibiste &b" + xp + " XP&e de recompensa!"
            ));
        }

        // Server-wide broadcast
        int x = chestLoc != null ? chestLoc.getBlockX() : 0;
        int z = chestLoc != null ? chestLoc.getBlockZ() : 0;
        String winMsg = "&8&m--------------------------------------------------\n" +
                "&e📦 &6&l¡SUMINISTRO RECLAMADO! &e📦\n" +
                "&f¡El jugador &a" + player.getName() + " &fha abierto el suministro en &eX: " + x + ", Z: " + z + "&f!\n" +
                "&8&m--------------------------------------------------";
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(winMsg));

        chest.onChestUnlocked();
    }

    /**
     * Cancel unlocking session if the player moves > 0.1 blocks.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UnlockSession session = plugin.getChestManager().getSession(player.getUniqueId());
        if (session == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }

        double dx = from.getX() - to.getX();
        double dy = from.getY() - to.getY();
        double dz = from.getZ() - to.getZ();
        double distSq = (dx * dx) + (dy * dy) + (dz * dz);

        if (distSq > (0.1 * 0.1)) {
            session.cancel(true);
        }
    }

    /**
     * Cancel unlocking session if the player takes any damage or is hit.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        UnlockSession session = plugin.getChestManager().getSession(player.getUniqueId());
        if (session != null) {
            session.cancel(true);
        }
    }

    /**
     * Show any active meteorite BossBars to connecting players and notify about active meteors.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Collection<MeteoriteChest> activeChests = plugin.getChestManager().getAllActiveChests();
        for (MeteoriteChest chest : activeChests) {
            if (chest.getGlobalBossBar() != null) {
                player.showBossBar(chest.getGlobalBossBar());
            }
        }
        // Notify about active meteorites 2 seconds after joining
        if (!activeChests.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&6[MeteoriteDrop] &e¡Hay " + activeChests.size() + " meteorito(s) activo(s) en el servidor!"
                ));
                for (MeteoriteChest chest : activeChests) {
                    Location loc = chest.getChestLocation();
                    String typeName = chest.getMeteorType() != null ? chest.getMeteorType().getDisplayName() : "Meteorito";
                    player.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "  &8» &f" + typeName + " &7en &eX: " + loc.getBlockX() + ", Z: " + loc.getBlockZ() +
                        " &8| &7" + chest.getState().name()
                    ));
                }
            }, 40L);
        }
    }

    /**
     * Detect when all loot is collected from a meteorite chest upon closing.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        MeteoriteChest chest = plugin.getChestManager().getChestByInventory(event.getInventory());
        if (chest == null) return;

        if (event.getInventory().isEmpty() && !chest.isRestoring()) {
            chest.startRestorationCountdown(plugin.getConfigManager().getCraterRestoreDelaySeconds());
        }
    }

    /**
     * Detect when the last item in a meteorite chest is picked up.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        MeteoriteChest chest = plugin.getChestManager().getChestByInventory(event.getInventory());
        if (chest == null) return;

        // Run 1 tick later to evaluate inventory contents after the click is processed
        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getInventory().isEmpty() && !chest.isRestoring()) {
                chest.startRestorationCountdown(plugin.getConfigManager().getCraterRestoreDelaySeconds());
            }
        });
    }

    /**
     * Cancel session if the player quits or disconnects.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getChestManager().cancelSession(event.getPlayer().getUniqueId(), false);
    }

    /**
     * Prevent breaking the active meteorite chest and core block.
     * All other crater blocks (ores, terrain) can be mined normally.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getChestManager().isProtectedBlock(event.getBlock().getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&c¡No puedes romper el cofre del meteorito mientras el evento esté activo!"
            ));
        }
    }

    /**
     * Prevent explosions from destroying active meteorite chests and crater cores.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.getChestManager().isProtectedBlock(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> plugin.getChestManager().isProtectedBlock(block.getLocation()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMeteorDisplayDamage(EntityDamageEvent event) {
        if (event.getEntity().getScoreboardTags().contains("meteoritedrop_chest_visual")
                || event.getEntity().getScoreboardTags().contains("meteoritedrop_hologram")
                || event.getEntity().getScoreboardTags().contains("meteoritedrop_chest_interaction")) {
            event.setCancelled(true);
        }
    }

    /**
     * Detect when a falling meteor entity makes contact.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Entity entity = event.getEntity();
        if (plugin.getMeteorManager().isFallingMeteor(entity)) {
            event.setCancelled(true);
            plugin.getMeteorManager().onFallingBlockHit(entity);
        }
    }
}
