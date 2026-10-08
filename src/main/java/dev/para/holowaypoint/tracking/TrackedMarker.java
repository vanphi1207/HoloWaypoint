package dev.para.holowaypoint.tracking;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

public final class TrackedMarker {

    private static final String[] FALLBACK_ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final Key ICON_FONT = Key.key("holowaypoint", "icons");
    private static final int WAYPOINT_GLYPH = 0xE000;
    private static final int EDGE_GLYPH_START = 0xE100;
    private static final int EDGE_GLYPH_COUNT = 16;
    private static final int MAX_DISPLAY_DURATION = 59;
    // Bỏ qua dự đoán khi camera "nhảy" (teleport, dash...) để marker không bị văng.
    private static final double MAX_PREDICT_MOVE_SQUARED = 16.0;
    private static final float MAX_PREDICT_ROTATION_DEGREES = 60.0f;

    private TextDisplay display;
    private Component lastText;
    private float lastScale = Float.NaN;
    private float smoothedScale = Float.NaN;
    private double edgePosition;
    private double edgeAngle;
    private boolean hasEdgePosition;
    private int appliedInterpolation;

    // Trạng thái camera ở lần cập nhật trước, dùng để dự đoán camera ở tick kế tiếp.
    private World lastEyeWorld;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private float lastEyeYaw;
    private float lastEyePitch;
    private boolean hasLastEye;

    public boolean isValid() {
        return display != null && display.isValid();
    }

    /**
     * Ngoại suy camera về phía trước theo vận tốc của lần cập nhật trước.
     *
     * <p>Marker ở rìa màn hình được đặt ngay trước camera, nhưng server chỉ biết hướng nhìn của
     * tick trước nên marker luôn chậm hơn camera một nhịp. Dự đoán này bù lại độ trễ đó.
     *
     * @param steps số lần cập nhật cần dự đoán trước (0 = tắt)
     */
    public Location predictEye(Location eye, double steps) {
        Location predicted = eye.clone();
        World world = eye.getWorld();
        if (steps > 0.0 && hasLastEye && world != null && world.equals(lastEyeWorld)) {
            double dx = eye.getX() - lastEyeX;
            double dy = eye.getY() - lastEyeY;
            double dz = eye.getZ() - lastEyeZ;
            if (dx * dx + dy * dy + dz * dz <= MAX_PREDICT_MOVE_SQUARED) {
                predicted.add(dx * steps, dy * steps, dz * steps);
            }
            float deltaYaw = wrapDegrees(eye.getYaw() - lastEyeYaw);
            float deltaPitch = eye.getPitch() - lastEyePitch;
            if (Math.abs(deltaYaw) <= MAX_PREDICT_ROTATION_DEGREES
                    && Math.abs(deltaPitch) <= MAX_PREDICT_ROTATION_DEGREES) {
                predicted.setYaw((float) (eye.getYaw() + deltaYaw * steps));
                predicted.setPitch((float) Math.max(-89.9, Math.min(89.9, eye.getPitch() + deltaPitch * steps)));
            }
        }
        lastEyeWorld = world;
        lastEyeX = eye.getX();
        lastEyeY = eye.getY();
        lastEyeZ = eye.getZ();
        lastEyeYaw = eye.getYaw();
        lastEyePitch = eye.getPitch();
        hasLastEye = true;
        return predicted;
    }

    public EdgeState updateEdge(Location eye, ScreenEdgeIndicator.Projection projection,
                                double halfWidth, double halfHeight, double edgeDistance,
                                double smoothingAmount) {
        double targetPosition = ScreenEdgeIndicator.perimeterPosition(projection.x(), projection.y());
        if (!hasEdgePosition) {
            edgePosition = targetPosition;
            edgeAngle = projection.angle();
            hasEdgePosition = true;
        } else {
            edgePosition = ScreenEdgeIndicator.smoothPerimeter(edgePosition, targetPosition, smoothingAmount);
            edgeAngle = ScreenEdgeIndicator.smoothAngle(edgeAngle, projection.angle(), smoothingAmount);
        }

        double[] point = ScreenEdgeIndicator.pointOnPerimeter(edgePosition);
        Location markerLocation = ScreenEdgeIndicator.worldPosition(
                eye, point[0], point[1], halfWidth, halfHeight, edgeDistance);
        return new EdgeState(markerLocation, ScreenEdgeIndicator.arrowDirection(edgeAngle));
    }

