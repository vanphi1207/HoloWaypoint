package dev.para.holowaypoint.listener;

import dev.para.holowaypoint.service.WaypointManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {

    private final WaypointManager manager;

    public PlayerQuitListener(WaypointManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        manager.clearPlayer(event.getPlayer().getUniqueId());
    }
}
