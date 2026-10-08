package dev.para.holowaypoint.service;

import dev.para.holowaypoint.model.Waypoint;
import dev.para.holowaypoint.resourcepack.ResourcePackService;
import dev.para.holowaypoint.storage.WaypointRepository;
import dev.para.holowaypoint.tracking.WaypointTracker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.UUID;

public final class WaypointManager {

    private final JavaPlugin plugin;
    private final WaypointRepository repository;
    private final WaypointTracker tracker;
    private final ResourcePackService resourcePackService;

    public WaypointManager(JavaPlugin plugin, ResourcePackService resourcePackService) {
        this.plugin = plugin;
        this.resourcePackService = resourcePackService;
        this.repository = new WaypointRepository(plugin);
        this.tracker = new WaypointTracker(plugin, repository);
    }

    public void load() {
        repository.load();
        tracker.load();
    }

    public void start() {
        tracker.start();
    }

    public void reload() {
        plugin.reloadConfig();
        repository.load();
        tracker.restart();
        resourcePackService.reload();
    }

    public void shutdown() {
        tracker.shutdown();
    }

    public boolean put(Waypoint waypoint) {
        return repository.put(waypoint);
    }

    public boolean putLocation(String name, String world, double x, double y, double z) {
        Waypoint existing = repository.get(name);
        Waypoint updated = existing == null
                ? new Waypoint(name, world, x, y, z)
                : existing.withLocation(world, x, y, z);
        return repository.put(updated);
    }

    public boolean remove(String name) {
        boolean removed = repository.remove(name);
        if (removed) {
            tracker.removeWaypoint(name);
        }
        return removed;
    }

    public Collection<Waypoint> all() {
        return repository.all();
    }

    public Waypoint get(String name) {
        return repository.get(name);
    }

    public boolean setArrivalMessage(String name, String message) {
        Waypoint waypoint = repository.get(name);
        if (waypoint == null) {
            return false;
        }
        repository.put(waypoint.withArrivalMessage(message));
        return true;
    }

    public boolean resetArrivalMessage(String name) {
        Waypoint waypoint = repository.get(name);
        if (waypoint == null) {
            return false;
        }
        repository.put(waypoint.withArrivalMessageReset());
        return true;
    }

    public boolean setArrivalMessageEnabled(String name, boolean enabled) {
        Waypoint waypoint = repository.get(name);
        if (waypoint == null) {
            return false;
        }
        repository.put(waypoint.withArrivalMessageEnabled(enabled));
        return true;
    }

    public boolean track(Player player, String name) {
        return tracker.track(player, name);
    }

    public void untrack(Player player, String name) {
        tracker.untrack(player, name);
    }

    public void untrackAll(Player player) {
        tracker.untrackAll(player);
    }

    public void clearPlayer(UUID playerId) {
        tracker.clearPlayer(playerId);
    }

    public void onPlayerQuit(UUID playerId) {
        tracker.onPlayerQuit(playerId);
    }
}
