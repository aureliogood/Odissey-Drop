package com.meteoritedrop;

import com.meteoritedrop.commands.MeteoriteCommand;
import com.meteoritedrop.commands.MissionCommand;
import com.meteoritedrop.gui.GuiListener;
import com.meteoritedrop.gui.GuiManager;
import com.meteoritedrop.listeners.ChestListener;
import com.meteoritedrop.listeners.MissionMenuListener;
import com.meteoritedrop.listeners.RitualListener;
import com.meteoritedrop.listeners.AdminPanelListener;
import com.meteoritedrop.managers.AutoDropManager;
import com.meteoritedrop.managers.ChestManager;
import com.meteoritedrop.managers.ConfigManager;
import com.meteoritedrop.managers.CraterEffectManager;
import com.meteoritedrop.managers.CraterRecoveryManager;
import com.meteoritedrop.managers.ProtectionManager;
import com.meteoritedrop.managers.DiscordChatBridge;
import com.meteoritedrop.managers.DiscordManager;
import com.meteoritedrop.managers.DropHistoryManager;
import com.meteoritedrop.managers.EconomyManager;
import com.meteoritedrop.managers.GuardianManager;
import com.meteoritedrop.managers.MeteorManager;
import com.meteoritedrop.managers.MeteorTypeManager;
import com.meteoritedrop.managers.MissionsManager;
import com.meteoritedrop.managers.MeteorEventManager;
import com.meteoritedrop.managers.KeyManager;
import com.meteoritedrop.managers.RitualManager;
import com.meteoritedrop.managers.StatsManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.logging.Level;

/**
 * Main entry point for the MeteoriteDrop Paper plugin.
 */
public class MeteoriteDrop extends JavaPlugin {

    private static MeteoriteDrop instance;

    private ConfigManager configManager;
    private MeteorTypeManager meteorTypeManager;
    private ChestManager chestManager;
    private MeteorManager meteorManager;
    private GuiManager guiManager;
    private GuardianManager guardianManager;
    private StatsManager statsManager;
    private AutoDropManager autoDropManager;
    private CraterEffectManager craterEffectManager;
    private DropHistoryManager dropHistoryManager;
    private EconomyManager economyManager;
    private DiscordManager discordManager;
    private DiscordChatBridge discordChatBridge;
    private CraterRecoveryManager craterRecoveryManager;
    private ProtectionManager protectionManager;
    private MissionsManager missionsManager;
    private RitualManager ritualManager;
    private KeyManager keyManager;
    private MeteorEventManager meteorEventManager;
    private volatile boolean shuttingDown;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Initialize configuration and meteor types
        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.craterRecoveryManager = new CraterRecoveryManager(this);
        this.craterRecoveryManager.restorePending();
        this.protectionManager = new ProtectionManager(this);
        this.meteorTypeManager = new MeteorTypeManager(this);

        // 2. Initialize feature and GUI managers
        this.chestManager = new ChestManager(this);
        this.meteorManager = new MeteorManager(this);
        this.guiManager = new GuiManager(this);

        // 3. Initialize extended managers
        this.guardianManager = new GuardianManager(this);
        this.statsManager = new StatsManager(this);
        this.craterEffectManager = new CraterEffectManager(this);
        this.autoDropManager = new AutoDropManager(this);
        this.dropHistoryManager = new DropHistoryManager(this);
        this.economyManager = new EconomyManager(this);
        this.missionsManager = new MissionsManager(this);
        this.ritualManager = new RitualManager(this);
        this.keyManager = new KeyManager(this);
        this.meteorEventManager = new MeteorEventManager(this);
        cleanupTransientEntitiesFromInterruptedEvents();
        this.discordChatBridge = new DiscordChatBridge(this);
        Bukkit.getPluginManager().registerEvents(discordChatBridge, this);
        this.discordChatBridge.start();
        this.discordManager = new DiscordManager(this);

