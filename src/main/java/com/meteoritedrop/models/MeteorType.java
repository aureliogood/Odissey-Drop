package com.meteoritedrop.models;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model representing a configurable meteorite type and its visual, physical, and loot properties.
 */
public class MeteorType {

    /** Hard limits keep administrator configuration from creating unbounded world edits. */
    public static final int MAX_CRATER_RADIUS = 16;
    public static final int MAX_CRATER_DEPTH = 16;

    private final String id;
    private String displayName;
    private Material icon;
    private Material fallingBlock;
    private Material craterMain;
    private Material craterRim;
    private Material craterScatter;
    private int craterRadius;
    private int craterDepth;
    private int lockDurationSeconds;
    private int unlockDurationSeconds;
    private int fallDurationSeconds = 10;
    private double rewardMoney;
    private int rewardXp;
    private final List<ItemStack> loot = new ArrayList<>();

    public MeteorType(String id, String displayName, Material icon, Material fallingBlock,
                      Material craterMain, Material craterRim, Material craterScatter,
                      int craterRadius, int craterDepth, int lockDurationSeconds,
                      int unlockDurationSeconds, List<ItemStack> initialLoot) {
        this(id, displayName, icon, fallingBlock, craterMain, craterRim, craterScatter,
             craterRadius, craterDepth, lockDurationSeconds, unlockDurationSeconds, initialLoot, 0.0, 0);
    }

    public MeteorType(String id, String displayName, Material icon, Material fallingBlock,
                      Material craterMain, Material craterRim, Material craterScatter,
                      int craterRadius, int craterDepth, int lockDurationSeconds,
                      int unlockDurationSeconds, List<ItemStack> initialLoot,
                      double rewardMoney, int rewardXp) {
        this.id = id;
        this.displayName = displayName != null ? displayName : id;
        this.icon = icon != null ? icon : Material.MAGMA_BLOCK;
        this.fallingBlock = fallingBlock != null ? fallingBlock : Material.MAGMA_BLOCK;
        this.craterMain = craterMain != null ? craterMain : Material.NETHERRACK;
        this.craterRim = craterRim != null ? craterRim : Material.OBSIDIAN;
        this.craterScatter = craterScatter != null ? craterScatter : Material.MAGMA_BLOCK;
        this.craterRadius = clampCraterRadius(craterRadius);
        this.craterDepth = clampCraterDepth(craterDepth);
        this.lockDurationSeconds = Math.max(0, lockDurationSeconds);
        this.unlockDurationSeconds = Math.max(0, unlockDurationSeconds);
        this.rewardMoney = Math.max(0, rewardMoney);
        this.rewardXp = Math.max(0, rewardXp);
        if (initialLoot != null) {
            for (ItemStack item : initialLoot) {
                if (item != null && !item.getType().isAir()) {
                    this.loot.add(item.clone());
                }
            }
        }
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Material getIcon() {
        return icon;
    }

    public void setIcon(Material icon) {
        this.icon = icon;
    }

    public Material getFallingBlock() {
        return fallingBlock;
    }

    public void setFallingBlock(Material fallingBlock) {
        this.fallingBlock = fallingBlock;
    }

    public Material getCraterMain() {
        return craterMain;
    }

    public void setCraterMain(Material craterMain) {
        this.craterMain = craterMain;
    }

    public Material getCraterRim() {
        return craterRim;
    }

    public void setCraterRim(Material craterRim) {
        this.craterRim = craterRim;
    }

    public Material getCraterScatter() {
        return craterScatter;
    }

    public void setCraterScatter(Material craterScatter) {
        this.craterScatter = craterScatter;
    }

    public int getCraterRadius() {
        return craterRadius;
    }

    public void setCraterRadius(int craterRadius) {
        this.craterRadius = clampCraterRadius(craterRadius);
    }

    public int getCraterDepth() {
        return craterDepth;
    }

    public void setCraterDepth(int craterDepth) {
        this.craterDepth = clampCraterDepth(craterDepth);
    }

    private static int clampCraterRadius(int radius) {
        return Math.max(0, Math.min(MAX_CRATER_RADIUS, radius));
    }

    private static int clampCraterDepth(int depth) {
        return Math.max(0, Math.min(MAX_CRATER_DEPTH, depth));
    }

    public int getLockDurationSeconds() {
        return lockDurationSeconds;
    }

    public void setLockDurationSeconds(int lockDurationSeconds) {
        this.lockDurationSeconds = Math.max(0, lockDurationSeconds);
    }

    public int getUnlockDurationSeconds() {
        return unlockDurationSeconds;
    }

    public void setUnlockDurationSeconds(int unlockDurationSeconds) {
        this.unlockDurationSeconds = Math.max(1, unlockDurationSeconds);
    }

    public int getFallDurationSeconds() { return fallDurationSeconds; }
    public void setFallDurationSeconds(int fallDurationSeconds) { this.fallDurationSeconds = Math.max(1, Math.min(120, fallDurationSeconds)); }

    public List<ItemStack> getLoot() {
        return loot;
    }

    public List<ItemStack> generateLoot() {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack item : loot) {
            if (item != null && !item.getType().isAir()) {
                list.add(item.clone());
            }
        }
        return list;
    }

    public double getRewardMoney() { return rewardMoney; }
    public void setRewardMoney(double rewardMoney) { this.rewardMoney = Math.max(0, rewardMoney); }
    public int getRewardXp() { return rewardXp; }
    public void setRewardXp(int rewardXp) { this.rewardXp = Math.max(0, rewardXp); }

    public boolean isNormalDrop() {
        return "normal".equalsIgnoreCase(id) || "supply".equalsIgnoreCase(id);
    }
}
