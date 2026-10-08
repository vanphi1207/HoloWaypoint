package dev.para.holowaypoint.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TrackingRepository {

    private final JavaPlugin plugin;
    private final File file;

    public TrackingRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tracking.yml");
    }

    public Map<UUID, Set<String>> load(WaypointRepository waypointRepository) {
        Map<UUID, Set<String>> tracking = new HashMap<>();
        if (!file.isFile()) {
            return tracking;
        }

        FileConfiguration saved = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = saved.getConfigurationSection("players");
        if (players == null) {
            return tracking;
        }

        for (String playerKey : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerKey);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("UUID người chơi không hợp lệ trong tracking.yml: "
                        + playerKey);
                continue;
            }

            Set<String> names = new HashSet<>();
            for (String name : saved.getStringList("players." + playerKey)) {
                if (waypointRepository.get(name) != null) {
                    names.add(name);
                } else {
                    plugin.getLogger().warning("Bỏ waypoint không tồn tại '" + name
                            + "' trong tracking.yml của " + playerKey + ".");
                }
            }
            if (!names.isEmpty()) {
                tracking.put(playerId, names);
            }
        }
        return tracking;
    }

    public void save(Map<UUID, Set<String>> tracking) {
        YamlConfiguration saved = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : tracking.entrySet()) {
            List<String> names = new ArrayList<>(entry.getValue());
            names.sort(String::compareTo);
            saved.set("players." + entry.getKey(), names);
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Không thể tạo thư mục " + parent.getPath());
            }
            saved.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Không thể lưu tracking.yml: " + exception.getMessage());
        }
    }
}
