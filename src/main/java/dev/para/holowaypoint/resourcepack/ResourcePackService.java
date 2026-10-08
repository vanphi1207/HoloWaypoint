package dev.para.holowaypoint.resourcepack;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ResourcePackService implements Listener {

    public enum Mode { NONE, SELF, EXTERNAL }

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes(
            "dev.para.holowaypoint:resourcepack".getBytes(StandardCharsets.UTF_8));

    private static final long QUIT_CLEANUP_TICKS = 20L * 60L;

    private final JavaPlugin plugin;

    private final Map<UUID, Set<UUID>> loadedPacks = new HashMap<>();
    private final Map<UUID, String> requestedPacks = new HashMap<>();

    private byte[] packHash;
    private boolean hashPrepared;
    private boolean warnedMissingUrl;
    private String warnedInvalidUrl;
    private String warnedIconMode;
    private String warnedMode;

    public ResourcePackService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Mode mode() {
        FileConfiguration config = plugin.getConfig();
        String raw = config.getString("resource-pack.mode");
        if (raw == null || raw.isBlank()) {
            return config.getBoolean("resource-pack.enabled", false) ? Mode.SELF : Mode.NONE;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "none" -> Mode.NONE;
            case "self" -> Mode.SELF;
            case "external" -> Mode.EXTERNAL;
            default -> {
                if (!raw.equals(warnedMode)) {
                    plugin.getLogger().warning("resource-pack.mode không hợp lệ: " + raw
                            + ". Dùng chế độ none.");
                    warnedMode = raw;
                }
                yield Mode.NONE;
            }
        };
    }

    public boolean usesCustomFont(Player player) {
        String mode = plugin.getConfig().getString("resource-pack.icon-mode", "auto");
        if (mode == null) {
            mode = "auto";
        }
        return switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "force" -> true;
            case "unicode" -> false;
            case "auto" -> hasIconPack(player);
            default -> {
                if (!mode.equals(warnedIconMode)) {
                    plugin.getLogger().warning("resource-pack.icon-mode không hợp lệ: " + mode
                            + ". Dùng chế độ auto.");
                    warnedIconMode = mode;
                }
                yield hasIconPack(player);
            }
        };
    }

    private boolean hasIconPack(Player player) {
        Set<UUID> loaded = loadedPacks.get(player.getUniqueId());
        if (loaded == null || loaded.isEmpty()) {
            return false;
        }
        return switch (mode()) {
            case SELF -> loaded.contains(PACK_ID);
            case EXTERNAL -> true;
            case NONE -> false;
        };
    }

    public void sendToOnlinePlayers() {
        Bukkit.getOnlinePlayers().forEach(this::sendTo);
    }

    public void reload() {
        hashPrepared = false;
        packHash = null;
        warnedMissingUrl = false;
        sendToOnlinePlayers();
    }

    public void shutdown() {
        loadedPacks.clear();
        requestedPacks.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        sendTo(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        requestedPacks.remove(playerId);
        if (!plugin.isEnabled()) {
            loadedPacks.remove(playerId);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(playerId) == null) {
                loadedPacks.remove(playerId);
            }
        }, QUIT_CLEANUP_TICKS);
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        UUID packId = event.getID();
        switch (event.getStatus()) {
            case SUCCESSFULLY_LOADED ->
                    loadedPacks.computeIfAbsent(playerId, ignored -> new HashSet<>()).add(packId);
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                Set<UUID> loaded = loadedPacks.get(playerId);
                if (loaded != null) {
                    loaded.remove(packId);
                    if (loaded.isEmpty()) {
                        loadedPacks.remove(playerId);
                    }
                }
            }
            default -> {
            }
        }
    }

    private void sendTo(Player player) {
        if (mode() != Mode.SELF) {
            removePackIfRequested(player);
            return;
        }

        String url = configuredUrl();
        UUID playerId = player.getUniqueId();
        if (url == null) {
            removePackIfRequested(player);
            return;
        }
        byte[] hash = packHash();
        if (hash == null) {
            return;
        }
        String requestKey = url + "#" + hex(hash);
        if (requestKey.equals(requestedPacks.get(playerId))) {
            return;
        }

        String prompt = plugin.getConfig().getString(
                "resource-pack.prompt", "Tải resource pack HoloWaypoint để hiển thị icon waypoint.");
        if (prompt == null || prompt.isBlank()) {
            prompt = "Tải resource pack HoloWaypoint để hiển thị icon waypoint.";
        }
        boolean required = plugin.getConfig().getBoolean("resource-pack.required", false);
        Set<UUID> loaded = loadedPacks.get(playerId);
        if (loaded != null) {
            loaded.remove(PACK_ID);
        }
        try {
            player.setResourcePack(PACK_ID, url, hash.clone(), Component.text(prompt), required);
            requestedPacks.put(playerId, requestKey);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Không thể gửi resource pack cho " + player.getName()
                    + ": " + exception.getMessage());
        }
    }

    private String configuredUrl() {
        String url = plugin.getConfig().getString("resource-pack.url", "");
        if (url == null || url.isBlank()) {
            if (!warnedMissingUrl) {
                plugin.getLogger().warning("resource-pack.mode đang là self nhưng chưa có URL công khai trong "
                        + "resource-pack.url; tiếp tục dùng icon Unicode.");
                warnedMissingUrl = true;
            }
            return null;
        }

        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("cần URL HTTP(S) công khai");
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            if (!url.equals(warnedInvalidUrl)) {
                plugin.getLogger().warning("resource-pack.url không hợp lệ: " + exception.getMessage());
                warnedInvalidUrl = url;
            }
            return null;
        }
    }

    /** Chỉ chuẩn bị resourcepack.zip khi thật sự cần (chế độ self). */
    private byte[] packHash() {
        if (hashPrepared) {
            return packHash;
        }
        hashPrepared = true;
        try {
            File packFile = new File(plugin.getDataFolder(), "resourcepack.zip");
            if (!packFile.isFile() && plugin.getResource("resourcepack.zip") != null) {
                plugin.saveResource("resourcepack.zip", false);
            }
            if (!packFile.isFile()) {
                plugin.getLogger().severe("Không tìm thấy " + packFile.getPath()
                        + ". Hãy đặt resourcepack.zip vào đó, hoặc dùng resource-pack.mode: external.");
                return null;
            }
            packHash = sha1(packFile);
        } catch (IOException | IllegalArgumentException exception) {
            plugin.getLogger().severe("Không thể chuẩn bị resourcepack.zip: " + exception.getMessage());
        }
        return packHash;
    }

    private void removePackIfRequested(Player player) {
        UUID playerId = player.getUniqueId();
        if (requestedPacks.remove(playerId) != null) {
            Set<UUID> loaded = loadedPacks.get(playerId);
            if (loaded != null) {
                loaded.remove(PACK_ID);
            }
            player.removeResourcePack(PACK_ID);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private static byte[] sha1(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }
}