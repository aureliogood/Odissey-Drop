package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;
import com.meteoritedrop.models.MeteorType;
import com.meteoritedrop.utils.AtomicYamlStorage;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Manages loading, saving, and querying meteorite types configured in config.yml.
 */
public class MeteorTypeManager {

    private final MeteoriteDrop plugin;
    private final Map<String, MeteorType> types = new ConcurrentHashMap<>();

    public MeteorTypeManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        loadTypes();
    }

    /**
     * Loads all configured meteor types from config.yml.
     */
    public synchronized void loadTypes() {
        types.clear();
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("meteor-types");

        if (section == null || section.getKeys(false).isEmpty()) {
            plugin.getLogger().info("No meteor types found in config.yml. Creating default 'lava' and 'ice' types.");
            createDefaultTypes();
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection typeSec = section.getConfigurationSection(key);
            if (typeSec == null) continue;

            String displayName = typeSec.getString("display-name", key);
            Material icon = parseItemMaterial(typeSec.getString("icon"), Material.MAGMA_BLOCK, key + ".icon");
            boolean normalDrop = key.equalsIgnoreCase("normal") || key.equalsIgnoreCase("supply");
            Material fallingBlock = parseBlockMaterial(typeSec.getString("falling-block"), Material.MAGMA_BLOCK, key + ".falling-block", false);
            Material craterMain = parseBlockMaterial(typeSec.getString("crater-main"), Material.NETHERRACK, key + ".crater-main", normalDrop);
            Material craterRim = parseBlockMaterial(typeSec.getString("crater-rim"), Material.OBSIDIAN, key + ".crater-rim", normalDrop);
            Material craterScatter = parseBlockMaterial(typeSec.getString("crater-scatter"), Material.MAGMA_BLOCK, key + ".crater-scatter", normalDrop);
            int craterRadius = typeSec.getInt("crater-radius", 5);
            int craterDepth = typeSec.getInt("crater-depth", 3);
            if (craterRadius > MeteorType.MAX_CRATER_RADIUS || craterRadius < 0) {
                plugin.getLogger().warning("Invalid crater radius for meteor type '" + key + "'; using a safe value between 0 and " + MeteorType.MAX_CRATER_RADIUS + ".");
            }
            if (craterDepth > MeteorType.MAX_CRATER_DEPTH || craterDepth < 0) {
                plugin.getLogger().warning("Invalid crater depth for meteor type '" + key + "'; using a safe value between 0 and " + MeteorType.MAX_CRATER_DEPTH + ".");
            }
            int lockDuration = typeSec.getInt("lock-duration-seconds", 120);
            int unlockDuration = typeSec.getInt("unlock-duration-seconds", 5);
            double rewardMoney = typeSec.getDouble("reward-money", 0.0);
            int rewardXp = typeSec.getInt("reward-xp", 0);

            List<?> rawLoot = typeSec.getList("loot");
            List<ItemStack> parsedLoot = parseSerializedLoot(rawLoot);

            MeteorType type = new MeteorType(
                    key.toLowerCase(Locale.ROOT),
                    displayName,
                    icon,
                    fallingBlock,
                    craterMain,
                    craterRim,
                    craterScatter,
                    craterRadius,
                    craterDepth,
                    lockDuration,
                    unlockDuration,
                    parsedLoot,
                    rewardMoney,
                    rewardXp
            );

            types.put(type.getId(), type);
            type.setFallDurationSeconds(typeSec.getInt("fall-duration-seconds", config.getInt("meteor-default-fall-seconds", 10)));
        }

        if (types.isEmpty()) {
            createDefaultTypes();
        } else {
            plugin.getLogger().info("Loaded " + types.size() + " meteorite type(s): " + String.join(", ", types.keySet()));
        }
    }

    private void createDefaultTypes() {
        // Default Lava Type
        List<ItemStack> lavaLoot = new ArrayList<>();
        lavaLoot.add(new ItemStack(Material.DIAMOND, 3));
        lavaLoot.add(new ItemStack(Material.EMERALD, 5));
        lavaLoot.add(new ItemStack(Material.GOLD_INGOT, 10));
        lavaLoot.add(new ItemStack(Material.IRON_INGOT, 16));
        lavaLoot.add(new ItemStack(Material.NETHERITE_SCRAP, 1));
        lavaLoot.add(new ItemStack(Material.GOLDEN_APPLE, 2));

        MeteorType lava = new MeteorType(
                "lava",
                "&c☄ Meteorito de Lava",
                Material.MAGMA_BLOCK,
                Material.MAGMA_BLOCK,
                Material.NETHERRACK,
                Material.OBSIDIAN,
                Material.MAGMA_BLOCK,
                5,
                3,
                120,
                5,
                lavaLoot
        );
        types.put("lava", lava);
        saveType(lava);

        // Default Ice Type
        List<ItemStack> iceLoot = new ArrayList<>();
        iceLoot.add(new ItemStack(Material.DIAMOND, 2));
        iceLoot.add(new ItemStack(Material.EMERALD, 3));
        iceLoot.add(new ItemStack(Material.SNOWBALL, 16));
        iceLoot.add(new ItemStack(Material.PACKED_ICE, 8));
        iceLoot.add(new ItemStack(Material.BLUE_ICE, 2));

        MeteorType ice = new MeteorType(
                "ice",
                "&b❄ Meteorito de Hielo",
                Material.PACKED_ICE,
                Material.PACKED_ICE,
                Material.SNOW_BLOCK,
                Material.PACKED_ICE,
                Material.ICE,
                5,
                3,
                90,
                5,
                iceLoot
        );
        types.put("ice", ice);
        saveType(ice);

        // Default End Type
        List<ItemStack> endLoot = new ArrayList<>();
        endLoot.add(new ItemStack(Material.ENDER_PEARL, 8));
        endLoot.add(new ItemStack(Material.DIAMOND, 4));
        endLoot.add(new ItemStack(Material.NETHERITE_SCRAP, 2));
        endLoot.add(new ItemStack(Material.SHULKER_SHELL, 4));
        endLoot.add(new ItemStack(Material.ELYTRA, 1));
        endLoot.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 16));

        MeteorType end = new MeteorType(
                "end",
                "&d🔮 Meteorito del End",
                Material.ENDER_EYE,
                Material.PURPUR_BLOCK,
                Material.END_STONE,
                Material.PURPUR_BLOCK,
                Material.CRYING_OBSIDIAN,
                6,
                3,
                150,
                6,
                endLoot
        );
        types.put("end", end);
        saveType(end);

        // Default Divine Type
        List<ItemStack> divineLoot = new ArrayList<>();
        divineLoot.add(new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 1));
        divineLoot.add(new ItemStack(Material.TOTEM_OF_UNDYING, 1));
        divineLoot.add(new ItemStack(Material.DIAMOND, 5));
        divineLoot.add(new ItemStack(Material.EMERALD_BLOCK, 2));
        divineLoot.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 32));

        MeteorType divine = new MeteorType(
                "divine",
                "&e⚡ Meteorito Celestial",
                Material.GOLD_BLOCK,
                Material.GOLD_BLOCK,
                Material.QUARTZ_BLOCK,
                Material.GOLD_BLOCK,
                Material.LAPIS_BLOCK,
                5,
                3,
                180,
                6,
                divineLoot
        );
        types.put("divine", divine);
        saveType(divine);

        // Default Toxic Type
        List<ItemStack> toxicLoot = new ArrayList<>();
        toxicLoot.add(new ItemStack(Material.NETHERITE_INGOT, 1));
        toxicLoot.add(new ItemStack(Material.DIAMOND, 4));
        toxicLoot.add(new ItemStack(Material.POISONOUS_POTATO, 5));
        toxicLoot.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 24));

        MeteorType toxic = new MeteorType(
                "toxic",
                "&2☣ Meteorito Corrupto",
                Material.SCULK_CATALYST,
                Material.SCULK,
                Material.SCULK,
                Material.CRYING_OBSIDIAN,
                Material.SCULK_VEIN,
                6,
                4,
                120,
                5,
                toxicLoot
        );
        types.put("toxic", toxic);
        saveType(toxic);

        // Default Storm Type
        List<ItemStack> stormLoot = new ArrayList<>();
        stormLoot.add(new ItemStack(Material.TRIDENT, 1));
        stormLoot.add(new ItemStack(Material.DIAMOND, 3));
        stormLoot.add(new ItemStack(Material.COPPER_INGOT, 32));
        stormLoot.add(new ItemStack(Material.IRON_BLOCK, 2));

        MeteorType storm = new MeteorType(
                "storm",
                "&9⚡ Meteorito de Tormenta",
                Material.LIGHTNING_ROD,
                Material.COPPER_BLOCK,
                Material.OXIDIZED_COPPER,
                Material.LIGHTNING_ROD,
                Material.WEATHERED_COPPER,
                5,
                3,
                100,
                5,
                stormLoot
        );
        types.put("storm", storm);
        saveType(storm);

        // Default Cursed Type (Maldito)
        List<ItemStack> cursedLoot = new ArrayList<>();
        cursedLoot.add(new ItemStack(Material.NETHERITE_INGOT, 1));
        cursedLoot.add(new ItemStack(Material.WITHER_SKELETON_SKULL, 1));
        cursedLoot.add(new ItemStack(Material.DIAMOND, 5));
        cursedLoot.add(new ItemStack(Material.GOLDEN_APPLE, 3));
        cursedLoot.add(new ItemStack(Material.OBSIDIAN, 16));

        MeteorType cursed = new MeteorType(
                "cursed",
                "☠ Meteorito Maldito",
                Material.WITHER_SKELETON_SKULL,
                Material.SOUL_SAND,
                Material.SOUL_SAND,
                Material.OBSIDIAN,
                Material.SOUL_SOIL,
                7,
                4,
                150,
                5,
                cursedLoot
        );
        types.put("cursed", cursed);
        saveType(cursed);

        // Default Abyssal Type (Abisal)
        List<ItemStack> abyssalLoot = new ArrayList<>();
        abyssalLoot.add(new ItemStack(Material.HEART_OF_THE_SEA, 1));
        abyssalLoot.add(new ItemStack(Material.TRIDENT, 1));
        abyssalLoot.add(new ItemStack(Material.DIAMOND, 4));
        abyssalLoot.add(new ItemStack(Material.PRISMARINE_CRYSTALS, 16));
        abyssalLoot.add(new ItemStack(Material.NAUTILUS_SHELL, 3));

        MeteorType abyssal = new MeteorType(
                "abyssal",
                "🌊 Meteorito Abisal",
                Material.PRISMARINE,
                Material.PRISMARINE,
                Material.DARK_PRISMARINE,
                Material.PRISMARINE_BRICKS,
                Material.SEA_LANTERN,
                6,
                4,
                120,
                5,
                abyssalLoot
        );
        types.put("abyssal", abyssal);
        saveType(abyssal);

        // Default Corrupted Type (Corrupto)
        List<ItemStack> corruptedLoot = new ArrayList<>();
        corruptedLoot.add(new ItemStack(Material.ECHO_SHARD, 3));
        corruptedLoot.add(new ItemStack(Material.SHULKER_SHELL, 2));
        corruptedLoot.add(new ItemStack(Material.DIAMOND, 4));
        corruptedLoot.add(new ItemStack(Material.SCULK_CATALYST, 2));
        corruptedLoot.add(new ItemStack(Material.EMERALD, 8));

        MeteorType corrupted = new MeteorType(
                "corrupted",
                "🍄 Meteorito Corrupto",
                Material.SCULK,
                Material.SCULK,
                Material.SCULK,
                Material.SCULK_CATALYST,
                Material.MYCELIUM,
                6,
                3,
                100,
                5,
                corruptedLoot
        );
        types.put("corrupted", corrupted);
        saveType(corrupted);

        // Default Normal Supply Drop Type (Drop Normal sin cráter)
        List<ItemStack> normalLoot = new ArrayList<>();
        normalLoot.add(new ItemStack(Material.DIAMOND, 3));
        normalLoot.add(new ItemStack(Material.IRON_INGOT, 12));
        normalLoot.add(new ItemStack(Material.GOLD_INGOT, 8));
        normalLoot.add(new ItemStack(Material.GOLDEN_APPLE, 2));
        normalLoot.add(new ItemStack(Material.ENDER_PEARL, 4));
        normalLoot.add(new ItemStack(Material.EXPERIENCE_BOTTLE, 8));
        normalLoot.add(new ItemStack(Material.COOKED_BEEF, 16));

        MeteorType normalDrop = new MeteorType(
                "normal",
                "&e📦 Suministro Normal",
                Material.CHEST,
                Material.CHEST,
                Material.AIR,
                Material.AIR,
                Material.AIR,
                0,
                0,
                60,
                0,
                normalLoot,
                300.0,
                50
        );
        types.put("normal", normalDrop);
        saveType(normalDrop);
    }

    /**
     * Saves a specific MeteorType to the configuration file immediately.
     */
    public synchronized void saveType(MeteorType type) {
        if (type == null) return;

        FileConfiguration config = plugin.getConfig();
        String path = "meteor-types." + type.getId();

        config.set(path + ".display-name", type.getDisplayName());
        config.set(path + ".icon", type.getIcon().name());
        config.set(path + ".falling-block", type.getFallingBlock().name());
        config.set(path + ".crater-main", type.getCraterMain().name());
        config.set(path + ".crater-rim", type.getCraterRim().name());
        config.set(path + ".crater-scatter", type.getCraterScatter().name());
        config.set(path + ".crater-radius", type.getCraterRadius());
        config.set(path + ".crater-depth", type.getCraterDepth());
        config.set(path + ".lock-duration-seconds", type.getLockDurationSeconds());
        config.set(path + ".unlock-duration-seconds", type.getUnlockDurationSeconds());
        config.set(path + ".fall-duration-seconds", type.getFallDurationSeconds());
        config.set(path + ".reward-money", type.getRewardMoney());
        config.set(path + ".reward-xp", type.getRewardXp());

        List<ItemStack> lootItems = new ArrayList<>();
        for (ItemStack item : type.getLoot()) {
            if (item != null && !item.getType().isAir()) {
                lootItems.add(item.clone());
            }
        }
        // Bukkit's ItemStack serialization preserves names, lore, enchants, attributes and PDC.
        config.set(path + ".loot", lootItems);

        try {
            AtomicYamlStorage.save((YamlConfiguration) config, new java.io.File(plugin.getDataFolder(), "config.yml"));
        } catch (java.io.IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not safely save config.yml after editing meteor type '" + type.getId() + "'.", e);
        }
    }

    public synchronized void saveAll() {
        for (MeteorType type : types.values()) {
            saveType(type);
        }
    }

    public MeteorType getType(String id) {
        if (id == null) return null;
        return types.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean hasType(String id) {
        if (id == null) return false;
        return types.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public Collection<MeteorType> getTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

    public List<String> getTypeIds() {
        return new ArrayList<>(types.keySet());
    }

    public MeteorType getRandomType() {
        // Normal/supply drops are intentionally craterless and are launched explicitly;
        // random meteor launches should always resolve to a crater meteorite.
        List<MeteorType> all = types.values().stream().filter(type -> !type.isNormalDrop()).toList();
        if (all.isEmpty()) return null;
        List<String> rareIds = plugin.getConfig().getStringList("meteor-rarity.rare-types");
        List<MeteorType> rare = all.stream().filter(type -> rareIds.stream().anyMatch(id -> id.equalsIgnoreCase(type.getId()))).toList();
        int chance = Math.max(0, Math.min(100, plugin.getConfig().getInt("meteor-rarity.chance-percent", 8)));
        if (plugin.getConfig().getBoolean("meteor-rarity.enabled", true) && !rare.isEmpty() && ThreadLocalRandom.current().nextInt(100) < chance) {
            return rare.get(ThreadLocalRandom.current().nextInt(rare.size()));
        }
        List<MeteorType> common = all.stream().filter(type -> rareIds.stream().noneMatch(id -> id.equalsIgnoreCase(type.getId()))).toList();
        if (common.isEmpty()) common = all;
        return common.get(ThreadLocalRandom.current().nextInt(common.size()));
    }
    private Material parseMaterial(String name, Material def) {
        if (name == null || name.isBlank()) return def;
        Material mat = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (mat == null) {
            mat = Material.getMaterial(name.trim().toUpperCase(Locale.ROOT));
        }
        return mat != null ? mat : def;
    }

    private Material parseItemMaterial(String name, Material fallback, String setting) {
        Material material = parseMaterial(name, fallback);
        if (material == null || !material.isItem() || material.isAir()) {
            plugin.getLogger().warning("Invalid item material at meteor-types." + setting + "; using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private Material parseBlockMaterial(String name, Material fallback, String setting, boolean allowAir) {
        Material material = parseMaterial(name, fallback);
        boolean fluid = material == Material.WATER || material == Material.LAVA;
        if (material == null || !material.isBlock() || (!allowAir && material.isAir()) || fluid) {
            plugin.getLogger().warning("Invalid block material at meteor-types." + setting + "; using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private List<ItemStack> parseLootList(List<String> rawList) {
        List<ItemStack> list = new ArrayList<>();
        if (rawList == null) return list;

        for (String entry : rawList) {
            if (entry == null || entry.isBlank()) continue;
            int separator = entry.lastIndexOf(':');
            String materialName = separator < 0 ? entry : entry.substring(0, separator);
            String amountSpec = separator < 0 ? "1" : entry.substring(separator + 1);
            Material mat = parseMaterial(materialName, null);
            if (mat == null || !mat.isItem()) {
                plugin.getLogger().log(Level.WARNING, "Invalid loot material specified in config: " + materialName);
                continue;
            }

            int amount = 1;
            if (!amountSpec.isBlank()) {
                String countPart = amountSpec.trim();
                if (countPart.matches("\\d+\\s*-\\s*\\d+")) {
                    String[] range = countPart.split("-", 2);
                    try {
                        int min = Integer.parseInt(range[0].trim());
                        int max = Integer.parseInt(range[1].trim());
                        if (min < 1 || max < min) throw new IllegalArgumentException("range must satisfy 1 <= min <= max");
                        amount = (int) ThreadLocalRandom.current().nextLong(min, (long) max + 1L);
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid loot amount range '" + countPart + "'; using 1.");
                        amount = 1;
                    } catch (IllegalArgumentException e) {
                        plugin.getLogger().warning("Invalid loot amount range '" + countPart + "'; using 1.");
                        amount = 1;
                    }
                } else {
                    try {
                        amount = Integer.parseInt(countPart);
                    } catch (NumberFormatException e) {
                        plugin.getLogger().warning("Invalid loot amount '" + countPart + "'; using 1.");
                        amount = 1;
                    }
                }
            }

            amount = Math.min(mat.getMaxStackSize(), Math.max(1, amount));
            list.add(new ItemStack(mat, amount));
        }

        return list;
    }

    private List<ItemStack> parseSerializedLoot(List<?> rawList) {
        List<ItemStack> list = new ArrayList<>();
        if (rawList == null) return list;
        for (Object entry : rawList) {
            if (entry instanceof ItemStack item) {
                if (!item.getType().isAir() && item.getAmount() > 0) list.add(item.clone());
            } else if (entry instanceof Map<?, ?> map) {
                try {
                    Map<String, Object> serialized = new HashMap<>();
                    map.forEach((key, value) -> {
                        if (key instanceof String stringKey) serialized.put(stringKey, value);
                    });
                    ItemStack item = ItemStack.deserialize(serialized);
                    if (!item.getType().isAir() && item.getAmount() > 0) list.add(item);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Invalid serialized loot entry; skipping it: " + ex.getMessage());
                }
            } else if (entry instanceof String legacy) {
                list.addAll(parseLootList(List.of(legacy)));
            } else if (entry != null) {
                plugin.getLogger().warning("Unsupported loot entry type " + entry.getClass().getSimpleName() + "; skipping it.");
            }
        }
        return list;
    }
}
