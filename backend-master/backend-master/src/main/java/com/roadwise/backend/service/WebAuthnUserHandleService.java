package com.roadwise.backend.service;

import com.roadwise.backend.model.User;
import com.roadwise.backend.model.WebAuthnUserHandle;
import com.roadwise.backend.repository.UserRepository;
import com.roadwise.backend.repository.WebAuthnUserHandleRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Adapts {@code webauthn_user_handles} onto Spring's
 * {@link PublicKeyCredentialUserEntityRepository}.
 *
 * <p>The handle is minted lazily on first passkey interaction and is then stable for the
 * life of the account. Minting it randomly (never derived from the user id) is what keeps
 * it non-identifying once it leaves the server -- it is handed to the authenticator and,
 * with a synced passkey, to a third party's cloud.
 */
@Service
public class WebAuthnUserHandleService implements PublicKeyCredentialUserEntityRepository {

    private final UserRepository userRepository;
    private final WebAuthnUserHandleRepository userHandleRepository;
    private final SecureRandom secureRandom = new SecureRandom();

    public WebAuthnUserHandleService(UserRepository userRepository,
                                     WebAuthnUserHandleRepository userHandleRepository) {
        this.userRepository = userRepository;
        this.userHandleRepository = userHandleRepository;
    }

    /**
     * Resolves the authenticated principal to its user-handle entity, minting the handle
     * on first use. The relying-party operations call this when building creation options
     * (both to embed the handle and to populate excludeCredentials).
     */
    @Override
    @Transactional
    public PublicKeyCredentialUserEntity findByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("No account matches that username."));
        return toEntity(user, handleFor(user));
    }

    @Override
    @Transactional(readOnly = true)
    public PublicKeyCredentialUserEntity findById(Bytes id) {
        return userHandleRepository.findByUserHandle(id.getBytes())
                .flatMap(handle -> userRepository.findById(handle.getUserId())
                        .map(user -> toEntity(user, handle.getUserHandle())))
                .orElse(null);
    }

    /**
     * Display-name refresh only. The handle itself is immutable for the life of the
     * account: changing it would orphan every credential bound to the old value.
     */
    @Override
    @Transactional
    public void save(PublicKeyCredentialUserEntity entity) {
        userHandleRepository.findByUserHandle(entity.getId().getBytes()).ifPresent(handle -> {
            handle.setDisplayName(entity.getDisplayName());
            userHandleRepository.save(handle);
        });
    }

    /** Handles are removed with their account by the foreign key, never individually. */
    @Override
    public void delete(Bytes id) {
        throw new UnsupportedOperationException(
                "WebAuthn user handles are deleted with their account, not individually.");
    }

    private byte[] handleFor(User user) {
        return userHandleRepository.findByUserId(user.getId())
                .map(WebAuthnUserHandle::getUserHandle)
                .orElseGet(() -> {
                    byte[] handle = new byte[32];
                    secureRandom.nextBytes(handle);
                    userHandleRepository.save(new WebAuthnUserHandle(user.getId(), handle, displayName(user)));
                    return handle;
                });
    }

    private static PublicKeyCredentialUserEntity toEntity(User user, byte[] handle) {
        return ImmutablePublicKeyCredentialUserEntity.builder()
                .name(user.getUsername())
                .id(new Bytes(handle))
                .displayName(displayName(user))
                .build();
    }

    private static String displayName(User user) {
        String name = ((user.getFirstName() == null ? "" : user.getFirstName().trim()) + " "
                + (user.getLastName() == null ? "" : user.getLastName().trim())).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }
}