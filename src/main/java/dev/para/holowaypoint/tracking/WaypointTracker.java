package dev.para.holowaypoint.tracking;

import dev.para.holowaypoint.model.Waypoint;
import dev.para.holowaypoint.resourcepack.ResourcePackService;
import dev.para.holowaypoint.storage.WaypointRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WaypointTracker {

    private final JavaPlugin plugin;
    private final WaypointRepository repository;
    private final ResourcePackService resourcePackService;
    private final Map<UUID, Set<String>> tracked = new HashMap<>();
    private final Map<UUID, Map<String, TrackedMarker>> markers = new HashMap<>();
    private BukkitTask task;

    public WaypointTracker(JavaPlugin plugin, WaypointRepository repository,
                           ResourcePackService resourcePackService) {
        this.plugin = plugin;
        this.repository = repository;
        this.resourcePackService = resourcePackService;
    }

    public boolean track(Player player, String name) {
        if (repository.get(name) == null) {
            return false;
        }
        tracked.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>()).add(name);
        return true;
    }

    public void untrack(Player player, String name) {
        UUID playerId = player.getUniqueId();
        Set<String> names = tracked.get(playerId);
        if (names != null) {
            names.remove(name);
            if (names.isEmpty()) {
                tracked.remove(playerId);
            }
        }
        removeMarker(playerId, name);
    }

    public void untrackAll(Player player) {
        clearPlayer(player.getUniqueId());
    }

    public void clearPlayer(UUID playerId) {
        tracked.remove(playerId);
        Map<String, TrackedMarker> playerMarkers = markers.remove(playerId);
        if (playerMarkers != null) {
            playerMarkers.values().forEach(TrackedMarker::remove);
        }
    }

    public void removeWaypoint(String name) {
        for (Map.Entry<UUID, Set<String>> entry : new ArrayList<>(tracked.entrySet())) {
            entry.getValue().remove(name);
            if (entry.getValue().isEmpty()) {
                tracked.remove(entry.getKey());
            }
            removeMarker(entry.getKey(), name);
        }
    }

    public void start() {
        long interval = Math.max(1L, plugin.getConfig().getLong("update-interval-ticks", 1L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void restart() {
        if (task != null) {
            task.cancel();
        }
        start();
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        markers.values().forEach(playerMarkers -> playerMarkers.values().forEach(TrackedMarker::remove));
        markers.clear();
        tracked.clear();
    }

    private void tick() {
        FileConfiguration config = plugin.getConfig();
        double maxRenderDistance = Math.max(1.0, config.getDouble("max-render-distance", 24.0));
        double markerHeight = config.getDouble("marker-height", 1.5);
        double arriveDistance = Math.max(0.0, config.getDouble("arrive-distance", 2.0));
        boolean edgeEnabled = config.getBoolean("edge-indicator", true);
        double edgeDistance = Math.max(0.1, config.getDouble("edge-distance", 3.0));
        double halfWidth = Math.max(0.05, config.getDouble("edge-half-width", 1.0));
        double halfHeight = Math.max(0.05, config.getDouble("edge-half-height", 0.55));
        float edgeScale = (float) Math.max(0.1, config.getDouble("edge-scale", 0.7));
        boolean edgeArrow = config.getBoolean("edge-arrow", true);
        double smoothingSpeed = Math.max(0.0, config.getDouble("edge-smoothing-speed", 12.0));
        long interval = Math.max(1L, config.getLong("update-interval-ticks", 1L));
        double smoothingAmount = smoothingSpeed == 0.0
                ? 1.0
                : 1.0 - Math.exp(-smoothingSpeed * interval / 20.0);
        // Số tick client dùng để nội suy vị trí/scale giữa hai lần cập nhật; 0 = tự khớp update-interval-ticks.
        long configuredInterpolation = config.getLong("interpolation-ticks", 0L);
        int interpolationTicks = (int) Math.min(59L,
                configuredInterpolation > 0 ? configuredInterpolation : interval);
        // Dự đoán camera trước bao nhiêu tick (quy ra số lần cập nhật); 0 = tắt.
        double lookAheadSteps = Math.max(0.0, config.getDouble("edge-lookahead-ticks", 1.0)) / interval;

        for (Map.Entry<UUID, Set<String>> entry : new ArrayList<>(tracked.entrySet())) {
            UUID playerId = entry.getKey();
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                continue;
            }

            Map<String, TrackedMarker> playerMarkers =
                    markers.computeIfAbsent(playerId, ignored -> new HashMap<>());
            for (String name : new ArrayList<>(entry.getValue())) {
                updateMarker(player, playerId, name, playerMarkers, edgeEnabled, edgeArrow,
                        maxRenderDistance, markerHeight, arriveDistance, edgeDistance,
                        halfWidth, halfHeight, edgeScale, smoothingAmount,
                        interpolationTicks, lookAheadSteps);
            }
            if (entry.getValue().isEmpty()) {
                tracked.remove(playerId);
                if (playerMarkers.isEmpty()) {
                    markers.remove(playerId);
                }
            }
        }
    }

    private void updateMarker(Player player, UUID playerId, String name,
                              Map<String, TrackedMarker> playerMarkers,
                              boolean edgeEnabled, boolean edgeArrow,
                              double maxRenderDistance, double markerHeight,
                              double arriveDistance, double edgeDistance,
                              double halfWidth, double halfHeight, float edgeScale,
                              double smoothingAmount, int interpolationTicks,
                              double lookAheadSteps) {
        Waypoint waypoint = repository.get(name);
        if (waypoint == null) {
            tracked.get(playerId).remove(name);
            removeMarker(playerMarkers, name);
            return;
        }

        World world = Bukkit.getWorld(waypoint.world());
        if (world == null || !world.equals(player.getWorld())) {
            removeMarker(playerMarkers, name);
            return;
        }

        Location eye = player.getEyeLocation();
        Location target = new Location(world, waypoint.x(), waypoint.y(), waypoint.z());
        double distance = eye.distance(target);
        if (distance <= arriveDistance) {
            tracked.get(playerId).remove(name);
            removeMarker(playerMarkers, name);
            if (waypoint.arrivalMessageEnabled()) {
                String message = waypoint.arrivalMessage().replace("{name}", waypoint.name());
                player.sendMessage(Component.text().color(NamedTextColor.GREEN)
                        .append(LegacyComponentSerializer.legacyAmpersand().deserialize(message))
                        .build());
            }
            return;
        }

        Location aim = target.clone().add(0.0, markerHeight, 0.0);
        TrackedMarker marker = playerMarkers.get(name);
        if (marker == null) {
            marker = new TrackedMarker();
            playerMarkers.put(name, marker);
        }

        Location markerLocation;
        int arrow = -1;
        float scale;
        if (edgeEnabled) {
            // Dùng camera đã được dự đoán trước một nhịp để marker ở rìa bớt trễ so với góc nhìn.
            Location view = marker.predictEye(eye, lookAheadSteps);
            Vector toTarget = aim.toVector().subtract(view.toVector());
            ScreenEdgeIndicator.Projection projection =
                    ScreenEdgeIndicator.project(view, toTarget, halfWidth, halfHeight);
            if (projection.offScreen()) {
                TrackedMarker.EdgeState edgeState = marker.updateEdge(
                        view, projection, halfWidth, halfHeight, edgeDistance, smoothingAmount);
                markerLocation = edgeState.location();
                arrow = edgeArrow ? edgeState.arrowDirection() : -1;
                scale = edgeScale;
            } else {
                marker.resetEdge();
                markerLocation = aim;
                scale = scaleForDistance(eye.distance(markerLocation));
                markerLocation = clampDistance(eye, markerLocation, maxRenderDistance);
            }
        } else {
            marker.resetEdge();
            markerLocation = clampDistance(eye, aim, maxRenderDistance);
            scale = scaleForDistance(eye.distance(markerLocation));
        }

        scale = marker.smoothScale(scale, smoothingAmount);
        Component text = TrackedMarker.label(
                distance, arrow, resourcePackService.usesCustomFont(player));
        if (!marker.isValid()) {
            marker.show(plugin, player, markerLocation, text, scale, interpolationTicks);
        } else {
            marker.updateDisplay(markerLocation, text, scale, interpolationTicks);
        }
    }

    private static Location clampDistance(Location eye, Location target, double maxDistance) {
        Location result = target.clone();
        double distance = eye.distance(result);
        if (distance > maxDistance) {
            Vector direction = result.toVector().subtract(eye.toVector()).normalize();
            result = eye.clone().add(direction.multiply(maxDistance));
        }
        return result;
    }

    private static float scaleForDistance(double distance) {
        return (float) Math.min(4.0, Math.max(1.0, distance * 0.15));
    }

    private void removeMarker(UUID playerId, String name) {
        Map<String, TrackedMarker> playerMarkers = markers.get(playerId);
        if (playerMarkers != null) {
            removeMarker(playerMarkers, name);
            if (playerMarkers.isEmpty()) {
                markers.remove(playerId);
            }
        }
    }

    private static void removeMarker(Map<String, TrackedMarker> playerMarkers, String name) {
        TrackedMarker marker = playerMarkers.remove(name);
        if (marker != null) {
            marker.remove();
        }
    }
}