    public record EdgeState(Location location, int arrowDirection) {}

    public void resetEdge() {
        hasEdgePosition = false;
    }

    public float smoothScale(float targetScale, double amount) {
        if (Float.isNaN(smoothedScale)) {
            smoothedScale = targetScale;
        } else {
            smoothedScale += (targetScale - smoothedScale) * (float) amount;
        }
        return smoothedScale;
    }

    public void show(Plugin plugin, Player player, Location location, Component text, float scale,
                     int interpolationTicks) {
        int duration = clampDuration(interpolationTicks);
        display = location.getWorld().spawn(location, TextDisplay.class, textDisplay -> {
            textDisplay.setVisibleByDefault(false);
            textDisplay.setPersistent(false);
            textDisplay.setBillboard(Display.Billboard.CENTER);
            textDisplay.setSeeThrough(true);
            textDisplay.setShadowed(true);
            textDisplay.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            textDisplay.setBrightness(new Display.Brightness(15, 15));
            textDisplay.setTransformation(scaleOf(scale));
            // Client tự nội suy vị trí giữa hai lần teleport thay vì nhảy cóc theo tick.
            textDisplay.setTeleportDuration(duration);
            textDisplay.text(text);
        });
        // Duration của transformation chỉ bật từ lần cập nhật sau, tránh hiệu ứng phóng to lúc vừa spawn.
        appliedInterpolation = 0;
        lastText = text;
        lastScale = scale;
        player.showEntity(plugin, display);
    }

    public void updateDisplay(Location location, Component text, float scale, int interpolationTicks) {
        if (!isValid()) {
            return;
        }
        int duration = clampDuration(interpolationTicks);
        if (display.getTeleportDuration() != duration) {
            display.setTeleportDuration(duration);
        }
        Location current = display.getLocation();
        if (!current.getWorld().equals(location.getWorld())
                || current.distanceSquared(location) > 1.0e-6) {
            display.teleport(location);
        }
        if (!text.equals(lastText)) {
            display.text(text);
            lastText = text;
        }
        if (Float.isNaN(lastScale) || Math.abs(lastScale - scale) > 0.001f) {
            if (appliedInterpolation != duration) {
                display.setInterpolationDuration(duration);
                appliedInterpolation = duration;
            }
            display.setTransformation(scaleOf(scale));
            // Đổi start tick để client bắt đầu nội suy scale ngay lập tức.
            display.setInterpolationDelay(0);
            lastScale = scale;
        }
    }

    public void remove() {
        if (display != null) {
            display.remove();
            display = null;
        }
        resetEdge();
        hasLastEye = false;
        lastEyeWorld = null;
    }

    public static Component label(double distance, int arrow, boolean useCustomFont) {
        String distanceText = distance >= 1000
                ? String.format(java.util.Locale.ROOT, "%.1fkm", distance / 1000.0)
                : Math.round(distance) + "m";
        Component icon;
        if (useCustomFont) {
            int glyph = arrow >= 0
                    ? EDGE_GLYPH_START + Math.floorMod(arrow, EDGE_GLYPH_COUNT)
                    : WAYPOINT_GLYPH;
            icon = Component.text(Character.toString((char) glyph)).font(ICON_FONT);
        } else {
            String symbol = arrow >= 0
                    ? FALLBACK_ARROWS[Math.floorMod((int) Math.round(arrow / 2.0), FALLBACK_ARROWS.length)]
                    : "◆";
            icon = Component.text(symbol);
        }
        return Component.empty()
                .append(icon)
                .appendNewline()
                .append(Component.text(distanceText));
    }

    private static int clampDuration(int ticks) {
        return Math.max(0, Math.min(MAX_DISPLAY_DURATION, ticks));
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        } else if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }

    private static Transformation scaleOf(float scale) {
        return new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }
}
