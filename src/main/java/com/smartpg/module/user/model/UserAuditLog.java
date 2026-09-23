package com.smartpg.module.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record of significant events on a user account.
 *
 * <p><b>Purpose</b>: Provides a tamper-evident trail of security-sensitive actions
 * for compliance, forensic investigations, and admin visibility. Examples:
 * <ul>
 *   <li>Failed login attempts from an unusual IP → flag for review</li>
 *   <li>Password changed at 3am from a new device → notify user via email</li>
 *   <li>Role elevated from TENANT to ADMIN → who did it and when?</li>
 * </ul>
 *
 * <p><b>Immutability Contract</b>:
 * Audit log rows are APPEND-ONLY. The application MUST NEVER issue UPDATE or DELETE
 * on this table. Enforce this by:
 * <ul>
 *   <li>No setter for {@code id} or {@code timestamp}.</li>
 *   <li>No {@code @PreUpdate} hook.</li>
 *   <li>Granting only INSERT + SELECT to the application DB user on this table
 *       (via Flyway migration: {@code REVOKE UPDATE, DELETE ON user_audit_logs FROM app_user}).</li>
 * </ul>
 *
 * <p><b>Retention</b>: Log rows must be retained for a minimum of 1 year
 * (per the NFR-5.7 compliance requirement). An archival job moves rows older
 * than 1 year to a cold storage table / S3 parquet archive.
 *
 * <p><b>Performance</b>: This is a write-heavy table on busy systems. Consider:
 * <ul>
 *   <li>Table partitioning by month (PostgreSQL range partitioning on {@code timestamp})</li>
 *   <li>Async writes via an event queue — don't block the login response on audit log write</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code user_audit_logs}
 */
@Entity
@Table(
    name = "user_audit_logs",
    indexes = {
        // Most common query: "show all logs for user X"
        @Index(name = "idx_user_audit_logs_user_id",   columnList = "user_id"),
        // Admin investigation: "show all FAILED_LOGIN events in the last hour"
        @Index(name = "idx_user_audit_logs_action",    columnList = "action"),
        // Retention / archival: "find all rows older than 1 year"
        @Index(name = "idx_user_audit_logs_timestamp", columnList = "timestamp"),
        // Composite: "all FAILED_LOGIN for user X between t1 and t2"
        @Index(name = "idx_user_audit_logs_user_action_ts", columnList = "user_id, action, timestamp")
    }
)
@Getter
@NoArgsConstructor  // Required by JPA
@ToString
public class UserAuditLog {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Who did it
    // -------------------------------------------------------------------------

    /**
     * The user whose account was affected by this event.
     *
     * <p>{@code ManyToOne}: one user can have many log entries.
     * {@code optional = false}: every log entry must reference a user.
     *
     * <p>Note: the FK is NOT {@code ON DELETE CASCADE}. If a user is
     * soft-deleted, their audit logs MUST be preserved for compliance.
     * Hard deletes on the {@code users} table are forbidden by design.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
                foreignKey = @ForeignKey(name = "fk_user_audit_logs_user_id"))
    private User user;

    // -------------------------------------------------------------------------
    // What happened
    // -------------------------------------------------------------------------

    /**
     * The type of action that was performed.
     * Stored as {@code EnumType.STRING} for readability.
     *
     * @see AuditAction
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private AuditAction action;

    /**
     * Whether this action completed successfully or not.
     * Stored as {@code EnumType.STRING}: "SUCCESS" or "FAILURE".
     *
     * @see AuditStatus
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AuditStatus status;

    /**
     * Free-form JSON payload for event-specific metadata.
     *
     * <p>Examples:
     * <ul>
     *   <li>LOGIN failure: {@code {"reason": "BAD_PASSWORD", "attemptCount": 3}}</li>
     *   <li>ROLE_CHANGE: {@code {"oldRole": "TENANT", "newRole": "ADMIN", "changedBy": "uuid"}}</li>
     *   <li>STATUS_CHANGE: {@code {"oldStatus": "ACTIVE", "newStatus": "SUSPENDED", "reason": "Fraud report"}}</li>
     * </ul>
     *
     * <p>Stored as {@code jsonb} in PostgreSQL for efficient querying of nested keys.
     * Using {@code columnDefinition = "jsonb"} tells Hibernate to use the DB's
     * native JSON type instead of plain TEXT.
     */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    // -------------------------------------------------------------------------
    // Where it happened (for security analysis)
    // -------------------------------------------------------------------------

    /**
     * Client IP address (IPv4 or IPv6).
     * Used to detect geographically anomalous login patterns.
     * May be null if the request came from an internal service with no client IP.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Raw {@code User-Agent} header from the HTTP request.
     * Used to identify the browser/app that performed the action.
     * Helps distinguish "Chrome on Windows" from "unknown bot".
     */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    // -------------------------------------------------------------------------
    // When it happened
    // -------------------------------------------------------------------------

    /**
     * Precise UTC timestamp of this event.
     * Set once at creation and never modified (immutable record).
     *
     * <p>This column is the primary axis for time-range queries and
     * the partitioning key for database table partitioning.
     */
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    // -------------------------------------------------------------------------
    // All-args Constructor (enforces immutability — no public setters)
    // -------------------------------------------------------------------------

    /**
     * Primary constructor for creating an audit log entry.
     *
     * <p>Immutability is enforced by:
     * <ul>
     *   <li>Only this constructor sets all meaningful fields.</li>
     *   <li>No {@code @Setter} annotation — Lombok won't generate setters.</li>
     *   <li>Timestamp is set inside this constructor, not by the caller — prevents
     *       caller from backdating events.</li>
     * </ul>
     *
     * @param user      the user whose account was affected
     * @param action    the type of event that occurred
     * @param status    whether the event was a success or failure
     * @param ipAddress the IP address of the actor
     * @param userAgent the User-Agent string from the HTTP request
     * @param details   JSON string with any event-specific metadata
     */
    public UserAuditLog(User user, AuditAction action, AuditStatus status,
                        String ipAddress, String userAgent, String details) {
        this.user      = user;
        this.action    = action;
        this.status    = status;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.details   = details;
        this.timestamp = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Nested Enums
    // -------------------------------------------------------------------------

    /**
     * Enum of all loggable action types.
     *
     * <p>Kept as a nested enum to co-locate the definition with the entity that
     * uses it. If reused elsewhere, extract to its own file.
     */
    public enum AuditAction {
        /** Successful or failed login attempt. */
        LOGIN,
        /** Failed login attempt (bad credentials). */
        FAILED_LOGIN,
        /** Explicit logout — refresh token revoked. */
        LOGOUT,
        /** User changed their own password after providing the old one. */
        PASSWORD_CHANGE,
        /** Password was reset via OTP or email reset link. */
        PASSWORD_RESET,
        /** An admin or super_admin changed this user's role. */
        ROLE_CHANGE,
        /** An admin changed this user's account status (e.g., suspended). */
        STATUS_CHANGE,
        /** User or admin updated profile information. */
        PROFILE_UPDATE,
        /** A refresh token was used to issue a new access token. */
        TOKEN_REFRESH,
        /** Email or phone OTP verification was completed. */
        VERIFICATION
    }

    /**
     * Outcome of the audited action.
     */
    public enum AuditStatus {
        /** The action completed without error. */
        SUCCESS,
        /** The action failed (e.g., wrong password, expired token). */
        FAILURE
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserAuditLog other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
