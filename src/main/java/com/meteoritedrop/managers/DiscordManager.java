package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Manages dual Discord notifications:
 * 1. Native asynchronous Discord Webhooks (no external plugins needed, sends rich Embeds).
 * 2. Optional DiscordSRV integration if installed on the server.
 */
public class DiscordManager {

    private final MeteoriteDrop plugin;
    private final HttpClient httpClient;

    public DiscordManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Warns Discord that an automated meteorite drop is incoming in X minutes.
     */
    public void sendAutoDropWarning(int minutes) {
        if (!isEventEnabled("auto-drop-warning")) return;

        int color = 0xF39C12; // Orange / Amber
        String title = "⏳ ¡Alerta de Meteorito en Camino!";
        String description = "¡Un meteorito caerá en el servidor en **" + minutes + " minutos**!\n" +
                "Prepárate y conéctate al servidor para asegurar el botín.";

        String fieldsJson = "[" +
                "{\"name\":\"⏱️ Tiempo Restante\",\"value\":\"" + minutes + " minuto(s)\",\"inline\":true}," +
                "{\"name\":\"🎮 Servidor\",\"value\":\"OdisseyMC\",\"inline\":true}" +
                "]";

        sendDiscordNotification("auto-drop-warning", title, description, color, fieldsJson, true);
    }

    /**
     * Notifies Discord that a meteorite has landed with its coordinates and type.
     */
    public void sendMeteorLanded(MeteorType type, int x, int z, String worldName) {
        if (!isEventEnabled("meteor-landed")) return;

        String typeName = type != null ? stripColor(type.getDisplayName()) : "Meteorito";
        int color = getColorForType(type);
        int lockSeconds = type != null ? type.getLockDurationSeconds() : 120;
        int minutes = lockSeconds / 60;
        int seconds = lockSeconds % 60;

        String title = "☄️ ¡Un Meteorito ha Impactado!";
        String description = "¡El **" + typeName + "** ha aterrizado de forma violenta en el mundo!\n" +
                "El cofre está sellado y se desbloqueará pronto para el más rápido.";

        String fieldsJson = "[" +
                "{\"name\":\"📍 Coordenadas\",\"value\":\"`X: " + x + " | Z: " + z + "`\",\"inline\":true}," +
                "{\"name\":\"🌍 Mundo\",\"value\":\"`" + (worldName != null ? worldName : "world") + "`\",\"inline\":true}," +
                "{\"name\":\"🔒 Desbloqueo en\",\"value\":\"`" + String.format("%02d:%02d", minutes, seconds) + "`\",\"inline\":true}" +
                "]";

        sendDiscordNotification("meteor-landed", title, description, color, fieldsJson, false);
    }

    /**
     * Notifies Discord that a normal supply drop has landed.
     */
    public void sendSupplyDropLanded(int x, int z, String worldName, int lockSeconds) {
        if (!isEventEnabled("supply-landed")) return;

        int color = 0x2ECC71; // Emerald Green
        int minutes = lockSeconds / 60;
        int seconds = lockSeconds % 60;

        String title = "📦 ¡Ha caído un Suministro Normal!";
        String description = "¡Un cofre de suministros ha aterrizado en la superficie (sin cráter)!\n" +
                "¡El primero en llegar cuando se abra se lleva el botín instantáneamente!";

        String fieldsJson = "[" +
                "{\"name\":\"📍 Coordenadas\",\"value\":\"`X: " + x + " | Z: " + z + "`\",\"inline\":true}," +
                "{\"name\":\"🌍 Mundo\",\"value\":\"`" + (worldName != null ? worldName : "world") + "`\",\"inline\":true}," +
                "{\"name\":\"⏱️ Abre en\",\"value\":\"`" + String.format("%02d:%02d", minutes, seconds) + "`\",\"inline\":true}" +
                "]";

        sendDiscordNotification("supply-landed", title, description, color, fieldsJson, false);
    }

    /**
     * Notifies Discord when a player claims the loot.
     */
    public void sendLootClaimed(String playerName, String typeName, int x, int z) {
        if (!isEventEnabled("loot-claimed")) return;

        int color = 0xFFD700; // Gold
        String cleanType = stripColor(typeName != null ? typeName : "Meteorito");

        String title = "🏆 ¡Botín Reclamado!";
        String description = "¡El jugador **" + playerName + "** ha conquistado con éxito el botín de **" + cleanType + "**!";

        String fieldsJson = "[" +
                "{\"name\":\"👑 Ganador\",\"value\":\"**" + playerName + "**\",\"inline\":true}," +
                "{\"name\":\"📍 Ubicación\",\"value\":\"`X: " + x + " | Z: " + z + "`\",\"inline\":true}" +
                "]";

        sendDiscordNotification("loot-claimed", title, description, color, fieldsJson, false);
    }

    /**
     * Notifies Discord when a season ends and crowns the champion.
     */
    public void sendSeasonEnded(int season, String winnerName, int winnerLooted) {
        if (!isEventEnabled("season-ended")) return;

        int color = 0x9B59B6; // Purple Crown
        String title = "👑 ¡FIN DE LA TEMPORADA " + season + "! 👑";
        String description = "¡La temporada ha concluido oficialmente!\n" +
                "El campeón absoluto de saqueos es **" + winnerName + "** con **" + winnerLooted + "** meteoritos conquistados.\n" +
                "¡El ranking ha sido reiniciado y comienza la nueva temporada!";

        String fieldsJson = "[" +
                "{\"name\":\"🏆 Campeón\",\"value\":\"**" + winnerName + "**\",\"inline\":true}," +
                "{\"name\":\"⭐ Saqueos\",\"value\":\"" + winnerLooted + "\",\"inline\":true}" +
                "]";

        sendDiscordNotification("season-ended", title, description, color, fieldsJson, true);
    }

