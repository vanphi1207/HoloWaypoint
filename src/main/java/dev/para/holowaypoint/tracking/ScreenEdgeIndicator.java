package dev.para.holowaypoint.tracking;

import org.bukkit.Location;
import org.bukkit.util.Vector;

public final class ScreenEdgeIndicator {

    private static final double BEHIND_EPSILON = 0.05;
    private static final double VECTOR_EPSILON_SQUARED = 1.0e-12;
    private static final double PERIMETER = 8.0;

    private ScreenEdgeIndicator() {}

    public record Projection(boolean offScreen, double x, double y, double angle) {}

    public static Projection project(Location eye, Vector toTarget, double halfWidth, double halfHeight) {
        double width = Math.max(0.05, halfWidth);
        double height = Math.max(0.05, halfHeight);
        if (toTarget.lengthSquared() < VECTOR_EPSILON_SQUARED) {
            return new Projection(false, 0.0, 0.0, 0.0);
        }
        Vector forward = eye.getDirection().normalize();
        Vector right = rightVector(forward);
        Vector up = right.clone().crossProduct(forward).normalize();

        double forwardDistance = toTarget.dot(forward);
        double screenX;
        double screenY;
        double angle;
        boolean offScreen;

        if (forwardDistance > BEHIND_EPSILON) {
            double horizontal = toTarget.dot(right);
            double vertical = toTarget.dot(up);
            screenX = horizontal / forwardDistance;
            screenY = vertical / forwardDistance;
            angle = Math.atan2(screenX, screenY);
            offScreen = Math.abs(screenX) > width || Math.abs(screenY) > height;
            if (!offScreen) {
                return new Projection(false, screenX / width, screenY / height, angle);
            }
        } else {
            double horizontal = toTarget.dot(right);
            double vertical = toTarget.dot(up);
            double length = Math.hypot(horizontal, vertical);
            if (length < 1.0e-6) {
                screenX = 0.0;
                screenY = -1000.0;
            } else {
                screenX = horizontal / length * 1000.0;
                screenY = vertical / length * 1000.0;
            }
            angle = Math.atan2(screenX, screenY);
            offScreen = true;
        }

        double normalizedX = screenX / width;
        double normalizedY = screenY / height;
        double scale = Math.max(Math.abs(normalizedX), Math.abs(normalizedY));
        if (scale < 1.0e-9) {
            normalizedY = -1.0;
            scale = 1.0;
        }
        return new Projection(offScreen, normalizedX / scale, normalizedY / scale, angle);
    }

    public static Location worldPosition(Location eye, double x, double y,
                                         double halfWidth, double halfHeight, double distance) {
        Vector forward = eye.getDirection().normalize();
        Vector right = rightVector(forward);
        Vector up = right.clone().crossProduct(forward).normalize();
        return eye.clone()
                .add(forward.multiply(distance))
                .add(right.multiply(x * halfWidth * distance))
                .add(up.multiply(y * halfHeight * distance));
    }

    public static double perimeterPosition(double x, double y) {
        double boundedX = clamp(x, -1.0, 1.0);
        double boundedY = clamp(y, -1.0, 1.0);
        if (boundedY >= 1.0 - 1.0e-8) {
            return 1.0 - boundedX;
        }
        if (boundedX <= -1.0 + 1.0e-8) {
            return 2.0 + (1.0 - boundedY);
        }
        if (boundedY <= -1.0 + 1.0e-8) {
            return 4.0 + (boundedX + 1.0);
        }
        return 6.0 + (boundedY + 1.0);
    }

    public static double smoothPerimeter(double current, double target, double amount) {
        double difference = target - current;
        difference -= Math.round(difference / PERIMETER) * PERIMETER;
        return wrapPerimeter(current + difference * clamp(amount, 0.0, 1.0));
    }

    public static double[] pointOnPerimeter(double position) {
        double value = wrapPerimeter(position);
        if (value < 2.0) {
            return new double[]{1.0 - value, 1.0};
        }
        if (value < 4.0) {
            return new double[]{-1.0, 1.0 - (value - 2.0)};
        }
        if (value < 6.0) {
            return new double[]{-1.0 + (value - 4.0), -1.0};
        }
        return new double[]{1.0, -1.0 + (value - 6.0)};
    }

    public static double smoothAngle(double current, double target, double amount) {
        double difference = target - current;
        difference -= Math.floor((difference + Math.PI) / (Math.PI * 2.0)) * (Math.PI * 2.0);
        return current + difference * clamp(amount, 0.0, 1.0);
    }

    public static int arrowDirection(double angle) {
        return Math.floorMod((int) Math.round(angle / (Math.PI / 8.0)), 16);
    }

    private static Vector rightVector(Vector forward) {
        // Minecraft: +X đông, +Z nam. Nhìn về +Z thì bên phải người chơi là -X,
        // nên "phải" = forward x up (up x forward sẽ ra bên TRÁI, làm marker bị lật gương).
        Vector right = forward.clone().crossProduct(new Vector(0.0, 1.0, 0.0));
        if (right.lengthSquared() < VECTOR_EPSILON_SQUARED) {
            right = new Vector(-1.0, 0.0, 0.0);
        }
        return right.normalize();
    }

    private static double wrapPerimeter(double position) {
        return ((position % PERIMETER) + PERIMETER) % PERIMETER;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}