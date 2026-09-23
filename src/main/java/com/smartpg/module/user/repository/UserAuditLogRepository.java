package com.smartpg.module.user.repository;

import com.smartpg.module.user.model.UserAuditLog;
import com.smartpg.module.user.model.UserAuditLog.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Data access interface for the {@link UserAuditLog} entity.
 *
 * <p><b>CRITICAL: This is a READ + APPEND-ONLY repository.</b>
 * There are NO update or delete methods defined here. The application MUST
 * only INSERT and SELECT on this table. This is enforced by:
 * <ol>
 *   <li>Not exposing any {@code @Modifying} / UPDATE / DELETE queries here.</li>
 *   <li>The DB user having only INSERT + SELECT privileges on this table
 *       (enforced via Flyway migration).</li>
 *   <li>The {@link UserAuditLog} entity having no setters — impossible to
 *       mutate and accidentally save via Hibernate.</li>
 * </ol>
 *
 * <p><b>Why keep delete out?</b>
 * Deleting audit logs defeats their purpose. If a compromised admin
 * could delete LOGIN_FAILURE events, they could hide a brute-force attack.
 * The log is evidence — treat it as immutable.
 *
 * <p><b>Table</b>: {@code user_audit_logs}
 */
@Repository
public interface UserAuditLogRepository extends JpaRepository<UserAuditLog, UUID> {

    // =========================================================================
    // ① USER-SPECIFIC HISTORY — accessed via user profile / admin view
    // =========================================================================

    /**
     * Get a paginated, reverse-chronological audit trail for a specific user.
     *
     * <p>Used by:
     * <ul>
     *   <li>Admin endpoint: {@code GET /api/v1/admin/users/{id}/audit-logs}</li>
     *   <li>User self-service: "Recent Account Activity" on the profile page</li>
     * </ul>
     *
     * <p>Always paginate this — a long-tenured user could have thousands of logs.
     * Typical page size: 20 entries.
     *
     * @param userId   the UUID of the user whose history to fetch
     * @param pageable pagination + sort (usually sort by timestamp DESC)
     * @return a page of audit log entries for this user
     */
    Page<UserAuditLog> findByUserId(UUID userId, Pageable pageable);

    /**
     * Get a paginated audit trail for a user, filtered by a specific action type.
     *
     * <p>Used by: Admin investigation "show me all FAILED_LOGIN events for this user".
     * Helps identify brute-force attempts or compromised accounts.
     *
     * @param userId   the UUID of the user
     * @param action   the type of action to filter (e.g., {@link AuditAction#FAILED_LOGIN})
     * @param pageable pagination config
     * @return a page of audit log entries matching user + action
     */
    Page<UserAuditLog> findByUserIdAndAction(UUID userId, AuditAction action, Pageable pageable);

    /**
     * Get audit logs for a user within a specific time window.
     *
     * <p>Used by: Admin security investigation "what happened on this account
     * between 2 AM and 3 AM last night?" — timeline-scoped lookups.
     *
     * <p>The composite index on {@code (user_id, action, timestamp)} makes
     * this query fast even on large datasets.
     *
     * @param userId the UUID of the user
     * @param from   start of the time window (inclusive)
     * @param to     end of the time window (inclusive)
     * @param pageable pagination config
     * @return paginated audit logs within the time window
     */
    @Query("""
            SELECT ual FROM UserAuditLog ual
            WHERE ual.user.id = :userId
              AND ual.timestamp >= :from
              AND ual.timestamp <= :to
            ORDER BY ual.timestamp DESC
            """)
    Page<UserAuditLog> findByUserIdAndTimestampBetween(@Param("userId") UUID userId,
                                                        @Param("from") Instant from,
                                                        @Param("to") Instant to,
                                                        Pageable pageable);

    // =========================================================================
    // ② PLATFORM-WIDE SECURITY ANALYTICS — admin/SUPER_ADMIN only
    // =========================================================================

    /**
     * Get a paginated, platform-wide list of audit events of a specific type.
     *
     * <p>Used by: Super admin security dashboard.
     * Examples:
     * <ul>
     *   <li>List all {@code FAILED_LOGIN} in the last hour — spike = brute force</li>
     *   <li>List all {@code ROLE_CHANGE} events — who changed whose role and when?</li>
     *   <li>List all {@code STATUS_CHANGE} events — which users were suspended?</li>
     * </ul>
     *
     * @param action   the audit action type to filter by
     * @param pageable pagination config
     * @return a page of matching audit log entries across all users
     */
    Page<UserAuditLog> findByAction(AuditAction action, Pageable pageable);

    /**
     * Count failed login attempts for a specific user since a given time.
     *
     * <p>Used by: Brute-force protection in {@code AuthService.login()}.
     * Before processing a login attempt, check how many FAILED_LOGINs the
     * user had in the past 15 minutes. If >= 5, reject with 429 Too Many Requests
     * and potentially trigger a CAPTCHA or temporary lock.
     *
     * <p>This is more reliable than in-memory counters (which reset on server restart).
     * The count is always accurate because it's sourced from the persistent log.
     *
     * @param userId the UUID of the user attempting to log in
     * @param since  the start of the window (e.g., {@code Instant.now().minus(15, MINUTES)})
     * @return the number of FAILED_LOGIN audit entries for this user since {@code since}
     */
    @Query("""
            SELECT COUNT(ual) FROM UserAuditLog ual
            WHERE ual.user.id = :userId
              AND ual.action = com.smartpg.module.user.model.UserAuditLog$AuditAction.FAILED_LOGIN
              AND ual.timestamp >= :since
            """)
    long countRecentFailedLogins(@Param("userId") UUID userId,
                                  @Param("since") Instant since);

    /**
     * Get platform-wide audit logs within a time range — for security incident review.
     *
     * <p>Used by: Super admin investigating a platform-wide incident.
     * "Show me everything that happened between T1 and T2."
     *
     * @param from     start of the time window
     * @param to       end of the time window
     * @param pageable pagination config (large incidents may have thousands of events)
     * @return paginated audit events across all users in the time range
     */
    @Query("""
            SELECT ual FROM UserAuditLog ual
            WHERE ual.timestamp >= :from
              AND ual.timestamp <= :to
            ORDER BY ual.timestamp DESC
            """)
    Page<UserAuditLog> findAllByTimestampBetween(@Param("from") Instant from,
                                                  @Param("to") Instant to,
                                                  Pageable pageable);
}
