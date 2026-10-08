package dev.para.holowaypoint.storage;

import dev.para.holowaypoint.model.Waypoint;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class WaypointRepository {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Waypoint> waypoints = new LinkedHashMap<>();

    public WaypointRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "waypoints.yml");
    }

    public void load() {
        waypoints.clear();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("waypoints");
        if (root == null) {
            return;
        }

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                plugin.getLogger().warning("Skipping invalid waypoint entry '" + key + "'.");
                continue;
            }

            String world = section.getString("world");
            double x = section.getDouble("x", Double.NaN);
            double y = section.getDouble("y", Double.NaN);
            double z = section.getDouble("z", Double.NaN);
            try {
                boolean messageEnabled = section.getBoolean("arrival-message.enabled", true);
                String message = section.getString("arrival-message.text", Waypoint.DEFAULT_ARRIVAL_MESSAGE);
                if (message == null || message.isBlank()) {
                    message = Waypoint.DEFAULT_ARRIVAL_MESSAGE;
                }
                Waypoint waypoint = new Waypoint(key.toLowerCase(Locale.ROOT), world, x, y, z,
                        messageEnabled, message);
                waypoints.put(waypoint.name(), waypoint);
            } catch (IllegalArgumentException | NullPointerException exception) {
                plugin.getLogger().warning("Skipping invalid waypoint entry '" + key + "': "
                        + exception.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Waypoint waypoint : waypoints.values()) {
            String path = "waypoints." + waypoint.name();
            yaml.set(path + ".world", waypoint.world());
            yaml.set(path + ".x", waypoint.x());
            yaml.set(path + ".y", waypoint.y());
            yaml.set(path + ".z", waypoint.z());
            yaml.set(path + ".arrival-message.enabled", waypoint.arrivalMessageEnabled());
            yaml.set(path + ".arrival-message.text", waypoint.arrivalMessage());
        }

        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save waypoints.yml: " + exception.getMessage());
        }
    }

    public boolean put(Waypoint waypoint) {
        boolean replaced = waypoints.put(waypoint.name(), waypoint) != null;
        save();
        return replaced;
    }

    public boolean remove(String name) {
        if (waypoints.remove(name) == null) {
            return false;
        }
        save();
        return true;
    }

    public Waypoint get(String name) {
        return waypoints.get(name);
    }

    public Collection<Waypoint> all() {
        return Collections.unmodifiableList(new ArrayList<>(waypoints.values()));
    }
}