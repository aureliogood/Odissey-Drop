package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.utils.AtomicYamlStorage;
import com.meteoritedrop.utils.PlayerBroadcast;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/** Admin-controlled ritual event, custom ritual items, and persistent custom-anchor tracking. */
public final class RitualManager {
    private final MeteoriteDrop plugin;
    private final File stateFile;
    private final File itemConfigFile;
    private YamlConfiguration state;
    private YamlConfiguration itemConfig;
    private final NamespacedKey anchorItemKey;
    private final NamespacedKey fireChargeItemKey;
    private boolean active;
    private final Set<UUID> usedPlayers = new HashSet<>();
    private final Set<String> activeLocations = new HashSet<>();
    private final Set<String> registeredAnchors = new HashSet<>();
    private final Set<UUID> pendingPlayers = new HashSet<>();
    private final Map<UUID, BukkitTask> pendingRituals = new HashMap<>();
    private final Map<UUID, String> pendingLocations = new HashMap<>();
    private final Map<UUID, String> pendingAnchorBlocks = new HashMap<>();
    private final Map<UUID, ItemStack> pendingCharges = new HashMap<>();
    private final Map<UUID, ItemStack> pendingRefunds = new HashMap<>();
    private final Map<UUID, ItemStack> pendingAnchorRefunds = new HashMap<>();
    private final Map<UUID, String> inFlightLocations = new HashMap<>();
    private final Map<UUID, ItemStack> inFlightCharges = new HashMap<>();

