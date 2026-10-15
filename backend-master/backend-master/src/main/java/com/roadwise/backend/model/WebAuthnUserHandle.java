package com.roadwise.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * The opaque WebAuthn user handle for an account.
 *
 * <p>Keeping this in its own table rather than a column on {@code users} is not
 * fastidiousness: the handle is transmitted to the authenticator and, with a synced
 * passkey, ends up in a third party's cloud. Generating it randomly and storing it
 * separately means that value carries no information about the account, which is the
 * property the specification requires and the one a derived id would quietly lose.
 */
@Entity
@Table(name = "webauthn_user_handles")
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class WebAuthnUserHandle {

    @Id
    @Column(name = "user_id")
    @JsonIgnore
    private Long userId;

    @Column(name = "user_handle", nullable = false, unique = true)
    @JsonIgnore
    private byte[] userHandle;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public WebAuthnUserHandle() {
    }

    public WebAuthnUserHandle(Long userId, byte[] userHandle, String displayName) {
        this.userId = userId;
        this.userHandle = userHandle;
        this.displayName = displayName;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public byte[] getUserHandle() { return userHandle; }
    public void setUserHandle(byte[] userHandle) { this.userHandle = userHandle; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
