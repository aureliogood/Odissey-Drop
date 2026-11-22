package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Applies temporary storm/night ambience and restores each world's previous state. */
public final class EnvironmentEffectManager {
    private static final class WorldState {
        final World world;
        final boolean storm;
        final boolean thunder;
        final int weatherDuration;
        final int thunderDuration;
        final long time;
        long appliedAt;
        BukkitTask restoreTask;
        WorldState(World world) {
            this.world=world; storm=world.hasStorm(); thunder=world.isThundering();
            weatherDuration=world.getWeatherDuration(); thunderDuration=world.getThunderDuration(); time=world.getTime();
        }
    }
    private final MeteoriteDrop plugin;
    private final Map<UUID,WorldState> active=new HashMap<>();
    public EnvironmentEffectManager(MeteoriteDrop plugin){this.plugin=plugin;}

    public synchronized void apply(World world) {
        if(world==null || !plugin.getConfig().getBoolean("environment-effects.enabled",true))return;
        WorldState state=active.computeIfAbsent(world.getUID(),ignored->new WorldState(world));
        state.appliedAt=System.currentTimeMillis();
        if(plugin.getConfig().getBoolean("environment-effects.storm",true))world.setStorm(true);
        if(plugin.getConfig().getBoolean("environment-effects.thunder",true))world.setThundering(true);
        if(plugin.getConfig().getBoolean("environment-effects.night-time",true))
            world.setTime(Math.floorMod(plugin.getConfig().getLong("environment-effects.night-time-value",18000L),24000L));
        int ticks=(int)Math.max(20,Math.min(72000L,plugin.getConfig().getLong("environment-effects.duration-seconds",60L)*20L));
        world.setWeatherDuration(ticks); world.setThunderDuration(ticks);
        if(state.restoreTask!=null)state.restoreTask.cancel();
        state.restoreTask=Bukkit.getScheduler().runTaskLater(plugin,()->restore(world.getUID()),ticks);
    }

    private synchronized void restore(UUID worldId) {
        WorldState state=active.remove(worldId); if(state==null)return;
        World world=Bukkit.getWorld(worldId); if(world==null)return;
        world.setStorm(state.storm); world.setThundering(state.thunder);
        world.setWeatherDuration(Math.max(1,state.weatherDuration)); world.setThunderDuration(Math.max(1,state.thunderDuration));
        long elapsedTicks=Math.max(0,(System.currentTimeMillis()-state.appliedAt)/50L);
        world.setTime(Math.floorMod(state.time+elapsedTicks,24000L));
    }

    public synchronized void cleanup(){for(UUID id:new java.util.ArrayList<>(active.keySet()))restore(id);}
}
