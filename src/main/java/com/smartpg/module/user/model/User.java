package com.smartpg.module.user.model;

import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * The central identity record for every actor in the Smart PG platform.
 *
 * <p><b>Design Decisions:</b>
 * <ul>
 *   <li><b>UUID Primary Key</b>: Non-sequential IDs prevent enumeration attacks
 *       (a bad actor can't guess valid user IDs by incrementing an integer).
 *       {@code @UuidGenerator} lets Hibernate generate the UUID before INSERT,
 *       so we never get a null ID on a new, unsaved entity.</li>
 *   <li><b>Separation of Concerns</b>: This entity holds ONLY authentication and
 *       identity data. Profile details (name, photo, Aadhaar) live in
 *       {@link UserProfile} and {@link OwnerProfile}. This keeps the {@code users}
 *       table narrow and fast for the high-frequency auth queries.</li>
 *   <li><b>No @Data on JPA Entities</b>: Lombok's {@code @Data} generates
 *       {@code equals/hashCode} using all fields, which causes infinite loops
 *       with bidirectional JPA relationships. We use {@code @Getter/@Setter}
 *       and explicitly implement {@code equals/hashCode} on the {@code id} field.</li>
 *   <li><b>Soft Delete</b>: Accounts are NEVER physically deleted. Setting
 *       {@code status = DELETED} preserves audit trails, payment history, and
 *       referential integrity. See {@link UserStatus#DELETED}.</li>
 *   <li><b>Audit Timestamps</b>: {@code @CreationTimestamp} / {@code @UpdateTimestamp}
 *       are managed by Hibernate — no manual {@code new Date()} calls anywhere.</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code users}
 */
@Entity
@Table(
    name = "users",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_users_email",  columnNames = "email"),
        @UniqueConstraint(name = "uk_users_phone",  columnNames = "phone")
    },
    indexes = {
        @Index(name = "idx_users_email",  columnList = "email"),
        @Index(name = "idx_users_phone",  columnList = "phone"),
        @Index(name = "idx_users_status", columnList = "status"),
        @Index(name = "idx_users_role",   columnList = "role")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = {"profile", "ownerProfile"})
public class User {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    /**
     * System-wide unique identifier.
     * Generated as a random UUID by Hibernate before INSERT — never null on a
     * managed entity, even before the first {@code flush()}.
     */
    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Authentication Credentials
    // -------------------------------------------------------------------------

    /**
     * Primary login identifier (email address).
     * Must be globally unique. Stored in lowercase to prevent case-sensitive
     * duplicate registrations (enforce toLowerCase in the service layer).
     */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /**
     * Optional secondary login identifier.
     * Users can register with email only; phone is required for OTP verification
     * flows and SMS notifications. Must be globally unique when provided.
     * Format: digits only, no spaces or dashes (e.g., "9876543210").
     */
    @Column(name = "phone", length = 15)
    private String phone;

    /**
     * BCrypt-hashed password.
     * NEVER store plaintext passwords. The service layer must hash with
     * {@code BCryptPasswordEncoder} (cost factor ≥ 12) before setting this field.
     *
     * <p>The column is named {@code password_hash} (not "password") to make it
     * obvious in DB logs and schema dumps that this is a hash, not plaintext.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // -------------------------------------------------------------------------
    // Role & Status
    // -------------------------------------------------------------------------

    /**
     * The single role that determines what this user is allowed to do.
     * Stored as a String in the DB (EnumType.STRING) so schema is human-readable
     * and adding new roles doesn't corrupt existing rows (unlike ORDINAL).
     *
     * @see Role
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    /**
     * Lifecycle state of this account.
     * Controls whether the user can log in, and what admin actions are available.
     * Defaults to {@link UserStatus#PENDING_VERIFICATION} on registration.
     *
     * @see UserStatus
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    // -------------------------------------------------------------------------
    // Verification Flags
    // -------------------------------------------------------------------------

    /**
     * Whether this user has clicked the email verification link.
     * An unverified email means OTP-only reset flows won't reach the user,
     * and certain admin features may be restricted.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /**
     * Whether this user has verified their phone number via OTP.
     * Required before phone-based login or SMS notifications are activated.
     */
    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    /**
     * Exact moment this account was created.
     * Set once on INSERT, never updated. Stored as UTC epoch in the DB.
     *
     * <p>Why {@code Instant}? It's timezone-agnostic. All timestamps in the
     * system are stored in UTC and converted to the user's timezone only at the
     * presentation layer.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Exact moment of the last update to this record.
     * Automatically refreshed by Hibernate on every {@code merge()}.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Timestamp of the user's most recent successful login.
     * Useful for: detecting inactive accounts, showing "last seen" info to admins,
     * and triggering inactivity warnings.
     *
     * <p>Nullable — a user who registered but never logged in has no last login.
     */
    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    // -------------------------------------------------------------------------
    // Relationships
    // -------------------------------------------------------------------------

    /**
     * The extended personal profile for this user (name, photo, DOB, KYC).
     *
     * <p>{@code CascadeType.ALL}: persisting/deleting a User cascades to its Profile.
     * {@code orphanRemoval = true}: if profile is set to null on the User,
     * Hibernate deletes the orphaned UserProfile row from the DB.
     * {@code fetch = LAZY}: profile is NOT loaded unless explicitly accessed —
     * keeps auth-path queries fast.
     */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private UserProfile profile;

    /**
     * Business profile — only populated for users with {@link Role#OWNER}.
     * Null for all other roles.
     */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private OwnerProfile ownerProfile;

    // -------------------------------------------------------------------------
    // Lifecycle Hooks
    // -------------------------------------------------------------------------

    /**
     * Called by Hibernate before the first INSERT.
     * Ensures created/updated timestamps are always populated — even if the caller
     * forgets to set them explicitly in the service layer.
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Called by Hibernate before every UPDATE.
     * Always refreshes {@code updatedAt} to the current UTC instant.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    /**
     * Equality is based solely on {@code id}.
     *
     * <p>WHY NOT @EqualsAndHashCode from Lombok?
     * Lombok's {@code @Data} / {@code @EqualsAndHashCode} uses all fields by
     * default, which causes:
     * (a) Infinite loops with bidirectional JPA relationships.
     * (b) Broken {@code HashSet} behavior — a new entity (id=null) can't be
     *     distinguished from another new entity.
     *
     * <p>Rule: JPA entities should implement equals/hashCode on their business
     * key (here: {@code id}), and handle the pre-persist null case gracefully.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        // A fixed constant is intentional: a new entity (id=null) gets
        // the same hash as all other new entities, which is correct —
        // they're distinguishable by equals(). Once the id is assigned,
        // a UUID-based hash would change, breaking HashSet/HashMap.
        return getClass().hashCode();
    }
}
