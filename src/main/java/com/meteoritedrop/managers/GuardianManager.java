package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.models.MeteoriteChest;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages spawning, protecting, and removing themed guardian mobs around meteorite craters.
 */
public class GuardianManager implements Listener {

    public static final String GUARDIAN_TAG = "meteoritedrop_guardian";

    private final MeteoriteDrop plugin;

    public GuardianManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
    }

    /**
     * Spawns 3 to 5 themed guardians on the rim of the crater around the meteorite chest.
     */
    public void spawnGuardians(MeteoriteChest chest) {
        if (chest == null || chest.getChestLocation() == null) return;
        MeteorType type=chest.getMeteorType();
        if(type!=null && type.isNormalDrop()) return;
        spawnWave(chest,1);
        String typeId=chest.getMeteorType()==null?"lava":chest.getMeteorType().getId().toLowerCase();
        spawnSpecialBoss(chest,typeId);
        int configuredWaves=plugin.getConfig().getInt("meteor-types."+typeId+".guardian-waves.count",plugin.getConfig().getInt("guardian-waves.count",3));
        int waves=Math.max(1,Math.min(10,configuredWaves));
        if(waves!=configuredWaves)plugin.getLogger().warning("Guardian wave count for '"+typeId+"' must be between 1 and 10; it was clamped.");
        String key=chestKey(chest.getChestLocation());
        if(waves<=1){finalWaveSpawned.add(key);return;}
        long configuredInterval=plugin.getConfig().getLong("meteor-types."+typeId+".guardian-waves.interval-seconds",plugin.getConfig().getLong("guardian-waves.interval-seconds",30));
        long interval=Math.max(5,Math.min(3600,configuredInterval))*20L;
        if(interval/20L!=configuredInterval)plugin.getLogger().warning("Guardian wave interval for '"+typeId+"' must be between 5 and 3600 seconds; it was clamped.");
        final org.bukkit.scheduler.BukkitTask[] taskRef=new org.bukkit.scheduler.BukkitTask[1];
        org.bukkit.scheduler.BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,new Runnable(){int wave=1;
            @Override public void run(){
                if(chest.getState()==MeteoriteChest.ChestState.RESTORED || chest.isRestoring() || !plugin.isEnabled()) {if(taskRef[0]!=null)taskRef[0].cancel();waveTasks.remove(key);finalWaveSpawned.remove(key);return;}
                if(wave>=waves){if(taskRef[0]!=null)taskRef[0].cancel();waveTasks.remove(key);return;}
                wave++;spawnWave(chest,wave);
                if(wave>=waves)finalWaveSpawned.add(key);
                if(plugin.getConfig().getBoolean("guardian-waves.announce",true)){
                    String text=plugin.getConfig().getString("event-announcements.guardian-wave-message","&c☠ Oleada {wave}/{total} de guardianes cerca del meteorito!").replace("{wave}",String.valueOf(wave)).replace("{total}",String.valueOf(waves));
                    text=net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(text));
                    for(Player player:chest.getChestLocation().getWorld().getPlayers())if(player.getLocation().distanceSquared(chest.getChestLocation())<1600)player.sendMessage(text);
                }
            }
        },interval,interval);
        taskRef[0]=task;
        waveTasks.put(key,task);
    }

    private void spawnSpecialBoss(MeteoriteChest chest,String typeId){
        String path="meteor-types."+typeId+".boss";
        boolean defaultEnabled=typeId.contains("legend")||typeId.contains("ritual");
        if(!plugin.getConfig().getBoolean("special-boss.enabled",true)||!plugin.getConfig().getBoolean(path+".enabled",defaultEnabled))return;
        Location center=chest.getChestLocation();World world=center==null?null:center.getWorld();if(world==null)return;
        int health=Math.max(50,Math.min(2048,plugin.getConfig().getInt(path+".health",plugin.getConfig().getInt("special-boss.health",300))));
        int radius=Math.max(4,Math.min(32,(chest.getMeteorType()==null?6:chest.getMeteorType().getCraterRadius())+2));
        int x=center.getBlockX()+Math.max(3,Math.min(6,radius/2)),z=center.getBlockZ(),y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING);
        Location spawnAt=new Location(world,x+.5,Math.max(y,center.getBlockY())+1,z+.5);
        Ravager boss;
        try { boss=world.spawn(spawnAt,Ravager.class); }
        catch(RuntimeException ex){plugin.getLogger().warning("Could not spawn the special boss for meteor type '"+typeId+"': "+ex.getMessage());return;}
        boss.addScoreboardTag(GUARDIAN_TAG);boss.addScoreboardTag("meteoritedrop_special_boss");boss.setRemoveWhenFarAway(false);boss.setPersistent(true);boss.setCustomNameVisible(true);
        String name=plugin.getConfig().getString(path+".name","&5☄ Coloso del Meteorito");boss.customName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        org.bukkit.attribute.Attribute maxHealthAttribute;try{maxHealthAttribute=Attribute.valueOf("MAX_HEALTH");}catch(IllegalArgumentException oldApi){maxHealthAttribute=Attribute.valueOf("GENERIC_MAX_HEALTH");}
        var maxHealth=boss.getAttribute(maxHealthAttribute);if(maxHealth!=null){maxHealth.setBaseValue(health);boss.setHealth(health);}
        Player target=world.getPlayers().stream().filter(p->p.getLocation().distanceSquared(center)<3600).min(java.util.Comparator.comparingDouble(p->p.getLocation().distanceSquared(spawnAt))).orElse(null);
        if(target!=null)boss.setTarget(target);
        boss.addScoreboardTag(eventTag(chest));
        chest.addGuardian(boss.getUniqueId());
        String title=LegacyComponentSerializer.legacySection().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        BossBar bar=Bukkit.createBossBar(title,BarColor.PURPLE,BarStyle.SEGMENTED_10);bar.setProgress(1.0);
        int showRadius=Math.max(32,Math.min(512,plugin.getConfig().getInt("special-boss.bossbar-radius",128)));
        long interval=Math.max(2,plugin.getConfig().getLong("special-boss.update-ticks",10));
        long[] nextAbilityTick={Math.max(1,plugin.getConfig().getInt("special-boss.ability-cooldown-seconds",10))*20L};
        BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            if(!boss.isValid()||boss.isDead()||chest.getState()==MeteoriteChest.ChestState.RESTORED||chest.isRestoring()){BossRuntime old=activeBosses.remove(boss.getUniqueId());if(old!=null)old.clear();return;}
            double max=maxHealth==null?health:maxHealth.getValue();bar.setProgress(Math.max(0,Math.min(1,boss.getHealth()/max)));
            for(Player p:world.getPlayers())if(p.getLocation().distanceSquared(boss.getLocation())<=((double)showRadius*showRadius))bar.addPlayer(p);else bar.removePlayer(p);
            if(plugin.getConfig().getBoolean("special-boss.ability-enabled",true)&&boss.getTicksLived()>=nextAbilityTick[0]){useBossAbility(boss,world);nextAbilityTick[0]=boss.getTicksLived()+Math.max(4,plugin.getConfig().getInt("special-boss.ability-cooldown-seconds",10))*20L;}
        },interval,interval);
        activeBosses.put(boss.getUniqueId(),new BossRuntime(bar,task,typeId));
        PlayerBroadcast.send("§5☄ ¡Ha aparecido el jefe §f"+title+" §5junto al meteorito!");
        plugin.getLogger().info("Spawned special boss for meteorite type '"+typeId+"' at "+world.getName()+" "+x+", "+y+", "+z+".");
    }

    private void useBossAbility(Ravager boss,World world){
        int radius=Math.max(3,Math.min(20,plugin.getConfig().getInt("special-boss.ability-radius",8)));
        double damage=Math.max(1,Math.min(30,plugin.getConfig().getDouble("special-boss.ability-damage",6)));
        Location at=boss.getLocation();world.spawnParticle(org.bukkit.Particle.SONIC_BOOM,at.clone().add(0,1,0),1,0,0,0,0);world.playSound(at,org.bukkit.Sound.ENTITY_RAVAGER_ROAR,1.4f,.8f);
        for(Player p:world.getPlayers())if(p.getLocation().distanceSquared(at)<=((double)radius*radius)){p.damage(damage,boss);p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,60,0));}
    }

    private final java.util.Map<String,org.bukkit.scheduler.BukkitTask> waveTasks=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> finalWaveSpawned=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> finalRewardClaimed=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID,BossRuntime> activeBosses=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class BossRuntime { final BossBar bar; final BukkitTask task; final String typeId; BossRuntime(BossBar bar,BukkitTask task,String typeId){this.bar=bar;this.task=task;this.typeId=typeId;} void clear(){task.cancel();bar.removeAll();} }
    private String chestKey(Location l){return l.getWorld().getUID()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ();}
    private String eventTag(MeteoriteChest chest){Location l=chest.getChestLocation();return "meteoritedrop_event_"+l.getWorld().getUID().toString().replace("-","")+"_"+l.getBlockX()+"_"+l.getBlockY()+"_"+l.getBlockZ();}
    private void spawnWave(MeteoriteChest chest,int wave){
        Location center=chest.getChestLocation();World world=center.getWorld();if(world==null)return;
        MeteorType type=chest.getMeteorType();String id=type==null?"lava":type.getId().toLowerCase();
        int configuredCount=plugin.getConfig().getInt("meteor-types."+id+".guardian-count",id.contains("ritual")?10:5);
        int base=Math.max(1,Math.min(28,configuredCount));
        if(base!=configuredCount)plugin.getLogger().warning("Guardian count for '"+id+"' must be between 1 and 28; it was clamped.");
        int count=base+java.util.concurrent.ThreadLocalRandom.current().nextInt(3);
        int perMeteorLimit=Math.max(1,plugin.getConfig().getInt("performance.max-guardians-per-meteor",80));
        int globalLimit=Math.max(1,plugin.getConfig().getInt("performance.max-active-guardians",240));
        int active=0;
        for(MeteoriteChest tracked:plugin.getChestManager().getAllActiveChests()) active+=tracked.getGuardianUuids().size();
        count=Math.min(count,Math.min(perMeteorLimit-chest.getGuardianUuids().size(),globalLimit-active));
        if(count<=0) return;
        int radius=Math.max(4,(type==null?5:type.getCraterRadius())+2);
        for(int i=0;i<count;i++){
            double angle=2*Math.PI*i/count+java.util.concurrent.ThreadLocalRandom.current().nextDouble(-.12,.12);
            int x=center.getBlockX()+(int)Math.round(radius*Math.cos(angle)),z=center.getBlockZ()+(int)Math.round(radius*Math.sin(angle));
            int y=world.getHighestBlockYAt(x,z,org.bukkit.HeightMap.MOTION_BLOCKING);
            Mob mob=spawnThemedMob(id,new Location(world,x+.5,Math.max(y,center.getBlockY())+1,z+.5));
            if(mob==null)continue;
            mob.addScoreboardTag(GUARDIAN_TAG);mob.addScoreboardTag(eventTag(chest));mob.setRemoveWhenFarAway(false);mob.setPersistent(true);mob.setCustomNameVisible(true);mob.setAI(true);
            Player target=world.getPlayers().stream().filter(p->p.getLocation().distanceSquared(center)<Math.pow(Math.max(40,radius*5),2)).min(java.util.Comparator.comparingDouble(p->p.getLocation().distanceSquared(center))).orElse(null);
            if(target!=null)mob.setTarget(target);
            chest.addGuardian(mob.getUniqueId());
        }
    }

    /** Track Vexes summoned by event Evokers and Allays placed among configured guardians. */
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onRelatedMobSpawn(CreatureSpawnEvent event){
        Entity entity=event.getEntity();EntityType kind=entity.getType();
        if(kind!=EntityType.VEX&&kind!=EntityType.ALLAY)return;
        if(kind==EntityType.VEX&&event.getSpawnReason()!=CreatureSpawnEvent.SpawnReason.SPELL)return;
        MeteoriteChest chest=plugin.getChestManager().getAllActiveChests().stream()
                .filter(c->c.getChestLocation()!=null&&c.getChestLocation().getWorld().equals(entity.getWorld())
                        &&c.getChestLocation().distanceSquared(entity.getLocation())<=48*48)
                .min(java.util.Comparator.comparingDouble(c->c.getChestLocation().distanceSquared(entity.getLocation()))).orElse(null);
        if(chest==null)return;
        entity.addScoreboardTag(GUARDIAN_TAG);entity.addScoreboardTag(eventTag(chest));entity.setPersistent(true);
        if(entity instanceof Mob mob)mob.setRemoveWhenFarAway(false);
        chest.addGuardian(entity.getUniqueId());
    }
    private Mob spawnThemedMob(String typeId, Location loc) {
        World world = loc.getWorld();
        if (world == null) return null;

        var configured=plugin.getConfig().getStringList("meteor-types."+typeId+".guardian-mobs");
        if(!configured.isEmpty()){
            String mobName=configured.get(ThreadLocalRandom.current().nextInt(configured.size())).toUpperCase(java.util.Locale.ROOT);
            try{
                EntityType entityType=EntityType.valueOf(mobName);
                Entity entity=world.spawnEntity(loc,entityType);
                if(entity instanceof Mob mob){mob.setCustomName("§d✦ Guardián Legendario");return mob;}
                entity.remove();
            }catch(IllegalArgumentException ignored){}
        }

        int pick = ThreadLocalRandom.current().nextInt(typeId.contains("ritual") ? 6 : 3);

        if(typeId.contains("ritual")){
            return switch(pick){
                case 0 -> world.spawn(loc, Blaze.class,m->m.customName(LegacyComponentSerializer.legacySection().deserialize("§c🔥 Infernal del Ritual")));
                case 1 -> world.spawn(loc, PiglinBrute.class,m->{m.customName(LegacyComponentSerializer.legacySection().deserialize("§6⚔ Bruto del Nether"));m.setImmuneToZombification(true);});
                case 2 -> world.spawn(loc, Enderman.class,m->m.customName(LegacyComponentSerializer.legacySection().deserialize("§5✦ Acechador del End")));
                case 3 -> world.spawn(loc, CaveSpider.class,m->m.customName(LegacyComponentSerializer.legacySection().deserialize("§2☣ Araña Venenosa")));
                case 4 -> world.spawn(loc, WitherSkeleton.class,m->m.customName(LegacyComponentSerializer.legacySection().deserialize("§8☠ Esqueleto del Vacío")));
                default -> world.spawn(loc, MagmaCube.class,m->{m.customName(LegacyComponentSerializer.legacySection().deserialize("§c☄ Cubo de Magma"));m.setSize(3);});
            };
        }
        if(typeId.contains("legend")){
            return switch(pick){
                case 0 -> world.spawn(loc, Evoker.class,m->m.customName(LegacyComponentSerializer.legacySection().deserialize("§d✦ Invocador de Estrellas")));
                case 1 -> world.spawn(loc, Ravager.class,m->m.customName(LegacyComponentSerializer.legacySection().deserialize("§5☄ Bestia del Cataclismo")));
                default -> world.spawn(loc, Vindicator.class,m->{m.customName(LegacyComponentSerializer.legacySection().deserialize("§d⚔ Caballero Estelar"));if(m.getEquipment()!=null){m.getEquipment().setItemInMainHand(new ItemStack(Material.NETHERITE_AXE));m.getEquipment().setItemInMainHandDropChance(0);}});
            };
        }
        if (typeId.contains("ice")) {
            if (pick == 0) {
                return world.spawn(loc, Stray.class, stray -> {
                    stray.customName(LegacyComponentSerializer.legacySection().deserialize("§b❄ Guardián del Frío"));
                    if (stray.getEquipment() != null) {
                        stray.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                        stray.getEquipment().setItemInMainHandDropChance(0.0f);
                        // Casco para evitar que se quemen con el sol
                        stray.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        stray.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            } else {
                return world.spawn(loc, Drowned.class, drowned -> {
                    drowned.customName(LegacyComponentSerializer.legacySection().deserialize("§b❄ Espectro Glacial"));
                    if (drowned.getEquipment() != null) {
                        drowned.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                        drowned.getEquipment().setItemInMainHandDropChance(0.0f);
                        // Casco para evitar que se quemen con el sol
                        drowned.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        drowned.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            }
        } else if (typeId.contains("end") || typeId.contains("void")) {
            if (pick == 0) {
                return world.spawn(loc, Shulker.class, shulker -> {
                    shulker.customName(LegacyComponentSerializer.legacySection().deserialize("§d🔮 Centinela del End"));
                });
            } else if (pick == 1) {
                return world.spawn(loc, Endermite.class, mite -> {
                    mite.customName(LegacyComponentSerializer.legacySection().deserialize("§d🔮 Parásito del Vacío"));
                });
            } else {
                return world.spawn(loc, Enderman.class, enderman -> {
                    enderman.customName(LegacyComponentSerializer.legacySection().deserialize("§d🔮 Guardián del Vacío"));
                    // Casco para evitar que se quemen con el sol
                    if (enderman.getEquipment() != null) {
                        enderman.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
                        enderman.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            }
        } else if (typeId.contains("divine") || typeId.contains("celestial")) {
            if (pick == 0) {
                return world.spawn(loc, IronGolem.class, golem -> {
                    golem.customName(LegacyComponentSerializer.legacySection().deserialize("§e⚡ Guardián Celestial"));
                });
            } else {
                return world.spawn(loc, PiglinBrute.class, brute -> {
                    brute.customName(LegacyComponentSerializer.legacySection().deserialize("§e⚡ Guardián Celestial"));
                    brute.setImmuneToZombification(true);
                    if (brute.getEquipment() != null) {
                        brute.getEquipment().setItemInMainHand(new ItemStack(Material.GOLDEN_AXE));
                        brute.getEquipment().setItemInMainHandDropChance(0.0f);
                    }
                });
            }
        } else if (typeId.contains("toxic") || typeId.contains("corrupto")) {
            if (pick == 0) {
                return world.spawn(loc, CaveSpider.class, spider -> {
                    spider.customName(LegacyComponentSerializer.legacySection().deserialize("§2☣ Abominación Tóxica"));
                });
            } else {
                return world.spawn(loc, PigZombie.class, piglin -> {
                    piglin.customName(LegacyComponentSerializer.legacySection().deserialize("§2☣ Abominación Tóxica"));
                    if (piglin.getEquipment() != null) {
                        piglin.getEquipment().setItemInMainHand(new ItemStack(Material.GOLDEN_SWORD));
                        piglin.getEquipment().setItemInMainHandDropChance(0.0f);
                    }
                });
            }
        } else if (typeId.contains("storm") || typeId.contains("tormenta") || typeId.contains("electric")) {
            if (pick == 0) {
                return world.spawn(loc, Creeper.class, creeper -> {
                    creeper.customName(LegacyComponentSerializer.legacySection().deserialize("§9⚡ Chispa de Tormenta"));
                    creeper.setPowered(true);
                });
            } else {
                return world.spawn(loc, Skeleton.class, skeleton -> {
                    skeleton.customName(LegacyComponentSerializer.legacySection().deserialize("§9⚡ Centinela de la Tormenta"));
                    if (skeleton.getEquipment() != null) {
                        skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                        skeleton.getEquipment().setItemInMainHandDropChance(0.0f);
                        // Casco para evitar que se quemen con el sol
                        skeleton.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        skeleton.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            }
        } else if (typeId.contains("cursed") || typeId.contains("maldito")) {
            if (pick == 0) {
                return world.spawn(loc, WitherSkeleton.class, ws -> {
                    ws.customName(LegacyComponentSerializer.legacySection().deserialize("§8☠ Guardián Maldito"));
                    if (ws.getEquipment() != null) {
                        ws.getEquipment().setItemInMainHand(new ItemStack(Material.STONE_SWORD));
                        ws.getEquipment().setItemInMainHandDropChance(0.0f);
                    }
                });
            } else if (pick == 1) {
                return world.spawn(loc, Skeleton.class, sk -> {
                    sk.customName(LegacyComponentSerializer.legacySection().deserialize("§8☠ Arquero Maldito"));
                    if (sk.getEquipment() != null) {
                        sk.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
                        sk.getEquipment().setItemInMainHandDropChance(0.0f);
                        // Casco para evitar que se quemen con el sol
                        sk.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        sk.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            } else {
                return world.spawn(loc, Phantom.class, ph -> {
                    ph.customName(LegacyComponentSerializer.legacySection().deserialize("§8☠ Espectro Maldito"));
                });
            }
        } else if (typeId.contains("abyssal") || typeId.contains("abisal")) {
            if (pick == 0) {
                return world.spawn(loc, Guardian.class, g -> {
                    g.customName(LegacyComponentSerializer.legacySection().deserialize("§3🌊 Guardián Abisal"));
                });
            } else if (pick == 1) {
                return world.spawn(loc, Drowned.class, dr -> {
                    dr.customName(LegacyComponentSerializer.legacySection().deserialize("§3🌊 Ahogado Abisal"));
                    if (dr.getEquipment() != null) {
                        dr.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                        dr.getEquipment().setItemInMainHandDropChance(0.0f);
                        // Casco para evitar que se quemen con el sol
                        dr.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        dr.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            } else {
                return world.spawn(loc, Squid.class, sq -> {
                    sq.customName(LegacyComponentSerializer.legacySection().deserialize("§3🌊 Calamar Abisal"));
                });
            }
        } else if (typeId.contains("corrupted") || typeId.contains("corrupto_meteor")) {
            if (pick == 0) {
                return world.spawn(loc, Zombie.class, z -> {
                    z.customName(LegacyComponentSerializer.legacySection().deserialize("§2🍄 Zombie Corrupto"));
                    if (z.getEquipment() != null) {
                        // Casco para evitar que se quemen con el sol
                        z.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
                        z.getEquipment().setHelmetDropChance(0.0f);
                    }
                });
            } else if (pick == 1) {
                return world.spawn(loc, Silverfish.class, sf -> {
                    sf.customName(LegacyComponentSerializer.legacySection().deserialize("§2🍄 Parásito Corrupto"));
                });
            } else {
                return world.spawn(loc, Slime.class, sl -> {
                    sl.customName(LegacyComponentSerializer.legacySection().deserialize("§2🍄 Limo Corrupto"));
                    sl.setSize(2);
                });
            }
        } else {
            // Default: Lava
            if (pick == 0) {
                return world.spawn(loc, MagmaCube.class, cube -> {
                    cube.customName(LegacyComponentSerializer.legacySection().deserialize("§c☄ Bestia de Magma"));
                    cube.setSize(3);
                });
            } else if (pick == 1) {
                return world.spawn(loc, Blaze.class, blaze -> {
                    blaze.customName(LegacyComponentSerializer.legacySection().deserialize("§c☄ Espíritu de Fuego"));
                });
            } else {
                return world.spawn(loc, WitherSkeleton.class, skeleton -> {
                    skeleton.customName(LegacyComponentSerializer.legacySection().deserialize("§c⚔ Guardián Ardiente"));
                    if (skeleton.getEquipment() != null) {
                        ItemStack sword = new ItemStack(Material.IRON_SWORD);
                        sword.addEnchantment(Enchantment.FIRE_ASPECT, 1);
                        skeleton.getEquipment().setItemInMainHand(sword);
                        skeleton.getEquipment().setItemInMainHandDropChance(0.0f);
                    }
                });
            }
        }
    }

    /**
     * Prevents Enderman guardians from teleporting away from the crater.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        Entity entity = event.getEntity();
        if (entity.getScoreboardTags().contains(GUARDIAN_TAG)) {
            event.setCancelled(true);
        }
    }

    /**
     * Removes dead guardian references from active chests.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuardianDeath(EntityDeathEvent event) {
        Entity entity=event.getEntity();if(!entity.getScoreboardTags().contains(GUARDIAN_TAG))return;
        BossRuntime boss=activeBosses.remove(entity.getUniqueId());
        if(boss!=null){boss.clear();java.util.List<String> loot=plugin.getConfig().getStringList("meteor-types."+boss.typeId+".boss.loot");if(loot.isEmpty())loot=plugin.getConfig().getStringList("special-boss.loot");for(String entry:loot)try{String[] parts=entry.split(":");Material m=Material.matchMaterial(parts[0].trim());if(m!=null){int amount=parts.length>1?Integer.parseInt(parts[1]):1;event.getDrops().add(new ItemStack(m,Math.max(1,Math.min(m.getMaxStackSize(),amount))));}}catch(Exception ex){plugin.getLogger().warning("Invalid special boss loot entry: "+entry);}PlayerBroadcast.send("§5☄ ¡Derrotaron al jefe especial del meteorito!");}
        for(MeteoriteChest chest:plugin.getChestManager().getAllActiveChests()){
            if(chest.getGuardianUuids().remove(entity.getUniqueId())){
                String key=chestKey(chest.getChestLocation());
                if(chest.getGuardianUuids().isEmpty()&&finalWaveSpawned.contains(key)&&finalRewardClaimed.add(key))dropFinalReward(chest,event.getEntity().getKiller());
                break;
            }
        }
    }
    /**
     * Removes all guardians linked to a specific chest upon crater restoration.
     */
    private void dropFinalReward(MeteoriteChest chest,Player killer){
        MeteorType type=chest.getMeteorType();if(type==null||chest.getChestLocation()==null)return;
        java.util.List<String> rewards=plugin.getConfig().getStringList("meteor-types."+type.getId()+".guardian-final-reward");if(rewards.isEmpty())return;
        for(String entry:rewards)try{String[] parts=entry.split(":");Material material=Material.matchMaterial(parts[0].trim());if(material==null)continue;int amount=parts.length>1?Integer.parseInt(parts[1]):1;chest.getChestLocation().getWorld().dropItemNaturally(chest.getChestLocation(),new ItemStack(material,Math.max(1,Math.min(material.getMaxStackSize(),amount))));}catch(Exception ignored){}
        PlayerBroadcast.send("§6✦ "+(killer==null?"Un jugador":killer.getName())+" derrotó la última oleada y desbloqueó la recompensa final del meteorito!");
    }

    public void removeGuardiansForChest(MeteoriteChest chest) {
        if (chest == null || chest.getChestLocation() == null) return;
        Location center=chest.getChestLocation();World world=center.getWorld();String tag=eventTag(chest);
        for(UUID uuid:new java.util.HashSet<>(chest.getGuardianUuids())){BossRuntime runtime=activeBosses.remove(uuid);if(runtime!=null)runtime.clear();if(world!=null){Entity entity=world.getEntity(uuid);if(entity!=null&&entity.isValid())entity.remove();}}
        // Catch event-linked entities whose UUID was lost (for example Vexes summoned by Evokers).
        if(world!=null)for(Entity entity:world.getNearbyEntities(center,128,96,128))if(entity.getScoreboardTags().contains(tag))entity.remove();
        chest.getGuardianUuids().clear();
        String key=chestKey(chest.getChestLocation());finalWaveSpawned.remove(key);finalRewardClaimed.remove(key);org.bukkit.scheduler.BukkitTask task=waveTasks.remove(key);if(task!=null)task.cancel();
    }

    /**
     * Removes all guardians on all active chests and worlds upon shutdown.
     */
    public void cleanup() {
        for(BossRuntime runtime:activeBosses.values())runtime.clear();activeBosses.clear();
        for (MeteoriteChest chest : plugin.getChestManager().getAllActiveChests()) {
            removeGuardiansForChest(chest);
        }

        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().contains(GUARDIAN_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event){
        for(Entity entity:event.getChunk().getEntities()){
            if(!entity.getScoreboardTags().contains(GUARDIAN_TAG))continue;
            String entityEventTag=entity.getScoreboardTags().stream().filter(tag->tag.startsWith("meteoritedrop_event_")).findFirst().orElse(null);
            if(entityEventTag==null)continue;
            boolean active=plugin.getChestManager().getAllActiveChests().stream().anyMatch(chest->chest.getChestLocation()!=null&&eventTag(chest).equals(entityEventTag));
            if(!active)entity.remove();
        }
    }
}
