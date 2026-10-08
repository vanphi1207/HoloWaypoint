package dev.para.holowaypoint.resourcepack;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ResourcePackService implements Listener {

    private static final List<HostProvider> HOST_PROVIDERS = List.of(
            new HostProvider("nexo", "Nexo"),
            new HostProvider("itemsadder", "ItemsAdder"),
            new HostProvider("oraxen", "Oraxen"));

    private final JavaPlugin plugin;
    private final File knownPacksFile;

    private final Map<UUID, Map<String, Set<UUID>>> loadedPacks = new HashMap<>();

    private boolean warnedMissingHost;
    private String warnedInvalidHost;
    private boolean warnedLegacyMode;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.knownPacksFile = new File(plugin.getDataFolder(), "resource-packs.yml");
        loadKnownPacks();
        warnAboutLegacyMode();
    }

    public boolean hasIconPack(Player player) {
        String host = activeHost();
        if (host == null) {
            return false;
        }
        Map<String, Set<UUID>> packsByHost = loadedPacks.get(player.getUniqueId());
        Set<UUID> packs = packsByHost == null ? null : packsByHost.get(host);
        return packs != null && !packs.isEmpty();
    }

    public void reload() {
        warnedMissingHost = false;
        warnedInvalidHost = null;
        warnedLegacyMode = false;
        warnAboutLegacyMode();
        activeHost();
    }

    public void shutdown() {
        saveKnownPacks();
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        UUID packId = event.getID();
        if (packId == null) {
            return;
        }
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                String host = activeHost();
                if (host == null) {
                    return;
                }
                boolean changed = loadedPacks
                        .computeIfAbsent(playerId, ignored -> new HashMap<>())
                        .computeIfAbsent(host, ignored -> new HashSet<>())
                        .add(packId);
                if (changed) {
                    saveKnownPacks();
                }
            }
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                if (removePack(playerId, packId)) {
                    saveKnownPacks();
                }
            }
            default -> {
            }
        }
    }

    private String activeHost() {
        String configured = plugin.getConfig().getString("resource-pack.host", "auto");
        if (configured == null || configured.isBlank()
                || configured.trim().equalsIgnoreCase("auto")) {
            for (HostProvider provider : HOST_PROVIDERS) {
                if (plugin.getServer().getPluginManager().isPluginEnabled(provider.pluginName())) {
                    warnedMissingHost = false;
                    return provider.key();
                }
            }
            if (!warnedMissingHost) {
                plugin.getLogger().warning("Không tìm thấy host resource pack được hỗ trợ. "
                        + "Cài Nexo, ItemsAdder hoặc Oraxen, hoặc đặt resource-pack.host phù hợp.");
                warnedMissingHost = true;
            }
            return null;
        }

        String normalized = configured.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("itemadder")) {
            normalized = "itemsadder";
        }
        HostProvider provider = findProvider(normalized);
        if (provider == null) {
            if (!configured.equals(warnedInvalidHost)) {
                plugin.getLogger().warning("resource-pack.host không hợp lệ: " + configured
                        + ". Chỉ hỗ trợ auto, nexo, itemsadder hoặc oraxen.");
                warnedInvalidHost = configured;
            }
            return null;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled(provider.pluginName())) {
            if (!warnedMissingHost) {
                plugin.getLogger().warning("Host resource pack " + provider.pluginName()
                        + " chưa được cài hoặc đang tắt.");
                warnedMissingHost = true;
            }
            return null;
        }
        warnedMissingHost = false;
        return provider.key();
    }

    private void warnAboutLegacyMode() {
        String legacyMode = plugin.getConfig().getString("resource-pack.mode");
        if (legacyMode != null && !legacyMode.equalsIgnoreCase("external") && !warnedLegacyMode) {
            plugin.getLogger().warning("resource-pack.mode: " + legacyMode
                    + " đã bị bỏ. HoloWaypoint chỉ theo dõi pack host bởi Nexo, ItemsAdder hoặc Oraxen; "
                    + "hãy dùng resource-pack.host.");
            warnedLegacyMode = true;
        }
    }

    private boolean removePack(UUID playerId, UUID packId) {
        Map<String, Set<UUID>> packsByHost = loadedPacks.get(playerId);
        if (packsByHost == null) {
            return false;
        }
        boolean changed = false;
        for (Set<UUID> packs : packsByHost.values()) {
            changed |= packs.remove(packId);
        }
        packsByHost.values().removeIf(Set::isEmpty);
        if (packsByHost.isEmpty()) {
            loadedPacks.remove(playerId);
        }
        return changed;
    }

    private void loadKnownPacks() {
        if (!knownPacksFile.isFile()) {
            return;
        }
        FileConfiguration saved = YamlConfiguration.loadConfiguration(knownPacksFile);
        ConfigurationSection players = saved.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String playerKey : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(playerKey);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("UUID người chơi không hợp lệ trong resource-packs.yml: "
                        + playerKey);
                continue;
            }
            ConfigurationSection playerSection = players.getConfigurationSection(playerKey);
            if (playerSection == null) {
                continue;
            }
            for (String hostKey : playerSection.getKeys(false)) {
                HostProvider provider = findProvider(hostKey.toLowerCase(Locale.ROOT));
                if (provider == null) {
                    continue;
                }
                Set<UUID> packs = new HashSet<>();
                for (String packKey : playerSection.getStringList(hostKey)) {
                    try {
                        packs.add(UUID.fromString(packKey));
                    } catch (IllegalArgumentException exception) {
                        plugin.getLogger().warning("UUID resource pack không hợp lệ trong "
                                + "resource-packs.yml: " + packKey);
                    }
                }
                if (!packs.isEmpty()) {
                    loadedPacks.computeIfAbsent(playerId, ignored -> new HashMap<>())
                            .put(provider.key(), packs);
                }
            }
        }
    }

    private void saveKnownPacks() {
        YamlConfiguration saved = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Set<UUID>>> playerEntry : loadedPacks.entrySet()) {
            for (Map.Entry<String, Set<UUID>> hostEntry : playerEntry.getValue().entrySet()) {
                List<String> packIds = new ArrayList<>();
                hostEntry.getValue().stream()
                        .map(UUID::toString)
                        .sorted()
                        .forEach(packIds::add);
                saved.set("players." + playerEntry.getKey() + "." + hostEntry.getKey(), packIds);
            }
        }
        try {
            File parent = knownPacksFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Không thể tạo thư mục " + parent.getPath());
            }
            saved.save(knownPacksFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Không thể lưu trạng thái resource pack: "
                    + exception.getMessage());
        }
    }

    private static HostProvider findProvider(String key) {
        for (HostProvider provider : HOST_PROVIDERS) {
            if (provider.key().equals(key)) {
                return provider;
            }
        }
        return null;
    }

    private record HostProvider(String key, String pluginName) {}
}
