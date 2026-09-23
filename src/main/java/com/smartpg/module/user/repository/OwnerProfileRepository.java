package com.smartpg.module.user.repository;

import com.smartpg.module.user.model.OwnerProfile;
import com.smartpg.module.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Data access interface for the {@link OwnerProfile} entity.
 *
 * <p><b>Who uses this?</b>
 * <ul>
 *   <li>{@code AuthService}: creates an OwnerProfile row when a new OWNER registers.</li>
 *   <li>{@code OwnerProfileService}: handles owner's business profile CRUD.</li>
 *   <li>{@code AdminVerificationService}: updates {@code verificationStatus} when
 *       an admin reviews an owner's documents.</li>
 *   <li>{@code PropertyService}: increments/decrements {@code totalProperties}.</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code owner_profiles}
 */
@Repository
public interface OwnerProfileRepository extends JpaRepository<OwnerProfile, UUID> {

    // =========================================================================
    // ① PROFILE LOOKUPS
    // =========================================================================

    /**
     * Find the owner business profile by the associated User entity.
     *
     * <p>Used by: {@code OwnerProfileService.getMyBusinessProfile()} — takes
     * the authenticated principal ({@code User}) and fetches their owner profile.
     *
     * @param user the OWNER-role User entity
     * @return {@code Optional} with the owner profile, or empty if not found
     */
    Optional<OwnerProfile> findByUser(User user);

    /**
     * Find an owner profile by user UUID.
     *
     * <p>Used by: Admin endpoints where the caller has the user's UUID but
     * hasn't loaded the User entity — saves one extra DB query.
     *
     * @param userId the UUID of the OWNER user
     * @return {@code Optional} with the owner profile
     */
    Optional<OwnerProfile> findByUserId(UUID userId);

    /**
     * Check if an owner profile exists for a given user ID.
     *
     * <p>Used by: {@code AuthService.register()} to guard against
     * creating duplicate owner profiles on re-registration attempts.
     *
     * @param userId the UUID of the user
     * @return {@code true} if an owner profile row exists for this user
     */
    boolean existsByUserId(UUID userId);

    // =========================================================================
    // ② ADMIN VERIFICATION WORKFLOW
    //    Admin dashboard: list owners by verification status, change status, etc.
    // =========================================================================

    /**
     * Get a paginated list of owner profiles filtered by verification status.
     *
     * <p>Used by: Admin dashboard "Owners Pending Review" tab.
     * Filter: {@code verificationStatus = "IN_REVIEW"}.
     *
     * <p>Typical usage:
     * <pre>
     *   Page&lt;OwnerProfile&gt; pendingOwners =
     *       ownerProfileRepo.findByVerificationStatus("IN_REVIEW",
     *           PageRequest.of(0, 20, Sort.by("createdAt").ascending()));
     * </pre>
     *
     * @param verificationStatus the status string (UNVERIFIED, IN_REVIEW, VERIFIED, REJECTED)
     * @param pageable           pagination and sort configuration
     * @return a page of owner profiles matching the given verification status
     */
    Page<OwnerProfile> findByVerificationStatus(String verificationStatus, Pageable pageable);

    /**
     * Update the verification status of a specific owner profile.
     *
     * <p>Used by: {@code AdminVerificationService.verifyOwner(ownerProfileId, "VERIFIED")}.
     *
     * <p>A targeted UPDATE on a single column is faster than loading the full
     * OwnerProfile entity, setting one field, and triggering Hibernate's
     * dirty-check to UPDATE all columns.
     *
     * @param id                 the UUID of the OwnerProfile row (not the User UUID)
     * @param verificationStatus the new status to set
     * @return 1 if successful, 0 if the profile was not found
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OwnerProfile op SET op.verificationStatus = :status WHERE op.id = :id")
    int updateVerificationStatus(@Param("id") UUID id,
                                  @Param("status") String verificationStatus);

    // =========================================================================
    // ③ PROPERTY COUNT MANAGEMENT
    //    Called by PropertyService — keeps the cached counter in sync
    // =========================================================================

    /**
     * Atomically increment the {@code totalProperties} counter for an owner.
     *
     * <p>Used by: {@code PropertyService.createProperty()} — called after a new
     * property is successfully created and persisted for this owner.
     *
     * <p><b>Why not fetch, increment, save?</b>
     * That pattern has a race condition: if two requests create properties
     * simultaneously for the same owner, both could read {@code totalProperties = 5},
     * both increment to 6, and both write 6 — losing one count. The SQL
     * {@code SET total_properties = total_properties + 1} is atomic at the
     * DB level — no race condition possible.
     *
     * @param userId the UUID of the OWNER user (uses user_id FK in the table)
     * @return 1 if updated, 0 if no owner profile found for this user
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE OwnerProfile op SET op.totalProperties = op.totalProperties + 1 WHERE op.user.id = :userId")
    int incrementPropertyCount(@Param("userId") UUID userId);

    /**
     * Atomically decrement the {@code totalProperties} counter, clamped at 0.
     *
     * <p>Used by: {@code PropertyService.deleteProperty()} — called after a
     * property is successfully deleted.
     *
     * <p>The {@code CASE WHEN} guard ensures the counter never goes negative,
     * even if there's a bug or a duplicate delete attempt.
     *
     * @param userId the UUID of the OWNER user
     * @return 1 if updated, 0 if no owner profile found
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE OwnerProfile op
            SET op.totalProperties = CASE
                WHEN op.totalProperties > 0 THEN op.totalProperties - 1
                ELSE 0
            END
            WHERE op.user.id = :userId
            """)
    int decrementPropertyCount(@Param("userId") UUID userId);
}
