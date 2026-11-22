package com.meteoritedrop.commands;

import com.meteoritedrop.MeteoriteDrop;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class MissionCommand implements CommandExecutor {
    private final MeteoriteDrop plugin;
    public MissionCommand(MeteoriteDrop plugin){this.plugin=plugin;}
    @Override public boolean onCommand(@NotNull CommandSender sender,@NotNull Command command,@NotNull String label,@NotNull String[] args){
        if(!(sender instanceof Player player)){sender.sendMessage("Este comando solo puede usarlo un jugador.");return true;}
        plugin.getMissionsManager().openMenu(player);return true;
    }
}
