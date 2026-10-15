package com.roadwise.backend.service;

/** Published inside the revocation transaction and handled only after it commits. */
public record SessionRevokedEvent(Long userId, String reason) {
}
