package com.roadwise.backend.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * CPDO / CEO engineer technical survey, appended to an existing field report.
 *
 * <p>Units follow the civil-works convention the CPDO already uses on paper and are
 * fixed here so the priority engine can be read without guessing:</p>
 *
 * <ul>
 *   <li>{@code damageLength} — metres</li>
 *   <li>{@code damageWidth} — metres</li>
 *   <li>{@code damageDepth} — centimetres</li>
 * </ul>
 *
 * <p>Everything is optional at the DTO level because a survey is recorded
 * incrementally: an engineer who has measured length and width but not yet cored for
 * depth must still be able to save progress. {@code CPDO} validation is what upgrades
 * a report out of {@code PENDING_VALIDATION}, and that step is where completeness is
 * enforced — not on every keystroke.</p>
 */
public class CpdoSurveyDto {

    @PositiveOrZero(message = "Damage length cannot be negative.")
    private Double damageLength;

    @PositiveOrZero(message = "Damage width cannot be negative.")
    private Double damageWidth;

    @PositiveOrZero(message = "Damage depth cannot be negative.")
    private Double damageDepth;

    /** "Portland Cement Concrete" or "Asphalt Concrete"; parsed via {@code SurfaceType}. */
    @Size(max = 50, message = "Surface type is too long.")
    private String surfaceType;

    /** Official scope of work. Required to validate, optional to draft. */
    @Size(max = 5000, message = "Repair plan is too long.")
    private String cpdoRepairPlan;

    public CpdoSurveyDto() {
    }

    public Double getDamageLength() { return damageLength; }
    public void setDamageLength(Double damageLength) { this.damageLength = damageLength; }

    public Double getDamageWidth() { return damageWidth; }
    public void setDamageWidth(Double damageWidth) { this.damageWidth = damageWidth; }

    public Double getDamageDepth() { return damageDepth; }
    public void setDamageDepth(Double damageDepth) { this.damageDepth = damageDepth; }

    public String getSurfaceType() { return surfaceType; }
    public void setSurfaceType(String surfaceType) { this.surfaceType = surfaceType; }

    public String getCpdoRepairPlan() { return cpdoRepairPlan; }
    public void setCpdoRepairPlan(String cpdoRepairPlan) { this.cpdoRepairPlan = cpdoRepairPlan; }
}
