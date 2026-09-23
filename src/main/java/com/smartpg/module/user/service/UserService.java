package com.smartpg.module.user.service;

import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.model.UserAuditLog.AuditAction;
import com.smartpg.module.user.model.UserAuditLog.AuditStatus;
import com.smartpg.module.user.model.UserAuditLog;
import com.smartpg.module.user.repository.RefreshTokenRepository;
import com.smartpg.module.user.repository.UserAuditLogRepository;
import com.smartpg.module.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service for user account management operations.
 *
 * <p><b>What is this?</b>
 * While {@link AuthService} handles WHO you are (identity / auth), UserService
 * handles WHAT happens to your account: changing password, soft deleting,
 * admin role changes, listing users, suspension, etc.
 *
 * <p>Think of it as the HR department of your app:
 * <ul>
 *   <li>Auth is the security desk that checks your badge</li>
 *   <li>UserService is HR that manages your employment (promote, suspend, terminate)</li>
 * </ul>
 *
 * <p><b>Admin vs. User operations</b>:
 * Some methods here are ADMIN-ONLY (changeRole, suspend, hardDelete).
 * Authorization enforcement happens at the CONTROLLER layer via {@code @PreAuthorize}.
 * This service trusts that the caller is authorized — it does not re-check roles.
 * This is the standard Spring Security pattern: "Secure at the entry point, trust inside."
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository            userRepository;
    private final RefreshTokenRepository    refreshTokenRepository;
    private final UserAuditLogRepository    auditLogRepository;
    private final PasswordEncoder           passwordEncoder;

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       UserAuditLogRepository auditLogRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository          = userRepository;
        this.refreshTokenRepository  = refreshTokenRepository;
        this.auditLogRepository      = auditLogRepository;
        this.passwordEncoder         = passwordEncoder;
    }

    // =========================================================================
    // ① USER LOOKUP
    // =========================================================================

    /**
     * Fetch a single user by their UUID.
     *
     * <p>Used by: admin endpoints, JWT filter (to load current user),
     * and any service that needs to work with a full User entity.
     *
     * @param userId the UUID of the user to fetch
     * @return the User entity
     * @throws jakarta.persistence.EntityNotFoundException if not found
     */
    @Transactional(readOnly = true)
    public User findById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException(
                        "User not found with ID: " + userId));
    }

    /**
     * Get a paginated list of all users filtered by role.
     *
     * <p>Used by: {@code GET /api/v1/admin/users?role=OWNER} — admin dashboard.
     *
     * @param role     the role to filter by
     * @param pageable pagination parameters (page, size, sort)
     * @return a page of users with the specified role
     */
    @Transactional(readOnly = true)
    public Page<User> findByRole(Role role, Pageable pageable) {
        return userRepository.findByRole(role, pageable);
    }

    /**
     * Get a paginated list of all users filtered by status.
     *
     * <p>Used by: {@code GET /api/v1/admin/users?status=SUSPENDED} —
     * admin sees all suspended users at once.
     *
     * @param status   the status to filter by
     * @param pageable pagination parameters
     * @return a page of users with the specified status
     */
    @Transactional(readOnly = true)
    public Page<User> findByStatus(UserStatus status, Pageable pageable) {
        return userRepository.findByStatus(status, pageable);
    }

    /**
     * Get a paginated list of inactive users (no login after cutoff date).
     *
     * <p>Used by: A weekly scheduled job that sends "we miss you" emails
     * or marks inactive accounts after 90 days.
     *
     * @param cutoff   users who haven't logged in since this time are "inactive"
     * @param status   filter by account status (typically ACTIVE)
     * @param pageable pagination parameters
     * @return a page of users who haven't logged in since the cutoff
     */
    @Transactional(readOnly = true)
    public Page<User> findInactiveUsers(Instant cutoff, UserStatus status, Pageable pageable) {
        return userRepository.findInactiveUsers(cutoff, status, pageable);
    }

    // =========================================================================
    // ② PASSWORD MANAGEMENT
    // =========================================================================

    /**
     * Change a user's password. Requires the current (old) password as verification.
     *
     * <p><b>Why require the old password?</b>
     * This is a "change password" flow, not a "reset password" flow. The user
     * is already logged in. Requiring the old password ensures it's really the
     * legitimate user, not someone who grabbed an unlocked screen.
     *
     * <p><b>Session invalidation on password change</b>:
     * After changing the password, ALL refresh tokens are revoked. If someone
     * changed your password (account takeover), they'd get kicked out of all
     * devices immediately when you change it back. This is critical for security.
     *
     * <p><b>@Transactional</b>: The password update + session revocation + audit log
     * happen atomically. You can't have the password changed but sessions still active.
     *
     * @param userId      the UUID of the user changing their password
     * @param oldPassword the current plaintext password (for verification)
     * @param newPassword the new plaintext password (will be BCrypt-hashed)
     * @param ipAddress   client IP for audit log
     * @param userAgent   client user-agent for audit log
     * @throws com.smartpg.module.user.exception.InvalidCredentialsException if old password is wrong
     */
    @Transactional
    public void changePassword(UUID userId, String oldPassword, String newPassword,
                               String ipAddress, String userAgent) {
        log.info("Password change request for user: {}", userId);

        User user = findById(userId);

        // Verify the old password — prevent unauthorized password changes
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            writeAuditLog(user, AuditAction.PASSWORD_CHANGE, AuditStatus.FAILURE,
                    ipAddress, userAgent, Map.of("reason", "WRONG_OLD_PASSWORD"));
            throw new com.smartpg.module.user.exception.InvalidCredentialsException(
                    "Current password is incorrect.");
        }

        // Hash the new password and save
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Revoke ALL sessions — force re-login on all devices
        int revokedSessions = refreshTokenRepository.revokeAllByUserId(userId);

        writeAuditLog(user, AuditAction.PASSWORD_CHANGE, AuditStatus.SUCCESS,
                ipAddress, userAgent, Map.of("sessionsRevoked", revokedSessions));

        log.info("Password changed for user: {}. {} sessions revoked.", userId, revokedSessions);
    }

    // =========================================================================
    // ③ ADMIN OPERATIONS
    // =========================================================================

    /**
     * Change a user's role. ADMIN/SUPER_ADMIN operation only.
     *
     * <p><b>Last SUPER_ADMIN protection</b>:
     * Before allowing a role change AWAY from SUPER_ADMIN, we check there's
     * more than one SUPER_ADMIN. You can't have a system with zero super admins.
     *
     * <p><b>Session impact</b>: After a role change, active JWTs still carry the
     * OLD role claim. The user must re-login (or refresh their token) for the new
     * role to take effect. We revoke all refresh tokens to force this.
     *
     * @param targetUserId the UUID of the user whose role is being changed
     * @param newRole      the new role to assign
     * @param adminId      the UUID of the admin performing the change (for audit)
     * @param ipAddress    client IP for audit log
     * @param userAgent    client user-agent for audit log
     * @throws IllegalStateException if attempting to remove the last SUPER_ADMIN
     */
    @Transactional
    public void changeRole(UUID targetUserId, Role newRole, UUID adminId,
                           String ipAddress, String userAgent) {
        log.info("Role change: user {} → {} (by admin: {})", targetUserId, newRole, adminId);

        User user = findById(targetUserId);
        Role oldRole = user.getRole();

        // Guard: cannot remove the last SUPER_ADMIN
        if (oldRole == Role.SUPER_ADMIN && newRole != Role.SUPER_ADMIN) {
            long superAdminCount = userRepository.countByRole(Role.SUPER_ADMIN);
            if (superAdminCount <= 1) {
                throw new IllegalStateException(
                        "Cannot demote the last SUPER_ADMIN. Assign another SUPER_ADMIN first.");
            }
        }

        user.setRole(newRole);
        userRepository.save(user);

        // Revoke sessions — old role claims in JWTs are now wrong
        refreshTokenRepository.revokeAllByUserId(targetUserId);

        writeAuditLog(user, AuditAction.ROLE_CHANGE, AuditStatus.SUCCESS, ipAddress, userAgent,
                Map.of("oldRole", oldRole.name(), "newRole", newRole.name(), "changedBy", adminId.toString()));

        log.info("Role changed: user {} from {} to {}", targetUserId, oldRole, newRole);
    }

    /**
     * Suspend a user account. ADMIN operation.
     *
     * <p>Suspension is a reversible admin action. The account row stays intact
     * but status = SUSPENDED. The user cannot log in while suspended.
     * All active sessions are terminated immediately.
     *
     * @param targetUserId the UUID of the user to suspend
     * @param reason       the reason for suspension (stored in audit log)
     * @param adminId      the UUID of the admin performing the action
     * @param ipAddress    client IP for audit log
     * @param userAgent    client user-agent for audit log
     */
    @Transactional
    public void suspendUser(UUID targetUserId, String reason, UUID adminId,
                            String ipAddress, String userAgent) {
        log.info("Suspending user: {} (reason: {}, by admin: {})", targetUserId, reason, adminId);

        User user = findById(targetUserId);

        if (user.getStatus() == UserStatus.SUSPENDED) {
            log.warn("User {} is already suspended", targetUserId);
            return; // Idempotent — suspending an already-suspended user is a no-op
        }

        UserStatus oldStatus = user.getStatus();
        user.setStatus(UserStatus.SUSPENDED);
        userRepository.save(user);

        // Terminate all sessions immediately
        int revokedSessions = refreshTokenRepository.revokeAllByUserId(targetUserId);

        writeAuditLog(user, AuditAction.STATUS_CHANGE, AuditStatus.SUCCESS, ipAddress, userAgent,
                Map.of("oldStatus", oldStatus.name(), "newStatus", "SUSPENDED",
                        "reason", reason, "changedBy", adminId.toString(),
                        "sessionsRevoked", revokedSessions));

        log.info("User {} suspended. {} sessions revoked.", targetUserId, revokedSessions);
    }

    /**
     * Reactivate a suspended or inactive user account. ADMIN operation.
     *
     * @param targetUserId the UUID of the user to reactivate
     * @param adminId      the UUID of the admin performing the action
     * @param ipAddress    client IP for audit log
     * @param userAgent    client user-agent for audit log
     */
    @Transactional
    public void reactivateUser(UUID targetUserId, UUID adminId, String ipAddress, String userAgent) {
        log.info("Reactivating user: {} (by admin: {})", targetUserId, adminId);

        User user = findById(targetUserId);
        UserStatus oldStatus = user.getStatus();

        if (user.getStatus() == UserStatus.ACTIVE) {
            log.warn("User {} is already active", targetUserId);
            return;
        }

        if (user.getStatus() == UserStatus.DELETED) {
            throw new IllegalStateException("Cannot reactivate a deleted account.");
        }

        user.setStatus(UserStatus.ACTIVE);
        userRepository.save(user);

        writeAuditLog(user, AuditAction.STATUS_CHANGE, AuditStatus.SUCCESS, ipAddress, userAgent,
                Map.of("oldStatus", oldStatus.name(), "newStatus", "ACTIVE",
                        "changedBy", adminId.toString()));

        log.info("User {} reactivated from status: {}", targetUserId, oldStatus);
    }

    /**
     * Soft-delete a user account.
     *
     * <p><b>Soft delete, not physical delete</b>:
     * The row is NEVER removed from the DB. {@code status = DELETED} is set,
     * and all sessions are revoked. All related data (bookings, payments, complaints)
     * remains intact — referential integrity preserved, audit trail intact.
     *
     * <p><b>Self-service vs admin</b>:
     * This can be called by the user themselves (account deletion request) OR
     * by an admin. The {@code callerIsAdmin} flag changes the audit log detail.
     * Authorization is enforced at the controller layer.
     *
     * @param targetUserId  the UUID of the user to soft-delete
     * @param callerId      the UUID of whoever is performing the action
     * @param callerIsAdmin true if an admin is performing this; false if user self-deleting
     * @param ipAddress     client IP for audit log
     * @param userAgent     client user-agent for audit log
     */
    @Transactional
    public void softDeleteUser(UUID targetUserId, UUID callerId, boolean callerIsAdmin,
                               String ipAddress, String userAgent) {
        log.info("Soft-delete request for user: {} (caller: {}, isAdmin: {})",
                targetUserId, callerId, callerIsAdmin);

        User user = findById(targetUserId);

        if (user.getStatus() == UserStatus.DELETED) {
            log.warn("User {} is already deleted", targetUserId);
            return; // Idempotent
        }

        // Soft delete via targeted UPDATE (avoids loading + saving entire entity)
        userRepository.softDeleteById(targetUserId, Instant.now());

        // Revoke all sessions immediately
        int revokedSessions = refreshTokenRepository.revokeAllByUserId(targetUserId);

        writeAuditLog(user, AuditAction.STATUS_CHANGE, AuditStatus.SUCCESS, ipAddress, userAgent,
                Map.of("oldStatus", user.getStatus().name(), "newStatus", "DELETED",
                        "requestedBy", callerId.toString(),
                        "isAdminAction", callerIsAdmin,
                        "sessionsRevoked", revokedSessions));

        log.info("User {} soft-deleted. {} sessions revoked.", targetUserId, revokedSessions);
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Writes an audit log entry. Failures are logged but NOT propagated.
     *
     * <p>Audit log writes should never crash the main operation. Log the error,
     * and in production, route audit writes through an async event queue.
     */
    private void writeAuditLog(User user, AuditAction action, AuditStatus status,
                               String ipAddress, String userAgent, Map<String, Object> details) {
        try {
            String detailsJson = details.isEmpty()
                    ? null
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details);
            auditLogRepository.save(
                    new UserAuditLog(user, action, status, ipAddress,
                            truncate(userAgent, 500), detailsJson));
        } catch (Exception e) {
            log.error("Failed to write audit log for user: {}, action: {}", user.getId(), action, e);
        }
    }

    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
