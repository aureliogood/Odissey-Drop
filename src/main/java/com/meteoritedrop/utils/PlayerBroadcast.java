package com.meteoritedrop.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Sends announcements to players without echoing them to the console. */
public final class PlayerBroadcast {
    private PlayerBroadcast() {}
    public static void send(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(message);
    }
    public static void send(String message) {
        for (Player player : Bukkit.getOnlinePlayers()) player.sendMessage(message);
    }
}
