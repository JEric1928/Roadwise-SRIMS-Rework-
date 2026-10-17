package com.roadwise.backend.model;

import java.util.Optional;

/**
 * Surveyed pavement construction, required before a repair plan can be costed.
 *
 * <p>Stored as the enum name in {@code road_reports.surface_type}, constrained by
 * {@code chk_road_reports_surface_type} (V20). Distinct from the legacy
 * {@code road_reports.road_type}, which holds the broader inventory description
 * ("Asphalt, Gravel, Earth, Concrete, Mixed").</p>
 */
public enum SurfaceType {

    PORTLAND_CEMENT_CONCRETE("Portland Cement Concrete"),

    ASPHALT_CONCRETE("Asphalt Concrete");

    private final String label;

    SurfaceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Lenient by design — this value arrives from a dropdown but is not a boundary. */
    public static Optional<SurfaceType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").toLowerCase();
        for (SurfaceType type : values()) {
            if (type.name().replace('_', ' ').toLowerCase().equals(normalized)
                    || type.label.toLowerCase().equals(normalized)) {
                return Optional.of(type);
            }
        }
        return switch (normalized) {
            case "pcc", "concrete", "portland cement" -> Optional.of(PORTLAND_CEMENT_CONCRETE);
            case "asphalt", "bituminous", "ac" -> Optional.of(ASPHALT_CONCRETE);
            default -> Optional.empty();
        };
    }
}
