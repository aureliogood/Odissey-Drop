package com.meteoritedrop.models;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.Inventory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents an active meteorite chest, its crater blocks, floating countdown hologram,
 * global server BossBar, and automated 30-second terrain restoration.
 */
public class MeteoriteChest {

    public enum ChestState {
        LOCKED,
        UNLOCKABLE,
        BEING_UNLOCKED,
        UNLOCKED,
        RESTORING,
        RESTORED
    }

    private final MeteoriteDrop plugin;
    private final Location chestLocation;
    private final Location coreLocation;
    private final UUID hologramUuid;
    private final UUID chestDisplayUuid;
    private final UUID interactionUuid;
    private final MeteorType meteorType;
    private final boolean partOfShower;
    private boolean showerReleased;
    private volatile long unlockAvailableTimeMillis;
    private volatile int configuredLockSeconds;
    private final Map<Location, BlockData> originalBlocks = new HashMap<>();
    private final Inventory lootInventory;
    private final Location displayBaseLocation;
    private final Set<UUID> guardianUuids = ConcurrentHashMap.newKeySet();

    private ChestState state;
    private UnlockSession currentSession;
    private BossBar globalBossBar;

    private boolean restoring = false;
    private int restoreCountdownSeconds;
    private BukkitTask restoreTask;
    private BukkitTask maxStayTask;
    private BukkitTask unopenedExpiryTask;
    private BukkitTask beamTask;
    private BukkitTask restoreAnimationTask;

    public MeteoriteChest(MeteoriteDrop plugin, Location chestLocation, Location coreLocation,
                          UUID hologramUuid, MeteorType meteorType, Map<Location, BlockData> originalBlocks) {
        this(plugin, chestLocation, coreLocation, hologramUuid, meteorType, originalBlocks, false);
    }

    public MeteoriteChest(MeteoriteDrop plugin, Location chestLocation, Location coreLocation,
                          UUID hologramUuid, MeteorType meteorType, Map<Location, BlockData> originalBlocks,
                          boolean partOfShower) {
        this(plugin, chestLocation, coreLocation, hologramUuid, null, null, meteorType, originalBlocks, partOfShower);
    }

    public MeteoriteChest(MeteoriteDrop plugin, Location chestLocation, Location coreLocation,
                          UUID hologramUuid, UUID chestDisplayUuid, MeteorType meteorType,
                          Map<Location, BlockData> originalBlocks, boolean partOfShower) {
        this(plugin, chestLocation, coreLocation, hologramUuid, chestDisplayUuid, null, meteorType, originalBlocks, partOfShower);
    }

    public MeteoriteChest(MeteoriteDrop plugin, Location chestLocation, Location coreLocation,
                          UUID hologramUuid, UUID chestDisplayUuid, UUID interactionUuid, MeteorType meteorType,
                          Map<Location, BlockData> originalBlocks, boolean partOfShower) {
        this.plugin = plugin;
        this.chestLocation = chestLocation;
        this.coreLocation = coreLocation;
        this.hologramUuid = hologramUuid;
        this.chestDisplayUuid = chestDisplayUuid;
        this.interactionUuid = interactionUuid;
        this.meteorType = meteorType;
        this.partOfShower = partOfShower;
        this.displayBaseLocation = chestLocation == null ? null : chestLocation.clone().add(0.1, 0.35, 0.1);
        LootInventoryHolder inventoryHolder = new LootInventoryHolder();
        String title = meteorType == null ? "Botín del meteorito" : meteorType.getDisplayName();
        this.lootInventory = Bukkit.createInventory(inventoryHolder, 54,
                LegacyComponentSerializer.legacyAmpersand().deserialize("&6" + title));
        inventoryHolder.setInventory(lootInventory);
        if (meteorType != null) {
            List<org.bukkit.inventory.ItemStack> loot = meteorType.generateLoot();
            Collections.shuffle(loot);
            int stacksThatDidNotFit = 0;
            for (org.bukkit.inventory.ItemStack item : loot) {
                // addItem merges compatible stacks and splits oversized stacks. Never spill
                // configured rewards into the world, where they could be stolen or lost.
                if (!lootInventory.addItem(item).isEmpty()) stacksThatDidNotFit++;
            }
            if (stacksThatDidNotFit > 0) {
                plugin.getLogger().warning("Loot for meteor type '" + meteorType.getId() + "' exceeds the 54-slot chest capacity; "
                        + stacksThatDidNotFit + " configured stack(s) were omitted. Reduce its loot list.");
            }
        }
        if (originalBlocks != null) {
            this.originalBlocks.putAll(originalBlocks);
        }

        int lockSeconds = meteorType != null ? meteorType.getLockDurationSeconds() : 120;
        this.configuredLockSeconds = Math.max(0, lockSeconds);
        this.unlockAvailableTimeMillis = System.currentTimeMillis() + (lockSeconds * 1000L);
        this.state = ChestState.LOCKED;

        initGlobalBossBar();
        startBeaconBeam();
        scheduleUnopenedExpiry();
    }