    private boolean isEventEnabled(String eventKey) {
        if (!plugin.getConfig().getBoolean("discord.enabled", true)) return false;
        return plugin.getDiscordChatBridge() == null || plugin.getDiscordChatBridge().isEventEnabled(eventKey);
    }

    private void sendDiscordNotification(String eventKey, String title, String description, int color, String fieldsJson, boolean allowMention) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("discord.enabled", true)) return;
        if (plugin.getDiscordChatBridge() != null) {
            plugin.getDiscordChatBridge().sendEvent(eventKey, "**" + stripColor(title) + "**\n" + stripColor(description));
        }
        String webhookUrl = config.getString("discord.webhook-url", "").trim();
        String mention = allowMention ? config.getString("discord.mention", "").trim() : "";
        String username = config.getString("discord.username", "OdisseyMC - MeteoriteDrop").trim();
        String avatarUrl = config.getString("discord.avatar-url", "https://i.imgur.com/8Q7Zl0S.png").trim();
        if (!webhookUrl.isEmpty() && webhookUrl.startsWith("http")) sendWebhookAsync(webhookUrl, username, avatarUrl, mention, title, description, color, fieldsJson);
        if (config.getBoolean("discord.discordsrv.enabled", true)) sendDiscordSrvNotification(title, description, config.getString("discord.discordsrv.channel", "global"));
    }
    private void sendWebhookAsync(String webhookUrl, String username, String avatarUrl, String mention,
                                  String title, String description, int color, String fieldsJson) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String timestamp = Instant.now().toString();

                StringBuilder json = new StringBuilder();
                json.append("{");
                if (!username.isEmpty()) {
                    json.append("\"username\":\"").append(escapeJson(username)).append("\",");
                }
                if (!avatarUrl.isEmpty()) {
                    json.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\",");
                }
                if (!mention.isEmpty()) {
                    json.append("\"content\":\"").append(escapeJson(mention)).append("\",");
                }

                // Embed object
                json.append("\"embeds\":[{");
                json.append("\"title\":\"").append(escapeJson(title)).append("\",");
                json.append("\"description\":\"").append(escapeJson(description)).append("\",");
                json.append("\"color\":").append(color).append(",");
                if (fieldsJson != null && !fieldsJson.isEmpty()) {
                    json.append("\"fields\":").append(fieldsJson).append(",");
                }
                json.append("\"footer\":{\"text\":\"OdisseyMC • Eventos Automáticos\"},");
                json.append("\"timestamp\":\"").append(timestamp).append("\"");
                json.append("}]");
                json.append("}");

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(webhookUrl))
                        .header("Content-Type", "application/json; charset=UTF-8")
                        .timeout(Duration.ofSeconds(6))
                        .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    plugin.getLogger().warning("[DiscordManager] Webhook returned HTTP " + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[DiscordManager] Error sending Discord webhook: " + e.getMessage());
            }
        });
    }

    private void sendDiscordSrvNotification(String title, String description, String channelName) {
        Plugin discordSrv = Bukkit.getPluginManager().getPlugin("DiscordSRV");
        if (discordSrv == null || !discordSrv.isEnabled()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Use DiscordSRV's broadcast / destination text channel via reflection
                Class<?> srvClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
                Object srvInstance = srvClass.getMethod("getPlugin").invoke(null);
                if (srvInstance == null) return;

                Object channelObj = srvClass.getMethod("getDestinationTextChannelForGameChannelName", String.class)
                        .invoke(srvInstance, channelName != null && !channelName.isEmpty() ? channelName : "global");

                if (channelObj == null) {
                    channelObj = srvClass.getMethod("getMainTextChannel").invoke(srvInstance);
                }

                if (channelObj != null) {
                    String plainMsg = "**" + stripColor(title) + "**\n" + stripColor(description);
                    channelObj.getClass().getMethod("sendMessage", CharSequence.class).invoke(channelObj, plainMsg);
                }
            } catch (Throwable ignored) {
                // DiscordSRV might not have channel configured or different API version; fallback cleanly
            }
        });
    }

    private int getColorForType(MeteorType type) {
        if (type == null) return 0xE74C3C;
        String id = type.getId().toLowerCase();
        if (id.contains("ice")) return 0x3498DB;          // Ice Blue
        if (id.contains("end")) return 0x9B59B6;          // End Purple
        if (id.contains("divine")) return 0xF1C40F;       // Divine Gold
        if (id.contains("toxic")) return 0x2ECC71;        // Toxic Green
        if (id.contains("storm")) return 0x00CED1;        // Storm Cyan
        if (id.contains("cursed")) return 0x2C3E50;       // Dark Wither Grey
        if (id.contains("abyssal")) return 0x16A085;      // Deep Sea Teal
        if (id.contains("corrupted")) return 0x117A65;   // Sculk Dark Cyan
        if (id.contains("normal")) return 0xF39C12;      // Normal Supply Orange
        return 0xE74C3C;                                 // Lava Red Default
    }

    private String stripColor(String input) {
        if (input == null) return "";
        return input.replaceAll("(?i)[&§][0-9a-fk-or]", "");
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
