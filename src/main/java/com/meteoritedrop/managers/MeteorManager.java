package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Display;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages meteor spawning, multi-block physical cluster descent, realistic bowl crater excavation,
 * and dramatic visual and auditory impact effects.
 */
public class MeteorManager {

    private final MeteoriteDrop plugin;
    private final Map<UUID, ActiveMeteor> activeMeteors = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveMeteor> meteorsByEntityUuid = new ConcurrentHashMap<>();
    private final Map<UUID, Long> manualLaunchCooldowns = new ConcurrentHashMap<>();
    private BossBar showerBossBar;
    private int showerChestCount;
    private boolean showerLaunching;

    public static class ActiveMeteor {
        public final UUID meteorId = UUID.randomUUID();
        public final MeteorType meteorType;
        public final List<FallingBlock> fallingBlocks = new ArrayList<>();
        public final List<UUID> fallingBlockUuids = new ArrayList<>();
        public final World world;
        public final int targetX;
        public final int targetZ;
        public final int groundY;
        public final double fallSpeed;
        public int requestedFallSeconds;
        public int elapsedFallTicks;
        public double startY;
        public final List<Vector> fallingBlockOffsets = new ArrayList<>();

        public BukkitTask task;
        public BukkitTask impactSequenceTask;
        public boolean broadcastedIncoming = false;
        public boolean impactPending = false;
        public int ticksLived = 0;
        public boolean impacted = false;
        public boolean partOfShower = false;
        public final Set<Integer> countdownHeightsPlayed = new HashSet<>();
        public UUID ritualOwner;
        public ItemStack ritualFireCharge;

        public ActiveMeteor(MeteorType meteorType, World world, int targetX, int targetZ, int groundY, double fallSpeed) {
            this.meteorType = meteorType;
            this.world = world;
            this.targetX = targetX;
            this.targetZ = targetZ;
            this.groundY = groundY;
            this.fallSpeed = fallSpeed;
        }
    }

    public int getActiveMeteorCount() { return activeMeteors.size(); }
    public long acquireManualLaunchCooldown(Player player) {
        if (player == null) return 0;
        long now = System.currentTimeMillis();
        long duration = Math.max(0, plugin.getConfig().getLong("anti-abuse.manual-drop-cooldown-seconds", 15)) * 1000L;
        long previous = manualLaunchCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (now < previous) return Math.max(1, (previous - now + 999) / 1000);
        if (duration > 0) manualLaunchCooldowns.put(player.getUniqueId(), now + duration);
        return 0;
    }