        // 4. Register commands
        PluginCommand command = getCommand("meteoritedrop");
        if (command != null) {
            MeteoriteCommand cmdExecutor = new MeteoriteCommand(this);
            command.setExecutor(cmdExecutor);
            command.setTabCompleter(cmdExecutor);
        } else {
            getLogger().log(Level.SEVERE, "Failed to register command 'meteoritedrop'. Check plugin.yml configuration.");
        }

        PluginCommand missionCommand = getCommand("meteorito");
        if (missionCommand != null) missionCommand.setExecutor(new MissionCommand(this));

        // 5. Register event listeners
        Bukkit.getPluginManager().registerEvents(new ChestListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AdminPanelListener(this), this);
        Bukkit.getPluginManager().registerEvents(new MissionMenuListener(this), this);
        Bukkit.getPluginManager().registerEvents(new RitualListener(this), this);
        Bukkit.getPluginManager().registerEvents(guardianManager, this);
        Bukkit.getPluginManager().registerEvents(meteorEventManager, this);

        getLogger().info("==================================================");
        getLogger().info(" MeteoriteDrop v" + getPluginMeta().getVersion() + " has been ENABLED!");
        getLogger().info(" Developed for Paper 1.21.x");
        getLogger().info("==================================================");
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        getLogger().info("Shutting down MeteoriteDrop and cleaning up active events...");

        if (discordChatBridge != null) discordChatBridge.shutdown();

        // 1. Cleanup extended managers
        if (autoDropManager != null) autoDropManager.cleanup();
        if (craterEffectManager != null) craterEffectManager.cleanup();
        if (guardianManager != null) guardianManager.cleanup();
        if (meteorEventManager != null) meteorEventManager.cleanup();
        if (statsManager != null) statsManager.saveStats();

        // 2. Cleanup all active chest sessions, bossbars, and holograms
        if (chestManager != null) chestManager.cleanup();

        // 3. Cleanup active falling meteors and their tasks
        if (meteorManager != null) meteorManager.cleanup();

        // 4. Cancel any remaining plugin tasks
        Bukkit.getScheduler().cancelTasks(this);

        instance = null;
        getLogger().info("MeteoriteDrop disabled successfully.");
    }

    public static MeteoriteDrop getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public MeteorTypeManager getMeteorTypeManager() { return meteorTypeManager; }
    public ChestManager getChestManager() { return chestManager; }
    public MeteorManager getMeteorManager() { return meteorManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public GuardianManager getGuardianManager() { return guardianManager; }
    public StatsManager getStatsManager() { return statsManager; }
    public AutoDropManager getAutoDropManager() { return autoDropManager; }
    public CraterEffectManager getCraterEffectManager() { return craterEffectManager; }
    public DropHistoryManager getDropHistoryManager() { return dropHistoryManager; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public DiscordManager getDiscordManager() { return discordManager; }
    public DiscordChatBridge getDiscordChatBridge() { return discordChatBridge; }
    public CraterRecoveryManager getCraterRecoveryManager() { return craterRecoveryManager; }
    public ProtectionManager getProtectionManager() { return protectionManager; }
    public MissionsManager getMissionsManager() { return missionsManager; }
    public RitualManager getRitualManager() { return ritualManager; }
    public KeyManager getKeyManager() { return keyManager; }
    public MeteorEventManager getMeteorEventManager() { return meteorEventManager; }
    public boolean isShuttingDown() { return shuttingDown; }

    /**
     * Active meteor/chest state is intentionally not reconstructed after a process crash.
     * Remove its tagged entities from loaded chunks so falling blocks, holograms and guardians
     * cannot remain in the world without a tracked event or loot inventory.
     */
    private void cleanupTransientEntitiesFromInterruptedEvents() {
        Set<String> transientTags = Set.of(
                "meteoritedrop_falling",
                "meteoritedrop_guardian",
                "meteoritedrop_chest_visual",
                "meteoritedrop_hologram",
                "meteoritedrop_chest_interaction",
                "meteoritedrop_coop"
        );
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getScoreboardTags().stream().anyMatch(transientTags::contains)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            getLogger().warning("Removed " + removed + " orphaned MeteoriteDrop entities from loaded chunks after startup.");
        }
    }
}


