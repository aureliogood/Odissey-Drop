package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.models.MeteoriteChest;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages periodic environmental anomaly / radiation effects applied to players
 * lingering inside active meteorite craters.
 */
public class CraterEffectManager {

    private final MeteoriteDrop plugin;
    private BukkitTask task;

    public CraterEffectManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        startTask();
    }

    public void startTask() {
        if (task != null) {
            task.cancel();
        }
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        for (MeteoriteChest chest : plugin.getChestManager().getAllActiveChests()) {
            MeteoriteChest.ChestState state = chest.getState();
            if (state != MeteoriteChest.ChestState.LOCKED &&
                    state != MeteoriteChest.ChestState.UNLOCKABLE &&
                    state != MeteoriteChest.ChestState.BEING_UNLOCKED) {
                continue;
            }

            Location cLoc = chest.getChestLocation();
            if (cLoc == null || cLoc.getWorld() == null) continue;

            World world = cLoc.getWorld();
            MeteorType type = chest.getMeteorType();
            if (type != null && type.isNormalDrop()) {
                continue;
            }
            int craterRadius = (type != null ? type.getCraterRadius() : 5) + 2;
            double radiusSq = craterRadius * craterRadius;
            int craterDepth = (type != null ? type.getCraterDepth() : 3);

            for (Player player : world.getPlayers()) {
                if (!player.isValid() || player.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }

                Location pLoc = player.getLocation();
                double dx = pLoc.getX() - cLoc.getX();
                double dz = pLoc.getZ() - cLoc.getZ();
                double distSq = (dx * dx) + (dz * dz);

                if (distSq <= radiusSq) {
                    double dy = pLoc.getY() - cLoc.getY();
                    if (dy >= -2 && dy <= (craterDepth + 8)) {
                        applyCraterEffect(player, type, world);
                    }
                }
            }
        }
    }

    private void applyCraterEffect(Player player, MeteorType type, World world) {
        String typeId = (type != null) ? type.getId().toLowerCase() : "lava";

        if (typeId.contains("ice")) {
            player.setFreezeTicks(Math.min(player.getMaxFreezeTicks(), player.getFreezeTicks() + 40));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, false, false, true));
            world.spawnParticle(Particle.SNOWFLAKE, player.getLocation().add(0, 0.5, 0), 6, 0.25, 0.3, 0.25, 0.02);
        } else if (typeId.contains("end")) {
            if (ThreadLocalRandom.current().nextDouble() < 0.25) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20, 0, false, false, true));
            } else {
                player.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 60, 0, false, false, true));
            }
            world.spawnParticle(Particle.PORTAL, player.getLocation().add(0, 0.8, 0), 8, 0.3, 0.5, 0.3, 0.1);
        } else if (typeId.contains("divine") || typeId.contains("celestial")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, true));
            world.spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1.0, 0), 4, 0.2, 0.4, 0.2, 0.02);
        } else if (typeId.contains("toxic") || typeId.contains("corrupto")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0, false, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, true));
            world.spawnParticle(Particle.SCULK_SOUL, player.getLocation().add(0, 0.5, 0), 5, 0.3, 0.3, 0.3, 0.02);
        } else if (typeId.contains("storm") || typeId.contains("tormenta")) {
            if (ThreadLocalRandom.current().nextDouble() < 0.20) {
                world.strikeLightningEffect(player.getLocation());
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, false, false, true));
            world.spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.5, 0), 6, 0.3, 0.3, 0.3, 0.05);
        } else {
            // Default: Lava
            if (!player.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)) {
                player.setFireTicks(Math.max(player.getFireTicks(), 40));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, false, false, true));
            }
            world.spawnParticle(Particle.FLAME, player.getLocation().add(0, 0.1, 0), 5, 0.2, 0.1, 0.2, 0.02);
        }
    }

    public void cleanup() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }
}
