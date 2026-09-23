package com.smartpg.module.user.repository;

import com.smartpg.module.user.model.User;
import com.smartpg.module.user.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access interface for the {@link UserProfile} entity.
 *
 * <p><b>Why a separate repository for UserProfile?</b>
 * Even though {@link UserProfile} has a 1-to-1 relationship with {@link User},
 * having its own repository is important because:
 * <ul>
 *   <li><b>Independent lifecycle</b>: Profile data can be updated without touching
 *       the {@code users} table at all — keeping auth-table writes minimal.</li>
 *   <li><b>PII operations</b>: GDPR anonymization (clearing Aadhaar, PAN, DOB)
 *       can be done with a targeted bulk UPDATE on just {@code user_profiles}.</li>
 *   <li><b>No join needed</b>: Instead of always loading User + Profile together,
 *       you can fetch ONLY the profile when that's all you need (profile page display).</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code user_profiles}
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    // =========================================================================
    // ① PRIMARY LOOKUP — the most common profile fetch pattern
    // =========================================================================

    /**
     * Find a user's profile by the owning User object.
     *
     * <p>Used by: {@code UserService.getMyProfile()} — after loading the
     * authenticated user from the security context, pass the {@code User}
     * object directly to avoid a second lookup.
     *
     * <p>Spring Data generates:
     * {@code SELECT * FROM user_profiles WHERE user_id = ?}
     * (it resolves {@code User} → its PK → the FK column automatically.)
     *
     * @param user the User entity whose profile to fetch
     * @return {@code Optional} containing the profile, or empty if no profile exists yet
     */
    Optional<UserProfile> findByUser(User user);

    /**
     * Find a user's profile by the user's UUID (when you only have the ID).
     *
     * <p>Used by: Admin GET /api/v1/admin/users/{id} — returns full profile
     * without loading the User entity first. Saves one DB round-trip.
     *
     * <p>Spring Data generates:
     * {@code SELECT * FROM user_profiles WHERE user_id = :userId}
     *
     * @param userId the UUID of the owning user
     * @return {@code Optional} containing the profile, or empty if not found
     */
    Optional<UserProfile> findByUserId(UUID userId);

    /**
     * Check if a profile already exists for the given user ID.
     *
     * <p>Used by: {@code UserService.createProfile()} to guard against
     * accidental duplicate profile creation. If a profile exists, update it
     * (not create another one).
     *
     * @param userId the UUID of the user
     * @return {@code true} if a profile row exists for this user
     */
    boolean existsByUserId(UUID userId);

    // =========================================================================
    // ② GDPR / ANONYMIZATION — PII wipe on account deletion
    // =========================================================================

    /**
     * Anonymize sensitive PII fields for a given user's profile.
     *
     * <p>Used by: {@code UserService.deleteUser()} — after soft-deleting the
     * user row, call this to wipe personal data per GDPR "right to erasure".
     *
     * <p><b>What gets wiped:</b>
     * <ul>
     *   <li>firstName, lastName → "DELETED_USER"</li>
     *   <li>profilePhotoUrl → null (S3 object deleted separately)</li>
     *   <li>dateOfBirth → null</li>
     *   <li>bio → null</li>
     *   <li>alternatePhone → null</li>
     *   <li>aadhaarNumber → null (encrypted value erased)</li>
     *   <li>panNumber → null</li>
     * </ul>
     *
     * <p><b>Why a @Query and not entity setters?</b>
     * A single UPDATE statement is atomic and faster than loading the entity,
     * setting fields one by one, and saving it. This operation is critical and
     * must be done in a single SQL statement to ensure consistency.
     *
     * <p><b>Note:</b> The caller should also delete the S3 profile photo object
     * after calling this method.
     *
     * @param userId the UUID of the user whose PII should be anonymized
     * @return 1 if successful, 0 if no profile was found for this user
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE UserProfile up SET
                up.firstName       = 'DELETED_USER',
                up.lastName        = 'DELETED_USER',
                up.profilePhotoUrl = NULL,
                up.dateOfBirth     = NULL,
                up.gender          = NULL,
                up.alternatePhone  = NULL,
                up.bio             = NULL,
                up.aadhaarNumber   = NULL,
                up.panNumber       = NULL
            WHERE up.user.id = :userId
            """)
    int anonymizeByUserId(@Param("userId") UUID userId);
}