    private void startBeaconBeam() {
        if (chestLocation == null || chestLocation.getWorld() == null) return;
        this.beamTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state == ChestState.RESTORED || chestLocation.getWorld() == null) {
                if (beamTask != null) {
                    beamTask.cancel();
                    beamTask = null;
                }
                return;
            }

            World world = chestLocation.getWorld();
            double cx = chestLocation.getX() + 0.5;
            double cz = chestLocation.getZ() + 0.5;
            double startY = chestLocation.getY() + 1.2;
            double topY = Math.min(world.getMaxHeight() - 5, startY + 70.0);

            Particle beamParticle = Particle.END_ROD;
            String typeId = meteorType != null ? meteorType.getId().toLowerCase() : "lava";

            if (typeId.contains("ice")) {
                beamParticle = Particle.SNOWFLAKE;
            } else if (typeId.contains("legend")) {
                beamParticle = Particle.END_ROD;
            } else if (typeId.contains("end")) {
                beamParticle = Particle.PORTAL;
            } else if (typeId.contains("divine") || typeId.contains("celestial")) {
                beamParticle = Particle.TOTEM_OF_UNDYING;
            } else if (typeId.contains("cursed") || typeId.contains("maldito")) {
                beamParticle = Particle.SOUL_FIRE_FLAME;
            } else if (typeId.contains("abyssal") || typeId.contains("abisal")) {
                beamParticle = Particle.GLOW;
            } else if (typeId.contains("corrupted") || typeId.contains("corrupto")) {
                beamParticle = Particle.SCULK_SOUL;
            } else if (typeId.contains("storm") || typeId.contains("tormenta")) {
                beamParticle = Particle.ELECTRIC_SPARK;
            } else {
                beamParticle = Particle.FLAME;
            }

