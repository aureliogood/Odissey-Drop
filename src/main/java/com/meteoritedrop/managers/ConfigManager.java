package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Manages plugin configuration loading, caching messages, and coordinating reloads.
 */
public class ConfigManager {

    private final MeteoriteDrop plugin;
    private double meteorFallSpeed;
    private int craterRestoreDelaySeconds;
    private int craterMaxStaySeconds;
    private final Map<String, String> messageCache = new HashMap<>();

    public ConfigManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        migrateMissingDefaults();
        loadConfig();
    }

    /**
     * Reloads configuration from disk and re-populates meteor types.
     */
    public void reload() {
        plugin.reloadConfig();
        migrateMissingDefaults();
        loadConfig();
        if (plugin.getMeteorTypeManager() != null) {
            plugin.getMeteorTypeManager().loadTypes();
        }
    }

    private void migrateMissingDefaults() {
        try (InputStreamReader reader = new InputStreamReader(plugin.getResource("config.yml"), StandardCharsets.UTF_8)) {
            FileConfiguration defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            FileConfiguration current = plugin.getConfig();
            boolean changed = false;
            // Earlier builds could force night or storms on every impact. Retire those options.
            for (String path : new String[]{"environment-effects.enabled", "environment-effects.storm", "environment-effects.thunder", "environment-effects.night-time"}) {
                if (current.getBoolean(path, false)) {
                    current.set(path, false);
                    changed = true;
                }
            }
            for (String path : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(path) && !current.isSet(path)) {
                    current.set(path, defaults.get(path));
                    changed = true;
                }
            }
            // The revamped ritual requires at least three lifetime meteorite captures.
            // Upgrade older server configs that still explicitly stored the former value of 2.
            if (current.getInt("ritual.required-captures", 3) < 3) {
                current.set("ritual.required-captures", 3);
                changed = true;
            }
            // Migrate the previous plugin defaults while preserving customized limits.
            if (current.getInt("performance.max-guardians-per-meteor", 80) == 40) {
                current.set("performance.max-guardians-per-meteor", 80);
                changed = true;
            }
            if (current.getInt("performance.max-active-guardians", 240) == 120) {
                current.set("performance.max-active-guardians", 240);
                changed = true;
            }
            if (changed) {
                plugin.saveConfig();
                plugin.getLogger().info("Added missing default configuration options without replacing existing settings.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not migrate new default config options: " + e.getMessage());
        }
    }
    private void loadConfig() {
        FileConfiguration config = plugin.getConfig();
        double configuredFallSpeed = config.getDouble("meteor-fall-speed", 2.0);
        meteorFallSpeed = Double.isFinite(configuredFallSpeed) && configuredFallSpeed > 0
                ? Math.min(10.0, configuredFallSpeed) : 2.0;
        if (meteorFallSpeed != configuredFallSpeed) {
            plugin.getLogger().warning("Invalid meteor-fall-speed; using a finite value between 0 and 10 (default 2.0).");
        }
        int configuredRestoreDelay = config.getInt("crater-restore-delay-seconds", 30);
        craterRestoreDelaySeconds = Math.max(1, Math.min(3600, configuredRestoreDelay));
        if (craterRestoreDelaySeconds != configuredRestoreDelay) {
            plugin.getLogger().warning("crater-restore-delay-seconds must be between 1 and 3600; it was clamped.");
        }
        int configuredMaxStay = config.getInt("crater-max-stay-seconds", 180);
        craterMaxStaySeconds = Math.max(0, Math.min(86400, configuredMaxStay));
        if (craterMaxStaySeconds != configuredMaxStay) {
            plugin.getLogger().warning("crater-max-stay-seconds must be between 0 and 86400; it was clamped.");
        }

        messageCache.clear();
        if (config.isConfigurationSection("messages")) {
            for (String key : config.getConfigurationSection("messages").getKeys(false)) {
                messageCache.put(key, config.getString("messages." + key, ""));
            }
        }

        // Default messages if missing
        messageCache.putIfAbsent("broadcast-incoming", "&8&m--------------------------------------------------\n&e⚠️ &c&l¡ALERTA DE IMPACTO! &e⚠️\n&7¡Un &e{type} &7ha sido avistado cayendo en las coordenadas &eX: {x}, Z: {z}&7!\n&a¡El botín espera al más rápido!\n&8&m--------------------------------------------------");
        messageCache.putIfAbsent("broadcast-landed", "&8&m--------------------------------------------------\n&6☄ &e&l¡METEORITO HA IMPACTADO! &6☄\n&7El meteorito &e{type} &7ha aterrizado en &eX: {x}, Z: {z}&7!\n&c¡Asegura el cráter y prepárate para lootearlo!\n&8&m--------------------------------------------------");
        messageCache.putIfAbsent("broadcast-restoring", "&e⏳ ¡El botín ha sido recolectado! El cráter en &eX: {x}, Z: {z} &ese regenerará en &c{seconds} &esegundos.");
        messageCache.putIfAbsent("broadcast-restored", "&a☄ El terreno del meteorito en &eX: {x}, Z: {z} &aha sido completamente restaurado.");
        messageCache.putIfAbsent("chest-locked", "&cEl cofre aún está sellado. Espera &e{remaining}s&c.");
        messageCache.putIfAbsent("unlock-start", "&aComenzando a abrir el cofre del meteorito...");
        messageCache.putIfAbsent("unlock-interrupted", "&cFuiste interrumpido. Alguien más puede intentarlo.");
        messageCache.putIfAbsent("unlock-complete", "&a¡Abriste el cofre del meteorito!");
    }

    public double getMeteorFallSpeed() {
        return meteorFallSpeed;
    }

    public int getCraterRestoreDelaySeconds() {
        return craterRestoreDelaySeconds;
    }

    public int getCraterMaxStaySeconds() {
        return craterMaxStaySeconds;
    }

    public Component getFormattedMessage(String key, String def, String... replacements) {
        String msg = messageCache.getOrDefault(key, def);
        if (replacements != null && replacements.length >= 2) {
            for (int i = 0; i < replacements.length; i += 2) {
                String placeholder = replacements[i];
                String value = (i + 1 < replacements.length) ? replacements[i + 1] : "";
                msg = msg.replace(placeholder, value);
            }
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(msg);
    }

    public Component getBroadcastIncoming(String typeName, int x, int z) {
        return getFormattedMessage(
                "broadcast-incoming",
                "&8&m--------------------------------------------------\n&e⚠️ &c&l¡ALERTA DE IMPACTO! &e⚠️\n&7¡Un &e{type} &7ha sido avistado cayendo en las coordenadas &eX: {x}, Z: {z}&7!\n&a¡El botín espera al más rápido!\n&8&m--------------------------------------------------",
                "{type}", typeName != null ? typeName : "Meteorito",
                "{x}", String.valueOf(x),
                "{z}", String.valueOf(z),
                "{X}", String.valueOf(x),
                "{Z}", String.valueOf(z)
        );
    }

    public Component getBroadcastIncoming(int x, int z) {
        return getBroadcastIncoming("Meteorito", x, z);
    }

    public Component getBroadcastLanded(String typeName, int x, int z) {
        return getFormattedMessage(
                "broadcast-landed",
                "&8&m--------------------------------------------------\n&6☄ &e&l¡METEORITO HA IMPACTADO! &6☄\n&7El meteorito &e{type} &7ha aterrizado en &eX: {x}, Z: {z}&7!\n&c¡Asegura el cráter y prepárate para lootearlo!\n&8&m--------------------------------------------------",
                "{type}", typeName != null ? typeName : "Meteorito",
                "{x}", String.valueOf(x),
                "{z}", String.valueOf(z),
                "{X}", String.valueOf(x),
                "{Z}", String.valueOf(z)
        );
    }

    public Component getBroadcastLanded(int x, int z) {
        return getBroadcastLanded("Meteorito", x, z);
    }

    /** Sends the impact announcement through the bot configured by this plugin. */
    public void broadcastLandedToDiscord(String typeName, int x, int z, String worldName) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("discord.broadcast-on-impact", true)) return;

        String template = config.getString("discord.impact-message",
                "☄ ¡METEORITO HA IMPACTADO! {type} en X: {x}, Z: {z} (mundo: {world}). ¡Vayan por el botín!");
        String type = PlainTextComponentSerializer.plainText().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(typeName != null ? typeName : "Meteorito"));
        String message = template.replace("{type}", type)
                .replace("{x}", String.valueOf(x))
                .replace("{z}", String.valueOf(z))
                .replace("{world}", worldName != null ? worldName : "desconocido");
        message = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message));

        if (plugin.getDiscordChatBridge() != null) {
            plugin.getDiscordChatBridge().sendEvent("meteor-impact", message);
        }
    }
    public Component getBroadcastRestoring(int x, int z, int seconds) {
        return getFormattedMessage(
                "broadcast-restoring",
                "&e⏳ ¡El botín ha sido recolectado! El cráter en &eX: {x}, Z: {z} &ese regenerará en &c{seconds} &esegundos.",
                "{x}", String.valueOf(x),
                "{z}", String.valueOf(z),
                "{X}", String.valueOf(x),
                "{Z}", String.valueOf(z),
                "{seconds}", String.valueOf(seconds)
        );
    }

    public Component getBroadcastRestored(int x, int z) {
        return getFormattedMessage(
                "broadcast-restored",
                "&a☄ El terreno del meteorito en &eX: {x}, Z: {z} &aha sido completamente restaurado.",
                "{x}", String.valueOf(x),
                "{z}", String.valueOf(z),
                "{X}", String.valueOf(x),
                "{Z}", String.valueOf(z)
        );
    }

    public Component getChestLocked(long remainingSeconds) {
        return getFormattedMessage(
                "chest-locked",
                "&cEl cofre aún está sellado. Espera &e{remaining}s&c.",
                "{remaining}", String.valueOf(remainingSeconds)
        );
    }

    public Component getUnlockStart() {
        return getFormattedMessage("unlock-start", "&aComenzando a abrir el cofre del meteorito...");
    }

    public Component getUnlockInterrupted() {
        return getFormattedMessage("unlock-interrupted", "&cFuiste interrumpido. Alguien más puede intentarlo.");
    }

    public Component getUnlockComplete() {
        return getFormattedMessage("unlock-complete", "&a¡Abriste el cofre del meteorito!");
    }
}



