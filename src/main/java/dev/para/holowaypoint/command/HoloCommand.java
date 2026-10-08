package dev.para.holowaypoint.command;

import dev.para.holowaypoint.model.Waypoint;
import dev.para.holowaypoint.service.WaypointManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class HoloCommand implements CommandExecutor, TabCompleter {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final List<String> SUBCOMMANDS =
            List.of("add", "remove", "list", "track", "untrack", "message", "reload");

    private final WaypointManager manager;

    public HoloCommand(WaypointManager manager) {
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            msg(sender, "/hwp add <tên> [x y z [world]] | remove <tên> | list"
                            + " | track <tên> [người chơi] | untrack <tên|all> [người chơi]"
                            + " | message <tên> [on|off|set <nội dung>|reset] | reload",
                    NamedTextColor.GRAY);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> add(sender, args);
            case "remove" -> remove(sender, args);
            case "list" -> list(sender);
            case "track" -> track(sender, args, true);
            case "untrack" -> track(sender, args, false);
            case "message", "msg" -> message(sender, args);
            case "reload" -> reload(sender);
            default -> msg(sender, "Lệnh không hợp lệ.", NamedTextColor.RED);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("holowaypoint.admin")) {
            msg(sender, "Bạn không có quyền.", NamedTextColor.RED);
            return;
        }
        manager.reload();
        msg(sender, "Đã tải lại config.yml và waypoints.yml.", NamedTextColor.GREEN);
    }

    private void add(CommandSender sender, String[] args) {
        if (!sender.hasPermission("holowaypoint.admin")) {
            msg(sender, "Bạn không có quyền.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            msg(sender, "/hwp add <tên> [x y z [world]]", NamedTextColor.GRAY);
            return;
        }
        if (args.length > 6 || (args.length > 2 && args.length < 5)) {
            msg(sender, "/hwp add <tên> [x y z [world]]", NamedTextColor.GRAY);
            return;
        }

        String name = args[1].toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            msg(sender, "Tên chỉ gồm a-z, 0-9, _ và - (tối đa 32 ký tự).", NamedTextColor.RED);
            return;
        }

        double x;
        double y;
        double z;
        String world;
        if (args.length >= 5) {
            try {
                x = Double.parseDouble(args[2]);
                y = Double.parseDouble(args[3]);
                z = Double.parseDouble(args[4]);
            } catch (NumberFormatException exception) {
                msg(sender, "Tọa độ không hợp lệ.", NamedTextColor.RED);
                return;
            }
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                msg(sender, "Tọa độ phải là số hữu hạn.", NamedTextColor.RED);
                return;
            }

            if (args.length >= 6) {
                world = args[5];
            } else if (sender instanceof Player player) {
                world = player.getWorld().getName();
            } else {
                msg(sender, "Từ console cần ghi rõ world.", NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player player) {
            Location location = player.getLocation();
            x = location.getBlockX() + 0.5;
            y = location.getBlockY();
            z = location.getBlockZ() + 0.5;
            world = player.getWorld().getName();
        } else {
            msg(sender, "Từ console cần ghi rõ x y z world.", NamedTextColor.RED);
            return;
        }

        if (Bukkit.getWorld(world) == null) {
            msg(sender, "Không có world: " + world, NamedTextColor.RED);
            return;
        }

        boolean replaced = manager.putLocation(name, world, x, y, z);
        msg(sender, (replaced ? "Đã cập nhật " : "Đã tạo ") + name, NamedTextColor.GREEN);
    }

    private void message(CommandSender sender, String[] args) {
        if (!sender.hasPermission("holowaypoint.admin")) {
            msg(sender, "Bạn không có quyền.", NamedTextColor.RED);
            return;
        }
        String usage = "/hwp message <tên> [on|off|set <nội dung>|reset]";
        if (args.length < 2) {
            msg(sender, usage, NamedTextColor.GRAY);
            return;
        }

        String name = args[1].toLowerCase(Locale.ROOT);
        Waypoint waypoint = manager.get(name);
        if (waypoint == null) {
            msg(sender, "Không tìm thấy waypoint.", NamedTextColor.RED);
            return;
        }

        if (args.length == 2) {
            msg(sender, name + ": " + (waypoint.arrivalMessageEnabled() ? "BẬT" : "TẮT")
                    + " | " + waypoint.arrivalMessage(), NamedTextColor.WHITE);
            return;
        }

        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                manager.setArrivalMessageEnabled(name, true);
                msg(sender, "Đã bật message khi đến nơi của " + name, NamedTextColor.GREEN);
            }
            case "off" -> {
                manager.setArrivalMessageEnabled(name, false);
                msg(sender, "Đã tắt message khi đến nơi của " + name, NamedTextColor.GREEN);
            }
            case "reset" -> {
                manager.resetArrivalMessage(name);
                msg(sender, "Đã đặt lại message mặc định của " + name, NamedTextColor.GREEN);
            }
            case "set" -> {
                if (args.length < 4) {
                    msg(sender, "/hwp message " + name + " set <nội dung>"
                            + " (dùng {name} cho tên waypoint, &a &l... cho màu)", NamedTextColor.GRAY);
                    return;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
                if (text.isEmpty()) {
                    msg(sender, "Nội dung không được để trống.", NamedTextColor.RED);
                    return;
                }
                manager.setArrivalMessage(name, text);
                msg(sender, "Đã đặt message của " + name + ": " + text, NamedTextColor.GREEN);
            }
            default -> msg(sender, usage, NamedTextColor.GRAY);
        }
    }

    private void remove(CommandSender sender, String[] args) {
        if (!sender.hasPermission("holowaypoint.admin")) {
            msg(sender, "Bạn không có quyền.", NamedTextColor.RED);
            return;
        }
        if (args.length < 2) {
            msg(sender, "/hwp remove <tên>", NamedTextColor.GRAY);
            return;
        }

        boolean removed = manager.remove(args[1].toLowerCase(Locale.ROOT));
        msg(sender, removed ? "Đã xóa." : "Không tìm thấy waypoint.",
                removed ? NamedTextColor.GREEN : NamedTextColor.RED);
    }

    private void list(CommandSender sender) {
        if (manager.all().isEmpty()) {
            msg(sender, "Chưa có waypoint nào.", NamedTextColor.GRAY);
            return;
        }
        for (Waypoint waypoint : manager.all()) {
            msg(sender, String.format(Locale.ROOT, "%s: %s %.1f %.1f %.1f [message: %s]",
                            waypoint.name(), waypoint.world(), waypoint.x(), waypoint.y(), waypoint.z(),
                            waypoint.arrivalMessageEnabled() ? "bật" : "tắt"),
                    NamedTextColor.WHITE);
        }
    }

    private void track(CommandSender sender, String[] args, boolean enable) {
        if (args.length < 2) {
            msg(sender, "/hwp " + (enable ? "track" : "untrack") + " <tên"
                    + (enable ? "" : "|all") + "> [người chơi]", NamedTextColor.GRAY);
            return;
        }

        Player target;
        if (args.length >= 3) {
            if (!sender.hasPermission("holowaypoint.admin")) {
                msg(sender, "Bạn không có quyền.", NamedTextColor.RED);
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                msg(sender, "Người chơi không online.", NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player player) {
            if (!player.hasPermission("holowaypoint.use")) {
                msg(sender, "Bạn không có quyền.", NamedTextColor.RED);
                return;
            }
            target = player;
        } else {
            msg(sender, "Từ console cần ghi rõ người chơi.", NamedTextColor.RED);
            return;
        }

        String name = args[1].toLowerCase(Locale.ROOT);
        if (enable) {
            boolean tracked = manager.track(target, name);
            msg(sender, tracked ? "Đang chỉ đường tới " + name : "Không tìm thấy waypoint.",
                    tracked ? NamedTextColor.GREEN : NamedTextColor.RED);
        } else if (name.equals("all")) {
            manager.untrackAll(target);
            msg(sender, "Đã tắt tất cả marker.", NamedTextColor.GREEN);
        } else {
            manager.untrack(target, name);
            msg(sender, "Đã tắt " + name, NamedTextColor.GREEN);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            SUBCOMMANDS.stream().filter(value -> value.startsWith(prefix)).forEach(suggestions::add);
        } else if (args.length == 2
                && List.of("remove", "track", "untrack", "message", "msg")
                .contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            for (Waypoint waypoint : manager.all()) {
                if (waypoint.name().startsWith(prefix)) {
                    suggestions.add(waypoint.name());
                }
            }
            if (args[0].equalsIgnoreCase("untrack") && "all".startsWith(prefix)) {
                suggestions.add("all");
            }
        } else if (args.length == 3
                && List.of("message", "msg").contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List.of("on", "off", "set", "reset").stream()
                    .filter(value -> value.startsWith(prefix)).forEach(suggestions::add);
        } else if (args.length == 3
                && List.of("track", "untrack").contains(args[0].toLowerCase(Locale.ROOT))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    suggestions.add(player.getName());
                }
            }
        }
        return suggestions;
    }

    private static void msg(CommandSender sender, String text, NamedTextColor color) {
        sender.sendMessage(Component.text(text, color));
    }
}