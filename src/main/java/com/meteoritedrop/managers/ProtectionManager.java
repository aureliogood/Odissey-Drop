package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import java.lang.reflect.Method;
import java.util.logging.Level;

/** Prevents impacts near spawn or inside WorldGuard regions that deny building. */
public final class ProtectionManager {
    private final MeteoriteDrop plugin;
    private final boolean worldGuardAvailable;
    private boolean loggedFailure;
    private String lastQueryFailure;
    public ProtectionManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.worldGuardAvailable = Bukkit.getPluginManager().getPlugin("WorldGuard") != null;
    }
    public boolean isWorldGuardAvailable() { return worldGuardAvailable; }
    public boolean isProtected(Location center, int radius) {
        return getProtectionReason(center, radius) != null;
    }
    /** Returns the blocking source for diagnostics, or null when this location is allowed. */
    public String getProtectionReason(Location center, int radius) {
        lastQueryFailure=null;
        if (center == null || center.getWorld() == null) return "invalid-location";
        if (plugin.getConfig().getBoolean("drop-protection.enabled", true)) {
            int safe = plugin.getConfig().getInt("drop-protection.spawn-radius-blocks", 128);
            int distance = safe + Math.max(0, radius);
            Location spawn = center.getWorld().getSpawnLocation();
            double dx = center.getX() - spawn.getX();
            double dz = center.getZ() - spawn.getZ();
            double actualDistance = Math.sqrt(dx * dx + dz * dz);
            if (safe > 0 && actualDistance < distance) {
                return "spawn-radius (distancia " + Math.round(actualDistance) + " bloques; radio protegido " + distance + ")";
            }
        }
        if (!plugin.getConfig().getBoolean("drop-protection.worldguard", true) || !worldGuardAvailable) return null;
        // Check every affected horizontal block column, not only the center and
        // circumference. A small WorldGuard region can sit wholly inside a crater.
        // Callers include the terrain-edit margin in radius (crater radius + rim).
        int coverageRadius = Math.max(0, radius);
        int extent = coverageRadius;
        double coverageSquared = (double) coverageRadius * coverageRadius;
        for (int dx = -extent; dx <= extent; dx++) {
            for (int dz = -extent; dz <= extent; dz++) {
                if ((double) dx * dx + (double) dz * dz > coverageSquared) continue;
                Location sample = center.clone().add(dx, 0, dz);
                if (deniesBuild(sample)) return worldGuardReason(sample);
            }
        }
        return null;
    }
    private String worldGuardReason(Location location) {
        if(lastQueryFailure!=null)return "worldguard-query-error ("+lastQueryFailure+")";
        return "worldguard-build-denied (muestra X="+location.getBlockX()+", Y="+location.getBlockY()+", Z="+location.getBlockZ()+")";
    }
    private boolean deniesBuild(Location location) {
        try {
            ClassLoader cl = Bukkit.getPluginManager().getPlugin("WorldGuard").getClass().getClassLoader();
            Class<?> wg = Class.forName("com.sk89q.worldguard.WorldGuard", true, cl);
            Object instance = wg.getMethod("getInstance").invoke(null);
            Object platform = wg.getMethod("getPlatform").invoke(instance);
            Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
            Object query = container.getClass().getMethod("createQuery").invoke(container);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", true, cl);
            Object adapted = adapter.getMethod("adapt", Location.class).invoke(null, location);
            Class<?> queryType = Class.forName("com.sk89q.worldguard.protection.regions.RegionQuery", true, cl);
            Object set = queryType.getMethod("getApplicableRegions", Class.forName("com.sk89q.worldedit.util.Location", true, cl)).invoke(query, adapted);
            Class<?> flags = Class.forName("com.sk89q.worldguard.protection.flags.Flags", true, cl);
            Object build = flags.getField("BUILD").get(null);
            Class<?> stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", true, cl);
            Object flagArray = java.lang.reflect.Array.newInstance(stateFlag, 1);
            java.lang.reflect.Array.set(flagArray, 0, build);
            // ApplicableRegionSet belongs to protection, while RegionQuery is in protection.regions.
            Class<?> setType = Class.forName("com.sk89q.worldguard.protection.ApplicableRegionSet", true, cl);
            Method queryState = java.util.Arrays.stream(setType.getMethods()).filter(m -> m.getName().equals("queryState") && m.getParameterCount() == 2).findFirst().orElseThrow();
            Class<?> associationType = Class.forName("com.sk89q.worldguard.domains.Association", true, cl);
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object nonMember = Enum.valueOf((Class<? extends Enum>) associationType.asSubclass(Enum.class), "NON_MEMBER");
            Class<?> associablesType = Class.forName("com.sk89q.worldguard.protection.association.Associables", true, cl);
            Object subject = associablesType.getMethod("constant", associationType).invoke(null, nonMember);
            Object result = queryState.invoke(set, subject, flagArray);
            return result != null && result.toString().equalsIgnoreCase("DENY");
        } catch (Throwable e) {
            Throwable root=e instanceof java.lang.reflect.InvocationTargetException invocation && invocation.getCause()!=null?invocation.getCause():e;
            lastQueryFailure=root.getClass().getSimpleName()+": "+String.valueOf(root.getMessage());
            if (!loggedFailure) {
                loggedFailure = true;
                plugin.getLogger().log(Level.WARNING, "WorldGuard query failed; impacts will be blocked until the integration issue is fixed.", e);
            }
            return true;
        }
    }
}
