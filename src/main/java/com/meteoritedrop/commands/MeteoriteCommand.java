package com.meteoritedrop.commands;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.managers.DropHistoryManager;
import com.meteoritedrop.models.MeteoriteChest;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.utils.CompassUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Command executor and tab completer for /meteoritedrop (alias /md).
 */
public class MeteoriteCommand implements CommandExecutor, TabCompleter {

    private final MeteoriteDrop plugin;

    public MeteoriteCommand(MeteoriteDrop plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        boolean publicCommand = args.length > 0 && (args[0].equalsIgnoreCase("shop") || args[0].equalsIgnoreCase("globaltop") || args[0].equalsIgnoreCase("teamtop") || args[0].equalsIgnoreCase("role") || args[0].equalsIgnoreCase("stats"));
        if (!sender.hasPermission("meteoritedrop.admin") && !publicCommand) {
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cNo tienes permisos para ejecutar este comando."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("admin") || sub.equals("panel")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("§cEste panel solo puede abrirse dentro del juego."); return true; }
            plugin.getGuiManager().openAdminPanel(player);
            return true;
        }
        if (sub.equals("diagnose") || sub.equals("diagnostico")) {
            sender.sendMessage("§6§lDiagnóstico MeteoriteDrop §8| §7Paper " + Bukkit.getBukkitVersion());
            sender.sendMessage("§7Activos: §f" + plugin.getChestManager().getAllActiveChests().size() + " cofres, " + plugin.getMeteorManager().getActiveMeteorCount() + " meteoritos en caída; §7Discord: " + (plugin.getDiscordChatBridge().isConnected()?"§a conectado":"§c desconectado"));
            sender.sendMessage("§7WorldGuard: " + (plugin.getProtectionManager().isWorldGuardAvailable()?"§adisponible":"§cno detectado") + " §7| Protección spawn: " + plugin.getConfig().getBoolean("drop-protection.enabled") + " §7| Protección WG: " + plugin.getConfig().getBoolean("drop-protection.worldguard"));
            Location check = null;
            if (args.length >= 3) {
                World w = args.length >= 4 ? Bukkit.getWorld(args[3]) : (sender instanceof Player p ? p.getWorld() : null);
                try { if (w != null) check = new Location(w, Integer.parseInt(args[1]), w.getSpawnLocation().getY(), Integer.parseInt(args[2])); } catch (NumberFormatException ignored) { }
            } else if (sender instanceof Player p) check = p.getLocation();
            if (check != null) { String reason=plugin.getProtectionManager().getProtectionReason(check, plugin.getConfig().getInt("meteor-types.meteoriteritual.crater-radius",8)+2); sender.sendMessage(reason==null?"§aUbicación permitida.":"§cBloqueo: §f"+reason); }
            else sender.sendMessage("§eUso opcional: /md diagnose <x> <z> [mundo]; desde consola indica mundo.");
            return true;
        }
        if (sub.equals("tp") || sub.equals("teleport")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("§cEste comando solo lo puede usar un jugador.");
                return true;
            }
            Collection<MeteoriteChest> activeChests = plugin.getChestManager().getAllActiveChests();
            if (args.length < 2) {
                if (activeChests.isEmpty()) {
                    player.sendMessage("§eNo hay meteoritos activos a los que teletransportarse.");
                    return true;
                }
                player.sendMessage("§6Meteoritos activos: §eUsa /" + label + " tp <mundo>,<x>,<z>. Las ubicaciones aparecen al pulsar Tab.");
                return true;
            }

            String[] parts = args[1].split(",", 3);
            if (parts.length != 3) {
                player.sendMessage("§eUso: /" + label + " tp <mundo>,<x>,<z>. Usa Tab para ver ubicaciones activas.");
                return true;
            }
            World world = Bukkit.getWorld(parts[0]);
            int x;
            int z;
            try {
                x = Integer.parseInt(parts[1]);
                z = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ex) {
                player.sendMessage("§cLa ubicación del meteorito no es válida. Usa Tab para elegir una activa.");
                return true;
            }
            if (world == null) {
                player.sendMessage("§cEl mundo de ese meteorito ya no está disponible.");
                return true;
            }

            MeteoriteChest target = activeChests.stream()
                    .filter(chest -> chest.getState() != MeteoriteChest.ChestState.RESTORED)
                    .filter(chest -> chest.getChestLocation() != null
                            && world.equals(chest.getChestLocation().getWorld())
                            && chest.getChestLocation().getBlockX() == x
                            && chest.getChestLocation().getBlockZ() == z)
                    .findFirst().orElse(null);
            if (target == null) {
                player.sendMessage("§cEse meteorito ya no está activo. Usa Tab para ver las ubicaciones actuales.");
                return true;
            }
            Location destination = target.getChestLocation().clone().add(0.5, 1.0, 0.5);
            destination.setYaw(player.getLocation().getYaw());
            destination.setPitch(player.getLocation().getPitch());
            player.teleport(destination);
            String typeName = target.getMeteorType() != null ? target.getMeteorType().getDisplayName() : "Meteorito";
            player.sendMessage("§aTeletransportado a " + typeName + " en §e" + world.getName()
                    + " §7(X: " + x + ", Z: " + z + ").");
            return true;
        }
        if (sub.equals("remove") || sub.equals("eliminar")) {
            if (args.length < 3) {
                sender.sendMessage("§eUso: /" + label + " remove <x> <z> [mundo]");
                return true;
            }
            int x;
            int z;
            try {
                x = Integer.parseInt(args[1]);
                z = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("§cLas coordenadas X y Z deben ser números enteros.");
                return true;
            }
            World world;
            if (args.length >= 4) {
                world = Bukkit.getWorld(args[3]);
                if (world == null) {
                    sender.sendMessage("§cNo existe el mundo: " + args[3]);
                    return true;
                }
            } else if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                sender.sendMessage("§eDesde consola debes indicar el mundo: /" + label + " remove <x> <z> <mundo>");
                return true;
            }

