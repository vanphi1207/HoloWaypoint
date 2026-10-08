package dev.para.holowaypoint;

import dev.para.holowaypoint.command.HoloCommand;
import dev.para.holowaypoint.listener.PlayerQuitListener;
import dev.para.holowaypoint.resourcepack.ResourcePackService;
import dev.para.holowaypoint.service.WaypointManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class HoloWaypointPlugin extends JavaPlugin {

    private WaypointManager manager;
    private ResourcePackService resourcePackService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        resourcePackService = new ResourcePackService(this);
        getServer().getPluginManager().registerEvents(resourcePackService, this);
        manager = new WaypointManager(this, resourcePackService);
        manager.load();
        manager.start();

        HoloCommand handler = new HoloCommand(manager);
        PluginCommand command = Objects.requireNonNull(getCommand("hwp"),
                "The 'hwp' command is missing from plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(manager), this);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.shutdown();
        }
        if (resourcePackService != null) {
            resourcePackService.shutdown();
        }
    }

    public WaypointManager getManager() {
        return manager;
    }
}
