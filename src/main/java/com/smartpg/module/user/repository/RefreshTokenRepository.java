package com.smartpg.module.user.repository;

import com.smartpg.module.user.model.RefreshToken;
import com.smartpg.module.user.model.User;
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
 * Data access interface for the {@link RefreshToken} entity.
 *
 * <p><b>High-frequency operations on this table:</b>
 * <ol>
 *   <li><b>Token validation</b>: Every API call that uses a refresh token
 *       calls {@link #findByToken(String)} — happens many times per day.</li>
 *   <li><b>Logout</b>: Marks one token as revoked.</li>
 *   <li><b>Password change/reset</b>: Revokes ALL tokens for a user.</li>
 *   <li><b>Cleanup</b>: Scheduled job deletes old expired+revoked tokens.</li>
 * </ol>
 *
 * <p><b>Why does this table grow fast?</b>
 * Every login from every device creates a new row. A user on 3 devices, logging in
 * daily, creates ~90 rows/month. With 1,000 users, that's 90,000 rows/month.
 * The scheduled cleanup job is non-optional for production.
 *
 * <p><b>Table</b>: {@code refresh_tokens}
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // =========================================================================
    // ① TOKEN VALIDATION — called on EVERY /auth/refresh endpoint hit
    // =========================================================================

    /**
     * Find a refresh token by its opaque token string value.
     *
     * <p>This is THE most-called query in the entire auth system. Every time
     * a client hits {@code POST /api/v1/auth/refresh}, this method fires.
     *
     * <p>The {@code token} column has:
     * <ul>
     *   <li>A UNIQUE constraint (enforced at DB level).</li>
     *   <li>An index ({@code idx_refresh_tokens_token}) for O(log n) lookup.</li>
     * </ul>
     *
     * <p>Flow after finding the token:
     * <pre>
     *   Optional&lt;RefreshToken&gt; opt = repo.findByToken(tokenStr);
     *   RefreshToken rt = opt.orElseThrow(() -> new TokenNotFoundException());
     *   if (rt.isExpiredOrRevoked()) {
     *       throw new TokenExpiredException();
     *   }
     *   // Issue new access token...
     * </pre>
     *
     * @param token the raw token string sent by the client (in request body or cookie)
     * @return {@code Optional} containing the RefreshToken if found
     */
    Optional<RefreshToken> findByToken(String token);

    // =========================================================================
    // ② SESSION MANAGEMENT — user's active sessions
    // =========================================================================

    /**
     * Find all non-revoked refresh tokens for a given user.
     *
     * <p>Used by: "Manage Sessions" feature — shows the user a list of all
     * their active devices/sessions with device info and IP address.
     *
     * <p>Only returns non-revoked tokens; expired-but-not-revoked tokens are also
     * excluded here (the user-facing UI should only show sessions they could
     * still use).
     *
     * <p>Note: Expired tokens where {@code expiresAt < now} are also returned
     * by this query — the service layer should filter them further using
     * {@link RefreshToken#isExpiredOrRevoked()} for UI display.
     *
     * @param user the user whose active sessions to list
     * @return list of non-revoked tokens for this user (may include expired ones)
     */
    List<RefreshToken> findByUserAndRevokedFalse(User user);

    /**
     * Find all non-revoked and non-expired refresh tokens for a given user.
     *
     * <p>Used by: Admin "how many devices is this user currently active on?"
     *
     * @param user      the user
     * @param now       current time — tokens with {@code expiresAt > now} are still valid
     * @return list of truly active (non-revoked, non-expired) tokens
     */
    @Query("""
            SELECT rt FROM RefreshToken rt
            WHERE rt.user = :user
              AND rt.revoked = false
              AND rt.expiresAt > :now
            """)
    List<RefreshToken> findActiveTokensByUser(@Param("user") User user,
                                               @Param("now") Instant now);

    // =========================================================================
    // ③ REVOCATION OPERATIONS
    // =========================================================================

    /**
     * Revoke a single refresh token by its string value (logout from one device).
     *
     * <p>Used by: {@code AuthService.logout(tokenStr)} — the client sends the
     * refresh token string in the request body. We find the row and mark it revoked.
     *
     * <p>We use a targeted UPDATE (not "find → set revoked = true → save") to
     * avoid loading the full entity + related User just to flip one boolean.
     *
     * @param token the raw token string to revoke
     * @return 1 if revoked, 0 if token was not found
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.token = :token")
    int revokeByToken(@Param("token") String token);

    /**
     * Revoke ALL refresh tokens for a given user (logout from all devices).
     *
     * <p>Used by: Security-critical events that require a global session invalidation:
     * <ul>
     *   <li>Password change: forces re-login everywhere — prevents a thief who
     *       has your old password from staying logged in after you change it.</li>
     *   <li>Password reset: same reason.</li>
     *   <li>Admin suspension: immediately kicks the user out of all sessions.</li>
     *   <li>Account deletion (soft): terminates all active sessions.</li>
     * </ul>
     *
     * <p>Uses {@code user.id} to avoid joining through the User entity — more
     * efficient when you only have the UUID (common in admin workflows).
     *
     * @param userId the UUID of the user whose sessions to terminate
     * @return the number of tokens revoked (equals the number of active sessions)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user.id = :userId AND rt.revoked = false")
    int revokeAllByUserId(@Param("userId") UUID userId);

    // =========================================================================
    // ④ CLEANUP — scheduled maintenance to keep the table compact
    // =========================================================================

    /**
     * Delete all refresh tokens that are either expired or revoked.
     *
     * <p>Used by: A scheduled job ({@code @Scheduled(cron = "0 0 2 * * ?")}) that
     * runs every night at 2 AM to purge stale rows.
     *
     * <p><b>Why delete instead of keeping?</b>
     * Revoked and expired tokens have zero functional value — they can never be
     * used again. Keeping them forever wastes storage and slows down the
     * {@link #findByToken} lookup (more rows = more index pages to scan).
     *
     * <p>Only security-relevant tokens (used ones, active ones) are kept.
     * This is safe because the audit trail is in {@code user_audit_logs},
     * not in this table.
     *
     * @param cutoff delete tokens that expired before this time (usually: now minus 7 days for grace period)
     * @return the number of rows deleted
     */
    @Modifying
    @Query("""
            DELETE FROM RefreshToken rt
            WHERE rt.revoked = true
               OR rt.expiresAt < :cutoff
            """)
    int deleteExpiredAndRevokedTokens(@Param("cutoff") Instant cutoff);

    /**
     * Count how many active (non-revoked, non-expired) sessions a user has.
     *
     * <p>Used by: A session limit guard — if an owner is limited to 5 concurrent
     * sessions, this is checked before issuing a new refresh token on login.
     * If count >= 5, the oldest session is revoked first.
     *
     * @param userId the UUID of the user
     * @param now    current time to check expiry
     * @return the number of currently active sessions
     */
    @Query("""
            SELECT COUNT(rt) FROM RefreshToken rt
            WHERE rt.user.id = :userId
              AND rt.revoked = false
              AND rt.expiresAt > :now
            """)
    long countActiveSessionsByUserId(@Param("userId") UUID userId,
                                      @Param("now") Instant now);
}
