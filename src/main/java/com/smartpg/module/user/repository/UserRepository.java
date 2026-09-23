package com.smartpg.module.user.repository;

import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access interface for the {@link User} entity.
 *
 * <p><b>Why extend JpaRepository and not CrudRepository or PagingAndSortingRepository?</b>
 * {@code JpaRepository} is the top of the Spring Data JPA hierarchy. It gives you:
 * <ul>
 *   <li>{@code CrudRepository}: save, findById, delete, etc.</li>
 *   <li>{@code PagingAndSortingRepository}: findAll(Pageable), findAll(Sort).</li>
 *   <li>{@code JpaRepository} extras: flush(), saveAndFlush(), deleteAllInBatch(), etc.</li>
 * </ul>
 * You get all of that without writing a single line of implementation code.
 * Spring Data JPA generates the SQL at runtime via proxy magic.
 *
 * <p><b>Why @Repository?</b>
 * Strictly speaking, Spring Data interfaces don't need this annotation —
 * Spring auto-detects them. BUT we add it for two reasons:
 * <ol>
 *   <li>Clarity: makes it obvious this is a DAO layer class.</li>
 *   <li>Exception translation: triggers Spring's PersistenceExceptionTranslationPostProcessor
 *       to convert raw JPA/JDBC exceptions (e.g., {@code DataIntegrityViolationException})
 *       into Spring's unified {@code DataAccessException} hierarchy.</li>
 * </ol>
 *
 * <p><b>Naming conventions for derived queries:</b>
 * Spring Data parses method names like English sentences:
 * <pre>
 *   findBy[FieldName][Condition]([param])
 *   existsBy[FieldName]([param])
 *   countBy[FieldName]([param])
 * </pre>
 * Example: {@code findByEmailAndStatus(email, status)} →
 * {@code SELECT * FROM users WHERE email = ? AND status = ?}
 *
 * <p><b>When to use @Query:</b>
 * Use {@code @Query} when:
 * <ol>
 *   <li>The derived name would be unreadably long.</li>
 *   <li>You need a JOIN, aggregate, or custom projection.</li>
 *   <li>You need a bulk UPDATE/DELETE (use {@code @Modifying} too).</li>
 * </ol>
 *
 * <p><b>Table</b>: {@code users}
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // =========================================================================
    // ① AUTHENTICATION LOOKUPS — called on EVERY login / token-refresh
    //    Must be fast. These columns are indexed in the entity definition.
    // =========================================================================

    /**
     * Find a user by their email address (case-sensitive).
     *
     * <p>Used by: {@code AuthService.login()}, {@code UserDetailsServiceImpl.loadUserByUsername()}.
     *
     * <p>Returns {@code Optional<User>} (not {@code User}) because the email
     * may not exist — forcing the caller to explicitly handle the "not found"
     * case instead of getting a silent {@code NullPointerException}.
     *
     * <p>Real-world scenario: A user types "raj@gmail.com" in the login form.
     * This query fires. If no row is found → Optional.empty() → AuthService
     * throws {@code BadCredentialsException("Invalid credentials")} — NOT
     * "user not found" (never reveal which part of the credential is wrong).
     *
     * @param email the email address to look up (store/compare in lowercase)
     * @return {@code Optional} containing the user, or empty if not found
     */
    Optional<User> findByEmail(String email);

    /**
     * Find a user by their phone number.
     *
     * <p>Used by: phone-based OTP login flow.
     *
     * @param phone 10-digit phone number (no spaces, no country code)
     * @return {@code Optional} containing the user, or empty if not found
     */
    Optional<User> findByPhone(String phone);

    /**
     * Find a user by email, but only if their account is in a specific status.
     *
     * <p>Used by: {@code AuthService.login()} to reject login attempts from
     * SUSPENDED, DELETED, or PENDING_VERIFICATION accounts with a specific
     * error message.
     *
     * <p>Without this: you'd have to findByEmail first, then check status in Java.
     * With this: one SQL round-trip, and the status check happens in the DB
     * where the index on (email, status) makes it efficient.
     *
     * @param email  the email address
     * @param status the required account status (e.g., {@link UserStatus#ACTIVE})
     * @return {@code Optional} user matching both email AND status
     */
    Optional<User> findByEmailAndStatus(String email, UserStatus status);

    // =========================================================================
    // ② EXISTENCE CHECKS — for duplicate validation at registration
    //    These are "boolean" queries — no entity hydration needed.
    //    Using existsBy is more efficient than findBy: DB returns a single bit.
    // =========================================================================

    /**
     * Check if an email is already registered in the system.
     *
     * <p>Used by: {@code AuthService.register()} before creating a new user.
     * If true → throw {@code DuplicateEmailException}.
     *
     * <p>Why not just catch the DataIntegrityViolationException from the DB
     * unique constraint? You could, but then you can't give the user a clean
     * "this email is already taken" message — the DB exception is generic.
     * A pre-check gives you a clear, user-friendly error before the INSERT.
     *
     * @param email the email to check
     * @return {@code true} if a user with this email already exists
     */
    boolean existsByEmail(String email);

    /**
     * Check if a phone number is already registered.
     *
     * <p>Used by: {@code AuthService.register()} and phone-update flow.
     * Prevents two users from claiming the same phone number.
     *
     * @param phone the phone number to check
     * @return {@code true} if a user with this phone already exists
     */
    boolean existsByPhone(String phone);

    // =========================================================================
    // ③ ADMIN USER MANAGEMENT — paginated listing with filters
    //    Admin dashboard calls: GET /api/v1/admin/users?role=OWNER&status=ACTIVE
    // =========================================================================

    /**
     * Get a paginated list of all users with a specific role.
     *
     * <p>Used by: Admin dashboard to list all OWNERs, or all TENANTs, etc.
     *
     * <p>Returns {@code Page<User>} — not a plain List — because:
     * <ul>
     *   <li>Page carries total count (for the UI's "Page 2 of 14" display).</li>
     *   <li>The Pageable parameter controls page number, page size, and sort order.</li>
     *   <li>Without pagination, a query like "all tenants" could return 100,000 rows.</li>
     * </ul>
     *
     * @param role     the role to filter by
     * @param pageable pagination and sorting instructions
     * @return a page of users with the given role
     */
    Page<User> findByRole(Role role, Pageable pageable);

    /**
     * Get a paginated list of all users with a specific account status.
     *
     * <p>Used by: Admin dashboard to list SUSPENDED users, or PENDING_VERIFICATION users.
     *
     * @param status   the status to filter by
     * @param pageable pagination and sorting instructions
     * @return a page of users with the given status
     */
    Page<User> findByStatus(UserStatus status, Pageable pageable);

    /**
     * Get a paginated list of users filtered by both role AND status.
     *
     * <p>Used by: Admin query "show all ACTIVE OWNERs" or "show all SUSPENDED TENANTs".
     *
     * <p>Spring Data generates:
     * {@code SELECT * FROM users WHERE role = :role AND status = :status}
     * with LIMIT / OFFSET for pagination.
     *
     * @param role     the role to filter by
     * @param status   the status to filter by
     * @param pageable pagination and sorting instructions
     * @return a page of users matching both role and status
     */
    Page<User> findByRoleAndStatus(Role role, UserStatus status, Pageable pageable);

    // =========================================================================
    // ④ BULK OPERATIONS — admin actions that affect multiple users at once
    //    Use @Modifying + @Query because derived names can't do bulk updates.
    // =========================================================================

    /**
     * Bulk-update the status of all users matching the given IDs.
     *
     * <p>Used by: Admin action "suspend all users selected in this checkbox list".
     *
     * <p><b>Why @Modifying?</b>
     * Any DML query (UPDATE, DELETE, INSERT) via {@code @Query} MUST be annotated
     * with {@code @Modifying}, or Spring Data will throw:
     * {@code UnsupportedOperationException: Only SELECT queries are supported}.
     *
     * <p><b>Why clearAutomatically = true?</b>
     * After a bulk UPDATE via JPQL, the first-level cache (EntityManager's identity map)
     * still holds the OLD entity objects. If your service reads those entities again
     * in the same transaction, it'll see stale data. Setting {@code clearAutomatically = true}
     * forces the EntityManager to clear its cache after the UPDATE, so subsequent
     * reads go to the DB and get the fresh values.
     *
     * <p>⚠️ Be aware: clearing the cache means any unsaved entity changes in the
     * same transaction are lost. Only use this in services that don't re-read
     * the same entities immediately after the bulk update.
     *
     * @param ids    the set of user UUIDs to update
     * @param status the new status to apply to all of them
     * @return the number of rows affected
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.status = :status WHERE u.id IN :ids")
    int bulkUpdateStatus(@Param("ids") List<UUID> ids, @Param("status") UserStatus status);

    // =========================================================================
    // ⑤ SECURITY QUERIES — detecting suspicious activity
    // =========================================================================

    /**
     * Count how many users with a given role currently exist in the system.
     *
     * <p>Used by: {@code UserService.changeRole()} to guard against
     * accidentally removing the last SUPER_ADMIN. Before demoting a user,
     * the service calls this — if count is 1 and target is SUPER_ADMIN,
     * the operation is rejected.
     *
     * <p>Returns a {@code long} (not {@code int}) to match the return type of
     * {@code JpaRepository.count()} and avoid overflow on large datasets.
     *
     * @param role the role to count
     * @return the number of active users with this role
     */
    long countByRole(Role role);

    /**
     * Find all users who haven't logged in since a given timestamp.
     *
     * <p>Used by: A scheduled job that runs weekly to send "we miss you" emails
     * to ACTIVE users who haven't logged in for 30 days, or to auto-mark
     * accounts as INACTIVE after 90 days of inactivity.
     *
     * <p>The {@code IS NULL OR} condition includes users who registered but never logged in.
     *
     * @param cutoff  the timestamp threshold — users last active before this are returned
     * @param status  only check users in this status (usually ACTIVE)
     * @param pageable pagination to process in batches (don't load 50k users at once)
     * @return a page of inactive users
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.status = :status
              AND (u.lastLoginAt IS NULL OR u.lastLoginAt < :cutoff)
            """)
    Page<User> findInactiveUsers(@Param("cutoff") Instant cutoff,
                                  @Param("status") UserStatus status,
                                  Pageable pageable);

    /**
     * Update the lastLoginAt timestamp for a specific user.
     *
     * <p>Used by: {@code AuthService.login()} immediately after a successful
     * credential check. Updates only the timestamp column — no need to load,
     * modify, and save the full entity.
     *
     * <p>This is more efficient than the "find entity → set field → save entity"
     * pattern, which would trigger a full UPDATE of ALL columns via Hibernate's
     * dirty-checking. This targeted UPDATE touches only one column.
     *
     * @param id        the UUID of the user who just logged in
     * @param loginTime the exact instant of the login (typically {@code Instant.now()})
     * @return 1 if the update succeeded, 0 if the user UUID was not found
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.lastLoginAt = :loginTime WHERE u.id = :id")
    int updateLastLoginAt(@Param("id") UUID id, @Param("loginTime") Instant loginTime);

    /**
     * Soft-delete a user by setting their status to DELETED.
     *
     * <p>Used by: {@code UserService.deleteUser()} — called when a user
     * requests account deletion (self-service) or an admin deletes a user.
     *
     * <p>This is NOT a physical DELETE. The row stays in the {@code users} table
     * forever. All related entities (bookings, payments, complaints) remain
     * intact and still reference this row via FK.
     *
     * @param id the UUID of the user to soft-delete
     * @return 1 if successful, 0 if user not found
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE User u SET u.status = 'DELETED', u.updatedAt = :now WHERE u.id = :id")
    int softDeleteById(@Param("id") UUID id, @Param("now") Instant now);
}
