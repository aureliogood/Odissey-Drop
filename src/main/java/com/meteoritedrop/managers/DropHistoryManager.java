package com.meteoritedrop.managers;

import com.meteoritedrop.MeteoriteDrop;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;
import com.meteoritedrop.utils.AtomicYamlStorage;
import java.util.logging.Level;
import org.bukkit.Location;

/**
 * Tracks the last N meteorite drop events for display via /md history.
 */
public class DropHistoryManager {

    public record DropRecord(String meteorType, int x, int z, String worldName, String timestamp, String winner) {}

    private final MeteoriteDrop plugin;
    private final Deque<DropRecord> history = new ArrayDeque<>();
    private static final int MAX_SIZE = 100;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private final File file;
    private YamlConfiguration data;

    public DropHistoryManager(MeteoriteDrop plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "drop-history.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        load();
    }

    /**
     * Records a new meteorite drop event.
     */
    public synchronized void record(String meteorType, int x, int z, String worldName) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        history.addFirst(new DropRecord(meteorType, x, z, worldName, timestamp, "Sin abrir"));
        while (history.size() > MAX_SIZE) {
            history.removeLast();
        }
        save();
    }

    /**
     * Updates the winner of the most recent drop at the given coordinates.
     */
    public synchronized void setWinner(Location location, String winnerName) {
        if (location == null || location.getWorld() == null) return;
        List<DropRecord> snapshot = new ArrayList<>(history);
        history.clear();
        boolean updated = false;
        for (DropRecord rec : snapshot) {
            if (!updated && rec.x() == location.getBlockX() && rec.z() == location.getBlockZ()
                    && rec.worldName().equalsIgnoreCase(location.getWorld().getName())
                    && rec.winner().equals("Sin abrir")) {
                history.addLast(new DropRecord(rec.meteorType(), rec.x(), rec.z(), rec.worldName(), rec.timestamp(), winnerName));
                updated = true;
            } else {
                history.addLast(rec);
            }
        }
        save();
    }

    /** Compatibility overload for older integrations; world-aware callers should use Location. */
    @Deprecated
    public synchronized void setWinner(int x, int z, String winnerName) {
        List<DropRecord> snapshot = new ArrayList<>(history);
        for (DropRecord record : snapshot) {
            if (record.x() == x && record.z() == z && record.winner().equals("Sin abrir")) {
                setWinner(record.worldName(), x, z, winnerName);
                return;
            }
        }
    }

    private synchronized void setWinner(String worldName, int x, int z, String winnerName) {
        List<DropRecord> snapshot = new ArrayList<>(history);
        history.clear();
        boolean updated=false;
        for (DropRecord rec : snapshot) {
            if (!updated && rec.x()==x && rec.z()==z && rec.worldName().equalsIgnoreCase(worldName) && rec.winner().equals("Sin abrir")) {
                history.addLast(new DropRecord(rec.meteorType(),rec.x(),rec.z(),rec.worldName(),rec.timestamp(),winnerName)); updated=true;
            } else history.addLast(rec);
        }
        if(updated) save();
    }

    /**
     * Returns a copy of the drop history list (most recent first).
     */
    public synchronized List<DropRecord> getHistory() {
        return new ArrayList<>(history);
    }

    private void load() {
        var section = data.getConfigurationSection("records");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String path="records."+key;
            history.addLast(new DropRecord(data.getString(path+".type","unknown"), data.getInt(path+".x"), data.getInt(path+".z"), data.getString(path+".world","world"), data.getString(path+".time",""), data.getString(path+".winner","Sin abrir")));
        }
        while(history.size()>MAX_SIZE) history.removeLast();
    }
    private void save() {
        YamlConfiguration next = new YamlConfiguration(); int i=0;
        for (DropRecord r : history) {
            String p="records."+(i++); next.set(p+".type",r.meteorType()); next.set(p+".x",r.x()); next.set(p+".z",r.z()); next.set(p+".world",r.worldName()); next.set(p+".time",r.timestamp()); next.set(p+".winner",r.winner());
        }
        try { AtomicYamlStorage.save(next,file); data=next; }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE,"No se pudo guardar drop-history.yml",e); }
    }
}
