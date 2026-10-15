package com.roadwise.backend.dto;

import com.roadwise.backend.model.MfaFactor;

import java.time.LocalDateTime;

/**
 * An enrolled factor as shown in account settings.
 *
 * <p>Deliberately has no field for the secret or its ciphertext: a factor's existence
 * is user-facing, its key material never is. This is a hand-written projection rather
 * than a serialised entity so that stays true even if the entity gains fields.
 */
public class MfaFactorResponseDto {

    private Long id;
    private String factorType;
    private String label;
    private String status;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;

    public static MfaFactorResponseDto from(MfaFactor factor) {
        MfaFactorResponseDto dto = new MfaFactorResponseDto();
        dto.id = factor.getId();
        dto.factorType = factor.getFactorType();
        dto.label = factor.getLabel();
        dto.status = factor.getStatus();
        dto.active = factor.isActive();
        dto.createdAt = factor.getCreatedAt();
        dto.lastUsedAt = factor.getLastUsedAt();
        return dto;
    }

    public Long getId() { return id; }
    public String getFactorType() { return factorType; }
    public String getLabel() { return label; }
    public String getStatus() { return status; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
}