    public MeteorManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
    }

    /**
     * Launches a meteorite of the specified type targeted at the given coordinates.
     */
    public boolean launchMeteor(MeteorType type, World world, int targetX, int targetZ) {
        return launchMeteorInternal(type, world, targetX, targetZ, false, null, null, 0);
    }

    /** Launches a meteor with a player-selected, time-controlled descent (1–120 seconds). */
    public boolean launchMeteor(MeteorType type, World world, int targetX, int targetZ, int fallSeconds) {
        if (fallSeconds < 1 || fallSeconds > 120) return false;
        return launchMeteorInternal(type, world, targetX, targetZ, false, null, null, fallSeconds);
    }

    /**
     * Launches a meteorite with optional bypass for active meteor limits (useful for showers).
     */
    public boolean launchMeteor(MeteorType type, World world, int targetX, int targetZ, boolean bypassLimit) {
        return launchMeteorInternal(type, world, targetX, targetZ, bypassLimit, null, null, 0);
    }

    public boolean launchRitualMeteor(MeteorType type, World world, int targetX, int targetZ, UUID ritualOwner, ItemStack ritualFireCharge) {
        return launchMeteorInternal(type, world, targetX, targetZ, false, ritualOwner, ritualFireCharge, 0);
    }

    private boolean launchMeteorInternal(MeteorType type, World world, int targetX, int targetZ, boolean bypassLimit,
                                         UUID ritualOwner, ItemStack ritualFireCharge, int fallSeconds) {
        if (world == null) return false;

        // Enforce max active meteors limit
        if (!bypassLimit) {
            int maxActive = Math.max(1, plugin.getConfig().getInt("max-active-meteors", 3));
            if (activeMeteors.size() >= maxActive) {
                plugin.getLogger().warning("[MeteoriteDrop] Cannot launch meteor: max active meteors (" + maxActive + ") reached.");
                return false;
            }
        }

        if (type == null) {
            type = plugin.getMeteorTypeManager().getRandomType();
        }
        if (type == null) {
            plugin.getLogger().warning("Cannot launch meteor: no meteor types configured.");
            return false;
        }

        int protectionRadius = type.isNormalDrop() ? 1 : Math.max(1, type.getCraterRadius()) + 2;
        double extent = protectionRadius + 2.0;
        double centerX = targetX + 0.5;
        double centerZ = targetZ + 0.5;
        if (!isImpactInsideWorldBorder(world, centerX, centerZ, extent)) {
            plugin.getLogger().warning("[MeteoriteDrop] Impact blocked outside the world border at " + world.getName()
                    + " X=" + targetX + ", Z=" + targetZ + ".");
            return false;
        }

        int groundY = findGroundY(world, targetX, targetZ);
        Location target = new Location(world, targetX + 0.5, groundY, targetZ + 0.5);
        if (plugin.getProtectionManager() != null) {
            String protectionReason=plugin.getProtectionManager().getProtectionReason(target, protectionRadius);
            if(protectionReason!=null){
                plugin.getLogger().warning("[MeteoriteDrop] Impact blocked at "+world.getName()+" X="+targetX+", Z="+targetZ+"; cause="+protectionReason+".");
                return false;
            }
        }

        // Safe high spawn position
        int spawnY = Math.min(world.getMaxHeight() - 10, Math.max(250, groundY + 60));
        if (fallSeconds > 0) {
            int desiredDrop = Math.max(30, fallSeconds * 16);
            spawnY = Math.min(world.getMaxHeight() - 10, groundY + desiredDrop);
        }
        if (spawnY <= groundY + 5) {
            plugin.getLogger().warning("[MeteoriteDrop] Impact blocked: not enough vertical space above X=" + targetX + ", Z=" + targetZ + ".");
            return false;
        }

        Location centerSpawn = new Location(world, targetX + 0.5, spawnY, targetZ + 0.5);
        world.getChunkAt(centerSpawn).load(true);

        double speed = plugin.getConfigManager().getMeteorFallSpeed();
        if (speed <= 0) speed = 2.0;

        ActiveMeteor meteor;

        if (type.isNormalDrop()) {
            // Normal Supply Drop: falls slower, single chest block with parachute effect
            speed = 0.8;
            meteor = new ActiveMeteor(type, world, targetX, targetZ, groundY, speed);
            meteor.requestedFallSeconds = fallSeconds;
            meteor.startY = spawnY;
            FallingBlock fb = world.spawnFallingBlock(centerSpawn, Material.CHEST.createBlockData());
            fb.setDropItem(false);
            fb.setCancelDrop(true);
            fb.setHurtEntities(false);
            fb.setGlowing(true);
            fb.addScoreboardTag("meteoritedrop_falling");
            if (fallSeconds > 0) fb.setGravity(false);
            else fb.setVelocity(new Vector(0, -speed, 0));

            meteor.fallingBlocks.add(fb);
            meteor.fallingBlockOffsets.add(new Vector(0, 0, 0));
            meteor.fallingBlockUuids.add(fb.getUniqueId());
            meteorsByEntityUuid.put(fb.getUniqueId(), meteor);
        } else {
            meteor = new ActiveMeteor(type, world, targetX, targetZ, groundY, speed);
            meteor.requestedFallSeconds = fallSeconds;
            meteor.startY = spawnY;

            // Multi-block cluster: spawn 5 FallingBlocks within +-0.3 blocks of each other
            double[][] clusterOffsets = {
                    {0.0, 0.0, 0.0},
                    {0.25, -0.15, 0.25},
                    {-0.25, -0.15, -0.25},
                    {0.25, 0.15, -0.25},
                    {-0.25, 0.15, 0.25}
            };

            for (double[] offset : clusterOffsets) {
                Location fbLoc = centerSpawn.clone().add(offset[0], offset[1], offset[2]);
                FallingBlock fb = world.spawnFallingBlock(fbLoc, type.getFallingBlock().createBlockData());
                fb.setDropItem(false);
                fb.setCancelDrop(true);
                fb.setHurtEntities(false);
                fb.setGlowing(true);
                fb.addScoreboardTag("meteoritedrop_falling");
                if (fallSeconds > 0) fb.setGravity(false);
                else fb.setVelocity(new Vector(0, -speed, 0));

                meteor.fallingBlocks.add(fb);
                meteor.fallingBlockOffsets.add(new Vector(offset[0], offset[1], offset[2]));
                meteor.fallingBlockUuids.add(fb.getUniqueId());
                meteorsByEntityUuid.put(fb.getUniqueId(), meteor);
            }
        }

        meteor.ritualOwner = ritualOwner;
        meteor.ritualFireCharge = ritualFireCharge == null ? null : ritualFireCharge.clone();
        activeMeteors.put(meteor.meteorId, meteor);
        meteor.partOfShower = bypassLimit && showerBossBar != null;
        if (meteor.partOfShower) showerChestCount++;


        meteor.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickMeteor(meteor), 1L, 1L);

        return true;
    }

    /**
     * Triggers a meteor shower event spawning multiple meteorites staggered over time in an area.
     */
    public boolean launchMeteorShower(MeteorType type, World world, int centerX, int centerZ, int count) {
        if (world == null || showerBossBar != null) return false;
        final int totalMeteors = Math.max(2, Math.min(12, count));
        final MeteorType meteorType = (type != null) ? type : plugin.getMeteorTypeManager().getRandomType();
        showerChestCount = 0;
        showerLaunching = true;
        String showerName = meteorType == null ? "Lluvia de meteoritos" : meteorType.getDisplayName();
        showerBossBar = BossBar.bossBar(
                LegacyComponentSerializer.legacyAmpersand().deserialize("&6☄ &eLluvia de " + showerName + " &7cerca de &fX: " + centerX + ", Z: " + centerZ),
                1.0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        for (Player player : Bukkit.getOnlinePlayers()) player.showBossBar(showerBossBar);

        String typeName = (meteorType != null) ? meteorType.getDisplayName() : "Meteoritos";
        String showerBroadcast = "&8&m--------------------------------------------------\n" +
                "&c&l☄ ¡LLUVIA DE METEORITOS AVISTADA! ☄\n" +
                "&eMúltiples impactos de &f" + typeName + " &ecaerán cerca de &fX: " + centerX + ", Z: " + centerZ + "&e!\n" +
                "&6¡Prepárense para una batalla por múltiples botines!\n" +
                "&8&m--------------------------------------------------";
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(showerBroadcast));

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.AMBIENT, 1.0f, 0.6f);
        }

        // Schedule staggered drops every 30 ticks (1.5 seconds)
        Bukkit.getScheduler().runTaskTimer(plugin, new java.util.function.Consumer<BukkitTask>() {
            private int spawned = 0;

            @Override
            public void accept(BukkitTask task) {
                if (spawned >= totalMeteors) {
                    task.cancel();
                    showerLaunching = false;
                    finishShowerIfDone();
                    return;
                }

                double angle = ThreadLocalRandom.current().nextDouble() * 2.0 * Math.PI;
                double dist = 10.0 + (ThreadLocalRandom.current().nextDouble() * 45.0);
                int dropX = (int) Math.round(centerX + dist * Math.cos(angle));
                int dropZ = (int) Math.round(centerZ + dist * Math.sin(angle));

                MeteorType dropType = (meteorType != null) ? meteorType : plugin.getMeteorTypeManager().getRandomType();
                launchMeteor(dropType, world, dropX, dropZ, true);
                spawned++;
            }
        }, 20L, 30L);
        return true;
    }

    public BossBar getShowerBossBar() { return showerBossBar; }

    public void onShowerChestRestored() {
        if (showerChestCount > 0) showerChestCount--;
        finishShowerIfDone();
    }

    private void finishShowerIfDone() {
        if (!showerLaunching && showerChestCount == 0 && showerBossBar != null) {
            for (Player player : Bukkit.getOnlinePlayers()) player.hideBossBar(showerBossBar);
            showerBossBar = null;
        }
    }

    private int findGroundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING);
        if (y < world.getMinHeight()) {
            y = 64;
        }

        while (y > world.getMinHeight() && world.getBlockAt(x, y, z).isPassable()) {
            y--;
        }

        return Math.max(world.getMinHeight() + 5, y);
    }

    private void tickMeteor(ActiveMeteor meteor) {
        if (meteor.impacted || meteor.impactPending) {
            return;
        }

        if (meteor.requestedFallSeconds > 0 && !advanceTimedDescent(meteor)) return;

        // Check if any block died or was removed
        boolean shouldImpact = false;
        for (FallingBlock fb : meteor.fallingBlocks) {
            if (!fb.isValid() || fb.isDead() || fb.isOnGround()) {
                shouldImpact = true;
                break;
            }
        }

        FallingBlock leadBlock = meteor.fallingBlocks.get(0);
        Location centerLoc = leadBlock.isValid() ? leadBlock.getLocation()
                : new Location(meteor.world, meteor.targetX + 0.5, meteor.groundY, meteor.targetZ + 0.5);
        double currentY = centerLoc.getY();

        // Safety fallback: on some Paper builds the falling-block change event may
        // be cancelled by another listener, so finish impact from the tracked height.
        if (currentY <= (meteor.groundY + 2.5)) {
            shouldImpact = true;
        }

        if (shouldImpact) {
            beginImpactSequence(meteor);
            return;
        }

        meteor.ticksLived++;

        // Maintain downward velocity for all 5 blocks
        for (FallingBlock fb : meteor.fallingBlocks) {
            if (fb.isValid()) {
                if (meteor.requestedFallSeconds <= 0) fb.setVelocity(new Vector(0, -meteor.fallSpeed, 0));
                if (fb.getTicksLived() > 500) {
                    fb.setTicksLived(1);
                }
            }
        }

        // Broadcast incoming warning when near ground
        int warningDistance = Math.max(10, Math.min(256, plugin.getConfig().getInt("meteor-animation.warning-distance", 60)));
        if (!meteor.broadcastedIncoming && currentY <= (meteor.groundY + warningDistance)) {
            meteor.broadcastedIncoming = true;
            if (meteor.meteorType.isNormalDrop()) {
                String warningMsg = "&8&m--------------------------------------------------\n" +
                        "&e📦 &6[SUMINISTRO DETECTADO] &eUn &6Suministro Normal &ecae hacia &fX: " +
                        meteor.targetX + ", Z: " + meteor.targetZ + "&e!\n" +
                        "&7¡El cofre caerá en la superficie sin dañar el terreno!\n" +
                        "&8&m--------------------------------------------------";
                PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(warningMsg));
            } else {
                PlayerBroadcast.send(plugin.getConfigManager().getBroadcastIncoming(
                        meteor.meteorType.getDisplayName(),
                        meteor.targetX,
                        meteor.targetZ
                ));
            }
            Sound warningSound = meteor.meteorType.isNormalDrop()
                    ? configuredSound("meteor-animation.countdown-sound", Sound.BLOCK_NOTE_BLOCK_BELL)
                    : configuredSound("event-announcements.incoming-sound", Sound.ENTITY_WITHER_SPAWN);
            int soundRadius = Math.max(16, Math.min(512, plugin.getConfig().getInt("meteor-animation.countdown-radius", 128)));
            Location warningLocation = new Location(meteor.world, meteor.targetX + 0.5, currentY, meteor.targetZ + 0.5);
            for (Player player : meteor.world.getPlayers()) {
                if (player.getLocation().distanceSquared(warningLocation) <= (double) soundRadius * soundRadius) {
                    player.playSound(warningLocation, warningSound, SoundCategory.MASTER, 0.9f, 0.7f);
                }
            }
        }

        if (plugin.getConfig().getBoolean("meteor-animation.enabled", true)) {
            for (int height : plugin.getConfig().getIntegerList("meteor-animation.countdown-heights")) {
                if (height > 0 && currentY <= meteor.groundY + height && meteor.countdownHeightsPlayed.add(height)) {
                    announceApproachStage(meteor, centerLoc, height);
                }
            }
        }

        // Keep particle work configurable and skip ticks when configured to do so.
        int particleInterval = Math.max(1, Math.min(10, plugin.getConfig().getInt("meteor-animation.particle-interval-ticks", 1)));
        if (meteor.ticksLived % particleInterval == 0) spawnTrailParticles(meteor, centerLoc, currentY);

        // Sound every 3 ticks, audible from 300 blocks away
        if (meteor.ticksLived % 3 == 0) {
            playDescentSound(meteor, centerLoc);
        }
    }

    /** Moves the cluster along an accelerating path so the requested time controls impact timing. */
    private boolean advanceTimedDescent(ActiveMeteor meteor) {
        meteor.elapsedFallTicks++;
        String delayPath = meteor.meteorType.isNormalDrop()
                ? "meteor-animation.normal-impact-delay-ticks" : "meteor-animation.impact-delay-ticks";
        int configuredDelay = plugin.getConfig().getBoolean("meteor-animation.enabled", true)
                ? Math.max(0, Math.min(40, plugin.getConfig().getInt(delayPath, meteor.meteorType.isNormalDrop() ? 4 : 12))) : 0;
        int totalTicks = Math.max(1, meteor.requestedFallSeconds * 20 - configuredDelay);
        double linear = Math.min(1.0, (double) meteor.elapsedFallTicks / totalTicks);
        // Starts gently, then accelerates toward the ground like an entering meteor.
        double progress = 1.0 - Math.pow(1.0 - linear, 1.65);
        double endY = meteor.groundY + 2.6;
        double y = meteor.startY - (meteor.startY - endY) * progress;
        for (int i = 0; i < meteor.fallingBlocks.size(); i++) {
            FallingBlock block = meteor.fallingBlocks.get(i);
            if (!block.isValid()) continue;
            Vector offset = i < meteor.fallingBlockOffsets.size() ? meteor.fallingBlockOffsets.get(i) : new Vector();
            Location destination = new Location(meteor.world,
                    meteor.targetX + 0.5 + offset.getX(), y + offset.getY(), meteor.targetZ + 0.5 + offset.getZ());
            block.teleport(destination);
        }
        if (linear >= 1.0) {
            beginImpactSequence(meteor);
            return false;
        }
        return true;
    }

    private void spawnTrailParticles(ActiveMeteor meteor, Location centerLoc, double currentY) {
        if (!plugin.getConfig().getBoolean("meteor-animation.enabled", true)) return;
        World world = meteor.world;
        double density = Math.max(0.1, Math.min(1.5, plugin.getConfig().getDouble("meteor-animation.particle-density", 0.65)));

        if (meteor.meteorType.isNormalDrop()) {
            world.spawnParticle(Particle.CLOUD, centerLoc.clone().add(0, 1.2, 0), scaledParticleCount(8, density), 0.4, 0.2, 0.4, 0.01);
            world.spawnParticle(Particle.WAX_ON, centerLoc.clone().add(0, 0.5, 0), scaledParticleCount(4, density), 0.2, 0.2, 0.2, 0.01);
            return;
        }

        String typeId = meteor.meteorType.getId().toLowerCase();

        // Every rocky meteor has a hot, burning core, even when its elemental particles differ.
        world.spawnParticle(Particle.FLAME, centerLoc, scaledParticleCount(24, density), 0.42, 0.48, 0.42, 0.025);
        world.spawnParticle(Particle.LAVA, centerLoc, scaledParticleCount(4, density), 0.24, 0.30, 0.24, 0.015);
        world.spawnParticle(Particle.SMOKE, centerLoc.clone().add(0, 0.6, 0), scaledParticleCount(14, density), 0.48, 0.55, 0.48, 0.02);

        if (typeId.contains("legend")) {
            world.spawnParticle(Particle.PORTAL,centerLoc,scaledParticleCount(28,density),.8,.8,.8,.4);
            world.spawnParticle(Particle.END_ROD,centerLoc,scaledParticleCount(18,density),.7,.7,.7,.06);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME,centerLoc,scaledParticleCount(12,density),.5,.6,.5,.02);
        } else if (typeId.contains("ice")) {
            world.spawnParticle(Particle.SNOWFLAKE, centerLoc, scaledParticleCount(26,density), 0.8, 0.8, 0.8, 0.05);
            world.spawnParticle(Particle.CLOUD, centerLoc, scaledParticleCount(14,density), 0.5, 0.5, 0.5, 0.03);
            world.spawnParticle(Particle.ITEM_SNOWBALL, centerLoc, scaledParticleCount(4,density), 0.4, 0.4, 0.4, 0.02);
        } else if (typeId.contains("end") || typeId.contains("void")) {
            world.spawnParticle(Particle.PORTAL, centerLoc, scaledParticleCount(32,density), 0.8, 0.8, 0.8, 0.5);
            world.spawnParticle(Particle.DRAGON_BREATH, centerLoc, scaledParticleCount(18,density), 0.5, 0.5, 0.5, 0.03, 1.0f);
            world.spawnParticle(Particle.REVERSE_PORTAL, centerLoc, scaledParticleCount(10,density), 0.5, 0.5, 0.5, 0.02);
        } else if (typeId.contains("divine") || typeId.contains("celestial")) {
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, centerLoc, scaledParticleCount(26,density), 0.8, 0.8, 0.8, 0.05);
            world.spawnParticle(Particle.END_ROD, centerLoc, scaledParticleCount(14,density), 0.5, 0.5, 0.5, 0.03);
        } else if (typeId.contains("toxic") || typeId.contains("corrupto")) {
            world.spawnParticle(Particle.SCULK_SOUL, centerLoc, scaledParticleCount(26,density), 0.8, 0.8, 0.8, 0.05);
            world.spawnParticle(Particle.SQUID_INK, centerLoc, scaledParticleCount(14,density), 0.5, 0.5, 0.5, 0.03);
        } else if (typeId.contains("storm") || typeId.contains("tormenta") || typeId.contains("electric")) {
            world.spawnParticle(Particle.ELECTRIC_SPARK, centerLoc, scaledParticleCount(26,density), 0.8, 0.8, 0.8, 0.05);
            world.spawnParticle(Particle.FIREWORK, centerLoc, scaledParticleCount(14,density), 0.5, 0.5, 0.5, 0.03);
        } else {
            world.spawnParticle(Particle.FLAME, centerLoc, scaledParticleCount(26,density), 0.8, 0.8, 0.8, 0.05);
            world.spawnParticle(Particle.LAVA, centerLoc, scaledParticleCount(7,density), 0.5, 0.5, 0.5, 0.02);
            world.spawnParticle(Particle.LARGE_SMOKE, centerLoc, scaledParticleCount(14,density), 0.6, 0.6, 0.6, 0.03);
        }

        // Animated, tapered smoke trail behind the core.
        double[] trailY = {2.0, 4.5, 7.5, 11.0};
        int smokeCount = Math.max(1, Math.min(12, plugin.getConfig().getInt("meteor-animation.smoke-particles", 3)));
        for (int i = 0; i < trailY.length; i++) {
            double dy = trailY[i];
            Location smokeLoc = centerLoc.clone().add(0, dy, 0);
            double spread = 0.18 + i * 0.12;
            world.spawnParticle(Particle.LARGE_SMOKE, smokeLoc, scaledParticleCount(smokeCount, density), spread, spread, spread, 0.01);
        }

        // A rotating ring of elemental sparks gives the core a clear silhouette and motion.
        Particle signature = signatureParticle(typeId);
        int ringPoints = Math.max(0, Math.min(10, plugin.getConfig().getInt("meteor-animation.spiral-points", 5)));
        double radius = Math.max(0.2, Math.min(3.0, plugin.getConfig().getDouble("meteor-animation.spiral-radius", 1.15)));
        double rotation = meteor.ticksLived * 0.24;
        for (int i = 0; i < ringPoints; i++) {
            double angle = rotation + (Math.PI * 2.0 * i / Math.max(1, ringPoints));
            double yOffset = Math.sin(angle * 1.7) * 0.55;
            Location spark = centerLoc.clone().add(Math.cos(angle) * radius, yOffset, Math.sin(angle) * radius);
            world.spawnParticle(signature, spark, 1, 0.025, 0.025, 0.025, 0.01);
        }
    }

    private int scaledParticleCount(int base, double density) {
        return Math.max(1, (int) Math.round(base * density));
    }

    private Particle signatureParticle(String typeId) {
        if (typeId.contains("legend") || typeId.contains("end") || typeId.contains("void")) return Particle.PORTAL;
        if (typeId.contains("ice")) return Particle.SNOWFLAKE;
        if (typeId.contains("divine") || typeId.contains("celestial")) return Particle.END_ROD;
        if (typeId.contains("toxic") || typeId.contains("corrupto")) return Particle.SCULK_SOUL;
        if (typeId.contains("storm") || typeId.contains("tormenta") || typeId.contains("electric")) return Particle.ELECTRIC_SPARK;
        return Particle.FLAME;
    }

    private void announceApproachStage(ActiveMeteor meteor, Location location, int height) {
        int radius = Math.max(16, Math.min(256, plugin.getConfig().getInt("meteor-animation.countdown-radius", 128)));
        String title = plugin.getConfig().getString("meteor-animation.countdown-title", "&c&l¡IMPACTO PRÓXIMO!");
        String subtitle = plugin.getConfig().getString("meteor-animation.countdown-subtitle", "&e{height} bloques · X: {x}, Z: {z}")
                .replace("{height}", String.valueOf(height))
                .replace("{x}", String.valueOf(meteor.targetX))
                .replace("{z}", String.valueOf(meteor.targetZ));
        Sound sound = configuredSound("meteor-animation.countdown-sound", Sound.BLOCK_NOTE_BLOCK_BELL);
        for (Player player : meteor.world.getPlayers()) {
            if (player.getLocation().distanceSquared(location) > (double) radius * radius) continue;
            player.showTitle(Title.title(
                    LegacyComponentSerializer.legacyAmpersand().deserialize(title),
                    LegacyComponentSerializer.legacyAmpersand().deserialize(subtitle),
                    Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(900), java.time.Duration.ofMillis(200))));
            player.playSound(location, sound, SoundCategory.AMBIENT, 1.2f, height <= 10 ? 0.7f : 1.0f);
        }
    }

    private void beginImpactSequence(ActiveMeteor meteor) {
        if (meteor == null || meteor.impacted || meteor.impactPending) return;
        boolean animationEnabled = plugin.getConfig().getBoolean("meteor-animation.enabled", true);
        boolean normalDrop = meteor.meteorType.isNormalDrop();
        String delayPath = normalDrop ? "meteor-animation.normal-impact-delay-ticks" : "meteor-animation.impact-delay-ticks";
        int defaultDelay = normalDrop ? 4 : 12;
        int delayTicks = animationEnabled
                ? Math.max(0, Math.min(40, plugin.getConfig().getInt(delayPath, defaultDelay))) : 0;
        if (delayTicks == 0) {
            triggerImpact(meteor);
            return;
        }

        meteor.impactPending = true;
        if (meteor.task != null) {
            meteor.task.cancel();
            meteor.task = null;
        }
        for (FallingBlock block : meteor.fallingBlocks) {
            if (!block.isValid()) continue;
            block.setGravity(false);
            block.setVelocity(new Vector(0, 0, 0));
        }

        Location center = new Location(meteor.world, meteor.targetX + 0.5, meteor.groundY + 1.0, meteor.targetZ + 0.5);
        Sound rumble = configuredSound("meteor-animation.impact-warning-sound", Sound.ENTITY_WARDEN_SONIC_BOOM);
        int visualRadius = Math.max(8, Math.min(128, plugin.getConfig().getInt("meteor-animation.visual-radius", 48)));
        int darknessTicks = Math.max(0, Math.min(40, plugin.getConfig().getInt("meteor-animation.darkness-ticks", 12)));
        boolean darkness = !normalDrop && plugin.getConfig().getBoolean("meteor-animation.darkness-before-impact", true);
        for (Player player : meteor.world.getPlayers()) {
            double distanceSquared = player.getLocation().distanceSquared(center);
            if (distanceSquared > (double) visualRadius * visualRadius) continue;
            player.playSound(center, rumble, SoundCategory.AMBIENT, 2.0f, 0.65f);
            if (darkness && darknessTicks > 0 && distanceSquared <= 32.0 * 32.0) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, darknessTicks, 0, true, false, false));
            }
        }

        int maxRingRadius = Math.max(3, Math.min(24, plugin.getConfig().getInt("meteor-animation.impact-wave-radius", 10)));
        Particle signature = normalDrop ? Particle.CLOUD : signatureParticle(meteor.meteorType.getId().toLowerCase(Locale.ROOT));
        int steps = Math.max(1, (delayTicks + 1) / 2);
        final int[] step = {0};
        meteor.impactSequenceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!plugin.isEnabled() || meteor.impacted) {
                if (meteor.impactSequenceTask != null) meteor.impactSequenceTask.cancel();
                return;
            }
            step[0]++;
            double progress = Math.min(1.0, (double) step[0] / steps);
            double ringRadius = Math.max(0.5, maxRingRadius * progress);
            for (int i = 0; i < 16; i++) {
                double angle = Math.PI * 2.0 * i / 16.0;
                Location point = center.clone().add(Math.cos(angle) * ringRadius, 0.15, Math.sin(angle) * ringRadius);
                meteor.world.spawnParticle(signature, point, 2, 0.04, 0.08, 0.04, 0.01);
            }
            meteor.world.spawnParticle(Particle.FIREWORK, center, 1, 0.15, 0.1, 0.15, 0.01);
            if (step[0] >= steps) {
                meteor.impactSequenceTask.cancel();
                meteor.impactSequenceTask = null;
                meteor.impactPending = false;
                triggerImpact(meteor);
            }
        }, 0L, 2L);
    }

    private void playDescentSound(ActiveMeteor meteor, Location loc) {
        String typeId = meteor.meteorType.getId().toLowerCase();
        Sound sound;
        float volume = 1.5f;
        float pitch = 0.5f;

        if (meteor.meteorType.isNormalDrop()) {
            sound = Sound.ITEM_ELYTRA_FLYING;
            volume = 0.8f;
            pitch = 1.0f;
        } else if(typeId.contains("legend")) {
            sound=configuredSound("event-announcements.legendary-sound",Sound.ENTITY_ENDER_DRAGON_GROWL);
            volume=1.8f;pitch=0.65f;
        } else if (typeId.contains("ice")) {
            sound = Sound.BLOCK_GLASS_BREAK;
            volume = 1.2f;
            pitch = 0.3f;
        } else if (typeId.contains("end") || typeId.contains("void")) {
            sound = Sound.ENTITY_ENDERMAN_TELEPORT;
            volume = 1.5f;
            pitch = 0.6f;
        } else if (typeId.contains("divine") || typeId.contains("celestial")) {
            sound = Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            volume = 1.5f;
            pitch = 0.8f;
        } else if (typeId.contains("toxic") || typeId.contains("corrupto")) {
            sound = Sound.BLOCK_SCULK_SHRIEKER_SHRIEK;
            volume = 1.2f;
            pitch = 0.6f;
        } else if (typeId.contains("storm") || typeId.contains("tormenta") || typeId.contains("electric")) {
            sound = Sound.ITEM_TRIDENT_THUNDER;
            volume = 1.5f;
            pitch = 0.6f;
        } else {
            sound = Sound.ENTITY_BLAZE_BURN;
            volume = 1.5f;
            pitch = 0.5f;
        }

        for (Player player : meteor.world.getPlayers()) {
            if (player.getWorld().equals(meteor.world) && player.getLocation().distanceSquared(loc) <= 300.0 * 300.0) {
                player.playSound(loc, sound, SoundCategory.MASTER, volume, pitch);
            }
        }
    }

    private Sound configuredSound(String path,Sound fallback){
        String name=plugin.getConfig().getString(path,"").trim();
        if(name.isEmpty())return fallback;
        try{return Sound.valueOf(name.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException ignored){plugin.getLogger().warning("Invalid configured sound at "+path+": "+name);return fallback;}
    }

    /**
     * Triggers the meteor impact on the ground, creating the crater and chest.
     */
    public synchronized void triggerImpact(ActiveMeteor meteor) {
        if (meteor.impacted) {
            return;
        }
        meteor.impacted = true;
        if (meteor.impactSequenceTask != null) {
            meteor.impactSequenceTask.cancel();
            meteor.impactSequenceTask = null;
        }

        if (meteor.task != null) {
            meteor.task.cancel();
            meteor.task = null;
        }

        activeMeteors.remove(meteor.meteorId);
        for (UUID uuid : meteor.fallingBlockUuids) {
            meteorsByEntityUuid.remove(uuid);
        }

        for (FallingBlock fb : meteor.fallingBlocks) {
            if (fb.isValid()) {
                fb.remove();
            }
        }

        World world = meteor.world;
        int cx = meteor.targetX;
        int groundY = meteor.groundY;
        int cz = meteor.targetZ;
        MeteorType type = meteor.meteorType;

        int protectionRadius = type.isNormalDrop() ? 1 : Math.max(1, type.getCraterRadius()) + 2;
        Location impactTarget = new Location(world, cx + 0.5, groundY, cz + 0.5);
        String protectionReason = plugin.getProtectionManager() == null ? null
                : plugin.getProtectionManager().getProtectionReason(impactTarget, protectionRadius);
        double borderExtent = protectionRadius + 2.0;
        if (protectionReason != null || !isImpactInsideWorldBorder(world, cx + 0.5, cz + 0.5, borderExtent)) {
            plugin.getLogger().warning("[MeteoriteDrop] Impact canceled at " + world.getName() + " X=" + cx + ", Z=" + cz
                    + (protectionReason == null ? "; cause=world-border changed during descent." : "; cause=" + protectionReason + " (protection changed during descent)."));
            PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&c☄ El impacto en X: " + cx + ", Z: " + cz + " fue cancelado porque el terreno quedó protegido."));
            cancelRitualMeteor(meteor, protectionReason == null ? "límite del mundo" : protectionReason);
            if (meteor.partOfShower) onShowerChestRestored();
            return;
        }

        if (type.isNormalDrop()) {
            triggerNormalDropImpact(meteor);
            return;
        }

        // Snapshot the full planned area before announcing or mutating it. Block entities
        // (containers, signs, spawners, etc.) are rejected because BlockData cannot restore
        // their contents or custom state after the crater expires.
        Map<Location, org.bukkit.block.data.BlockData> originalBlocks = new HashMap<>();
        int craterRadius = Math.max(1, type.getCraterRadius());
        int craterDepth = Math.max(1, type.getCraterDepth());
        Material craterMaterial = type.getCraterMain();
        Material rimMaterial = type.getCraterRim() != null ? type.getCraterRim() : craterMaterial;
        Material scatterMaterial = type.getFallingBlock();
        Location tileStateLocation = null;
        outerSnapshot:
        for (int dx = -craterRadius - 2; dx <= craterRadius + 2; dx++) {
            for (int dz = -craterRadius - 2; dz <= craterRadius + 2; dz++) {
                for (int dy = -craterDepth - 2; dy <= 5; dy++) {
                    Block block = world.getBlockAt(cx + dx, groundY + dy, cz + dz);
                    if (block.getState() instanceof org.bukkit.block.TileState) {
                        tileStateLocation = block.getLocation();
                        break outerSnapshot;
                    }
                    originalBlocks.putIfAbsent(block.getLocation(), block.getBlockData().clone());
                }
            }
        }
        if (tileStateLocation != null) {
            plugin.getLogger().warning("[MeteoriteDrop] Impact canceled to preserve block entity at " + tileStateLocation + ".");
            PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&c☄ El impacto fue cancelado para proteger un bloque especial en X: " + cx + ", Z: " + cz + "."));
            cancelRitualMeteor(meteor, "bloque especial protegido");
            if (meteor.partOfShower) onShowerChestRestored();
            return;
        }
        plugin.getCraterRecoveryManager().saveSnapshot(new Location(world, cx, groundY - craterDepth + 1, cz), originalBlocks);

        Location impactCenter = new Location(world, cx + 0.5, groundY + 1.0, cz + 0.5);
        double impactDensity = Math.max(0.1, Math.min(1.5, plugin.getConfig().getDouble("meteor-animation.impact-particle-density", 0.5)));

        // 0. Cinematic title to ALL players on the server
        Title impactTitle = Title.title(
            LegacyComponentSerializer.legacySection().deserialize("§6§l☄ ¡Cayó Un Meteorito!"),
            LegacyComponentSerializer.legacySection().deserialize(
                "§e" + type.getDisplayName() + " §7» §eX: " + cx + ", Z: " + cz),
            Title.Times.times(
                java.time.Duration.ofMillis(500),
                java.time.Duration.ofMillis(3000),
                java.time.Duration.ofMillis(1000)
            )
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(impactTitle);
        }

        // 1. Play massive thunder and sonic boom sounds to all players in the world
        for (Player p : world.getPlayers()) {
            p.playSound(impactCenter, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 15.0f, 0.8f);
            p.playSound(impactCenter, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 10.0f, 0.7f);
        }
        world.playSound(impactCenter, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 5.0f, 0.8f);

        // 2. Impact particles
        world.spawnParticle(Particle.SONIC_BOOM, impactCenter, scaledParticleCount(2, impactDensity), 0.5, 0.5, 0.5, 0.0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, impactCenter, scaledParticleCount(10, impactDensity), 2.0, 1.2, 2.0, 0.0);
        world.spawnParticle(Particle.FLAME, impactCenter, scaledParticleCount(180, impactDensity), 2.5, 2.5, 2.5, 0.15);
        world.spawnParticle(Particle.LAVA, impactCenter, scaledParticleCount(30, impactDensity), 2.0, 1.0, 2.0, 0.05);

        String typeId = type.getId().toLowerCase();
        if (typeId.contains("ritual")) {
            world.strikeLightningEffect(impactCenter);
            world.playSound(impactCenter, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 8.0f, 0.6f);
            world.spawnParticle(Particle.PORTAL, impactCenter, scaledParticleCount(220, impactDensity), 4.0, 3.0, 4.0, 0.8);
            world.spawnParticle(Particle.FLAME, impactCenter, scaledParticleCount(180, impactDensity), 3.0, 2.0, 3.0, 0.2);
            world.spawnParticle(Particle.DRAGON_BREATH, impactCenter, scaledParticleCount(110, impactDensity), 3.0, 2.0, 3.0, 0.08, 1.0f);
            world.spawnParticle(Particle.SCULK_SOUL, impactCenter, scaledParticleCount(120, impactDensity), 3.0, 2.0, 3.0, 0.15);
            world.spawnParticle(Particle.LAVA, impactCenter, scaledParticleCount(50, impactDensity), 2.0, 1.5, 2.0, 0.05);
        } else if (typeId.contains("ice")) {
            world.spawnParticle(Particle.SNOWFLAKE, impactCenter, scaledParticleCount(180, impactDensity), 2.5, 2.5, 2.5, 0.15);
            world.spawnParticle(Particle.ITEM_SNOWBALL, impactCenter, scaledParticleCount(30, impactDensity), 2.0, 1.0, 2.0, 0.05);
        } else if (typeId.contains("end") || typeId.contains("void")) {
            world.playSound(impactCenter, Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 5.0f, 0.8f);
            world.spawnParticle(Particle.PORTAL, impactCenter, scaledParticleCount(180, impactDensity), 3.0, 2.0, 3.0, 0.5);
            world.spawnParticle(Particle.DRAGON_BREATH, impactCenter, scaledParticleCount(80, impactDensity), 2.5, 1.5, 2.5, 0.05, 1.0f);
        } else if (typeId.contains("divine") || typeId.contains("celestial")) {
            world.playSound(impactCenter, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.AMBIENT, 5.0f, 1.2f);
            world.spawnParticle(Particle.TOTEM_OF_UNDYING, impactCenter, scaledParticleCount(180, impactDensity), 3.0, 2.0, 3.0, 0.5);
            world.spawnParticle(Particle.FIREWORK, impactCenter, scaledParticleCount(4, impactDensity), 1.0, 1.0, 1.0, 0.0);
        } else if (typeId.contains("toxic") || typeId.contains("corrupto")) {
            world.playSound(impactCenter, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 5.0f, 0.7f);
            world.spawnParticle(Particle.SCULK_SOUL, impactCenter, scaledParticleCount(180, impactDensity), 3.0, 2.0, 3.0, 0.5);
            world.spawnParticle(Particle.SQUID_INK, impactCenter, scaledParticleCount(60, impactDensity), 2.0, 1.5, 2.0, 0.05);
        } else if (typeId.contains("storm") || typeId.contains("tormenta") || typeId.contains("electric")) {
            world.strikeLightningEffect(impactCenter);
            world.playSound(impactCenter, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 10.0f, 1.0f);
            world.spawnParticle(Particle.ELECTRIC_SPARK, impactCenter, scaledParticleCount(180, impactDensity), 3.0, 2.0, 3.0, 0.2);
            world.spawnParticle(Particle.FIREWORK, impactCenter, scaledParticleCount(60, impactDensity), 2.0, 2.0, 2.0, 0.1);
        }

        // 3. Shockwave sound at multiple radiuses (10, 20, 30 blocks)
        int[] radiuses = {10, 20, 30};
        float[] volumes = {3.0f, 2.0f, 1.0f};
        for (int i = 0; i < radiuses.length; i++) {
            int r = radiuses[i];
            float vol = volumes[i];
            world.playSound(impactCenter.clone().add(r, 0, 0), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, vol, 0.6f);
            world.playSound(impactCenter.clone().add(-r, 0, 0), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, vol, 0.6f);
            world.playSound(impactCenter.clone().add(0, 0, r), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, vol, 0.6f);
            world.playSound(impactCenter.clone().add(0, 0, -r), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, vol, 0.6f);
        }

        for (Player player : world.getPlayers()) {
            if (player.getWorld().equals(world) && player.getLocation().distanceSquared(impactCenter) <= 50.0 * 50.0) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.AMBIENT, 1.0f, 0.5f);
            }
        }

        // 4. Realistic bowl crater generation; the full rollback snapshot is already durable.
        // Step 2 — Carve bowl shape and add rim
        for (int dx = -(craterRadius + 1); dx <= craterRadius + 1; dx++) {
            for (int dz = -(craterRadius + 1); dz <= craterRadius + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > craterRadius + 1.2) continue;

                int bx = cx + dx;
                int bz = cz + dz;

                if (dist <= craterRadius) {
                    // Inside bowl: parabolic depth — deepest at center, 0 at rim
                    double t = dist / craterRadius; // 0 at center, 1 at rim
                    int bowlFloor = (int) Math.round(craterDepth * (1.0 - t * t));
                    // bowlFloor = how many blocks below groundY we carve at this (dx,dz)

                    // Carve from groundY down to (groundY - bowlFloor), set to AIR
                    for (int dy = 0; dy >= -bowlFloor; dy--) {
                        Block b = world.getBlockAt(bx, groundY + dy, bz);
                        b.setType(Material.AIR, false);
                    }

                    // If within inner 40% of crater, scatter crater material on the floor
                    if (dist < craterRadius * 0.7) {
                        int floorY = groundY - bowlFloor;
                        Block floorBlock = world.getBlockAt(bx, floorY - 1, bz);
                        originalBlocks.putIfAbsent(floorBlock.getLocation(), floorBlock.getBlockData().clone());
                        if (ThreadLocalRandom.current().nextInt(3) != 0) {
                            floorBlock.setType(craterMaterial, false);
                        }
                    }

                    // Scatter meteor material on inner walls (random chance)
                    if (dist > craterRadius * 0.3 && dist < craterRadius * 0.85) {
                        int floorY = groundY - bowlFloor;
                        if (ThreadLocalRandom.current().nextInt(4) == 0) {
                            Block wallBlock = world.getBlockAt(bx, floorY, bz);
                            wallBlock.setType(scatterMaterial, false);
                        }
                    }

                } else if (dist <= craterRadius + 1.2) {
                    // Rim zone: raise terrain by 1-2 blocks above ground
                    int rimHeight = (dist <= craterRadius + 0.6) ? 2 : 1;
                    for (int dy = 1; dy <= rimHeight; dy++) {
                        Block b = world.getBlockAt(bx, groundY + dy, bz);
                        originalBlocks.putIfAbsent(b.getLocation(), b.getBlockData().clone());
                        b.setType(rimMaterial, false);
                    }
                }
            }
        }

        // Step 3 — Clear air shaft above impact center so blocks above don't float
        for (int dy = 1; dy <= 5; dy++) {
            Block airB = world.getBlockAt(cx, groundY + dy, cz);
            originalBlocks.putIfAbsent(airB.getLocation(), airB.getBlockData().clone());
            airB.setType(Material.AIR, false);
        }

        // Step 4 — Place chest at bottom center of the bowl
        int chestY = groundY - craterDepth + 1;
        // Make sure there's solid ground below the chest
        Block belowChest = world.getBlockAt(cx, chestY - 1, cz);
        originalBlocks.putIfAbsent(belowChest.getLocation(), belowChest.getBlockData().clone());
        belowChest.setType(craterMaterial, false);

        Block chestBlock = world.getBlockAt(cx, chestY, cz);
        originalBlocks.putIfAbsent(chestBlock.getLocation(), chestBlock.getBlockData().clone());
        chestBlock.setType(Material.AIR, false);

        // Step 5 — Keep the block above the chest empty. The old decorative core
        // could be mined by other plugins and drop an item into the crater.
        Block coreBlock = world.getBlockAt(cx, chestY + 1, cz);
        originalBlocks.putIfAbsent(coreBlock.getLocation(), coreBlock.getBlockData().clone());
        coreBlock.setType(Material.AIR, false);

        // Clear air above the chest up to above ground level
        for (int dy = chestY + 2; dy <= groundY + 3; dy++) {
            Block airB = world.getBlockAt(cx, dy, cz);
            originalBlocks.putIfAbsent(airB.getLocation(), airB.getBlockData().clone());
            airB.setType(Material.AIR, false);
        }

        // Step 6 — Spawn a floating chest BlockDisplay. Loot is kept in a virtual inventory.
        BlockDisplay chestVisual = world.spawn(chestBlock.getLocation().clone().add(0.1, 0.35, 0.1), BlockDisplay.class, display -> {
            display.setBlock(Material.CHEST.createBlockData());
            display.setTeleportDuration(10);
            display.addScoreboardTag("meteoritedrop_chest_visual");
        });
        Interaction chestHitbox = spawnChestHitbox(world, chestBlock.getLocation().clone().add(0.5, 0.35, 0.5));

        // Step 7 — Spawn TextDisplay hologram above the floating chest
        Location holoLoc = new Location(world, cx + 0.5, groundY + 2.0, cz + 0.5);
        TextDisplay display = world.spawn(holoLoc, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(80, 0, 0, 0));
            d.setSeeThrough(false);
            d.setTextOpacity((byte) 200);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.addScoreboardTag("meteoritedrop_hologram");
            long remaining = type.getLockDurationSeconds();
            long minutes = remaining / 60;
            long seconds = remaining % 60;
            String text = "§c§l☄ §4[SELLADO]\n§7Abre en: §c" + String.format("%02d:%02d", minutes, seconds);
            d.text(LegacyComponentSerializer.legacySection().deserialize(text));
        });

        // Step 8 — Register chest with ChestManager
        com.meteoritedrop.models.MeteoriteChest chest = plugin.getChestManager().registerChest(
                chestBlock.getLocation(),
                chestBlock.getLocation(),
                display.getUniqueId(),
                chestVisual.getUniqueId(),
                chestHitbox.getUniqueId(),
                type,
                originalBlocks,
                meteor.partOfShower
        );

        if (meteor.ritualOwner != null && plugin.getRitualManager() != null) {
            plugin.getRitualManager().onRitualMeteorImpacted(meteor.ritualOwner);
            meteor.ritualOwner = null;
            meteor.ritualFireCharge = null;
        }

        if (plugin.getMeteorEventManager() != null) plugin.getMeteorEventManager().onMeteorLanded(chest);

        // Step 9 — Spawn themed crater guardians
        if (plugin.getGuardianManager() != null) {
            plugin.getGuardianManager().spawnGuardians(chest);
        }


        // Step 10 — Broadcast landed message
        PlayerBroadcast.send(plugin.getConfigManager().getBroadcastLanded(type.getDisplayName(), cx, cz));
        plugin.getConfigManager().broadcastLandedToDiscord(type.getDisplayName(), cx, cz, world.getName());
        if (plugin.getConfig().getBoolean("rarity-alerts.enabled", true) && plugin.getConfig().getStringList("meteor-rarity.rare-types").stream().anyMatch(id -> id.equalsIgnoreCase(type.getId()))) {
            String rareMessage = plugin.getConfig().getString("rarity-alerts.message", "§d✦ ¡Meteorito raro {type} en X: {x}, Z: {z}!")
                    .replace("{type}", type.getDisplayName()).replace("{x}", String.valueOf(cx)).replace("{z}", String.valueOf(cz));
            PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(rareMessage));
            if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("rare-impact", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(rareMessage)));
        }

        // Step 11 — Record drop in history
        if (plugin.getDropHistoryManager() != null) {
            plugin.getDropHistoryManager().record(type.getId(), cx, cz, world.getName());
        }
    }

    private void triggerNormalDropImpact(ActiveMeteor meteor) {
        World world = meteor.world;
        int cx = meteor.targetX;
        int groundY = meteor.groundY;
        int cz = meteor.targetZ;
        MeteorType type = meteor.meteorType;

        Location chestLoc = new Location(world, cx, groundY + 1, cz);
        Location holoLoc = new Location(world, cx + 0.5, groundY + 2.2, cz + 0.5);

        // Friendly gentle landing sound & particles
        world.playSound(chestLoc, Sound.BLOCK_CHEST_LOCKED, SoundCategory.BLOCKS, 1.5f, 1.0f);
        world.playSound(chestLoc, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 1.0f, 1.2f);
        world.spawnParticle(Particle.FIREWORK, chestLoc.clone().add(0.5, 0.5, 0.5), 35, 0.5, 0.5, 0.5, 0.05);
        world.spawnParticle(Particle.CLOUD, chestLoc.clone().add(0.5, 1.0, 0.5), 20, 0.4, 0.3, 0.4, 0.02);

        // Global title
        Title title = Title.title(
            LegacyComponentSerializer.legacySection().deserialize("§e📦 ¡CAYÓ UN SUMINISTRO!"),
            LegacyComponentSerializer.legacySection().deserialize("§7En §eX: " + cx + ", Z: " + cz + " §8— §a¡Sin cráter!"),
            Title.Times.times(java.time.Duration.ofMillis(400), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(800))
        );
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
        }

        // Set chest block on the surface (no crater!)
        Map<Location, org.bukkit.block.data.BlockData> originalBlocks = new HashMap<>();
        Block chestBlock = chestLoc.getBlock();
        if (chestBlock.getState() instanceof org.bukkit.block.TileState) {
            plugin.getLogger().warning("[MeteoriteDrop] Supply drop canceled to preserve block entity at " + chestBlock.getLocation() + ".");
            PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&c📦 El suministro no pudo aterrizar porque había un bloque especial en X: " + cx + ", Z: " + cz + "."));
            if (meteor.partOfShower) onShowerChestRestored();
            return;
        }
        originalBlocks.putIfAbsent(chestBlock.getLocation(), chestBlock.getBlockData().clone());
        plugin.getCraterRecoveryManager().saveSnapshot(chestLoc, originalBlocks);
        chestBlock.setType(Material.AIR, false);

        BlockDisplay chestVisual = world.spawn(chestLoc.clone().add(0.1, 0.35, 0.1), BlockDisplay.class, chestDisplay -> {
            chestDisplay.setBlock(Material.CHEST.createBlockData());
            chestDisplay.setTeleportDuration(10);
            chestDisplay.addScoreboardTag("meteoritedrop_chest_visual");
        });
        Interaction chestHitbox = spawnChestHitbox(world, chestLoc.clone().add(0.5, 0.35, 0.5));

        // Floating countdown TextDisplay hologram
        TextDisplay display = world.spawn(holoLoc, TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(Color.fromARGB(80, 0, 0, 0));
            d.setSeeThrough(false);
            d.setTextOpacity((byte) 200);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.addScoreboardTag("meteoritedrop_hologram");

            long remaining = type.getLockDurationSeconds();
            long minutes = remaining / 60;
            long seconds = remaining % 60;
            String text = "§e📦 §6[SUMINISTRO NORMAL]\n§7Abre en: §c" + String.format("%02d:%02d", minutes, seconds);
            d.text(LegacyComponentSerializer.legacySection().deserialize(text));
        });

        // Register with ChestManager (no core block needed)
        plugin.getChestManager().registerChest(
                chestBlock.getLocation(),
                chestBlock.getLocation(),
                display.getUniqueId(),
                chestVisual.getUniqueId(),
                chestHitbox.getUniqueId(),
                type,
                originalBlocks,
                meteor.partOfShower
        );

        // Broadcast to chat
        String msg = "&8&m--------------------------------------------------\n" +
                "&e📦 &6&l¡SUMINISTRO NORMAL ATERRIZÓ! &e📦\n" +
                "&7Un suministro ha caído en &eX: " + cx + ", Z: " + cz + "&7!\n" +
                "&a¡El primero en llegar se lo lleva (sin tiempo de apertura)!\n" +
                "&8&m--------------------------------------------------";
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));

        if (plugin.getDropHistoryManager() != null) {
            plugin.getDiscordManager().sendSupplyDropLanded(cx, cz, world.getName(), type.getLockDurationSeconds());
            plugin.getDropHistoryManager().record("normal", cx, cz, world.getName());
        }

    }

    private Interaction spawnChestHitbox(World world, Location location) {
        return world.spawn(location, Interaction.class, hitbox -> {
            hitbox.setInteractionWidth(1.2f);
            hitbox.setInteractionHeight(1.1f);
            hitbox.setResponsive(true);
            hitbox.setGravity(false);
            hitbox.addScoreboardTag("meteoritedrop_chest_interaction");
        });
    }

    private boolean isImpactInsideWorldBorder(World world, double centerX, double centerZ, double extent) {
        WorldBorder border = world.getWorldBorder();
        return border.isInside(new Location(world, centerX - extent, 0, centerZ - extent))
                && border.isInside(new Location(world, centerX - extent, 0, centerZ + extent))
                && border.isInside(new Location(world, centerX + extent, 0, centerZ - extent))
                && border.isInside(new Location(world, centerX + extent, 0, centerZ + extent));
    }

    private void cancelRitualMeteor(ActiveMeteor meteor, String reason) {
        if (meteor == null || meteor.ritualOwner == null || plugin.getRitualManager() == null) return;
        plugin.getRitualManager().onRitualMeteorCancelled(meteor.ritualOwner, meteor.ritualFireCharge, reason);
        meteor.ritualOwner = null;
        meteor.ritualFireCharge = null;
    }

    /**
     * Checks if an entity is part of an active falling meteorite.
     */
    public boolean isFallingMeteor(Entity entity) {
        return entity != null && (meteorsByEntityUuid.containsKey(entity.getUniqueId()) ||
                entity.getScoreboardTags().contains("meteoritedrop_falling"));
    }

    /**
     * Handles block change event when a falling meteorite entity makes contact.
     */
    public void onFallingBlockHit(Entity entity) {
        ActiveMeteor meteor = meteorsByEntityUuid.get(entity.getUniqueId());
        if (meteor != null) {
            beginImpactSequence(meteor);
        } else if (entity.getScoreboardTags().contains("meteoritedrop_falling")) {
            entity.remove();
        }
    }

    /**
     * Cancels all falling meteor tasks and removes entities.
     */
    public void cleanup() {
        showerLaunching = false;
        showerChestCount = 0;
        finishShowerIfDone();
        for (ActiveMeteor meteor : activeMeteors.values()) {
            if (meteor.task != null) {
                meteor.task.cancel();
            }
            if (meteor.impactSequenceTask != null) meteor.impactSequenceTask.cancel();
            for (FallingBlock fb : meteor.fallingBlocks) {
                if (fb.isValid()) {
                    fb.remove();
                }
            }
            cancelRitualMeteor(meteor, "servidor reiniciado o plugin apagado");
        }
        activeMeteors.clear();
        meteorsByEntityUuid.clear();
    }
}