            // Draw vertical light beam every 2 blocks up to the sky
            for (double y = startY; y <= topY; y += 2.0) {
                world.spawnParticle(beamParticle, cx, y, cz, 2, 0.1, 0.0, 0.1, 0.01);
            }
        }, 10L, 10L);
    }

    private void initGlobalBossBar() {
        if (partOfShower) {
            this.globalBossBar = plugin.getMeteorManager().getShowerBossBar();
            return;
        }
        BossBar.Color color = BossBar.Color.RED;
        if (meteorType != null) {
            String id = meteorType.getId().toLowerCase();
            if (id.contains("ice")) color = BossBar.Color.BLUE;
            else if (id.contains("end") || id.contains("legend")) color = BossBar.Color.PURPLE;
            else if (id.contains("divine") || id.contains("celestial")) color = BossBar.Color.YELLOW;
            else if (id.contains("toxic") || id.contains("corrupto")) color = BossBar.Color.GREEN;
            else if (id.contains("storm") || id.contains("tormenta")) color = BossBar.Color.BLUE;
        }

        String typeName = meteorType != null ? meteorType.getDisplayName() : "Meteorito";
        int x = chestLocation.getBlockX();
        int z = chestLocation.getBlockZ();

        this.globalBossBar = BossBar.bossBar(
                LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&e⚠️ " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &c[SELLADO]"
                ),
                1.0f,
                color,
                BossBar.Overlay.NOTCHED_10
        );

        // Show BossBar to all online players
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showBossBar(globalBossBar);
        }
    }

    /**
     * Checks if the chest is still locked by time.
     */
    public boolean isLocked() {
        return System.currentTimeMillis() < unlockAvailableTimeMillis;
    }

    /**
     * Returns the remaining lock duration in seconds.
     */
    public long getRemainingLockSeconds() {
        long diff = unlockAvailableTimeMillis - System.currentTimeMillis();
        return Math.max(0, (long) Math.ceil(diff / 1000.0));
    }

    public ChestState getState() {
        if (state == ChestState.LOCKED && !isLocked() && currentSession == null) {
            state = ChestState.UNLOCKABLE;
        }
        return state;
    }

    public void setState(ChestState state) {
        this.state = state;
        if(state==ChestState.UNLOCKED||state==ChestState.RESTORING||state==ChestState.RESTORED)cancelUnopenedExpiry();
    }

    public UnlockSession getCurrentSession() {
        return currentSession;
    }

    public void setCurrentSession(UnlockSession currentSession) {
        this.currentSession = currentSession;
        if (currentSession != null) {
            this.state = ChestState.BEING_UNLOCKED;
        } else if (!isLocked() && this.state != ChestState.UNLOCKED && !restoring) {
            this.state = ChestState.UNLOCKABLE;
        }
    }

    public Location getChestLocation() {
        return chestLocation;
    }

    public Location getCoreLocation() {
        return coreLocation;
    }

    public Location getMagmaLocation() {
        return coreLocation;
    }

    public UUID getHologramUuid() {
        return hologramUuid;
    }

    public UUID getChestDisplayUuid() { return chestDisplayUuid; }
    public UUID getInteractionUuid() { return interactionUuid; }
    public Inventory getLootInventory() { return lootInventory; }
    public Location getDisplayBaseLocation() { return displayBaseLocation == null ? null : displayBaseLocation.clone(); }

    public MeteorType getMeteorType() {
        return meteorType;
    }

    public long getUnlockAvailableTimeMillis() {
        return unlockAvailableTimeMillis;
    }

    /** Adds or removes lock time from a live meteorite; returns false once it is no longer locked. */
    public synchronized boolean adjustLockSeconds(int delta) {
        if (state != ChestState.LOCKED || !isLocked()) return false;
        long now=System.currentTimeMillis();
        long remaining=Math.max(0,unlockAvailableTimeMillis-now);
        long next=Math.max(0,Math.min(86_400_000L,remaining+(long)delta*1000L));
        unlockAvailableTimeMillis=now+next;
        configuredLockSeconds=(int)Math.max(1,(next+999)/1000);
        return true;
    }

    public int getConfiguredLockSeconds() { return configuredLockSeconds; }

    public BossBar getGlobalBossBar() {
        return partOfShower ? plugin.getMeteorManager().getShowerBossBar() : globalBossBar;
    }

    public Map<Location, BlockData> getOriginalBlocks() {
        return originalBlocks;
    }

    public boolean isRestoring() {
        return restoring;
    }

    /**
     * Called when the chest is unlocked to schedule max stay cleanup in case it's never emptied.
     */
    public void onChestUnlocked() {
        this.state = ChestState.UNLOCKED;
        cancelUnopenedExpiry();

        // Update BossBar
        if (!partOfShower && globalBossBar != null) {
            String typeName = meteorType != null ? meteorType.getDisplayName() : "Meteorito";
            int x = chestLocation.getBlockX();
            int z = chestLocation.getBlockZ();
            globalBossBar.name(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&a☄ " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &a[¡ABIERTO! Recoge el botín]"
            ));
            globalBossBar.progress(0.0f);
        }

        // Schedule maximum stay safety timer (e.g. 180s)
        int maxStay = plugin.getConfigManager().getCraterMaxStaySeconds();
        if (maxStay > 0) {
            maxStayTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!restoring && state != ChestState.RESTORED) {
                    startRestorationCountdown(plugin.getConfigManager().getCraterRestoreDelaySeconds());
                }
            }, maxStay * 20L);
        }
    }

    private void scheduleUnopenedExpiry(){
        long configured=plugin.getConfig().getLong("crater-unopened-timeout-seconds",600L);
        if(configured<=0)return;
        long lockSeconds=meteorType==null?0:meteorType.getLockDurationSeconds();
        long delay=Math.max(configured,lockSeconds)*20L;
        unopenedExpiryTask=Bukkit.getScheduler().runTaskLater(plugin,this::expireIfNeverOpened,delay);
    }

    private void expireIfNeverOpened(){
        unopenedExpiryTask=null;
        if(state==ChestState.RESTORED||state==ChestState.RESTORING||state==ChestState.UNLOCKED)return;
        if(state==ChestState.BEING_UNLOCKED||currentSession!=null){
            unopenedExpiryTask=Bukkit.getScheduler().runTaskLater(plugin,this::expireIfNeverOpened,60L*20L);
            return;
        }
        int x=chestLocation.getBlockX(),z=chestLocation.getBlockZ();
        String type=meteorType==null?"Meteorito":net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(meteorType.getDisplayName()));
        String message=plugin.getConfig().getString("messages.broadcast-unopened-expired","&e⌛ El meteorito {type} en X: {x}, Z: {z} expiró sin abrirse; se restaurará el terreno.")
                .replace("{type}",type).replace("{x}",String.valueOf(x)).replace("{z}",String.valueOf(z));
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
        if(plugin.getDiscordChatBridge()!=null)plugin.getDiscordChatBridge().sendEvent("meteor-unopened-expired",net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(message)));
        restoreCrater();
    }

    private void cancelUnopenedExpiry(){
        if(unopenedExpiryTask!=null){unopenedExpiryTask.cancel();unopenedExpiryTask=null;}
    }

    /**
     * Starts the 30-second countdown to restore the terrain crater to its original state.
     */
    public synchronized void startRestorationCountdown(int seconds) {
        if (restoring || state == ChestState.RESTORED) {
            return;
        }
        this.restoring = true;
        this.state = ChestState.RESTORING;
        this.restoreCountdownSeconds = Math.max(1, seconds);

        if (maxStayTask != null) {
            maxStayTask.cancel();
            maxStayTask = null;
        }
        cancelUnopenedExpiry();

        int x = chestLocation.getBlockX();
        int z = chestLocation.getBlockZ();
        PlayerBroadcast.send(plugin.getConfigManager().getBroadcastRestoring(x, z, restoreCountdownSeconds));

        // Start countdown task
        this.restoreTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickRestoration, 20L, 20L);
    }

    private void tickRestoration() {
        restoreCountdownSeconds--;

        // Update BossBar
        if (!partOfShower && globalBossBar != null) {
            String typeName = meteorType != null ? meteorType.getDisplayName() : "Meteorito";
            int x = chestLocation.getBlockX();
            int z = chestLocation.getBlockZ();
            globalBossBar.name(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e⏳ " + typeName + " &7en &fX: " + x + ", Z: " + z + " &8— &eRegenerando en &c" + restoreCountdownSeconds + "s"
            ));
            float ratio = Math.max(0.0f, (float) restoreCountdownSeconds / 30.0f);
            globalBossBar.progress(Math.min(1.0f, ratio));
        }

        // Update TextDisplay hologram
        updateHologramRestoring(restoreCountdownSeconds);

        if (restoreCountdownSeconds <= 0) {
            if (restoreTask != null) {
                restoreTask.cancel();
                restoreTask = null;
            }
            restoreCrater();
        }
    }

    private void updateHologramRestoring(int remainingSeconds) {
        if (hologramUuid == null || chestLocation.getWorld() == null) return;
        Entity entity = chestLocation.getWorld().getEntity(hologramUuid);
        if (entity instanceof TextDisplay display && display.isValid()) {
            String text = "§e⏳ §6[REGENERANDO]\n§7El cráter desaparecerá en: §c" + remainingSeconds + "s";
            display.text(LegacyComponentSerializer.legacySection().deserialize(text));
        }
    }

    /**
     * Completely rolls back the crater and restores all original block states.
     */
    public synchronized void restoreCrater() {
        if (state == ChestState.RESTORED && !plugin.isShuttingDown()) {
            return;
        }
        this.state = ChestState.RESTORED;
        if (partOfShower && !showerReleased) {
            showerReleased = true;
            plugin.getMeteorManager().onShowerChestRestored();
        }
        if(plugin.getMeteorEventManager()!=null)plugin.getMeteorEventManager().onChestRestored(this);
        if (meteorType != null && meteorType.getId().equalsIgnoreCase("meteoriteritual")) plugin.getRitualManager().release(chestLocation);

        if (restoreTask != null) {
            restoreTask.cancel();
            restoreTask = null;
        }
        if (maxStayTask != null) {
            maxStayTask.cancel();
            maxStayTask = null;
        }
        cancelUnopenedExpiry();
        if (beamTask != null) {
            beamTask.cancel();
            beamTask = null;
        }
        if (restoreAnimationTask != null) {
            restoreAnimationTask.cancel();
            restoreAnimationTask = null;
        }

        // 0. Stop guardian waves/bossbars and remove every mob linked to this meteorite.
        if(plugin.getGuardianManager()!=null)plugin.getGuardianManager().removeGuardiansForChest(this);
        else {
            for (UUID guardianUuid : guardianUuids) {
                if (chestLocation != null && chestLocation.getWorld() != null) {
                    Entity guardian = chestLocation.getWorld().getEntity(guardianUuid);
                    if (guardian != null && guardian.isValid()) guardian.remove();
                }
            }
            guardianUuids.clear();
        }

        // 1. Remove Hologram
        removeHologram();

        // 2. Remove Global BossBar
        removeGlobalBossBar();

        // 3. Clear chest inventory if still present
        lootInventory.clear();

        // 4. Restore original blocks with step-by-step progressive animation
        if (chestLocation != null && chestLocation.getWorld() != null) {
            if (!plugin.isEnabled() || plugin.isShuttingDown()) {
                // Instant fallback if server is shutting down
                for (Map.Entry<Location, BlockData> entry : originalBlocks.entrySet()) {
                    Location loc = entry.getKey();
                    BlockData originalData = entry.getValue();
                    if (loc.getWorld() != null && originalData != null) {
                        loc.getBlock().setBlockData(originalData, false);
                    }
                }
                plugin.getCraterRecoveryManager().removeSnapshot(chestLocation);
                plugin.getChestManager().untrackChest(chestLocation);
                return;
            }

            // Progressive animated restoration (bottom-to-top)
            List<Map.Entry<Location, BlockData>> sortedBlocks = new ArrayList<>(originalBlocks.entrySet());
            sortedBlocks.sort(Comparator.comparingInt(e -> e.getKey().getBlockY()));

            final int batchSize = 15;
            final Queue<Map.Entry<Location, BlockData>> queue = new ArrayDeque<>(sortedBlocks);
            final World world = chestLocation.getWorld();

            this.restoreAnimationTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                private int tickCounter = 0;

                @Override
                public void run() {
                    tickCounter++;
                    int processed = 0;
                    while (!queue.isEmpty() && processed < batchSize) {
                        Map.Entry<Location, BlockData> entry = queue.poll();
                        Location loc = entry.getKey();
                        BlockData data = entry.getValue();
                        if (loc.getWorld() != null && data != null) {
                            Block b = loc.getBlock();
                            b.setBlockData(data, false);
                            if (processed % 4 == 0) {
                                world.spawnParticle(Particle.POOF, loc.clone().add(0.5, 0.5, 0.5), 1, 0.1, 0.1, 0.1, 0.02);
                            }
                        }
                        processed++;
                    }

                    if (tickCounter % 3 == 0) {
                        world.playSound(chestLocation, Sound.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 1.0f, 1.2f);
                    }

                    if (queue.isEmpty()) {
                        if (restoreAnimationTask != null) {
                            restoreAnimationTask.cancel();
                            restoreAnimationTask = null;
                        }

                        // Final magical restoration sound and particles
                        world.playSound(chestLocation, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 2.0f, 0.8f);
                        world.playSound(chestLocation, Sound.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.BLOCKS, 2.0f, 1.2f);
                        world.spawnParticle(Particle.POOF, chestLocation, 80, 4.0, 2.0, 4.0, 0.05);
                        world.spawnParticle(Particle.ENCHANT, chestLocation, 150, 4.0, 2.0, 4.0, 0.5);

                        int x = chestLocation.getBlockX();
                        int z = chestLocation.getBlockZ();
                        PlayerBroadcast.send(plugin.getConfigManager().getBroadcastRestored(x, z));

                        // 5. Untrack from ChestManager
                        plugin.getCraterRecoveryManager().removeSnapshot(chestLocation);
                        plugin.getChestManager().untrackChest(chestLocation);
                    }
                }
            }, 1L, 1L);
        } else {
            plugin.getChestManager().untrackChest(chestLocation);
        }
    }

    /**
     * Cleans up and removes the TextDisplay hologram entity from the world.
     */
    public void removeHologram() {
        if (hologramUuid == null || chestLocation == null || chestLocation.getWorld() == null) {
            if (chestDisplayUuid == null || chestLocation == null || chestLocation.getWorld() == null) return;
        }
        Entity entity = hologramUuid == null ? null : chestLocation.getWorld().getEntity(hologramUuid);
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        Entity visual = chestDisplayUuid == null ? null : chestLocation.getWorld().getEntity(chestDisplayUuid);
        if (visual != null && visual.isValid()) visual.remove();
        Entity interaction = interactionUuid == null ? null : chestLocation.getWorld().getEntity(interactionUuid);
        if (interaction != null && interaction.isValid()) interaction.remove();
    }

    /**
     * Cleans up the Global BossBar.
     */
    public void removeGlobalBossBar() {
        if (partOfShower) return;
        if (globalBossBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(globalBossBar);
            }
            globalBossBar = null;
        }
    }

    public Set<UUID> getGuardianUuids() {
        return guardianUuids;
    }

    public void addGuardian(UUID uuid) {
        if (uuid != null) {
            guardianUuids.add(uuid);
        }
    }
}
