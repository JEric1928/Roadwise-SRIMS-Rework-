package com.roadwise.backend.repository;

import com.roadwise.backend.model.WebAuthnUserHandle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WebAuthnUserHandleRepository extends JpaRepository<WebAuthnUserHandle, Long> {

    Optional<WebAuthnUserHandle> findByUserId(Long userId);

    Optional<WebAuthnUserHandle> findByUserHandle(byte[] userHandle);
}
