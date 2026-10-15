package com.roadwise.backend.service;

import com.roadwise.backend.model.MfaFactor;
import com.roadwise.backend.repository.MfaFactorRepository;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.repository.WebAuthnUserHandleRepository;
import org.springframework.security.web.webauthn.api.*;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adapts Spring Security's {@link UserCredentialRepository} onto
 * {@code user_mfa_factors}.
 *
 * <p>Spring ships {@code JdbcUserCredentialRepository}, which expects its own
 * {@code user_credentials} table. That is deliberately not used here. Passkeys are a
 * factor type like any other in this application, and a second credential store would
 * mean {@code MfaPolicyService} could only ever see part of a user's factor set -- so
 * the ANY_ONE policy, the "don't remove your last factor" guard, and the recovery-code
 * escape hatch would all silently ignore passkeys.
 */
@Service
public class WebAuthnCredentialRepository implements UserCredentialRepository {

    private final MfaFactorRepository mfaFactorRepository;
    private final WebAuthnUserHandleRepository userHandleRepository;
    private final UserRepository userRepository;

    public WebAuthnCredentialRepository(MfaFactorRepository mfaFactorRepository,
                                       WebAuthnUserHandleRepository userHandleRepository,
                                       UserRepository userRepository) {
        this.mfaFactorRepository = mfaFactorRepository;
        this.userHandleRepository = userHandleRepository;
        this.userRepository = userRepository;
    }

    /**
     * Upsert. Called by the relying-party operations both when a registration ceremony
     * completes and when an assertion refreshes the signature counter.
     *
     * <p>The factor is stored {@code ACTIVE} immediately, with no equivalent of TOTP's
     * "prove you can generate a code" confirmation step. That is not a shortcut: the
     * registration ceremony has already been cryptographically proved by the
     * authenticator signing with the freshly created key, so the proof already happened.
     */
    @Override
    @Transactional
    public void save(CredentialRecord record) {
        byte[] credentialId = record.getCredentialId().getBytes();
        MfaFactor factor = mfaFactorRepository.findByCredentialId(credentialId).orElse(null);

        if (factor == null) {
            Long ownerId = userHandleRepository.findByUserHandle(record.getUserEntityUserId().getBytes())
                    .map(handle -> handle.getUserId())
                    .orElseThrow(() -> new IllegalStateException(
                            "No account is mapped to the user handle on this credential."));
            factor = new MfaFactor(
                    // A lazy reference: only the foreign key is written, so this does not
                    // read the user row back just to satisfy the association.
                    userRepository.getReferenceById(ownerId),
                    MfaFactor.TYPE_WEBAUTHN,
                    record.getLabel(),
                    MfaFactor.STATUS_ACTIVE);
            factor.setCredentialId(credentialId);
        }

        if (!MfaFactor.STATUS_ACTIVE.equals(factor.getStatus())) {
            // A completed registration ceremony is cryptographic proof the key works, so a
            // row still marked disabled is reinstated rather than left silently unusable.
            // Previously this path updated the row and left it DISABLED, producing a
            // passkey that looked enrolled and could never authenticate.
            factor.setStatus(MfaFactor.STATUS_ACTIVE);
            factor.setDisabledAt(null);
        }

        factor.setPublicKey(record.getPublicKey().getBytes());
        factor.setSignatureCount(record.getSignatureCount());
        factor.setUvInitialized(record.isUvInitialized());
        factor.setBackupEligible(record.isBackupEligible());
        factor.setBackupState(record.isBackupState());
        // Transports reported by the authenticator are a browser hint, not part of
        // cryptographic verification, and the API exposes no stable accessor for them in
        // this version, so the transports column is left unwritten rather than guessed at.
        factor.setTransports(null);
        factor.setAttestationObject(toBytes(record.getAttestationObject()));
        factor.setAttestationClientDataJson(toBytes(record.getAttestationClientDataJSON()));
        factor.setLastUsedAt(LocalDateTime.now());
        mfaFactorRepository.save(factor);
    }

    @Override
    @Transactional
    public void delete(Bytes credentialId) {
        mfaFactorRepository.findByCredentialId(credentialId.getBytes()).ifPresent(factor -> {
            factor.setStatus(MfaFactor.STATUS_DISABLED);
            factor.setDisabledAt(LocalDateTime.now());
            // Same invariant the management flow applies. The removal guard
            // (assertCanRemove) deliberately does NOT live here: this is a low-level
            // store primitive, and policy belongs in the flow a user actually drives.
            factor.clearKeyMaterial();
            mfaFactorRepository.save(factor);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public CredentialRecord findByCredentialId(Bytes credentialId) {
        // Only ACTIVE rows: a disabled passkey keeps its row for history but must never
        // satisfy an assertion. The handle is resolved from its own table.
        //
        // Nullable return, not Optional -- that is this interface's own contract.
        return mfaFactorRepository
                .findByCredentialIdAndStatus(credentialId.getBytes(), MfaFactor.STATUS_ACTIVE)
                .flatMap(factor -> handleFor(factor).map(handle -> toRecord(factor, handle)))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialRecord> findByUserId(Bytes userId) {
        return userHandleRepository.findByUserHandle(userId.getBytes())
                .map(handle -> mfaFactorRepository
                        .findByUserIdAndFactorTypeAndStatus(
                                handle.getUserId(), MfaFactor.TYPE_WEBAUTHN, MfaFactor.STATUS_ACTIVE)
                        .stream()
                        .filter(factor -> factor.getCredentialId() != null)
                        .map(factor -> toRecord(factor, handle.getUserHandle()))
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Maps a factor row back to a {@link CredentialRecord}.
     *
     * <p>The user handle is passed in rather than read from the factor, because it
     * lives in {@code webauthn_user_handles} and not on the factor row. That
     * separation is intentional -- the handle is transmitted to the authenticator and
     * into any cloud that syncs the passkey, so it is kept out of the factor table.
     */
    static CredentialRecord toRecord(MfaFactor factor, byte[] userHandle) {
        return ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(new Bytes(factor.getCredentialId()))
                .userEntityUserId(new Bytes(userHandle))
                .publicKey(new ImmutablePublicKeyCose(factor.getPublicKey()))
                .signatureCount(factor.getSignatureCount())
                .uvInitialized(factor.isUvInitialized())
                .transports(Set.of())
                .backupEligible(Boolean.TRUE.equals(factor.getBackupEligible()))
                .backupState(Boolean.TRUE.equals(factor.getBackupState()))
                .attestationObject(toBytesOrNull(factor.getAttestationObject()))
                .attestationClientDataJSON(toBytesOrNull(factor.getAttestationClientDataJson()))
                .created(toInstant(factor.getCreatedAt()))
                .lastUsed(toInstant(factor.getLastUsedAt()))
                .label(factor.getLabel() == null ? "Passkey" : factor.getLabel())
                .build();
    }


    /** @return the stored handle for a factor's owner, or empty when none exists yet. */
    private Optional<byte[]> handleFor(MfaFactor factor) {
        return userHandleRepository.findByUserId(factor.getUser().getId())
                .map(handle -> handle.getUserHandle());
    }


    private static byte[] toBytes(Bytes bytes) {
        return bytes == null ? null : bytes.getBytes();
    }

    private static Bytes toBytesOrNull(byte[] bytes) {
        return bytes == null ? null : new Bytes(bytes);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? Instant.now() : value.atZone(ZoneId.systemDefault()).toInstant();
    }
}
