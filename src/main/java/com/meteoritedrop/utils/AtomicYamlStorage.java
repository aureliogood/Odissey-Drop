package com.meteoritedrop.utils;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes YAML beside the target and swaps it in only after serialization completes. */
public final class AtomicYamlStorage {
    private AtomicYamlStorage() {}

    public static void save(YamlConfiguration configuration, File file) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, file.getName(), ".tmp");
        try {
            configuration.save(temporary.toFile());
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