            MeteoriteChest target = plugin.getChestManager().getAllActiveChests().stream()
                    .filter(chest -> chest.getChestLocation() != null
                            && world.equals(chest.getChestLocation().getWorld())
                            && chest.getChestLocation().getBlockX() == x
                            && chest.getChestLocation().getBlockZ() == z)
                    .findFirst().orElse(null);
            if (target == null) {
                sender.sendMessage("§cNo hay ningún meteorito activo en X: " + x + ", Z: " + z + " del mundo " + world.getName() + ".");
                return true;
            }
            target.restoreCrater();
            sender.sendMessage("§aSe eliminó el meteorito en X: " + x + ", Z: " + z + ". El terreno se está restaurando.");
            return true;
        }
        if (sub.equals("role")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Solo los jugadores pueden elegir un rol."); return true; }
            if (args.length < 2) { player.sendMessage("§eUso: /md role <tank|sanador|explorador>"); return true; }
            if (!plugin.getMeteorEventManager().chooseRole(player, args[1])) player.sendMessage("§cRol desconocido. Elige tank, sanador o explorador.");
            return true;
        }
        if (sub.equals("stats")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Uso: /md stats <jugador>"); return true; }
            if (args.length > 1 && args[1].equalsIgnoreCase("top")) {
                sender.sendMessage("§6§lMejores participantes de combate");
                List<String> top=plugin.getMeteorEventManager().getTopParticipants(10);
                for(int i=0;i<top.size();i++)sender.sendMessage("§e"+(i+1)+". §f"+top.get(i));
                return true;
            }
            sender.sendMessage(plugin.getMeteorEventManager().getPersonalSummary(player.getUniqueId()));
            return true;
        }
        if (sub.equals("event")) {
            if(args.length>=3 && args[1].equalsIgnoreCase("team")){
                switch(args[2].toLowerCase()){
                    case "start","iniciar" -> plugin.getStatsManager().startTeamEvent(sender);
                    case "stop","detener" -> plugin.getStatsManager().stopTeamEvent(sender);
                    case "status","estado" -> sender.sendMessage(plugin.getStatsManager().isTeamEventActive()?"§bCompetencia por equipos activa.":"§7Competencia por equipos inactiva.");
                    default -> sender.sendMessage("§eUso: /"+label+" event team <start|stop|status>");
                }return true;
            }            if (args.length < 3 || !args[1].equalsIgnoreCase("meteoriteritual")) { sender.sendMessage("§eUso: /" + label + " event meteoriteritual <start|stop|status>"); return true; }
            switch(args[2].toLowerCase()) {
                case "start", "on", "iniciar" -> { plugin.getRitualManager().start(); sender.sendMessage("§aEvento MeteoriteRitual activado."); }
                case "stop", "off", "detener" -> { plugin.getRitualManager().stop(); sender.sendMessage("§cEvento MeteoriteRitual detenido."); }
                case "status", "estado" -> sender.sendMessage(plugin.getRitualManager().isActive()?"§aMeteoriteRitual está activo.":"§7MeteoriteRitual está inactivo.");
                default -> sender.sendMessage("§eUso: /" + label + " event meteoriteritual <start|stop|status>");
            }
            return true;
        }
        if ((sub.equals("drop") || sub.equals("supply") || sub.equals("shower")) && sender instanceof Player player) {
            long wait = plugin.getMeteorManager().acquireManualLaunchCooldown(player);
            if (wait > 0) { player.sendMessage("§cEspera " + wait + " segundos antes de lanzar otro evento."); return true; }
        }

        if (sub.equals("shop")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Solo jugadores pueden usar la tienda."); return true; }
            if (args.length < 2) { player.sendMessage("§eUso: /md shop <key <tipo>|compass>"); return true; }
            if (args[1].equalsIgnoreCase("compass")) {
                double price=plugin.getConfig().getDouble("shop.compass-price",500);
                if(!buy(player,price,"§6Brújula Cósmica"))return true;
                player.getInventory().addItem(CompassUtil.createCosmicCompass(plugin));return true;
            }
            if (args[1].equalsIgnoreCase("key")) {
                if(args.length<3){player.sendMessage("§eUso: /md shop key <tipo>");return true;}
                String type=args[2].toLowerCase();
                if(!plugin.getKeyManager().isRequired(type)){player.sendMessage("§cEse tipo no requiere llave o no está configurado para usarlas.");return true;}
                double price=plugin.getConfig().getDouble("meteor-types."+type+".key-price",750);
                if(!buy(player,price,"§5Llave de "+type))return true;
                plugin.getKeyManager().give(player,type,1);return true;
            }
            player.sendMessage("§eUso: /md shop key <tipo> o /md shop compass");return true;
        }
        if (sub.equals("compass")) {
            if(!(sender instanceof Player player)){sender.sendMessage("Solo jugadores pueden usar este comando.");return true;}
            player.getInventory().addItem(CompassUtil.createCosmicCompass(plugin));player.sendMessage("§aRecibiste una Brújula Cósmica.");return true;
        }
        if (sub.equals("key")) {
            if(args.length<4||!args[1].equalsIgnoreCase("give")){sender.sendMessage("§eUso: /md key give <jugador> <tipo> [cantidad]");return true;}
            Player target=Bukkit.getPlayerExact(args[2]);if(target==null){sender.sendMessage("§cJugador no conectado.");return true;}
            int amount=1; if(args.length>4)try{amount=Integer.parseInt(args[4]);}catch(NumberFormatException ignored){}
            plugin.getKeyManager().give(target,args[3].toLowerCase(),amount);sender.sendMessage("§aLlave entregada a "+target.getName()+".");return true;
        }
        if (sub.equals("globaltop") || sub.equals("teamtop")) {
            if(sub.equals("globaltop")){
                var entries=plugin.getStatsManager().getGlobalTopLooters(10);sender.sendMessage("§6§lTop global de meteoritos");
                for(int i=0;i<entries.size();i++)sender.sendMessage("§e"+(i+1)+". §f"+entries.get(i).name()+" §7— §b"+entries.get(i).looted());
            }else{
                var teams=plugin.getStatsManager().getTeamCaptureCounts().entrySet().stream().sorted(java.util.Map.Entry.<String,Integer>comparingByValue().reversed()).limit(10).toList();
                sender.sendMessage("§6§lTop de equipos");for(int i=0;i<teams.size();i++)sender.sendMessage("§e"+(i+1)+". §f"+teams.get(i).getKey()+" §7— §b"+teams.get(i).getValue()+" capturas");
            }return true;
        }
        if (sub.equals("discord")) {
            if (args.length > 1 && (args[1].equalsIgnoreCase("leaderboard") || args[1].equalsIgnoreCase("top"))) {
                boolean sent=plugin.getMeteorEventManager().sendLeaderboardToDiscord();
                sender.sendMessage(sent?"§aClasificación de combate enviada al canal de discord.yml.":"§cDiscord está desconectado o combat-leaderboard está desactivado en discord.yml.");
                return true;
            }
            if (args.length < 2 || !args[1].equalsIgnoreCase("status")) { sender.sendMessage("§eUso: /" + label + " discord <status|leaderboard>"); return true; }
            var bridge = plugin.getDiscordChatBridge();
            sender.sendMessage("§6[MeteoriteDrop] §eDiscord: " + (bridge != null && bridge.isConnected() ? "§aconectado" : "§cdesconectado")
                    + " §8| §eNonChat: " + (bridge != null && bridge.isNonChatIntegrated() ? "§aconectado (" + bridge.getNonChatChannelCount() + " canales)" : "§cno integrado")
                    + " §8| §eWorldGuard: " + (plugin.getProtectionManager().isWorldGuardAvailable() ? "§adisponible" : "§cno instalado")
                    + " §8| §eDrops activos: §f" + plugin.getMeteorManager().getActiveMeteorCount()
                    + " §8| §eCofres activos: §f" + plugin.getChestManager().getAllActiveChests().size());
            return true;
        }
        if (sub.equals("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cSolo jugadores pueden abrir la interfaz gráfica."
                ));
                return true;
            }
            plugin.getGuiManager().openMainMenu(player);
            return true;
        }

        if (sub.equals("reload")) {
            plugin.getConfigManager().reload();
            if (plugin.getAutoDropManager() != null) plugin.getAutoDropManager().loadConfig();
            if (plugin.getMissionsManager() != null) plugin.getMissionsManager().reload();
            if (plugin.getDiscordChatBridge() != null) plugin.getDiscordChatBridge().reload();
            if (plugin.getRitualManager() != null) plugin.getRitualManager().reloadItemConfig();
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&a[MeteoriteDrop] Configuración y Discord recargados. Si cambiaste el token, espera a que el bot conecte."
            ));
            return true;
        }

        if (sub.equals("info")) {
            Collection<MeteoriteChest> activeChests = plugin.getChestManager().getAllActiveChests();
            if (activeChests.isEmpty()) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e[MeteoriteDrop] &7No hay meteoritos activos en este momento."
                ));
                return true;
            }
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6========== &eMeteoritos Activos &6=========="
            ));
            int idx = 1;
            for (MeteoriteChest chest : activeChests) {
                Location loc = chest.getChestLocation();
                String typeName = chest.getMeteorType() != null ? chest.getMeteorType().getDisplayName() : "Desconocido";
                String state = chest.getState().name();
                long remaining = chest.getRemainingLockSeconds();
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e" + idx + ". &f" + typeName + " &8| &7X: &e" + loc.getBlockX() +
                    " &7Z: &e" + loc.getBlockZ() + " &8| &7Estado: &a" + state +
                    " &8| &7Tiempo: &c" + remaining + "s"
                ));
                idx++;
            }
            return true;
        }

        if (sub.equals("history")) {
            if (plugin.getDropHistoryManager() == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cHistorial no disponible."));
                return true;
            }
            List<DropHistoryManager.DropRecord> records = plugin.getDropHistoryManager().getHistory();
            if (records.isEmpty()) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e[MeteoriteDrop] &7Aún no hay meteoritos en el historial."
                ));
                return true;
            }
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6========== &eHistorial de Meteoritos &6=========="
            ));
            for (int i = 0; i < records.size(); i++) {
                DropHistoryManager.DropRecord r = records.get(i);
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&e" + (i + 1) + ". &f" + r.meteorType() + " &8| &7X: &e" + r.x() +
                    " &7Z: &e" + r.z() + " &8| &7Ganador: &a" + r.winner() + " &8| &8" + r.timestamp()
                ));
            }
            return true;
        }

        if (sub.equals("top")) {
            if (plugin.getStatsManager() == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cEstadísticas no disponibles."));
                return true;
            }
            int season = plugin.getStatsManager().getCurrentSeason();
            List<com.meteoritedrop.managers.StatsManager.LooterEntry> top = plugin.getStatsManager().getTopLooters(10);
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6========== &e🏆 Top Saqueadores (Temporada " + season + ") &6=========="
            ));
            if (top.isEmpty()) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&7Aún no hay saqueos registrados en esta temporada."
                ));
            } else {
                for (int i = 0; i < top.size(); i++) {
                    com.meteoritedrop.managers.StatsManager.LooterEntry entry = top.get(i);
                    sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&e#" + (i + 1) + " &a" + entry.name() + " &8— &6" + entry.looted() + " &7meteoritos"
                    ));
                }
            }
            return true;
        }

        if (sub.equals("season")) {
            if (plugin.getStatsManager() == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&cEstadísticas no disponibles."));
                return true;
            }
            int currentSeason = plugin.getStatsManager().getCurrentSeason();
            if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
                plugin.getStatsManager().resetSeason(sender);
                return true;
            }

            // Season info
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6========== &eTemporada " + currentSeason + " &6==========\n" +
                "&7Usa &e/md top &7para ver la tabla de clasificación.\n" +
                "&7Los administradores pueden reiniciar la temporada con: &e/md season reset"
            ));
            return true;
        }

        if (sub.equals("shower")) {
            World world;
            Location origin;

            if (sender instanceof Player player) {
                world = player.getWorld();
                origin = player.getLocation();
            } else {
                world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                origin = (world != null) ? world.getSpawnLocation() : null;
            }

            if (world == null || origin == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cNo se pudo determinar un mundo válido para la lluvia de meteoritos."
                ));
                return true;
            }

            MeteorType selectedType = null;
            int count = 5;

            if (args.length >= 2) {
                if (plugin.getMeteorTypeManager().hasType(args[1])) {
                    selectedType = plugin.getMeteorTypeManager().getType(args[1]);
                }
            }
            if (args.length >= 3) {
                try {
                    count = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            }

            if (selectedType == null) {
                selectedType = plugin.getMeteorTypeManager().getRandomType();
            }

            int cx = origin.getBlockX();
            int cz = origin.getBlockZ();
            if (!plugin.getMeteorManager().launchMeteorShower(selectedType, world, cx, cz, count)) {
                sender.sendMessage("§cYa hay una lluvia de meteoritos activa.");
                return true;
            }
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    "&a[MeteoriteDrop] ¡Lluvia de " + count + " meteoritos iniciada en X: " + cx + ", Z: " + cz + "!"
            ));
            return true;
        }

        if (sub.equals("supply")) {
            World world;
            Location origin;

            if (sender instanceof Player player) {
                world = player.getWorld();
                origin = player.getLocation();
            } else {
                world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                origin = (world != null) ? world.getSpawnLocation() : null;
            }

            if (world == null || origin == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cNo se pudo determinar un mundo válido para el suministro."
                ));
                return true;
            }

            MeteorType normalType = plugin.getMeteorTypeManager().getType("normal");
            if (normalType == null) {
                normalType = plugin.getMeteorTypeManager().getRandomType();
            }

            Integer targetX = null;
            Integer targetZ = null;

            if (args.length >= 3) {
                try {
                    targetX = Integer.parseInt(args[1]);
                    targetZ = Integer.parseInt(args[2]);
                } catch (NumberFormatException ignored) {}
            } else if (args.length >= 2) {
                Player targetPlayer = Bukkit.getPlayerExact(args[1]);
                if (targetPlayer != null) {
                    targetX = targetPlayer.getLocation().getBlockX();
                    targetZ = targetPlayer.getLocation().getBlockZ();
                    world = targetPlayer.getWorld();
                }
            }

            if (targetX == null || targetZ == null) {
                double angle = ThreadLocalRandom.current().nextDouble() * 2.0 * Math.PI;
                double distance = 15.0 + (ThreadLocalRandom.current().nextDouble() * 50.0);
                targetX = (int) Math.round(origin.getX() + distance * Math.cos(angle));
                targetZ = (int) Math.round(origin.getZ() + distance * Math.sin(angle));
            }

            boolean launched = plugin.getMeteorManager().launchMeteor(normalType, world, targetX, targetZ);
            if (launched) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&a[MeteoriteDrop] Suministro normal lanzado hacia &eX: " + targetX +
                                "&a, &eZ: " + targetZ + " &aen el mundo &e" + world.getName() + "&a (¡sin cráter!)."
                ));
            } else {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cNo se pudo iniciar el evento de suministro."
                ));
            }
            return true;
        }

        if (sub.equals("drop")) {
            World world;
            Location origin;

            if (sender instanceof Player player) {
                world = player.getWorld();
                origin = player.getLocation();
            } else {
                world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
                origin = (world != null) ? world.getSpawnLocation() : null;
            }

            if (world == null || origin == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cNo se pudo determinar un mundo válido para el meteorito."
                ));
                return true;
            }

            MeteorType selectedType = null;
            Integer targetX = null;
            Integer targetZ = null;
            int fallSeconds = 0;

            if (args.length >= 2) {
                if (plugin.getMeteorTypeManager().hasType(args[1])) {
                    selectedType = plugin.getMeteorTypeManager().getType(args[1]);
                    if (args.length == 3) {
                        Player targetPlayer = Bukkit.getPlayerExact(args[2]);
                        if (targetPlayer != null) {
                            targetX = targetPlayer.getLocation().getBlockX();
                            targetZ = targetPlayer.getLocation().getBlockZ();
                            world = targetPlayer.getWorld();
                        } else {
                            Integer seconds = parseFallSeconds(sender, args[2]);
                            if (seconds == null) return true;
                            fallSeconds = seconds;
                        }
                    } else if (args.length >= 4 && Bukkit.getPlayerExact(args[2]) != null) {
                        Player targetPlayer = Bukkit.getPlayerExact(args[2]);
                        targetX = targetPlayer.getLocation().getBlockX();
                        targetZ = targetPlayer.getLocation().getBlockZ();
                        world = targetPlayer.getWorld();
                        Integer seconds = parseFallSeconds(sender, args[3]);
                        if (seconds == null || args.length > 4) return true;
                        fallSeconds = seconds;
                    } else if (args.length >= 4) {
                        try {
                            targetX = Integer.parseInt(args[2]);
                            targetZ = Integer.parseInt(args[3]);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                                    "&cLas coordenadas X y Z deben ser números enteros válidos."
                            ));
                            return true;
                        }
                        if (args.length >= 5) {
                            Integer seconds = parseFallSeconds(sender, args[4]);
                            if (seconds == null || args.length > 5) return true;
                            fallSeconds = seconds;
                        }
                    }
                } else if (args.length >= 3) {
                    // Backwards compatibility check: /md drop <x> <z>
                    try {
                        targetX = Integer.parseInt(args[1]);
                        targetZ = Integer.parseInt(args[2]);
                        selectedType = plugin.getMeteorTypeManager().getRandomType();
                        if (args.length >= 4) {
                            Integer seconds = parseFallSeconds(sender, args[3]);
                            if (seconds == null || args.length > 4) return true;
                            fallSeconds = seconds;
                        }
                    } catch (NumberFormatException ignored) {
                        // Not coordinates, invalid type
                    }
                } else {
                    Integer seconds = parseFallSeconds(sender, args[1]);
                    if (seconds == null) {
                        String available = String.join(", ", plugin.getMeteorTypeManager().getTypeIds());
                        sender.sendMessage("§cTipo desconocido. Tipos: §e" + available + " §7| o usa /md drop <segundos>.");
                        return true;
                    }
                    fallSeconds = seconds;
                    selectedType = plugin.getMeteorTypeManager().getRandomType();
                }

                if (selectedType == null && targetX == null) {
                    String available = String.join(", ", plugin.getMeteorTypeManager().getTypeIds());
                    sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                            "&cTipo de meteorito desconocido: &e" + args[1] + "&c. Tipos disponibles: &e" + available
                    ));
                    return true;
                }
            }

            if (selectedType == null) {
                selectedType = plugin.getMeteorTypeManager().getRandomType();
            }

            if (selectedType == null) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cNo hay tipos de meteorito configurados."
                ));
                return true;
            }

            if (targetX == null || targetZ == null) {
                // Random location within 25 to 200 blocks of origin
                double angle = ThreadLocalRandom.current().nextDouble() * 2.0 * Math.PI;
                double distance = 25.0 + (ThreadLocalRandom.current().nextDouble() * 175.0);
                targetX = (int) Math.round(origin.getX() + distance * Math.cos(angle));
                targetZ = (int) Math.round(origin.getZ() + distance * Math.sin(angle));
            }

            boolean launched = fallSeconds > 0
                    ? plugin.getMeteorManager().launchMeteor(selectedType, world, targetX, targetZ, fallSeconds)
                    : plugin.getMeteorManager().launchMeteor(selectedType, world, targetX, targetZ);
            if (launched) {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&a[MeteoriteDrop] Meteorito &e" + selectedType.getId() + " &alanzado hacia &eX: " + targetX +
                                "&a, &eZ: " + targetZ + " &aen el mundo &e" + world.getName() + "&a"
                                + (fallSeconds > 0 ? "; descenso: &e" + fallSeconds + " segundos&a." : ".")
                ));
            } else {
                sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                        "&cNo se pudo iniciar el evento del meteorito."
                ));
            }
            return true;
        }

        sendHelp(sender, label);
        return true;
    }

    private boolean buy(Player player,double price,String itemName){
        if(plugin.getEconomyManager()==null||!plugin.getEconomyManager().isAvailable()){player.sendMessage("§cLa tienda requiere Vault y una economía instalada.");return false;}
        if(price>0&&!plugin.getEconomyManager().withdraw(player,price)){player.sendMessage("§cNo tienes suficiente dinero para comprar "+itemName+".");return false;}
        player.sendMessage("§aCompra realizada: "+itemName+".");return true;
    }

    @Nullable
    private Integer parseFallSeconds(CommandSender sender, String raw) {
        try {
            int seconds = Integer.parseInt(raw);
            if (seconds < 1 || seconds > 120) {
                sender.sendMessage("§cEl tiempo de caída debe ser de 1 a 120 segundos.");
                return null;
            }
            return seconds;
        } catch (NumberFormatException ex) {
            sender.sendMessage("§eUso: /md drop [tipo] [jugador|x z] [segundos]. El tiempo debe ser un número de 1 a 120.");
            return null;
        }
    }
    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(
                "&6========== &eMeteoriteDrop &6==========\n" +
                        "&e/" + label + " gui &7- Abre el menú de gestión de meteoritos\n" +
                        "&e/" + label + " supply [jugador|x z] &7- Lanza un suministro normal (sin cráter)\n" +
                        "&e/" + label + " drop [tipo] &7- Lanza un meteorito cerca de ti\n" +
                        "&e/" + label + " drop [tipo] [jugador] &7- Lanza sobre un jugador\n" +
                        "&e/" + label + " drop [tipo] [x] [z] &7- Lanza a coordenadas específicas\n" +
                        "&e/" + label + " drop [tipo] [jugador|x z] [segundos] &7- Define la duración de caída (1-120s)\n" +
                        "&e/" + label + " shower [tipo] [cantidad] &7- Inicia una lluvia de meteoritos\n" +
                        "&e/" + label + " top &7- Ver clasificación de la temporada actual\n" +
                        "&e/" + label + " season &7- Información de la temporada\n" +
                        "&e/" + label + " season reset &7- Finalizar y premiar al campeón\n" +
                        "&e/" + label + " info &7- Lista todos los meteoritos activos\n" +
                        "&e/" + label + " tp [mundo,x,z] &7- Teletransportarse a un meteorito activo (ubicaciones con Tab)\n" +
                        "&e/" + label + " remove <x> <z> [mundo] &7- Elimina un meteorito activo y restaura el terreno\n" +
                        "&e/" + label + " history &7- Historial de los últimos meteoritos\n" +
                        "&e/" + label + " admin &7- Panel de gestión de meteoritos activos\n" +
                        "&e/" + label + " diagnose [x] [z] [mundo] &7- Diagnóstico del plugin y protecciones\n" +
                        "&e/" + label + " role <tank|sanador|explorador> &7- Elige un rol de combate cerca de un evento\n" +
                        "&e/" + label + " stats [top] &7- Ver estadísticas de combate\n" +
                        "&e/" + label + " reload &7- Recarga el archivo config.yml\n" +
                        "&6====================================="
        ));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("meteoritedrop.admin")) {
            if(args.length==1)return List.of("role","stats","shop","globaltop","teamtop").stream().filter(s->s.startsWith(args[0].toLowerCase())).toList();
            if(args.length==2&&args[0].equalsIgnoreCase("role"))return List.of("tank","sanador","explorador").stream().filter(s->s.startsWith(args[1].toLowerCase())).toList();
            if(args.length==2&&args[0].equalsIgnoreCase("stats"))return List.of("top");
            return List.of();
        }

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            if ("drop".startsWith(prefix)) completions.add("drop");
            if ("supply".startsWith(prefix)) completions.add("supply");
            if ("shower".startsWith(prefix)) completions.add("shower");
            if ("gui".startsWith(prefix)) completions.add("gui");
            if ("admin".startsWith(prefix)) completions.add("admin");
            if ("diagnose".startsWith(prefix)) completions.add("diagnose");
            if ("top".startsWith(prefix)) completions.add("top");
            if ("season".startsWith(prefix)) completions.add("season");
            if ("info".startsWith(prefix)) completions.add("info");
            if ("tp".startsWith(prefix)) completions.add("tp");
            if ("remove".startsWith(prefix)) completions.add("remove");
            if ("eliminar".startsWith(prefix)) completions.add("eliminar");
            if ("history".startsWith(prefix)) completions.add("history");
            if ("reload".startsWith(prefix)) completions.add("reload");
            if ("role".startsWith(prefix)) completions.add("role");
            if ("stats".startsWith(prefix)) completions.add("stats");
            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("supply")) {
            String prefix = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
            if (sender instanceof Player player) {
                completions.add(String.valueOf(player.getLocation().getBlockX()));
            }
            return completions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("supply")) {
            if (sender instanceof Player player) {
                completions.add(String.valueOf(player.getLocation().getBlockZ()));
            }
            return completions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("drop") || args[0].equalsIgnoreCase("shower"))) {
            String prefix = args[1].toLowerCase();
            for (String typeId : plugin.getMeteorTypeManager().getTypeIds()) {
                if (typeId.toLowerCase().startsWith(prefix)) {
                    completions.add(typeId);
                }
            }
            return completions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("drop") && plugin.getMeteorTypeManager().hasType(args[1])) {
            String prefix=args[2].toLowerCase();
            for (Player online : Bukkit.getOnlinePlayers()) if (online.getName().toLowerCase().startsWith(prefix)) completions.add(online.getName());
            for (String seconds : List.of("5","10","15","30","60")) if (seconds.startsWith(prefix)) completions.add(seconds);
            return completions;
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("drop") && plugin.getMeteorTypeManager().hasType(args[1])
                && Bukkit.getPlayerExact(args[2]) != null) {
            String prefix=args[3];
            return List.of("5","10","15","30","60").stream().filter(value->value.startsWith(prefix)).toList();
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("drop") && plugin.getMeteorTypeManager().hasType(args[1])) {
            String prefix=args[4];
            return List.of("5","10","15","30","60").stream().filter(value->value.startsWith(prefix)).toList();
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("event")) return List.of("meteoriteritual", "team");
        if (args.length == 2 && (args[0].equalsIgnoreCase("tp") || args[0].equalsIgnoreCase("teleport"))) {
            String prefix = args[1].toLowerCase();
            return plugin.getChestManager().getAllActiveChests().stream()
                    .filter(chest -> chest.getState() != MeteoriteChest.ChestState.RESTORED)
                    .map(MeteoriteChest::getChestLocation)
                    .filter(loc -> loc != null && loc.getWorld() != null)
                    .map(loc -> loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockZ())
                    .filter(location -> location.toLowerCase().startsWith(prefix))
                    .distinct()
                    .sorted()
                    .toList();
        }
        if (args.length == 4 && (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("eliminar"))) {
            String prefix = args[3].toLowerCase();
            return Bukkit.getWorlds().stream().map(World::getName).filter(name -> name.toLowerCase().startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role")) return List.of("tank","sanador","explorador");
        if (args.length == 2 && args[0].equalsIgnoreCase("stats")) return List.of("top");
        if (args.length == 2 && args[0].equalsIgnoreCase("discord")) return List.of("status","leaderboard");
        if (args.length == 3 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("team")) return List.of("start", "stop", "status");
        if (args.length == 3 && args[0].equalsIgnoreCase("event") && args[1].equalsIgnoreCase("meteoriteritual")) return List.of("start", "stop", "status");
        if (args.length == 2 && args[0].equalsIgnoreCase("discord") && "status".startsWith(args[1].toLowerCase())) return List.of("status");
        if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            String prefix = args[1].toLowerCase();
            if ("info".startsWith(prefix)) completions.add("info");
            if ("reset".startsWith(prefix)) completions.add("reset");
            return completions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("shower")) {
            return List.of("3", "5", "8");
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("drop")) {
            String prefix = args[2].toLowerCase();
            // Suggest online player names OR X coordinate
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(prefix)) {
                    completions.add(p.getName());
                }
            }
            if (sender instanceof Player player) {
                completions.add(String.valueOf(player.getLocation().getBlockX()));
            }
            return completions;
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("drop")) {
            if (sender instanceof Player player) {
                completions.add(String.valueOf(player.getLocation().getBlockZ()));
            }
            return completions;
        }

        return List.of();
    }
}
