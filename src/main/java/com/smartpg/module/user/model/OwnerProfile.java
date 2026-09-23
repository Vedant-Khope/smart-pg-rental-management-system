package com.smartpg.module.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Business-specific profile for a Property Owner.
 *
 * <p><b>Why a separate entity from {@link UserProfile}?</b>
 * <ul>
 *   <li><b>Role-Specific Data</b>: Business name, GST number, and verification
 *       status are meaningless for Tenants and Caretakers. Putting them in
 *       {@code UserProfile} would pollute the table with mostly null columns
 *       for non-Owner users.</li>
 *   <li><b>Independent Lifecycle</b>: An Owner's business profile can be
 *       verified, rejected, or re-submitted independently of their personal
 *       profile. A separate entity models this cleanly.</li>
 *   <li><b>Query Efficiency</b>: Admin workflows that list "all owners pending
 *       verification" only need to scan {@code owner_profiles}, not
 *       {@code user_profiles}.</li>
 * </ul>
 *
 * <p><b>Lifecycle</b>: Created automatically when a user registers with
 * {@code role = OWNER} (handled in {@code AuthService.register()}). Not
 * applicable to any other role.
 *
 * <p><b>Table</b>: {@code owner_profiles}
 */
@Entity
@Table(
    name = "owner_profiles",
    indexes = {
        @Index(name = "idx_owner_profiles_user_id",             columnList = "user_id"),
        @Index(name = "idx_owner_profiles_verification_status", columnList = "verification_status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "user")
public class OwnerProfile {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Owning Side of the 1:1 Relationship
    // -------------------------------------------------------------------------

    /**
     * The OWNER-role user this business profile belongs to.
     *
     * <p>The {@code @JoinColumn} places the {@code user_id} FK in this table.
     * {@code unique = true} enforces the one-to-one constraint at the DB level —
     * one user can have at most one owner profile.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
                foreignKey = @ForeignKey(name = "fk_owner_profiles_user_id"))
    private User user;

    // -------------------------------------------------------------------------
    // Business Details
    // -------------------------------------------------------------------------

    /**
     * Registered business / trade name of the PG or rental business.
     * Displayed on property listings and rent receipts.
     * Optional — individual landlords may not have a formal business name.
     */
    @Column(name = "business_name", length = 255)
    private String businessName;

    /**
     * 15-character GST Identification Number (GSTIN).
     * Required for owners who charge GST on rent (commercial properties).
     * Validated against the GST format: {@code \d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]}.
     * Null for owners who are below the GST registration threshold.
     */
    @Column(name = "gst_number", length = 15)
    private String gstNumber;

    /**
     * PAN of the business / individual owner — used for TDS compliance.
     * Stored encrypted at rest (same AES-256 strategy as UserProfile PAN).
     */
    @Column(name = "pan_number", length = 64)
    private String panNumber;

    // -------------------------------------------------------------------------
    // Verification Status
    // -------------------------------------------------------------------------

    /**
     * Admin-controlled verification state of this owner's business profile.
     *
     * <p>State machine:
     * <pre>
     *   UNVERIFIED → IN_REVIEW   (owner submits docs, admin picks up for review)
     *   IN_REVIEW  → VERIFIED    (admin approves — listings can now go live)
     *   IN_REVIEW  → REJECTED    (admin rejects — owner must resubmit)
     *   REJECTED   → IN_REVIEW   (owner resubmits)
     * </pre>
     *
     * <p>Stored as {@code EnumType.STRING} — human-readable in DB logs.
     * Valid values: UNVERIFIED, IN_REVIEW, VERIFIED, REJECTED.
     */
    @Column(name = "verification_status", nullable = false, length = 20)
    private String verificationStatus = "UNVERIFIED";

    // -------------------------------------------------------------------------
    // Computed / Cached Aggregates
    // -------------------------------------------------------------------------

    /**
     * Cached count of properties this owner currently has on the platform.
     *
     * <p><b>Why cache this here?</b> Listing owners for admin dashboards often
     * needs "how many properties does this owner have?" A cached counter avoids
     * a JOIN to the {@code properties} table on every admin list query.
     *
     * <p><b>Consistency</b>: This counter is updated by {@code PropertyService}
     * whenever a property is created or deleted. It's eventually consistent —
     * not a real-time aggregate — which is acceptable for dashboard displays.
     */
    @Column(name = "total_properties", nullable = false)
    private int totalProperties = 0;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Lifecycle Hooks
    // -------------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OwnerProfile other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
