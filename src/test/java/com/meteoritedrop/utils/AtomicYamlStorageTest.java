package com.meteoritedrop.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtomicYamlStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void replacesExistingYamlWithoutLosingNewValues() throws Exception {
        File file = tempDir.resolve("state.yml").toFile();
        YamlConfiguration first = new YamlConfiguration();
        first.set("state", "old");
        AtomicYamlStorage.save(first, file);

        YamlConfiguration replacement = YamlConfiguration.loadConfiguration(file);
        replacement.set("state", "new");
        replacement.set("count", 7);
        AtomicYamlStorage.save(replacement, file);

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        assertEquals("new", loaded.getString("state"));
        assertEquals(7, loaded.getInt("count"));
    }
}
