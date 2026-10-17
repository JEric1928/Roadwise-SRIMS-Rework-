package com.roadwise.backend.model;

/**
 * Which of the three required capture angles a report photo represents.
 *
 * <p>The value is persisted as its enum name ({@code CLOSE_UP}, {@code WIDE_ANGLE},
 * {@code PERPENDICULAR}) and enforced by {@code chk_report_images_perspective} in V20,
 * so adding a constant here requires a matching migration.</p>
 *
 * <p>{@link #displayOrder()} exists because the frontend widget and the
 * {@code report_images.display_order} column both need a stable ordering that does
 * not depend on enum declaration order drifting over time.</p>
 */
public enum ImagePerspective {

    /** Close-up of the distress itself — the image severity inference trusts most. */
    CLOSE_UP(0),

    /** Wide shot establishing the road and surrounding context. */
    WIDE_ANGLE(1),

    /** Perpendicular view used to judge extent across the carriageway. */
    PERPENDICULAR(2);

    private final int displayOrder;

    ImagePerspective(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public int displayOrder() {
        return displayOrder;
    }

    /**
     * Lenient parser for client-supplied perspective labels.
     *
     * <p>Falls back to {@link #CLOSE_UP} rather than throwing: the perspective is a
     * capture hint, not a security boundary, and a handset that sends
     * {@code "close-up"} or omits the field should still be able to file a report.</p>
     */
    public static ImagePerspective fromString(String value) {
        if (value == null || value.isBlank()) {
            return CLOSE_UP;
        }
        String normalized = value.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (ImagePerspective perspective : values()) {
            if (perspective.name().equals(normalized)) {
                return perspective;
            }
        }
        return CLOSE_UP;
    }
}
