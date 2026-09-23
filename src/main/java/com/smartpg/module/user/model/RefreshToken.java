package com.smartpg.module.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents an issued JWT refresh token, persisted for session management.
 *
 * <p><b>Why persist refresh tokens?</b>
 * JWTs are stateless by design — once issued, they're valid until expiry.
 * Storing refresh tokens in the database enables:
 * <ul>
 *   <li><b>Logout</b>: Revoking the refresh token immediately invalidates the
 *       session. Without this, a stolen token would remain valid for its full TTL.</li>
 *   <li><b>Multi-device sessions</b>: A user can be logged in from their phone,
 *       laptop, and tablet simultaneously, each with its own refresh token row.</li>
 *   <li><b>Device management</b>: Users (and admins) can see all active sessions
 *       and selectively revoke specific devices.</li>
 *   <li><b>Security audit</b>: IP address and device info on each token provide
 *       a trail for detecting unauthorized access from unknown locations.</li>
 * </ul>
 *
 * <p><b>Token Strategy</b>:
 * The access token (JWT) is short-lived (15 min) and NOT stored in the DB.
 * The refresh token is a random UUID string, long-lived (e.g., 30 days), and
 * IS stored here. When the access token expires, the client uses the refresh
 * token to get a new access token — and we validate it exists and is not revoked.
 *
 * <p><b>Cleanup</b>: Expired, revoked tokens should be purged periodically by a
 * scheduled job ({@code @Scheduled}) to keep the table compact.
 *
 * <p><b>Table</b>: {@code refresh_tokens}
 */
@Entity
@Table(
    name = "refresh_tokens",
    indexes = {
        // High-frequency: every token refresh hits this index
        @Index(name = "idx_refresh_tokens_token",   columnList = "token"),
        // Used when revoking all sessions for a user (logout-all, password reset)
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"),
        // Cleanup job uses this to delete old expired tokens
        @Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "user")
public class RefreshToken {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Owner
    // -------------------------------------------------------------------------

    /**
     * The user who owns this session.
     *
     * <p>{@code ManyToOne}: one user can have MANY active refresh tokens
     * (one per device/session). Each token belongs to exactly ONE user.
     *
     * <p>{@code FetchType.LAZY}: loading the full User object is not needed
     * just to validate a token — we only need {@code user_id} for that.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_refresh_tokens_user_id"))
    private User user;

    // -------------------------------------------------------------------------
    // Token Value
    // -------------------------------------------------------------------------

    /**
     * The opaque refresh token string sent to the client.
     *
     * <p>This is a randomly generated UUID (e.g., from {@code UUID.randomUUID().toString()})
     * — NOT a JWT. It's meaningless without looking it up in this table.
     *
     * <p>Stored with a {@code UNIQUE} constraint — no two tokens can have
     * the same value. The {@code @Column(unique = true)} here and the
     * DB-level constraint via {@code @Table} together ensure this.
     *
     * <p>Length 500: accommodates future migration to signed opaque tokens
     * (e.g., PASETO) which may be longer than UUIDs.
     */
    @Column(name = "token", nullable = false, unique = true, length = 500)
    private String token;

    // -------------------------------------------------------------------------
    // Expiry & Revocation
    // -------------------------------------------------------------------------

    /**
     * UTC instant when this token expires and can no longer be used.
     * Default: 30 days from issuance (configurable in application properties).
     *
     * <p>Validation rule in {@code AuthService.refreshToken()}:
     * <pre>
     *   if (refreshToken.isRevoked() || refreshToken.getExpiresAt().isBefore(Instant.now())) {
     *       throw new TokenExpiredException("Refresh token is expired or revoked");
     *   }
     * </pre>
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Whether this token has been explicitly revoked.
     *
     * <p>Set to {@code true} on:
     * <ul>
     *   <li>Logout: the specific session's token is revoked.</li>
     *   <li>Password change/reset: ALL of the user's tokens are revoked
     *       to force re-login on all devices.</li>
     *   <li>Admin suspension: all tokens for the suspended user are revoked.</li>
     * </ul>
     *
     * <p>We keep the revoked row in the DB (not delete it) so that the
     * audit log can reference it. The cleanup job eventually purges old rows.
     */
    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    // -------------------------------------------------------------------------
    // Session Context (for audit & device management UI)
    // -------------------------------------------------------------------------

    /**
     * Human-readable description of the client device/browser.
     * Parsed from the {@code User-Agent} HTTP header at login time.
     *
     * <p>Examples: "Chrome 124 on macOS", "iPhone 15 Safari", "Postman".
     * Used in the "Manage Sessions" UI so users can identify which session
     * belongs to which device and selectively revoke them.
     *
     * <p>Optional — may be null if the client doesn't send a User-Agent header.
     */
    @Column(name = "device_info", length = 500)
    private String deviceInfo;

    /**
     * IPv4 or IPv6 address of the client that obtained this token.
     *
     * <p>Max length 45 accommodates IPv6 addresses
     * (e.g., "2001:0db8:85a3:0000:0000:8a2e:0370:7334").
     *
     * <p>Used in the security audit log to flag sessions from unusual locations.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    // -------------------------------------------------------------------------
    // Audit Timestamp
    // -------------------------------------------------------------------------

    /**
     * UTC instant when this token was first issued.
     * Immutable after creation.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // -------------------------------------------------------------------------
    // Lifecycle Hook
    // -------------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Convenience Method
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if this token is no longer valid — either because
     * it has been explicitly revoked or because it has passed its expiry time.
     *
     * <p>Callers should prefer this method over checking {@code isRevoked()} and
     * {@code getExpiresAt()} separately to avoid missing one of the two conditions.
     */
    public boolean isExpiredOrRevoked() {
        return revoked || Instant.now().isAfter(expiresAt);
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
