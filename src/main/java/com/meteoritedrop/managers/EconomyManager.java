package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Wraps Vault's Economy API as a soft dependency.
 * All methods are null-safe — if Vault is not installed, rewards are silently skipped.
 */
public class EconomyManager {

    private final MeteoriteDrop plugin;
    private Economy economy;
    private final boolean available;

    public EconomyManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.available = setupEconomy();
    }

    private boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().info("[EconomyManager] Vault no encontrado — recompensas económicas desactivadas.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            plugin.getLogger().info("[EconomyManager] No hay proveedor de economía registrado en Vault.");
            return false;
        }
        economy = rsp.getProvider();
        plugin.getLogger().info("[EconomyManager] ¡Vault detectado! Recompensas económicas activadas con: " + economy.getName());
        return true;
    }

    /**
     * Returns true if Vault and an economy provider are available.
     */
    public boolean isAvailable() {
        return available && economy != null;
    }

    /**
     * Deposits the given amount into a player's account.
     * @return true if successful, false if Vault is unavailable or transaction failed
     */
    public boolean deposit(Player player, double amount) {
        if (!isAvailable() || player == null || amount <= 0) return false;
        try {
            economy.depositPlayer(player, amount);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[EconomyManager] Error al dar dinero a " + player.getName() + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Formats an amount as a currency string using Vault's formatter.
     */
    public boolean withdraw(Player player,double amount){
        if(!isAvailable()||player==null||amount<=0)return false;
        try{return economy.withdrawPlayer(player,amount).transactionSuccess();}catch(Exception e){plugin.getLogger().warning("[EconomyManager] Error al cobrar a "+player.getName()+": "+e.getMessage());return false;}
    }

    public String format(double amount) {
        if (!isAvailable()) return String.valueOf(amount);
        return economy.format(amount);
    }
}
