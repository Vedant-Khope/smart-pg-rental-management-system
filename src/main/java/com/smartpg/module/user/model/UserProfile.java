package com.smartpg.module.user.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Extended personal and KYC information for a registered user.
 *
 * <p><b>Why is this a separate table from {@link User}?</b>
 * <ul>
 *   <li><b>Performance</b>: Authentication queries (login, token refresh) hit
 *       the {@code users} table constantly. They only need email, password hash,
 *       role, and status. Keeping bulky profile data (photo URLs, bio, Aadhaar)
 *       in a separate table means those auth queries never load data they don't need.</li>
 *   <li><b>GDPR / PII Isolation</b>: Sensitive personal data (Aadhaar, PAN)
 *       can be anonymized in a single targeted UPDATE on this table when a user
 *       requests account deletion — without touching auth records.</li>
 *   <li><b>Optional Richness</b>: All profile fields are optional at registration.
 *       A user can complete their profile progressively — no registration friction.</li>
 * </ul>
 *
 * <p><b>Encryption Note</b>:
 * {@code aadhaarNumber} and {@code panNumber} are encrypted at rest using AES-256
 * before being persisted. This is handled by an
 * {@code AttributeConverter<String, String>} — NOT done in this entity class itself.
 * The converter transparently encrypts on write and decrypts on read.
 *
 * <p><b>Table</b>: {@code user_profiles}
 */
@Entity
@Table(
    name = "user_profiles",
    indexes = {
        @Index(name = "idx_user_profiles_user_id", columnList = "user_id"),
        // Aadhaar uniqueness is enforced at the service layer (not DB) because
        // the column is stored encrypted — a DB unique constraint on ciphertext
        // would work but makes migration and re-encryption difficult.
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "user")
public class UserProfile {

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
     * The user this profile belongs to.
     *
     * <p>This side owns the foreign key column {@code user_id} in the DB.
     * The inverse side ({@link User#profile}) uses {@code mappedBy = "user"}.
     *
     * <p>{@code @JoinColumn} with {@code nullable = false} ensures every profile
     * row always references a valid user — orphaned profiles are impossible.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true,
                foreignKey = @ForeignKey(name = "fk_user_profiles_user_id"))
    private User user;

    // -------------------------------------------------------------------------
    // Basic Identity
    // -------------------------------------------------------------------------

    /**
     * Legal first name. Used in emails, receipts, and all user-facing text.
     * Not required at registration — can be set later during profile completion.
     */
    @Column(name = "first_name", length = 100)
    private String firstName;

    /**
     * Legal last name / family name.
     */
    @Column(name = "last_name", length = 100)
    private String lastName;

    /**
     * Full public URL to the user's uploaded profile photo.
     * Points to cloud storage (S3/GCS). URL is stored here after the file is
     * uploaded by {@code UserService.uploadProfilePhoto()}.
     *
     * <p>Length 500: standard S3 pre-signed URL can be long. We store the
     * permanent object URL, not the pre-signed URL.
     */
    @Column(name = "profile_photo_url", length = 500)
    private String profilePhotoUrl;

    // -------------------------------------------------------------------------
    // Personal Details
    // -------------------------------------------------------------------------

    /**
     * Date of birth in ISO-8601 format (YYYY-MM-DD).
     * Used for age verification — certain PGs have age restrictions.
     * Stored as {@code LocalDate} (no time, no timezone — just the date).
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /**
     * Gender identity. Stored as a string to future-proof against adding values
     * like 'PREFER_NOT_TO_SAY' without a DB migration.
     * Valid values: MALE, FEMALE, OTHER, PREFER_NOT_TO_SAY.
     */
    @Column(name = "gender", length = 20)
    private String gender;

    /**
     * A secondary contact number (different from the primary phone on {@link User}).
     * Useful for emergency contact or WhatsApp (if different from primary).
     */
    @Column(name = "alternate_phone", length = 15)
    private String alternatePhone;

    /**
     * Short biography or self-introduction.
     * Displayed on the tenant's public profile when applying for a PG.
     * Max ~2000 characters in practice; stored as TEXT in the DB.
     */
    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    // -------------------------------------------------------------------------
    // KYC Documents (Encrypted at Rest)
    // -------------------------------------------------------------------------

    /**
     * 12-digit Aadhaar number.
     *
     * <p><b>SECURITY</b>: This value MUST be AES-256 encrypted before storage.
     * The encryption is handled by an {@code @Convert} AttributeConverter.
     * Searching by Aadhaar requires encrypting the search value first, then
     * querying — a deterministic encryption mode (AES/ECB or AES/SIV) is
     * used to support equality lookups.
     *
     * <p>Uniqueness is NOT enforced at the DB level (because ciphertext uniqueness
     * doesn't equal plaintext uniqueness if IVs differ). The service layer
     * must check for duplicates by encrypting and querying.
     */
    @Column(name = "aadhaar_number", length = 64)   // 64 chars covers AES-128 ciphertext in Base64
    private String aadhaarNumber;

    /**
     * 10-character PAN (Permanent Account Number) issued by the Indian Income Tax dept.
     * Used for GST compliance and financial record-keeping.
     *
     * <p><b>SECURITY</b>: Encrypted at rest. Same strategy as {@code aadhaarNumber}.
     */
    @Column(name = "pan_number", length = 64)
    private String panNumber;

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
        if (!(o instanceof UserProfile other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
