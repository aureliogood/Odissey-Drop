package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteoriteChest;
import com.meteoritedrop.utils.PlayerBroadcast;
import com.meteoritedrop.utils.AtomicYamlStorage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks cooperative meteor objectives, temporary combat roles and per-event contributions. */
public final class MeteorEventManager implements Listener {
    private record Contribution(double damage, int kills, double healing) {
        Contribution addDamage(double value) { return new Contribution(damage + value, kills, healing); }
        Contribution addKill() { return new Contribution(damage, kills + 1, healing); }
        Contribution addHealing(double value) { return new Contribution(damage, kills, healing + value); }
    }
    private record Role(String name, long expiresAt, BukkitTask expiryTask) {}

    private final MeteoriteDrop plugin;
    private final File statsFile;
    private final YamlConfiguration stats;
    private final Map<String, Map<UUID, Contribution>> contributions = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> objectiveUsers = new ConcurrentHashMap<>();
    private final Map<String, List<UUID>> objectiveEntities = new ConcurrentHashMap<>();
    private final Map<UUID, String> entityObjectives = new ConcurrentHashMap<>();
    private final Map<UUID, Role> roles = new ConcurrentHashMap<>();

    public MeteorEventManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.statsFile = new File(plugin.getDataFolder(), "event-stats.yml");
        this.stats = YamlConfiguration.loadConfiguration(statsFile);
        Bukkit.getScheduler().runTaskTimer(plugin, this::applyHealerAura, 20L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveStats, 600L, 600L);
    }

    private String key(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    public void onMeteorLanded(MeteoriteChest chest) {
        if (chest == null || chest.getChestLocation() == null) return;
        String id = chest.getMeteorType() == null ? "" : chest.getMeteorType().getId().toLowerCase(Locale.ROOT);
        if (!plugin.getConfig().getBoolean("cooperative.enabled", true)
                || !plugin.getConfig().getStringList("cooperative.meteor-types").stream().anyMatch(id::equalsIgnoreCase)) return;
        String eventKey = key(chest.getChestLocation());
        int count = Math.max(2, plugin.getConfig().getInt("cooperative.objectives", 3));
        int radius = Math.max(5, chest.getMeteorType().getCraterRadius() + 4);
        World world = chest.getChestLocation().getWorld();
        List<UUID> spawned = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            int x = chest.getChestLocation().getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = chest.getChestLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            int y = world.getHighestBlockYAt(x, z) + 1;
            ArmorStand marker = world.spawn(new Location(world, x + .5, y, z + .5), ArmorStand.class, stand -> {
                stand.setInvisible(true); stand.setMarker(false); stand.setGravity(false); stand.setInvulnerable(true);
                stand.setCustomNameVisible(true); stand.setCustomName("§d✦ §fPunto de resonancia §7(clic)" );
                stand.getEquipment().setHelmet(new ItemStack(Material.AMETHYST_CLUSTER));
                stand.addScoreboardTag("meteoritedrop_coop");
            });
            spawned.add(marker.getUniqueId()); entityObjectives.put(marker.getUniqueId(), eventKey);
        }
        objectiveUsers.put(eventKey, ConcurrentHashMap.newKeySet()); objectiveEntities.put(eventKey, spawned);
        String message = plugin.getConfig().getString("cooperative.start-message", "&d✦ El meteorito legendario está protegido: jugadores distintos deben activar sus puntos de resonancia.")
                .replace("{objectives}", String.valueOf(count)).replace("{required}",String.valueOf(Math.max(2,plugin.getConfig().getInt("cooperative.required-players",3))));
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    public boolean canUnlock(MeteoriteChest chest, Player player) {
        if (chest == null || chest.getChestLocation() == null) return true;
        String eventKey = key(chest.getChestLocation());
        if (!objectiveUsers.containsKey(eventKey)) return true;
        int required = Math.max(2, plugin.getConfig().getInt("cooperative.required-players", 3));
        int current = objectiveUsers.get(eventKey).size();
        if (current >= required) return true;
        player.sendMessage(plugin.getConfig().getString("cooperative.locked-message", "&cEl grupo debe activar {remaining} puntos de resonancia más.")
                .replace("{remaining}", String.valueOf(required - current)));
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onObjectiveUse(PlayerInteractAtEntityEvent event) {
        UUID entityId = event.getRightClicked().getUniqueId();
        String eventKey = entityObjectives.get(entityId);
        if (eventKey == null) return;
        event.setCancelled(true);
        Set<UUID> users = objectiveUsers.get(eventKey);
        if (users == null) return;
        Player player = event.getPlayer();
        if (!users.add(player.getUniqueId())) { player.sendMessage("§cYa activaste un punto de resonancia para este meteorito."); return; }
        player.getWorld().spawnParticle(Particle.END_ROD, event.getRightClicked().getLocation().add(0, 1, 0), 35, .5, .7, .5, .04);
        player.getWorld().playSound(event.getRightClicked().getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME, .8f, 1.4f);
        recordObjective(player, "cooperation");
        int required = Math.max(2, plugin.getConfig().getInt("cooperative.required-players", 3));
        if (users.size() >= required) {
            clearObjectives(eventKey);
            PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(plugin.getConfig().getString("cooperative.complete-message", "&d✦ ¡La resonancia se completó! El cofre ya puede disputarse.")));
            if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("cooperative-complete", "La resonancia del meteorito legendario fue completada por " + player.getName() + ".");
        } else player.sendMessage("§aPunto activado §7(" + users.size() + "/" + required + "). Necesitamos jugadores distintos.");
    }

    private void clearObjectives(String eventKey) {
        List<UUID> entities = objectiveEntities.remove(eventKey);
        if (entities != null) for (UUID uuid : entities) {
            entityObjectives.remove(uuid);
            Entity entity = Bukkit.getEntity(uuid); if (entity != null) entity.remove();
        }
        objectiveUsers.remove(eventKey);
    }

    public boolean chooseRole(Player player, String requested) {
        String role = requested.toLowerCase(Locale.ROOT);
        if (!Set.of("tank", "sanador", "explorador").contains(role)) return false;
        if (roles.containsKey(player.getUniqueId())) { player.sendMessage("§cYa tienes un rol activo."); return true; }
        boolean nearby = plugin.getChestManager().getAllActiveChests().stream().anyMatch(c -> c.getChestLocation() != null
                && c.getChestLocation().getWorld().equals(player.getWorld())
                && c.getChestLocation().distanceSquared(player.getLocation()) <= 10000);
        if (!nearby) { player.sendMessage("§cDebes estar cerca de un meteorito activo para elegir un rol."); return true; }
        int seconds = Math.max(60, plugin.getConfig().getInt("combat-roles.duration-seconds", 900));
        switch (role) {
            case "tank" -> { player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, seconds * 20, 0)); player.addPotionEffect(new PotionEffect(PotionEffectType.HEALTH_BOOST, seconds * 20, 1)); }
            case "sanador" -> player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, seconds * 20, 0));
            case "explorador" -> { player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, seconds * 20, 1)); player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, seconds * 20, 0)); }
        }
        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> clearRole(player.getUniqueId()), seconds * 20L);
        roles.put(player.getUniqueId(), new Role(role, System.currentTimeMillis() + seconds * 1000L, expiry));
        player.sendMessage("§aRol §e" + role + " §aactivado por " + seconds + " segundos.");
        if (plugin.getMissionsManager() != null) plugin.getMissionsManager().recordObjective(player, "role");
        return true;
    }

    private void clearRole(UUID uuid) {
        Role role = roles.remove(uuid); Player player = Bukkit.getPlayer(uuid);
        if (role == null || player == null) return;
        if (role.name().equals("tank")) { player.removePotionEffect(PotionEffectType.RESISTANCE); player.removePotionEffect(PotionEffectType.HEALTH_BOOST); }
        if (role.name().equals("sanador")) player.removePotionEffect(PotionEffectType.REGENERATION);
        if (role.name().equals("explorador")) { player.removePotionEffect(PotionEffectType.SPEED); player.removePotionEffect(PotionEffectType.NIGHT_VISION); }
        player.sendMessage("§7Tu rol de combate terminó.");
    }

    private void applyHealerAura() {
        for (Map.Entry<UUID, Role> entry : roles.entrySet()) {
            if (!entry.getValue().name().equals("sanador")) continue;
            Player healer = Bukkit.getPlayer(entry.getKey()); if (healer == null || !healer.isOnline()) continue;
            for (Player target : healer.getWorld().getPlayers()) {
                if (target.equals(healer) || target.getLocation().distanceSquared(healer.getLocation()) > 64 || target.getHealth() >= target.getMaxHealth()) continue;
                boolean fighting = plugin.getChestManager().getAllActiveChests().stream().anyMatch(c -> c.getChestLocation() != null
                        && c.getChestLocation().getWorld().equals(target.getWorld()) && c.getChestLocation().distanceSquared(target.getLocation()) < 2500);
                if (!fighting) continue;
                double healed = Math.min(2.0, target.getMaxHealth() - target.getHealth()); target.setHealth(target.getHealth() + healed);
                recordHealing(healer, target.getLocation(), healed);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGuardianDamage(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(GuardianManager.GUARDIAN_TAG)) return;
        Player attacker = event.getDamager() instanceof Player p ? p
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p ? p : null;
        if (attacker == null) return;
        MeteoriteChest chest = chestFor(event.getEntity().getLocation()); if (chest == null) return;
        contributions.computeIfAbsent(key(chest.getChestLocation()), ignored -> new ConcurrentHashMap<>())
                .merge(attacker.getUniqueId(), new Contribution(0, 0, 0).addDamage(event.getFinalDamage()), (a, b) -> a.addDamage(b.damage()));
        recordGlobal(attacker, "damage", event.getFinalDamage());
        recordObjective(attacker, "damage");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuardianDeath(EntityDeathEvent event) {
        if (!event.getEntity().getScoreboardTags().contains(GuardianManager.GUARDIAN_TAG)) return;
        Player killer = event.getEntity().getKiller(); if (killer == null) return;
        MeteoriteChest chest = chestFor(event.getEntity().getLocation()); if (chest == null) return;
        contributions.computeIfAbsent(key(chest.getChestLocation()), ignored -> new ConcurrentHashMap<>())
                .merge(killer.getUniqueId(), new Contribution(0, 0, 0).addKill(), (a, b) -> a.addKill());
        recordGlobal(killer, "kills", 1); recordObjective(killer, "guardian-kills");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDefeat(PlayerDeathEvent event) {
        Player player = event.getEntity();
        MeteoriteChest chest = chestFor(player.getLocation()); if (chest == null) return;
        recordGlobal(player, "defeats", 1);
        PlayerBroadcast.send("§c☠ " + player.getName() + " cayó luchando por un meteorito.");
        if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("combat-result", player.getName() + " fue derrotado en " + chest.getMeteorType().getId() + ".");
    }

    private MeteoriteChest chestFor(Location location) {
        return plugin.getChestManager().getAllActiveChests().stream().filter(c -> c.getChestLocation() != null
                && c.getChestLocation().getWorld().equals(location.getWorld()) && c.getChestLocation().distanceSquared(location) < 3600).min(Comparator.comparingDouble(c -> c.getChestLocation().distanceSquared(location))).orElse(null);
    }

    private void recordHealing(Player healer, Location location, double amount) {
        MeteoriteChest chest = chestFor(location); if (chest == null) return;
        contributions.computeIfAbsent(key(chest.getChestLocation()), ignored -> new ConcurrentHashMap<>())
                .merge(healer.getUniqueId(), new Contribution(0, 0, amount), (a, b) -> a.addHealing(b.healing()));
        recordGlobal(healer, "healing", amount);
        recordObjective(healer, "healing");
    }

    public void rewardContributors(MeteoriteChest chest, Player winner) {
        if (chest == null || chest.getChestLocation() == null) return;
        String eventKey = key(chest.getChestLocation());
        Map<UUID, Contribution> scores = contributions.remove(eventKey);
        clearObjectives(eventKey);
        if (scores == null || scores.isEmpty()) { recordGlobal(winner, "victories", 1); return; }
        double damageWeight = plugin.getConfig().getDouble("participation.damage-points-per-health", .1);
        double killWeight = plugin.getConfig().getDouble("participation.points-per-guardian-kill", 10);
        double healWeight = plugin.getConfig().getDouble("participation.healing-points-per-health", .2);
        double payoutRate = plugin.getConfig().getDouble("participation.money-per-point", 2.0);
        int xpRate = plugin.getConfig().getInt("participation.xp-per-point", 1);
        for (Map.Entry<UUID, Contribution> entry : scores.entrySet()) {
            UUID uuid = entry.getKey(); Contribution c = entry.getValue();
            double points = c.damage() * damageWeight + c.kills() * killWeight + c.healing() * healWeight;
            if (points < plugin.getConfig().getDouble("participation.minimum-points", 1)) continue;
            Player participant = Bukkit.getPlayer(uuid);
            if (participant != null) {
                double money = Math.floor(points * payoutRate * 100) / 100;
                int xp = Math.max(0, (int) Math.floor(points * xpRate));
                if (money > 0 && plugin.getEconomyManager() != null && plugin.getEconomyManager().isAvailable()) plugin.getEconomyManager().deposit(participant, money);
                if (xp > 0) participant.giveExp(xp);
                participant.sendMessage("§6Participación: §f" + String.format(Locale.US, "%.1f", points) + " puntos §7| §a$" + String.format(Locale.US, "%.2f", money) + " §7| §b" + xp + " XP");
            }
        }
        if (winner != null) recordGlobal(winner, "victories", 1);
        if (plugin.getDiscordChatBridge() != null && winner != null) plugin.getDiscordChatBridge().sendEvent("combat-result", "Victoria de " + winner.getName() + " en el meteorito " + chest.getMeteorType().getId() + ".");
    }

    public void onChestRestored(MeteoriteChest chest) {
        if(chest==null||chest.getChestLocation()==null)return;
        String eventKey=key(chest.getChestLocation());
        clearObjectives(eventKey);
        contributions.remove(eventKey);
    }

    private void recordGlobal(Player player, String path, double amount) {
        String id = player.getUniqueId().toString();
        stats.set("players." + id + ".name", player.getName());
        stats.set("players." + id + "." + path, stats.getDouble("players." + id + "." + path) + amount);
    }

    public void recordObjective(Player player, String objective) {
        if (plugin.getMissionsManager() != null) plugin.getMissionsManager().recordObjective(player, objective);
    }

    public String getPersonalSummary(UUID uuid) {
        String path = "players." + uuid;
        return "§6Combate §8| §cDaño: §f" + String.format(Locale.US, "%.1f", stats.getDouble(path + ".damage"))
                + " §8| §eBajas: §f" + stats.getInt(path + ".kills") + " §8| §aCuración: §f" + String.format(Locale.US, "%.1f", stats.getDouble(path + ".healing"))
                + " §8| §bVictorias: §f" + stats.getInt(path + ".victories") + " §8| §4Derrotas: §f" + stats.getInt(path + ".defeats");
    }

    public List<String> getTopParticipants(int limit) {
        ConfigurationSection players=stats.getConfigurationSection("players");
        if(players==null)return List.of();
        return players.getKeys(false).stream().map(id->{
            String path="players."+id;
            double score=stats.getDouble(path+".damage")+stats.getDouble(path+".healing")+stats.getInt(path+".kills")*20.0;
            return new AbstractMap.SimpleEntry<>(score,stats.getString(path+".name","Jugador")+" §7— daño "+String.format(Locale.US,"%.0f",stats.getDouble(path+".damage"))+", bajas "+stats.getInt(path+".kills")+", curación "+String.format(Locale.US,"%.0f",stats.getDouble(path+".healing")));
        }).sorted(Map.Entry.<Double,String>comparingByKey().reversed()).limit(Math.max(1,limit)).map(e->e.getValue()).toList();
    }

    public boolean sendLeaderboardToDiscord() {
        if(plugin.getDiscordChatBridge()==null||!plugin.getDiscordChatBridge().isConnected()||!plugin.getDiscordChatBridge().isEventEnabled("combat-leaderboard"))return false;
        List<String> top=getTopParticipants(10);
        String text=top.isEmpty()?"Aún no hay participantes registrados.":String.join("\n",top);
        plugin.getDiscordChatBridge().sendEvent("combat-leaderboard","🏆 Mejores participantes de MeteoriteDrop:\n"+text);
        return true;
    }

    public void cleanup() {
        for (String eventKey : new HashSet<>(objectiveEntities.keySet())) clearObjectives(eventKey);
        for (UUID uuid : new HashSet<>(roles.keySet())) clearRole(uuid);
        saveStats();
    }

    public void saveStats() { try { AtomicYamlStorage.save(stats, statsFile); } catch (IOException e) { plugin.getLogger().warning("No se pudo guardar event-stats.yml: " + e.getMessage()); } }
}
