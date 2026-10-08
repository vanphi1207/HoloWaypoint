package dev.para.holowaypoint.tracking;

import dev.para.holowaypoint.model.Waypoint;
import dev.para.holowaypoint.storage.TrackingRepository;
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
    private final TrackingRepository trackingRepository;
    private final Map<UUID, Set<String>> tracked = new HashMap<>();
    private final Map<UUID, Map<String, TrackedMarker>> markers = new HashMap<>();
    private BukkitTask task;

    public WaypointTracker(JavaPlugin plugin, WaypointRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.trackingRepository = new TrackingRepository(plugin);
    }

    public void load() {
        tracked.clear();
        tracked.putAll(trackingRepository.load(repository));
    }

    public boolean track(Player player, String name) {
        if (repository.get(name) == null) {
            return false;
        }
        boolean changed = tracked.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>())
                .add(name);
        if (changed) {
            saveTracking();
        }
        return true;
    }

    public void untrack(Player player, String name) {
        UUID playerId = player.getUniqueId();
        Set<String> names = tracked.get(playerId);
        if (names != null) {
            boolean changed = names.remove(name);
            if (names.isEmpty()) {
                tracked.remove(playerId);
            }
            if (changed) {
                saveTracking();
            }
        }
        removeMarker(playerId, name);
    }

    public void untrackAll(Player player) {
        clearPlayer(player.getUniqueId());
    }

    public void clearPlayer(UUID playerId) {
        if (tracked.remove(playerId) != null) {
            saveTracking();
        }
        removePlayerMarkers(playerId);
    }

    public void onPlayerQuit(UUID playerId) {
        removePlayerMarkers(playerId);
    }

    private void removePlayerMarkers(UUID playerId) {
        Map<String, TrackedMarker> playerMarkers = markers.remove(playerId);
        if (playerMarkers != null) {
            playerMarkers.values().forEach(TrackedMarker::remove);
        }
    }

    public void removeWaypoint(String name) {
        boolean changed = false;
        for (Map.Entry<UUID, Set<String>> entry : new ArrayList<>(tracked.entrySet())) {
            changed |= entry.getValue().remove(name);
            if (entry.getValue().isEmpty()) {
                tracked.remove(entry.getKey());
            }
            removeMarker(entry.getKey(), name);
        }
        if (changed) {
            saveTracking();
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
        saveTracking();
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
        double safeArea = Math.max(0.4, Math.min(0.95, config.getDouble("edge-safe-area", 0.82)));
        double safeHalfWidth = halfWidth * safeArea;
        double safeHalfHeight = halfHeight * safeArea;
        float edgeScale = (float) Math.max(0.1, config.getDouble("edge-scale", 0.7));
        boolean edgeArrow = config.getBoolean("edge-arrow", true);
        double smoothingSpeed = Math.max(0.0, config.getDouble("edge-smoothing-speed", 12.0));
        long interval = Math.max(1L, config.getLong("update-interval-ticks", 1L));
        double smoothingAmount = smoothingSpeed == 0.0
                ? 1.0
                : 1.0 - Math.exp(-smoothingSpeed * interval / 20.0);
        long configuredInterpolation = config.getLong("interpolation-ticks", 0L);
        int interpolationTicks = (int) Math.min(59L,
                configuredInterpolation > 0 ? configuredInterpolation : interval);
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
                        safeHalfWidth, safeHalfHeight, edgeScale, smoothingAmount,
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
                              double safeHalfWidth, double safeHalfHeight, float edgeScale,
                              double smoothingAmount, int interpolationTicks,
                              double lookAheadSteps) {
        Waypoint waypoint = repository.get(name);
        if (waypoint == null) {
            removeTrackedWaypoint(playerId, name);
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
            removeTrackedWaypoint(playerId, name);
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
            Location view = marker.predictEye(eye, lookAheadSteps);
            Vector toTarget = aim.toVector().subtract(view.toVector());
            ScreenEdgeIndicator.Projection projection =
                    ScreenEdgeIndicator.project(view, toTarget, safeHalfWidth, safeHalfHeight);
            if (projection.offScreen()) {
                TrackedMarker.EdgeState edgeState = marker.updateEdge(
                        view, projection, safeHalfWidth, safeHalfHeight, edgeDistance, smoothingAmount);
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
        Component text = TrackedMarker.label(distance, arrow);
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

    private void removeTrackedWaypoint(UUID playerId, String name) {
        Set<String> names = tracked.get(playerId);
        if (names != null && names.remove(name)) {
            if (names.isEmpty()) {
                tracked.remove(playerId);
            }
            saveTracking();
        }
    }

    private void saveTracking() {
        trackingRepository.save(tracked);
    }

    private static void removeMarker(Map<String, TrackedMarker> playerMarkers, String name) {
        TrackedMarker marker = playerMarkers.remove(name);
        if (marker != null) {
            marker.remove();
        }
    }
}
