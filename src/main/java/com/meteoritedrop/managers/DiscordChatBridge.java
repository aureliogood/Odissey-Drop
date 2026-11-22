package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;

import java.util.logging.Level;

/** Bidirectional Minecraft <-> Discord chat bridge using the configured bot. */
public final class DiscordChatBridge extends ListenerAdapter implements Listener {
    private final MeteoriteDrop plugin;
    private volatile FileConfiguration config;
    private volatile JDA jda;
    private volatile boolean stopping;
    private final AtomicLong connectionGeneration = new AtomicLong();
    private volatile boolean nonChatIntegrated;
    private Class<?> nonChatChannelApi;
    private Class<?> nonChatProcessorType;
    private final Map<String, Object> nonChatProcessors = new HashMap<>();

    public DiscordChatBridge(MeteoriteDrop plugin) {
        this.plugin = plugin;
        File file = new File(plugin.getDataFolder(), "discord.yml");
        if (!file.exists()) {
            plugin.saveResource("discord.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void start() {
        long generation = connectionGeneration.incrementAndGet();
        stopping = false;
        if (!config.getBoolean("enabled", true)) {
            plugin.getLogger().info("Discord chat bridge is disabled in discord.yml.");
            return;
        }
        hookNonChatChannels();
        String token = config.getString("bot-token", "").trim();
        if (token.isEmpty() || token.equalsIgnoreCase("YOUR_BOT_TOKEN_HERE")) {
            plugin.getLogger().warning("Discord bot is not connected: set bot-token in plugins/MeteoriteDrop/discord.yml.");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // The light builder intentionally omits most entity caches. Outbound chat and
                // event delivery resolve the configured channel through JDA's channel cache.
                JDA connected = JDABuilder.create(token, EnumSet.of(
                                GatewayIntent.GUILD_MESSAGES,
                                GatewayIntent.MESSAGE_CONTENT))
                        .addEventListeners(this)
                        .build();
                if (stopping || connectionGeneration.get() != generation || !plugin.isEnabled()) {
                    connected.shutdownNow();
                    return;
                }
                jda = connected;
                connected.awaitReady();
                if (stopping || connectionGeneration.get() != generation || !plugin.isEnabled()) {
                    connected.shutdownNow();
                    if (jda == connected) jda = null;
                    return;
                }
                plugin.getLogger().info("Discord bot connected as " + connected.getSelfUser().getName() + ".");
                registerSlashCommands(connected);
                String channelId = config.getString("channel-id", "").trim();
                if (channelId.isEmpty()) {
                    plugin.getLogger().warning("Discord bot is connected, but channel-id is empty in discord.yml.");
                } else if (connected.getTextChannelById(channelId) == null) {
                    plugin.getLogger().warning("Discord channel-id was not found or the bot cannot access it.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                plugin.getLogger().warning("Discord bot startup was interrupted.");
            } catch (Exception e) {
                if (connectionGeneration.get() != generation || stopping) return;
                plugin.getLogger().log(Level.SEVERE, "Could not connect the Discord bot. Check the token and bot intents in discord.yml.", e);
                JDA failed = jda;
                if (failed != null) failed.shutdownNow();
                jda = null;
            }
        });
    }

    public void shutdown() {
        stopping = true;
        connectionGeneration.incrementAndGet();
        unregisterNonChatProcessors();
        JDA connected = jda;
        jda = null;
        if (connected != null) connected.shutdownNow();
    }

    /** Reloads discord.yml and cleanly replaces the current JDA and NonChat hooks. */
    public synchronized void reload() {
        shutdown();
        File file = new File(plugin.getDataFolder(), "discord.yml");
        if (!file.exists()) plugin.saveResource("discord.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
        start();
    }

    public boolean isNonChatIntegrated() { return nonChatIntegrated; }
    public int getNonChatChannelCount() { return nonChatProcessors.size(); }
    public boolean isEventEnabled(String key) { return config.getBoolean("events.forward-to-discord", true) && config.getBoolean("events." + key, true); }
    public void sendEvent(String key, String message) {
        if (!isEventEnabled(key)) {
            if (config.getBoolean("debug", false)) plugin.getLogger().info("Discord event '" + key + "' was not sent because it is disabled in discord.yml.");
            return;
        }
        sendMessageToChannel(config.getString("channel-id", "").trim(), message);
    }
    public boolean isConnected() {
        JDA connected = jda;
        return !stopping && connected != null && connected.getStatus() == JDA.Status.CONNECTED;
    }

    /** Sends plugin event announcements through the same bot and configured chat channel. */
    public void sendToDiscord(String message) { sendEvent("meteor-impact", message); }

    private void sendMessageToChannel(String channelId, String message) {
        JDA connected=jda;
        if(message==null || message.isBlank()) return;
        if(connected==null || connected.getStatus()!=JDA.Status.CONNECTED) {
            if(config.getBoolean("debug", false)) plugin.getLogger().warning("Discord event was not sent: bot is not connected.");
            return;
        }
        if(channelId==null || channelId.isBlank()) {
            if(config.getBoolean("debug", false)) plugin.getLogger().warning("Discord event was not sent: channel-id is empty in discord.yml.");
            return;
        }
        TextChannel channel=connected.getTextChannelById(channelId);
        if(channel==null) {
            if(config.getBoolean("debug", false)) plugin.getLogger().warning("Discord event was not sent: channel " + channelId + " is not visible to the bot. Check guild/channel IDs and View Channel/Send Messages permissions.");
            return;
        }
        String safe=message.replace("@everyone","@\u200beveryone").replace("@here","@\u200bhere");
        channel.sendMessage(truncate(safe,2000)).queue(
                ignored -> { if(config.getBoolean("debug", false)) plugin.getLogger().info("Discord announcement delivered to channel " + channelId + "."); },
                error -> plugin.getLogger().warning("Could not send Discord announcement to channel " + channelId + ": " + error.getMessage()));
    }

    private void registerSlashCommands(JDA connected) {
        if(!config.getBoolean("discord-commands.enabled",true)) return;
        String guildId=config.getString("guild-id","").trim();
        if(guildId.isEmpty()) { plugin.getLogger().warning("Discord slash commands need guild-id configured in discord.yml."); return; }
        var guild=connected.getGuildById(guildId);
        if(guild==null) { plugin.getLogger().warning("Discord slash commands could not find configured guild-id."); return; }
        var command=Commands.slash("odyssey","Consultas y eventos de MeteoriteDrop")
            .addSubcommands(new SubcommandData("status","Estado del plugin y eventos") , new SubcommandData("players","Jugadores conectados"),
                new SubcommandData("drop","Lanzar un drop en ubicación aleatoria")
                    .addOption(OptionType.STRING,"type","ID del tipo de meteorito",true));
        guild.upsertCommand(command).queue(ignored -> plugin.getLogger().info("Registered Discord slash command /odyssey."),
            error -> plugin.getLogger().warning("Could not register Discord slash command: "+error.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if(!event.getName().equals("odyssey")) return;
        String sub=event.getSubcommandName();
        if(sub==null) return;
        if(sub.equals("drop") && !hasDropRole(event)) { event.reply("No tienes un rol autorizado para lanzar drops.").setEphemeral(true).queue(); return; }
        event.deferReply(true).queue(hook -> Bukkit.getScheduler().runTask(plugin, () -> {
            String response;
            if("status".equals(sub)) {
                response="MeteoriteDrop: "+(plugin.isEnabled()?"activo":"inactivo")+" | Bot: "+(isConnected()?"conectado":"desconectado")+
                    " | Meteoroides activos: "+plugin.getMeteorManager().getActiveMeteorCount()+" | Cofres activos: "+plugin.getChestManager().getAllActiveChests().size()+
                    " | WorldGuard: "+(plugin.getProtectionManager().isWorldGuardAvailable()?"disponible":"no instalado");
            } else if("players".equals(sub)) {
                response="Jugadores conectados ("+Bukkit.getOnlinePlayers().size()+"): "+(Bukkit.getOnlinePlayers().isEmpty()?"ninguno":Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(java.util.stream.Collectors.joining(", ")));
            } else if("drop".equals(sub)) {
                String typeId=event.getOption("type").getAsString();
                var type=plugin.getMeteorTypeManager().getType(typeId);
                if(type==null) response="Tipo desconocido. IDs: "+String.join(", ",plugin.getMeteorTypeManager().getTypeIds());
                else {
                    List<World> worlds=Bukkit.getWorlds();
                    String configured=plugin.getConfig().getStringList("auto-drop.worlds").stream().findFirst().orElse("");
                    World world=configured.isBlank()?worlds.stream().findFirst().orElse(null):Bukkit.getWorld(configured);
                    if(world==null) response="No hay un mundo válido para generar el drop.";
                    else {
                        int radius=Math.max(256,plugin.getConfig().getInt("auto-drop.random-radius",1000));
                        boolean launched=false; int x=0,z=0;
                        for(int i=0;i<20 && !launched;i++){x=world.getSpawnLocation().getBlockX()+java.util.concurrent.ThreadLocalRandom.current().nextInt(-radius,radius+1);z=world.getSpawnLocation().getBlockZ()+java.util.concurrent.ThreadLocalRandom.current().nextInt(-radius,radius+1);launched=plugin.getMeteorManager().launchMeteor(type,world,x,z);}
                        response=launched?"Drop "+type.getId()+" lanzado en "+world.getName()+" X="+x+" Z="+z:"No se pudo lanzar el drop; revisa límites y protecciones del mundo.";
                    }
                }
            } else response="Subcomando desconocido.";
            hook.editOriginal(response).queue();
        }));
    }
    private boolean hasDropRole(SlashCommandInteractionEvent event) {
        List<String> allowed=config.getStringList("discord-commands.allowed-drop-role-ids");
        if(allowed.isEmpty() || event.getMember()==null) return false;
        return event.getMember().getRoles().stream().anyMatch(role->allowed.contains(role.getId()));
    }
    /** Hooks into NonChat after its channel access, anti-spam, and blocked-word checks. */
    private void hookNonChatChannels() {
        Plugin nonChat = Bukkit.getPluginManager().getPlugin("nonchat");
        if (nonChat == null || !nonChat.isEnabled()) return;
        try {
            ClassLoader loader = nonChat.getClass().getClassLoader();
            nonChatChannelApi = Class.forName("com.nonxedy.nonchat.api.ChannelAPI", true, loader);
            Class<?> channelType = Class.forName("com.nonxedy.nonchat.api.Channel", true, loader);
            nonChatProcessorType = Class.forName("com.nonxedy.nonchat.api.MessageProcessor", true, loader);
            Collection<?> channels = (Collection<?>) nonChatChannelApi.getMethod("getEnabledChannels").invoke(null);
            List<String> configuredChannels = config.getStringList("chat.nonchat-channels");
            boolean explicitSelection = !configuredChannels.isEmpty();
            var register = nonChatChannelApi.getMethod("registerMessageProcessor", String.class, nonChatProcessorType);

            for (Object channel : channels) {
                String channelId = (String) channelType.getMethod("getId").invoke(channel);
                boolean global = (boolean) channelType.getMethod("isGlobal").invoke(channel);
                if (!explicitSelection && !global) continue;
                if (explicitSelection && configuredChannels.stream().noneMatch(id -> id.equalsIgnoreCase(channelId))) continue;

                InvocationHandler handler = (proxy, method, args) -> {
                    if (method.getName().equals("process") && args != null && args.length == 2) {
                        Player player = (Player) args[0];
                        String message = (String) args[1];
                        forwardNonChatMessage(player, channelId, message);
                        return message;
                    }
                    if (method.getName().equals("toString")) return "MeteoriteDropNonChatProcessor[" + channelId + "]";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == (args == null ? null : args[0]);
                    return null;
                };
                Object processor = Proxy.newProxyInstance(loader, new Class<?>[]{nonChatProcessorType}, handler);
                register.invoke(null, channelId, processor);
                nonChatProcessors.put(channelId, processor);
            }
            nonChatIntegrated = !nonChatProcessors.isEmpty();
            if (nonChatIntegrated) {
                plugin.getLogger().info("Connected Discord chat bridge to NonChat for " + nonChatProcessors.size() + " channel(s).");
            } else {
                plugin.getLogger().warning("NonChat is installed, but no matching global channels were found. Set chat.nonchat-channels in discord.yml to select channel IDs.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not connect to NonChat's message API; cancelled chat will not be forwarded.", e);
        }
    }

    private void forwardNonChatMessage(Player player, String channelId, String message) {
        if (player == null || message == null || message.isBlank()) return;
        if (!config.getBoolean("chat.minecraft-to-discord", true)) return;
        String permission = config.getString("chat.permission", "meteoritedrop.discordchat").trim();
        if (!permission.isEmpty() && !player.hasPermission(permission)) return;
        String format = config.getString("chat.minecraft-format", "**{player}**: {message}");
        if (config.getBoolean("debug", false)) {
            plugin.getLogger().info("Discord chat debug: forwarding accepted NonChat message from " + player.getName() + " (channel " + channelId + ").");
        }
        sendToChannel(config.getString("channel-id", "").trim(), format
                .replace("{player}", player.getName())
                .replace("{message}", message));
    }

    private void unregisterNonChatProcessors() {
        if (nonChatChannelApi == null || nonChatProcessorType == null || nonChatProcessors.isEmpty()) return;
        try {
            var unregister = nonChatChannelApi.getMethod("unregisterMessageProcessor", String.class, nonChatProcessorType);
            for (Map.Entry<String, Object> entry : nonChatProcessors.entrySet()) {
                unregister.invoke(null, entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "Could not unregister NonChat message processors cleanly.", e);
        } finally {
            nonChatProcessors.clear();
            nonChatIntegrated = false;
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMinecraftChat(AsyncChatEvent event) {
        boolean debug = config.getBoolean("debug", false);
        String playerName = event.getPlayer().getName();
        if (event.isCancelled()) {
            // NonChat's processor is the canonical route when that integration is active.
            // Otherwise, still forward the Paper chat event: chat-format plugins commonly
            // cancel it after consuming it, and returning here silently breaks MC -> Discord.
            if (nonChatIntegrated) {
                if (debug) plugin.getLogger().info("Discord chat debug: chat event was cancelled; relying on the active NonChat processor for " + playerName + ".");
                return;
            }
            if (debug) plugin.getLogger().warning("Discord chat debug: chat event for " + playerName + " was cancelled by another plugin; forwarding anyway because no NonChat processor is active.");
        }
        if (!config.getBoolean("chat.minecraft-to-discord", true)) {
            if (debug) plugin.getLogger().info("Discord chat debug: Minecraft-to-Discord forwarding is disabled in discord.yml.");
            return;
        }
        String permission = config.getString("chat.permission", "meteoritedrop.discordchat").trim();
        if (!permission.isEmpty() && !event.getPlayer().hasPermission(permission)) {
            if (debug) plugin.getLogger().warning("Discord chat debug: " + playerName + " lacks the required permission " + permission + ".");
            return;
        }
        String channelId = config.getString("channel-id", "").trim();
        String content = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (channelId.isEmpty()) {
            if (debug) plugin.getLogger().warning("Discord chat debug: channel-id is empty in discord.yml.");
            return;
        }
        if (content.isEmpty()) {
            if (debug) plugin.getLogger().info("Discord chat debug: the Minecraft chat event had no plain-text content.");
            return;
        }
        String format = config.getString("chat.minecraft-format", "**{player}**: {message}");
        String message = format.replace("{player}", playerName).replace("{message}", content);
        if (debug) plugin.getLogger().info("Discord chat debug: forwarding Minecraft chat from " + playerName + " to channel " + channelId + ".");
        sendToChannel(channelId, message);
    }
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (stopping || !plugin.isEnabled()) return;
        if (!event.isFromGuild() || !config.getBoolean("chat.discord-to-minecraft", true)) return;
        if (event.getAuthor().isBot() || event.getMessage().isWebhookMessage()) return;

        String channelId = config.getString("channel-id", "").trim();
        if (!event.getChannel().getId().equals(channelId)) return;
        String guildId = config.getString("guild-id", "").trim();
        if (!guildId.isEmpty() && !event.getGuild().getId().equals(guildId)) return;

        String content = event.getMessage().getContentDisplay().trim();
        if (content.isEmpty()) return;
        String name = event.getMember() != null ? event.getMember().getEffectiveName() : event.getAuthor().getName();
        String format = config.getString("chat.discord-format", "&9[Discord] &b{player}&f: &7{message}");
        String rendered = format.replace("{player}", name).replace("{message}", content);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(
                LegacyComponentSerializer.legacyAmpersand().deserialize(rendered)));
    }

    private void sendToChannel(String channelId, String message) {
        boolean debug = config.getBoolean("debug", false);
        JDA connected = jda;
        if (stopping || connected == null) {
            if (debug) plugin.getLogger().warning("Discord chat debug: bot client is not available while forwarding Minecraft chat.");
            return;
        }
        if (!config.getBoolean("chat.minecraft-to-discord", true)) return;
        TextChannel channel = connected.getTextChannelById(channelId);
        if (channel == null) {
            if (debug) plugin.getLogger().warning("Discord chat debug: JDA cannot find configured text channel " + channelId + ".");
            return;
        }
        String safeMessage = message.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
        channel.sendMessage(truncate(safeMessage, 2000)).queue(
                ignored -> {
                    if (debug) plugin.getLogger().info("Discord chat debug: Minecraft message delivered to Discord channel " + channelId + ".");
                },
                error -> plugin.getLogger().warning("Discord chat debug: Discord rejected the Minecraft message: " + error.getMessage())
        );
    }
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 1) + "…";
    }
}







