package dev.para.holowaypoint.model;

import java.util.Locale;
import java.util.Objects;

public record Waypoint(String name, String world, double x, double y, double z,
                       boolean arrivalMessageEnabled, String arrivalMessage) {

    public static final String DEFAULT_ARRIVAL_MESSAGE = "Đã đến nơi: {name}";

    public Waypoint(String name, String world, double x, double y, double z) {
        this(name, world, x, y, z, true, DEFAULT_ARRIVAL_MESSAGE);
    }

    public Waypoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(arrivalMessage, "arrivalMessage");
        name = name.toLowerCase(Locale.ROOT);

        if (name.isBlank()) {
            throw new IllegalArgumentException("Waypoint name must not be blank");
        }
        if (world.isBlank()) {
            throw new IllegalArgumentException("Waypoint world must not be blank");
        }
        if (arrivalMessage.isBlank()) {
            throw new IllegalArgumentException("Arrival message must not be blank");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Waypoint coordinates must be finite");
        }
    }

    public Waypoint withArrivalMessage(String message) {
        return new Waypoint(name, world, x, y, z, arrivalMessageEnabled, message);
    }

    public Waypoint withArrivalMessageReset() {
        return new Waypoint(name, world, x, y, z, arrivalMessageEnabled, DEFAULT_ARRIVAL_MESSAGE);
    }

    public Waypoint withLocation(String world, double x, double y, double z) {
        return new Waypoint(name, world, x, y, z, arrivalMessageEnabled, arrivalMessage);
    }

    public Waypoint withArrivalMessageEnabled(boolean enabled) {
        return new Waypoint(name, world, x, y, z, enabled, arrivalMessage);
    }
}