    public RitualManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "ritual-state.yml");
        this.itemConfigFile = new File(plugin.getDataFolder(), "ritual.yml");
        this.anchorItemKey = new NamespacedKey(plugin, "ritual_anchor_item");
        this.fireChargeItemKey = new NamespacedKey(plugin, "ritual_fire_charge_item");
        loadItemConfig();
        this.state = YamlConfiguration.loadConfiguration(stateFile);
        this.active = state.getBoolean("active", false);
        for (String id : state.getStringList("used-players")) addUuid(usedPlayers, id);
        for (String id : state.getStringList("pending-players")) addUuid(pendingPlayers, id);
        registeredAnchors.addAll(state.getStringList("registered-anchors"));
        loadPendingMaps();

        // A countdown cannot continue after restart. Reset its charged anchor and refund its
        // serialized custom fire charge when the player next joins.
        for (Map.Entry<UUID, String> entry : pendingAnchorBlocks.entrySet()) {
            Location anchor = decodeAnchorLocation(entry.getValue());
            if (anchor != null) setAnchorCharges(anchor, 0);
        }
        for (Map.Entry<UUID, ItemStack> entry : pendingCharges.entrySet()) {
            pendingRefunds.putIfAbsent(entry.getKey(), entry.getValue().clone());
        }
        // Falling meteors are transient and are removed on startup. Refund their ritual item
        // and clear their per-event reservation instead of leaving players locked out.
        for (Map.Entry<UUID, ItemStack> entry : inFlightCharges.entrySet()) {
            pendingRefunds.putIfAbsent(entry.getKey(), entry.getValue().clone());
        }
        for (UUID playerId : inFlightLocations.keySet()) {
            usedPlayers.remove(playerId);
            pendingAnchorRefunds.putIfAbsent(playerId, createRitualAnchorItem());
        }
        usedPlayers.removeAll(pendingPlayers);
        pendingPlayers.clear();
        pendingLocations.clear();
        pendingAnchorBlocks.clear();
        pendingCharges.clear();
        inFlightLocations.clear();
        inFlightCharges.clear();
        activeLocations.clear(); // Live summoned chests are not restored after a process restart.
        save();
    }

    private void loadItemConfig() {
        if (!itemConfigFile.exists()) {
            plugin.getDataFolder().mkdirs();
            try {
                plugin.saveResource("ritual.yml", false);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().log(Level.SEVERE, "Missing default ritual.yml resource.", ex);
            }
        }
        itemConfig = YamlConfiguration.loadConfiguration(itemConfigFile);
    }

    public void reloadItemConfig() {
        loadItemConfig();
    }

    public boolean isActive() { return active; }
    public int getRequiredCaptures() { return Math.max(3, plugin.getConfig().getInt("ritual.required-captures", 3)); }

    public ItemStack createRitualAnchorItem() {
        return createTaggedItem(Material.RESPAWN_ANCHOR, "items.anchor", anchorItemKey);
    }

    public ItemStack createRitualFireChargeItem() {
        return createTaggedItem(Material.FIRE_CHARGE, "items.fire-charge", fireChargeItemKey);
    }

    private ItemStack createTaggedItem(Material material, String path, NamespacedKey key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    itemConfig.getString(path + ".name", "&dObjeto de Ritual")));
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String line : itemConfig.getStringList(path + ".lore")) {
                lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public boolean isRitualAnchorItem(ItemStack item) { return hasTag(item, anchorItemKey); }
    public boolean isRitualFireChargeItem(ItemStack item) { return hasTag(item, fireChargeItemKey); }

    private boolean hasTag(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public void giveRitualAnchor(Player player) { giveItem(player, createRitualAnchorItem(), "§aRecibiste un ancla ritual especial."); }
    public void giveRitualFireCharge(Player player) { giveItem(player, createRitualFireChargeItem(), "§aRecibiste una carga ígnea ritual especial."); }

    private void giveItem(Player player, ItemStack item, String message) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        player.sendMessage(message);
    }

    public synchronized void registerPlacedAnchor(Location location) {
        if (location == null || location.getWorld() == null || location.getBlock().getType() != Material.RESPAWN_ANCHOR) return;
        if (registeredAnchors.add(encodeAnchorLocation(location))) save();
    }

    public synchronized boolean isRegisteredAnchor(Location location) {
        if (location == null || location.getWorld() == null) return false;
        String key = encodeAnchorLocation(location);
        if (!registeredAnchors.contains(key)) return false;
        if (location.getBlock().getType() == Material.RESPAWN_ANCHOR) return true;
        registeredAnchors.remove(key);
        save();
        return false;
    }

    public synchronized void unregisterAnchor(Location location) {
        if (location == null || location.getWorld() == null) return;
        if (registeredAnchors.remove(encodeAnchorLocation(location))) save();
    }

    public synchronized boolean isAnchorPending(Location location) {
        if (location == null || location.getWorld() == null) return false;
        String key = encodeAnchorLocation(location);
        return pendingAnchorBlocks.containsValue(key);
    }

    public synchronized void deliverPendingRefund(Player player) {
        if (player == null) return;
        ItemStack item = pendingRefunds.remove(player.getUniqueId());
        ItemStack anchor = pendingAnchorRefunds.remove(player.getUniqueId());
        if (item == null && anchor == null) return;
        if (item != null) giveItem(player, item, "§eSe te devolvieron los objetos del ritual cancelado.");
        if (anchor != null) giveItem(player, anchor, "§eSe te devolvieron los objetos del ritual cancelado.");
        save();
    }

    /** Records the escrowed ritual charge while its meteor is still falling. */
    public synchronized void onRitualMeteorLaunched(UUID playerId, Location target, ItemStack charge) {
        if (playerId == null || target == null || target.getWorld() == null || charge == null) return;
        inFlightLocations.put(playerId, locationKey(target));
        inFlightCharges.put(playerId, charge.clone());
        save();
    }

    /** Successful impact finalizes consumption; the event lock remains until its chest is restored. */
    public synchronized void onRitualMeteorImpacted(UUID playerId) {
        if (playerId == null) return;
        inFlightLocations.remove(playerId);
        inFlightCharges.remove(playerId);
        save();
    }

    /** A protection/border cancellation refunds the charge and releases all ritual reservations. */
    public synchronized void onRitualMeteorCancelled(UUID playerId, ItemStack charge, String reason) {
        if (playerId == null) return;
        String reservedLocation = inFlightLocations.remove(playerId);
        ItemStack refund = inFlightCharges.remove(playerId);
        if (refund == null && charge != null) refund = charge.clone();
        if (reservedLocation != null) activeLocations.remove(reservedLocation);
        usedPlayers.remove(playerId);
        if (refund != null) refundCharge(playerId, refund);
        refundAnchor(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) player.sendMessage("§eEl impacto ritual fue cancelado (" + reason + "). Se devolvieron los objetos del ritual y puedes intentarlo en otra zona.");
        save();
    }

    public synchronized void start() {
        if (active) return;
        cancelPendingRituals(true);
        active = true;
        usedPlayers.clear();
        save();
        String msg = plugin.getConfig().getString("event-announcements.ritual-event-start", "&5☄ &d¡Evento MeteoriteRitual activado!");
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
        if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("meteoriteritual", plain(msg));
    }

    public synchronized void stop() {
        active = false;
        cancelPendingRituals(true);
        usedPlayers.clear();
        save();
        String msg = plugin.getConfig().getString("event-announcements.ritual-event-stop", "&5☄ &7El evento MeteoriteRitual ha terminado.");
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(msg));
        if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("meteoriteritual", plain(msg));
    }

    public synchronized String tryInvoke(Player player, Location anchorLocation) {
        if (player == null || anchorLocation == null || anchorLocation.getWorld() == null) return "§cNo se pudo iniciar el ritual.";
        if (!isRegisteredAnchor(anchorLocation)) return "§cNecesitas usar el ancla especial entregada por MeteoriteDrop.";
        if (!active) return "§cEl evento MeteoriteRitual no está activo.";
        int captures = plugin.getStatsManager().getCaptureCount(player.getUniqueId());
        if (captures < getRequiredCaptures()) return "§cNecesitas capturar al menos " + getRequiredCaptures() + " meteoritos. Progreso: " + captures + ".";
        if (usedPlayers.contains(player.getUniqueId())) return "§cYa invocaste tu meteorito en este evento. Espera a que se reinicie.";
        if (pendingAnchorBlocks.containsValue(encodeAnchorLocation(anchorLocation))) return "§cEste altar ya está siendo utilizado.";
        int limit = Math.max(1, plugin.getConfig().getInt("ritual.max-active-summons", 1));
        if (activeLocations.size() >= limit) return "§cYa hay un meteorito ritual activo. Espera a que termine.";
        MeteorType type = plugin.getMeteorTypeManager().getType("meteoriteritual");
        if (type == null) return "§cFalta configurar meteor-types.meteoriteritual en config.yml.";
        if (!isRitualFireChargeItem(player.getInventory().getItemInMainHand())) return "§cDebes usar la carga ígnea especial de MeteoriteDrop en tu mano principal.";

        World world = anchorLocation.getWorld();
        String locationKey = locationKey(anchorLocation);
        String anchorKey = encodeAnchorLocation(anchorLocation);
        ItemStack escrowCharge = player.getInventory().getItemInMainHand().clone();
        escrowCharge.setAmount(1);
        consumeOneFromMainHand(player);
        setAnchorCharges(anchorLocation, 4);

        UUID playerId = player.getUniqueId();
        usedPlayers.add(playerId);
        pendingPlayers.add(playerId);
        pendingLocations.put(playerId, locationKey);
        pendingAnchorBlocks.put(playerId, anchorKey);
        pendingCharges.put(playerId, escrowCharge);
        activeLocations.add(locationKey);
        save();

        int x = anchorLocation.getBlockX();
        int z = anchorLocation.getBlockZ();
        int duration = Math.max(1, Math.min(30, plugin.getConfig().getInt("ritual.countdown-seconds", 5)));
        String startMessage = plugin.getConfig().getString("event-announcements.ritual-start",
                        "&5☄ &d{player} ha invocado un meteorito en X: {x}, Z: {z} ({world}).")
                .replace("{player}", player.getName()).replace("{x}", String.valueOf(x))
                .replace("{z}", String.valueOf(z)).replace("{world}", world.getName())
                .replace("{seconds}", String.valueOf(duration));
        PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(startMessage));
        if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("meteoriteritual", plain(startMessage));

        String title = itemConfig.getString("countdown.title", "&d&l¡Has invocado un Meteorito!");
        String subtitle = itemConfig.getString("countdown.subtitle", "&7Prepárate para la llegada");
        player.showTitle(Title.title(LegacyComponentSerializer.legacyAmpersand().deserialize(title),
                LegacyComponentSerializer.legacyAmpersand().deserialize(subtitle),
                Title.Times.times(java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(duration), java.time.Duration.ofMillis(250))));
        String soundName = plugin.getConfig().getString("event-announcements.ritual-start-sound", "BLOCK_BEACON_ACTIVATE");
        playSoundNearby(anchorLocation, soundName, 1.0f, 0.8f);

        final int[] remaining = {duration};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!active || !player.isOnline()) {
                finishPending(playerId, true);
                return;
            }
            if (remaining[0] <= 0) {
                launchSummonedMeteor(player, anchorLocation, type, locationKey, anchorKey);
                return;
            }
            String actionbar = itemConfig.getString("countdown.actionbar", "&dEl meteorito caerá en &f{seconds} &dsegundos...")
                    .replace("{seconds}", String.valueOf(remaining[0]));
            player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize(actionbar));
            Sound tick = remaining[0] <= 3 ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_BEACON_AMBIENT;
            player.playSound(player.getLocation(), tick, 0.8f, remaining[0] <= 3 ? 1.2f : 0.8f);
            remaining[0]--;
        }, 0L, 20L);
        pendingRituals.put(playerId, task);
        return "§aRitual iniciado. El meteorito caerá en " + duration + " segundos.";
    }

    private synchronized void launchSummonedMeteor(Player player, Location anchorLocation, MeteorType type,
                                                    String locationKey, String anchorKey) {
        UUID playerId = player.getUniqueId();
        BukkitTask task = pendingRituals.remove(playerId);
        if (task != null) task.cancel();

        Block anchor = anchorLocation.getBlock();
        if (anchor.getType() != Material.RESPAWN_ANCHOR || !registeredAnchors.contains(anchorKey)) {
            finishPending(playerId, true);
            if (player.isOnline()) player.sendMessage("§cEl ancla ritual ya no está disponible. Se canceló la invocación.");
            return;
        }

        // Remove the custom altar core so the crater snapshot never destroys a tile-state anchor.
        registeredAnchors.remove(anchorKey);
        anchor.setType(Material.AIR, false);
        ItemStack escrowCharge = pendingCharges.get(playerId);
        if (plugin.getMeteorManager().launchRitualMeteor(type, anchorLocation.getWorld(), anchorLocation.getBlockX(), anchorLocation.getBlockZ(), playerId, escrowCharge)) {
            pendingPlayers.remove(playerId);
            pendingLocations.remove(playerId);
            pendingAnchorBlocks.remove(playerId);
            pendingCharges.remove(playerId);
            onRitualMeteorLaunched(playerId, anchorLocation, escrowCharge);
            save();
            if (plugin.getMissionsManager() != null) plugin.getMissionsManager().recordObjective(player, "rituals");
            String falling = plugin.getConfig().getString("event-announcements.ritual-fall",
                            "&5☄ &d¡El Meteorito Ritual de {player} está cayendo hacia X: {x}, Z: {z} ({world})!")
                    .replace("{player}", player.getName()).replace("{x}", String.valueOf(anchorLocation.getBlockX()))
                    .replace("{z}", String.valueOf(anchorLocation.getBlockZ())).replace("{world}", anchorLocation.getWorld().getName());
            PlayerBroadcast.send(LegacyComponentSerializer.legacyAmpersand().deserialize(falling));
            if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().sendEvent("meteoriteritual", plain(falling));
        } else {
            anchor.setType(Material.RESPAWN_ANCHOR, false);
            setAnchorCharges(anchorLocation, 0);
            registeredAnchors.add(anchorKey);
            finishPending(playerId, true);
            if (player.isOnline()) player.sendMessage("§cNo se pudo lanzar el meteorito (límite o zona protegida). Se devolvió la carga ígnea.");
        }
    }

    private void consumeOneFromMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    private void setAnchorCharges(Location location, int charges) {
        if (location == null || location.getWorld() == null) return;
        Block block = location.getBlock();
        if (block.getType() != Material.RESPAWN_ANCHOR) return;
        BlockData data = block.getBlockData();
        if (data instanceof RespawnAnchor anchorData) {
            anchorData.setCharges(Math.max(0, Math.min(anchorData.getMaximumCharges(), charges)));
            block.setBlockData(anchorData, false);
        }
    }

    private void playSoundNearby(Location location, String name, float volume, float pitch) {
        try {
            Sound sound = Sound.valueOf(name.toUpperCase(Locale.ROOT));
            for (Player online : location.getWorld().getPlayers()) {
                if (online.getLocation().distanceSquared(location) <= 64.0 * 64.0) online.playSound(location, sound, volume, pitch);
            }
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Sonido inválido en event-announcements.ritual-start-sound: " + name);
        }
    }

    private String plain(String message) {
        return PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(message));
    }

    private synchronized void finishPending(UUID playerId, boolean refundCharge) {
        BukkitTask task = pendingRituals.remove(playerId);
        if (task != null) task.cancel();
        pendingPlayers.remove(playerId);
        String activeKey = pendingLocations.remove(playerId);
        if (activeKey != null) activeLocations.remove(activeKey);
        String anchorKey = pendingAnchorBlocks.remove(playerId);
        if (anchorKey != null) {
            Location anchor = decodeAnchorLocation(anchorKey);
            if (anchor != null) setAnchorCharges(anchor, 0);
        }
        ItemStack charge = pendingCharges.remove(playerId);
        if (refundCharge && charge != null) refundCharge(playerId, charge);
        usedPlayers.remove(playerId);
        save();
    }

    private void refundCharge(UUID playerId, ItemStack item) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            Map<Integer, ItemStack> leftover = online.getInventory().addItem(item);
            leftover.values().forEach(stack -> online.getWorld().dropItemNaturally(online.getLocation(), stack));
            online.sendMessage("§eSe devolvió la carga ígnea del ritual cancelado.");
        } else {
            pendingRefunds.put(playerId, item.clone());
        }
    }

    private void refundAnchor(UUID playerId) {
        ItemStack anchor = createRitualAnchorItem();
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            Map<Integer, ItemStack> leftover = online.getInventory().addItem(anchor);
            leftover.values().forEach(stack -> online.getWorld().dropItemNaturally(online.getLocation(), stack));
        } else {
            pendingAnchorRefunds.put(playerId, anchor);
        }
    }

    private synchronized void cancelPendingRituals(boolean refund) {
        for (UUID playerId : new HashSet<>(pendingPlayers)) finishPending(playerId, refund);
        for (BukkitTask task : pendingRituals.values()) task.cancel();
        pendingRituals.clear();
    }

    public synchronized void release(Location location) {
        if (location == null || location.getWorld() == null) return;
        if (activeLocations.remove(locationKey(location))) save();
    }

    private String locationKey(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockZ();
    }

    private String encodeAnchorLocation(Location location) {
        return location.getWorld().getUID() + "," + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    private Location decodeAnchorLocation(String encoded) {
        try {
            String[] parts = encoded.split(",", 4);
            if (parts.length != 4) return null;
            World world = Bukkit.getWorld(UUID.fromString(parts[0]));
            if (world == null) return null;
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void loadPendingMaps() {
        ConfigurationSection pendingLocs = state.getConfigurationSection("pending-locations");
        if (pendingLocs != null) for (String id : pendingLocs.getKeys(false)) try { pendingLocations.put(UUID.fromString(id), pendingLocs.getString(id)); } catch (IllegalArgumentException ignored) {}
        ConfigurationSection pendingAnchors = state.getConfigurationSection("pending-anchors");
        if (pendingAnchors != null) for (String id : pendingAnchors.getKeys(false)) try { pendingAnchorBlocks.put(UUID.fromString(id), pendingAnchors.getString(id)); } catch (IllegalArgumentException ignored) {}
        ConfigurationSection charges = state.getConfigurationSection("pending-charge-items");
        if (charges != null) for (String id : charges.getKeys(false)) try {
            ItemStack item = charges.getItemStack(id);
            if (item != null) pendingCharges.put(UUID.fromString(id), item);
        } catch (IllegalArgumentException ignored) {}
        ConfigurationSection refunds = state.getConfigurationSection("pending-refunds");
        if (refunds != null) for (String id : refunds.getKeys(false)) try {
            ItemStack item = refunds.getItemStack(id);
            if (item != null) pendingRefunds.put(UUID.fromString(id), item);
        } catch (IllegalArgumentException ignored) {}
        ConfigurationSection anchorRefunds = state.getConfigurationSection("pending-anchor-refunds");
        if (anchorRefunds != null) for (String id : anchorRefunds.getKeys(false)) try {
            ItemStack item = anchorRefunds.getItemStack(id);
            if (item != null) pendingAnchorRefunds.put(UUID.fromString(id), item);
        } catch (IllegalArgumentException ignored) {}
        ConfigurationSection inFlightLocs = state.getConfigurationSection("inflight-locations");
        if (inFlightLocs != null) for (String id : inFlightLocs.getKeys(false)) try { inFlightLocations.put(UUID.fromString(id), inFlightLocs.getString(id)); } catch (IllegalArgumentException ignored) {}
        ConfigurationSection inFlight = state.getConfigurationSection("inflight-charge-items");
        if (inFlight != null) for (String id : inFlight.getKeys(false)) try {
            ItemStack item = inFlight.getItemStack(id);
            if (item != null) inFlightCharges.put(UUID.fromString(id), item);
        } catch (IllegalArgumentException ignored) {}
    }

    private void addUuid(Set<UUID> target, String value) {
        try { target.add(UUID.fromString(value)); } catch (IllegalArgumentException ignored) {}
    }

    private synchronized void save() {
        state.set("active", active);
        state.set("used-players", usedPlayers.stream().map(UUID::toString).toList());
        state.set("pending-players", pendingPlayers.stream().map(UUID::toString).toList());
        state.set("registered-anchors", new ArrayList<>(registeredAnchors));
        state.set("active-locations", new ArrayList<>(activeLocations));
        state.set("pending-locations", null);
        pendingLocations.forEach((id, loc) -> state.set("pending-locations." + id, loc));
        state.set("pending-anchors", null);
        pendingAnchorBlocks.forEach((id, loc) -> state.set("pending-anchors." + id, loc));
        state.set("pending-charge-items", null);
        pendingCharges.forEach((id, item) -> state.set("pending-charge-items." + id, item));
        state.set("pending-refunds", null);
        pendingRefunds.forEach((id, item) -> state.set("pending-refunds." + id, item));
        state.set("pending-anchor-refunds", null);
        pendingAnchorRefunds.forEach((id, item) -> state.set("pending-anchor-refunds." + id, item));
        state.set("inflight-locations", null);
        inFlightLocations.forEach((id, loc) -> state.set("inflight-locations." + id, loc));
        state.set("inflight-charge-items", null);
        inFlightCharges.forEach((id, item) -> state.set("inflight-charge-items." + id, item));
        try {
            AtomicYamlStorage.save(state, stateFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save ritual-state.yml", e);
        }
    }
}
