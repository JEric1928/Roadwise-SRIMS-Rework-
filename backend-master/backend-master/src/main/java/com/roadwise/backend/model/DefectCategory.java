package com.roadwise.backend.model;

import java.util.Optional;

/**
 * The four field-reportable distress categories.
 *
 * <p>The barangay upload form offers exactly these. The platform also carries a set
 * of legacy free-text labels in {@code road_reports.damage_type}
 * ("Surface Cracking", "Edge Deformation", "Washout/Sinkhole", "Other"), so parsing
 * here is deliberately split in two:</p>
 *
 * <ul>
 *   <li>{@link #parseStrict} — used by the new 3-field endpoint. Unknown input is
 *       rejected, because that form controls its own option list.</li>
 *   <li>{@link #parseLenient} — used when reading or migrating existing rows. It
 *       returns {@link Optional#empty()} for anything that is not a genuine synonym,
 *       so a value like "Edge Deformation" is never silently relabelled as
 *       "Surface Disintegration". Preserving an unknown legacy label is more honest
 *       than inventing a category for it.</li>
 * </ul>
 */
public enum DefectCategory {

    POTHOLE("Pothole"),

    CRACKING("Cracking"),

    SURFACE_DISINTEGRATION("Surface Disintegration"),

    BLOCK_FAILURE("Block Failure");

    private final String label;

    DefectCategory(String label) {
        this.label = label;
    }

    /** Human-readable value written to {@code road_reports.damage_type}. */
    public String label() {
        return label;
    }

    /**
     * Strict parse for the constrained upload form.
     *
     * @throws IllegalArgumentException when the value matches none of the four
     *         categories — callers translate this into a 400 with the allowed list.
     */
    public static DefectCategory parseStrict(String value) {
        return parseLenient(value).orElseThrow(() -> new IllegalArgumentException(
                "Unsupported defect category. Expected one of: Pothole, Cracking, "
                        + "Surface Disintegration, Block Failure."));
    }

    /** Lenient parse: exact labels and documented synonyms only. */
    public static Optional<DefectCategory> parseLenient(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().replace('_', ' ').replaceAll("\\s+", " ").toLowerCase();
        for (DefectCategory category : values()) {
            if (category.name().toLowerCase().replace('_', ' ').equals(normalized)
                    || category.label.toLowerCase().equals(normalized)) {
                return Optional.of(category);
            }
        }
        // Real synonyms seen in existing rows and in the older dropdown.
        return switch (normalized) {
            case "surface cracking", "alligator cracking", "longitudinal cracking",
                 "transverse cracking", "cracked" -> Optional.of(CRACKING);
            case "disintegration", "raveling", "ravelling", "surface wear" -> Optional.of(SURFACE_DISINTEGRATION);
            case "block cracking", "block failure" -> Optional.of(BLOCK_FAILURE);
            case "pot hole", "holes" -> Optional.of(POTHOLE);
            default -> Optional.empty();
        };
    }
}
