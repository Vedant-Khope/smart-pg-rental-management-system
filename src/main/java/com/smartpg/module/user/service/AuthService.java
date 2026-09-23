package com.smartpg.module.user.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpg.module.user.dto.request.LoginRequest;
import com.smartpg.module.user.dto.request.RefreshTokenRequest;
import com.smartpg.module.user.dto.request.RegisterRequest;
import com.smartpg.module.user.dto.response.AuthResponse;
import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.exception.AccountStatusException;
import com.smartpg.module.user.exception.DuplicateEmailException;
import com.smartpg.module.user.exception.InvalidCredentialsException;
import com.smartpg.module.user.exception.InvalidTokenException;
import com.smartpg.module.user.model.OwnerProfile;
import com.smartpg.module.user.model.RefreshToken;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.model.UserAuditLog;
import com.smartpg.module.user.model.UserAuditLog.AuditAction;
import com.smartpg.module.user.model.UserAuditLog.AuditStatus;
import com.smartpg.module.user.model.UserProfile;
import com.smartpg.module.user.repository.OwnerProfileRepository;
import com.smartpg.module.user.repository.RefreshTokenRepository;
import com.smartpg.module.user.repository.UserAuditLogRepository;
import com.smartpg.module.user.repository.UserProfileRepository;
import com.smartpg.module.user.repository.UserRepository;
import com.smartpg.security.jwt.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Core authentication service responsible for registration, login,
 * token refresh, and logout operations.
 *
 * <p><b>What is this?</b>
 * If the entire backend is a city, AuthService is the passport office.
 * It creates identities (register), checks credentials (login), renews
 * passports (refresh), and revokes them (logout). Everything auth-related
 * starts and ends here.
 *
 * <p><b>Layer responsibilities</b>:
 * <ul>
 *   <li>This service orchestrates: validates input, calls repositories,
 *       generates tokens, writes audit logs, and returns DTOs.</li>
 *   <li>It does NOT write raw SQL (that's the repository layer).</li>
 *   <li>It does NOT handle HTTP (that's the controller layer).</li>
 *   <li>It does NOT sign JWTs (that's {@link JwtService}).</li>
 * </ul>
 *
 * <p><b>Transaction strategy</b>:
 * Each public method is its own transaction. If anything fails inside
 * (e.g., audit log write fails), the entire operation rolls back —
 * you can't have a user created without an audit log, or tokens issued
 * without the refresh token row being saved.
 *
 * <p><b>Why constructor injection instead of @Autowired?</b>
 * <ul>
 *   <li>Makes dependencies explicit — you can see exactly what this service needs.</li>
 *   <li>Enables unit testing: you can new AuthService(mockRepo, ...) without Spring.</li>
 *   <li>If a required dependency is missing, the app fails fast at startup (not at runtime).</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Configurable: how many days a refresh token lives.
    // Set in application.properties: jwt.refresh-token-expiration-days=30
    @Value("${jwt.refresh-token-expiration-days:30}")
    private long refreshTokenExpirationDays;

    // Brute-force protection: max failed logins before lockout
    // Set in application.properties: security.max-failed-logins=5
    @Value("${security.max-failed-logins:5}")
    private int maxFailedLogins;

    // Brute-force window: how far back to look for failed logins (in minutes)
    @Value("${security.failed-login-window-minutes:15}")
    private int failedLoginWindowMinutes;

    // Roles that CANNOT be self-registered (must be assigned by an admin)
    private static final java.util.Set<Role> RESTRICTED_ROLES =
            java.util.Set.of(Role.ADMIN, Role.SUPER_ADMIN, Role.CARETAKER);

    private final UserRepository            userRepository;
    private final UserProfileRepository     userProfileRepository;
    private final OwnerProfileRepository    ownerProfileRepository;
    private final RefreshTokenRepository    refreshTokenRepository;
    private final UserAuditLogRepository    auditLogRepository;
    private final PasswordEncoder           passwordEncoder;
    private final JwtService                jwtService;
    // ObjectMapper created directly (not injected) to avoid bean wiring issues
    // with spring-boot-starter-webmvc. Used only for serializing audit log details.
    private final ObjectMapper              objectMapper = new ObjectMapper();

    public AuthService(UserRepository userRepository,
                       UserProfileRepository userProfileRepository,
                       OwnerProfileRepository ownerProfileRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       UserAuditLogRepository auditLogRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository         = userRepository;
        this.userProfileRepository  = userProfileRepository;
        this.ownerProfileRepository = ownerProfileRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.auditLogRepository     = auditLogRepository;
        this.passwordEncoder        = passwordEncoder;
        this.jwtService             = jwtService;
    }


    // =========================================================================
    // ① REGISTRATION
    // =========================================================================

    /**
     * Registers a new user account with an associated profile.
     *
     * <p><b>Step-by-step flow</b>:
     * <ol>
     *   <li>Validate role — can't self-register as ADMIN/SUPER_ADMIN/CARETAKER</li>
     *   <li>Check email uniqueness — throw {@link DuplicateEmailException} if taken</li>
     *   <li>Build User entity — hash password, set status=PENDING_VERIFICATION</li>
     *   <li>Save User (generates UUID via @UuidGenerator)</li>
     *   <li>Create UserProfile skeleton (firstName/lastName optional at register)</li>
     *   <li>If role=OWNER, create OwnerProfile skeleton too</li>
     *   <li>Write registration audit log</li>
     *   <li>Issue access token + refresh token</li>
     *   <li>Return AuthResponse</li>
     * </ol>
     *
     * <p><b>Why issue tokens on registration?</b>
     * User experience. If we force a separate login step right after register,
     * the user goes through the same credential-check again unnecessarily.
     * We already have the user entity — just issue the tokens.
     * NOTE: The access token works even with PENDING_VERIFICATION status,
     * but certain endpoints check for ACTIVE status via @PreAuthorize.
     *
     * <p><b>@Transactional</b>: All DB writes (user, profile, ownerProfile, auditLog,
     * refreshToken) are in ONE transaction. If any fails, ALL roll back.
     * You won't end up with a user row but no profile row.
     *
     * @param request the validated registration payload
     * @param ipAddress the client IP (from request header, for audit log)
     * @param userAgent the client user-agent (for audit log + session info)
     * @return {@link AuthResponse} with tokens and basic user info
     * @throws DuplicateEmailException if email is already registered
     * @throws IllegalArgumentException if role is restricted
     */
    @Transactional
    public AuthResponse register(RegisterRequest request, String ipAddress, String userAgent) {
        log.info("Registration attempt for email: {} with role: {}", request.email(), request.role());

        // ── Guard 1: Prevent self-registration as admin roles ─────────────────
        if (RESTRICTED_ROLES.contains(request.role())) {
            throw new IllegalArgumentException(
                    "Role '" + request.role() + "' cannot be self-registered. Contact an administrator.");
        }

        // ── Guard 2: Email uniqueness check ───────────────────────────────────
        String normalizedEmail = request.email().toLowerCase().trim();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException(normalizedEmail);
        }

        // ── Step 1: Build and save the User entity ────────────────────────────
        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.password()));  // BCrypt hash
        user.setRole(request.role());
        user.setStatus(UserStatus.PENDING_VERIFICATION);  // Must verify email first
        user.setEmailVerified(false);
        user.setPhoneVerified(false);
        if (request.phone() != null && !request.phone().isBlank()) {
            user.setPhone(request.phone().trim());
        }

        User savedUser = userRepository.save(user);
        log.debug("User created with ID: {}", savedUser.getId());

        // ── Step 2: Create UserProfile skeleton ───────────────────────────────
        // Profile is empty on registration — user fills it in later.
        // We create the row NOW so profile-related endpoints can always
        // assume the row exists (no null checks in profile service).
        UserProfile profile = new UserProfile();
        profile.setUser(savedUser);
        userProfileRepository.save(profile);

        // ── Step 3: If OWNER, create OwnerProfile skeleton ────────────────────
        if (request.role() == Role.OWNER) {
            OwnerProfile ownerProfile = new OwnerProfile();
            ownerProfile.setUser(savedUser);
            // verificationStatus defaults to "UNVERIFIED" (set in entity)
            ownerProfileRepository.save(ownerProfile);
            log.debug("OwnerProfile skeleton created for user: {}", savedUser.getId());
        }

        // ── Step 4: Write audit log ────────────────────────────────────────────
        writeAuditLog(savedUser, AuditAction.LOGIN, AuditStatus.SUCCESS,
                ipAddress, userAgent, Map.of("event", "REGISTRATION", "role", request.role().name()));

        // ── Step 5: Issue tokens ──────────────────────────────────────────────
        String accessToken   = jwtService.generateAccessToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole());
        String refreshTokenStr = issueRefreshToken(savedUser, ipAddress, userAgent);

        log.info("Registration successful for user: {}", savedUser.getId());
        return AuthResponse.from(savedUser, accessToken, refreshTokenStr, jwtService.getExpiresInSeconds());
    }

    // =========================================================================
    // ② LOGIN
    // =========================================================================

    /**
     * Authenticates a user with email and password, returning JWT tokens on success.
     *
     * <p><b>Security design — the order of operations matters:</b>
     * <ol>
     *   <li><b>Brute-force check FIRST</b> — before even hitting the DB for the user,
     *       check if this email has too many recent failed logins. This prevents
     *       an attacker from hammering the endpoint. (We need the userId for this,
     *       so we find the user first, then check the count.)</li>
     *   <li><b>Load user</b> — if not found, throw generic InvalidCredentialsException
     *       (never say "email not found").</li>
     *   <li><b>Brute-force count</b> — count failed logins in the last N minutes.</li>
     *   <li><b>Account status check</b> — BEFORE password check. If account is DELETED,
     *       we don't want to waste BCrypt CPU on a dead account.</li>
     *   <li><b>Password check</b> — BCrypt.matches(plaintext, hash). This is the
     *       expensive step (~100ms). Do it last to avoid wasting it on blocked accounts.</li>
     *   <li><b>Issue tokens + update lastLoginAt</b></li>
     * </ol>
     *
     * <p><b>Why not use Spring Security's AuthenticationManager here?</b>
     * We could do:
     * <pre>
     *   authManager.authenticate(new UsernamePasswordAuthenticationToken(email, password));
     * </pre>
     * That would call {@code UserDetailsServiceImpl.loadUserByUsername} + BCrypt check internally.
     * But then we lose control over:
     * <ul>
     *   <li>Brute-force protection (we can't inject our counter easily)</li>
     *   <li>Audit log writing on failure</li>
     *   <li>Custom error messages per account status</li>
     * </ul>
     * Manual control here is intentional and gives us production-grade security control.
     *
     * @param request   the login payload (email + password)
     * @param ipAddress client IP for audit log
     * @param userAgent client user-agent for session info
     * @return {@link AuthResponse} with access + refresh tokens
     */
    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress, String userAgent) {
        String normalizedEmail = request.email().toLowerCase().trim();
        log.info("Login attempt for email: {} from IP: {}", normalizedEmail, ipAddress);

        // ── Step 1: Find user by email ────────────────────────────────────────
        // SECURITY: Use the same exception regardless of WHY auth fails.
        // "Invalid credentials" covers both "email not found" and "wrong password".
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> {
                    log.warn("Login attempt for non-existent email: {}", normalizedEmail);
                    return new InvalidCredentialsException();
                });

        // ── Step 2: Brute-force protection ────────────────────────────────────
        Instant bruteForceWindowStart = Instant.now().minus(failedLoginWindowMinutes, ChronoUnit.MINUTES);
        long recentFailures = auditLogRepository.countRecentFailedLogins(user.getId(), bruteForceWindowStart);
        if (recentFailures >= maxFailedLogins) {
            log.warn("Brute-force lockout for user: {} ({} failures in {} minutes)",
                    user.getId(), recentFailures, failedLoginWindowMinutes);
            writeAuditLog(user, AuditAction.FAILED_LOGIN, AuditStatus.FAILURE,
                    ipAddress, userAgent, Map.of("reason", "BRUTE_FORCE_LOCKOUT", "failureCount", recentFailures));
            // Return the same generic error — don't tell attacker they're locked out
            throw new InvalidCredentialsException();
        }

        // ── Step 3: Account status check ─────────────────────────────────────
        // Check BEFORE BCrypt (cheap DB read before expensive crypto)
        validateAccountStatus(user);

        // ── Step 4: Password verification ────────────────────────────────────
        // BCrypt.matches() — timing-safe comparison, ~100ms to prevent timing attacks
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Wrong password for user: {}", user.getId());
            writeAuditLog(user, AuditAction.FAILED_LOGIN, AuditStatus.FAILURE,
                    ipAddress, userAgent, Map.of("reason", "BAD_PASSWORD", "failureCount", recentFailures + 1));
            throw new InvalidCredentialsException();
        }

        // ── Step 5: Issue tokens ──────────────────────────────────────────────
        String accessToken    = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole());
        String refreshTokenStr = issueRefreshToken(user, ipAddress, userAgent);

        // ── Step 6: Update last login timestamp ──────────────────────────────
        // Targeted update — touches only one column, no full entity load+save
        userRepository.updateLastLoginAt(user.getId(), Instant.now());

        // ── Step 7: Write success audit log ──────────────────────────────────
        writeAuditLog(user, AuditAction.LOGIN, AuditStatus.SUCCESS, ipAddress, userAgent, Map.of());

        log.info("Login successful for user: {}", user.getId());
        return AuthResponse.from(user, accessToken, refreshTokenStr, jwtService.getExpiresInSeconds());
    }

    // =========================================================================
    // ③ TOKEN REFRESH
    // =========================================================================

    /**
     * Exchanges a valid refresh token for a new access token + new refresh token
     * (refresh token rotation).
     *
     * <p><b>Refresh Token Rotation</b>:
     * On every successful refresh:
     * <ul>
     *   <li>The OLD refresh token is revoked (set revoked=true).</li>
     *   <li>A NEW refresh token is issued.</li>
     * </ul>
     * This means if a stolen refresh token is used by an attacker, and the legitimate
     * user also tries to use it (or vice versa), the second use will find the token
     * already revoked → both sessions are invalidated → user must re-login.
     * This is the industry-standard protection for long-lived tokens.
     *
     * @param request the refresh token request payload
     * @param ipAddress client IP for audit log
     * @param userAgent client user-agent for new session tracking
     * @return a new {@link AuthResponse} with fresh tokens
     * @throws InvalidTokenException if the token is not found, expired, or revoked
     */
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request, String ipAddress, String userAgent) {
        log.debug("Token refresh attempt from IP: {}", ipAddress);

        // ── Step 1: Find the refresh token in DB ─────────────────────────────
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(InvalidTokenException::notFound);

        // ── Step 2: Validate the token state ─────────────────────────────────
        if (refreshToken.isRevoked()) {
            log.warn("Revoked refresh token used by user: {}. Possible token theft!",
                    refreshToken.getUser().getId());
            // Security escalation: if a revoked token is used, revoke ALL tokens
            // for this user — their account may be compromised.
            refreshTokenRepository.revokeAllByUserId(refreshToken.getUser().getId());
            writeAuditLog(refreshToken.getUser(), AuditAction.TOKEN_REFRESH, AuditStatus.FAILURE,
                    ipAddress, userAgent, Map.of("reason", "REVOKED_TOKEN_REUSE_DETECTED"));
            throw InvalidTokenException.revoked();
        }

        if (refreshToken.getExpiresAt().isBefore(Instant.now())) {
            log.debug("Expired refresh token for user: {}", refreshToken.getUser().getId());
            throw InvalidTokenException.expired();
        }

        User user = refreshToken.getUser();

        // ── Step 3: Validate account status (could have been suspended since last login)
        validateAccountStatus(user);

        // ── Step 4: Rotate the refresh token ─────────────────────────────────
        // Revoke the OLD token (token rotation — old token is now dead)
        refreshTokenRepository.revokeByToken(request.refreshToken());

        // Issue a new refresh token
        String newRefreshTokenStr = issueRefreshToken(user, ipAddress, userAgent);

        // ── Step 5: Issue new access token ────────────────────────────────────
        String newAccessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getRole());

        writeAuditLog(user, AuditAction.TOKEN_REFRESH, AuditStatus.SUCCESS, ipAddress, userAgent, Map.of());

        log.debug("Token refresh successful for user: {}", user.getId());
        return AuthResponse.from(user, newAccessToken, newRefreshTokenStr, jwtService.getExpiresInSeconds());
    }

    // =========================================================================
    // ④ LOGOUT
    // =========================================================================

    /**
     * Logs out a user by revoking their specific refresh token.
     *
     * <p><b>What about the access token?</b>
     * The JWT access token is stateless — once issued, it's valid until expiry.
     * We can't "revoke" a JWT without maintaining a blocklist (which negates
     * the stateless benefit). The security trade-off: access tokens live for
     * 15 minutes max, so the window for misuse after logout is small.
     *
     * <p>If you need immediate access token invalidation (e.g., admin force-logout),
     * use a Redis-based JWT blocklist — out of scope for v1.
     *
     * @param refreshTokenStr the refresh token to revoke (the specific session to end)
     * @param userId the authenticated user's UUID (from JWT claims, for audit log)
     * @param ipAddress client IP for audit log
     * @param userAgent client user-agent for audit log
     */
    @Transactional
    public void logout(String refreshTokenStr, UUID userId, String ipAddress, String userAgent) {
        log.info("Logout request for user: {}", userId);

        int revoked = refreshTokenRepository.revokeByToken(refreshTokenStr);
        if (revoked == 0) {
            log.warn("Logout called with non-existent token for user: {}", userId);
            // Don't throw — just log. Idempotent logout (calling logout twice is OK)
        }

        // We need the user entity only for the audit log.
        // Use findById — if user doesn't exist (edge case), just log it.
        userRepository.findById(userId).ifPresent(user ->
                writeAuditLog(user, AuditAction.LOGOUT, AuditStatus.SUCCESS, ipAddress, userAgent, Map.of())
        );

        log.info("Logout successful for user: {}", userId);
    }

    /**
     * Logout from ALL devices — revokes every active refresh token for the user.
     *
     * <p>Used in security-critical scenarios:
     * <ul>
     *   <li>User changes password → force logout everywhere</li>
     *   <li>Admin suspends user → terminate all sessions</li>
     *   <li>User explicitly chooses "sign out everywhere"</li>
     * </ul>
     *
     * @param userId the UUID of the user
     * @param ipAddress client IP for audit log
     * @param userAgent client user-agent for audit log
     * @return the number of sessions terminated
     */
    @Transactional
    public int logoutAll(UUID userId, String ipAddress, String userAgent) {
        log.info("Logout-all request for user: {}", userId);

        int revoked = refreshTokenRepository.revokeAllByUserId(userId);

        userRepository.findById(userId).ifPresent(user ->
                writeAuditLog(user, AuditAction.LOGOUT, AuditStatus.SUCCESS, ipAddress, userAgent,
                        Map.of("event", "LOGOUT_ALL_DEVICES", "sessionsTerminated", revoked))
        );

        log.info("Logged out {} sessions for user: {}", revoked, userId);
        return revoked;
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Creates and persists a new refresh token for the given user.
     *
     * <p>The token value is a random UUID string — opaque, unpredictable,
     * and unique (UUID.randomUUID() has collision probability of ~1 in 10^37).
     * It's NOT a JWT — it's just a random string that we look up in the DB.
     *
     * @param user      the user to create the session for
     * @param ipAddress the IP address of the client (for session audit)
     * @param userAgent the User-Agent header value (for device info display)
     * @return the raw token string to send back to the client
     */
    private String issueRefreshToken(User user, String ipAddress, String userAgent) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());   // Opaque random token — NOT a JWT
        token.setExpiresAt(Instant.now().plus(refreshTokenExpirationDays, ChronoUnit.DAYS));
        token.setRevoked(false);
        token.setIpAddress(ipAddress);
        token.setDeviceInfo(truncate(userAgent, 500));  // Truncate to column limit

        refreshTokenRepository.save(token);
        return token.getToken();
    }

    /**
     * Validates that the user's account status allows login.
     *
     * <p>Called during login and token refresh. An account that was ACTIVE when
     * the user registered could be SUSPENDED by the time they try to refresh.
     * This catch point ensures suspended users are kicked out even with a valid refresh token.
     *
     * @param user the user to check
     * @throws AccountStatusException if the account cannot proceed
     */
    private void validateAccountStatus(User user) {
        switch (user.getStatus()) {
            case ACTIVE       -> { /* ✓ all good */ }
            case PENDING_VERIFICATION -> throw AccountStatusException.pendingVerification();
            case SUSPENDED    -> throw AccountStatusException.suspended();
            case DELETED      -> throw AccountStatusException.deleted();
            case INACTIVE     -> throw AccountStatusException.inactive();
        }
    }

    /**
     * Appends an audit log entry. Failures here must NOT crash the main flow.
     *
     * <p><b>Design decision</b>: We log the error but do NOT rethrow if the audit
     * log write fails. The primary operation (login, logout) should still succeed
     * even if the audit table is temporarily unavailable. In production, consider
     * async audit logging via an event queue (Kafka/RabbitMQ) for this reason.
     *
     * @param user      the affected user
     * @param action    the type of event
     * @param status    SUCCESS or FAILURE
     * @param ipAddress client IP
     * @param userAgent client user-agent
     * @param extraData additional key-value pairs to include in the JSON details field
     */
    private void writeAuditLog(User user, AuditAction action, AuditStatus status,
                               String ipAddress, String userAgent, Map<String, Object> extraData) {
        try {
            String details = extraData.isEmpty() ? null : objectMapper.writeValueAsString(extraData);
            UserAuditLog logEntry = new UserAuditLog(user, action, status, ipAddress,
                    truncate(userAgent, 500), details);
            auditLogRepository.save(logEntry);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize audit log details for user: {}", user.getId(), e);
        } catch (Exception e) {
            // Audit log failure must never kill the main flow
            log.error("Audit log write failed for user: {}, action: {}", user.getId(), action, e);
        }
    }

    /**
     * Truncates a string to a maximum length. Prevents DB column overflow exceptions.
     *
     * @param value  the string to truncate (may be null)
     * @param maxLen the maximum allowed length
     * @return the truncated string, or null if input is null
     */
    private String truncate(String value, int maxLen) {
        if (value == null) return null;
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
