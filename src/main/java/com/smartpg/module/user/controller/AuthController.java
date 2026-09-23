package com.smartpg.module.user.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.common.util.HttpRequestUtil;
import com.smartpg.module.user.dto.request.LoginRequest;
import com.smartpg.module.user.dto.request.LogoutRequest;
import com.smartpg.module.user.dto.request.RefreshTokenRequest;
import com.smartpg.module.user.dto.request.RegisterRequest;
import com.smartpg.module.user.dto.response.AuthResponse;
import com.smartpg.module.user.service.AuthService;
import com.smartpg.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for all authentication endpoints.
 *
 * <p><b>What is this?</b>
 * This is the "front door" of your application. Every HTTP request for
 * registration, login, token refresh, and logout goes through this class.
 * Think of it as the receptionist at a company: it takes your request,
 * checks if it's valid (format-wise), and routes it to the right department
 * (service layer). It does NOT do actual work — it delegates.
 *
 * <p><b>Controller Responsibilities (what IT does):</b>
 * <ul>
 *   <li>Maps HTTP verbs + URL paths to Java methods.</li>
 *   <li>Deserializes JSON request body → Java record (via Jackson).</li>
 *   <li>Triggers Bean Validation ({@code @Valid}) — returns 400 if invalid.</li>
 *   <li>Extracts HTTP metadata (IP, User-Agent) and passes to service.</li>
 *   <li>Wraps service result in {@link ApiResponse} with correct HTTP status.</li>
 * </ul>
 *
 * <p><b>What the controller does NOT do:</b>
 * <ul>
 *   <li>NO business logic (no password hashing, no token generation).</li>
 *   <li>NO database access (never touches repositories).</li>
 *   <li>NO try-catch (GlobalExceptionHandler handles everything).</li>
 * </ul>
 *
 * <p><b>@RestController</b>: Shorthand for {@code @Controller + @ResponseBody}.
 * Without {@code @ResponseBody}, Spring tries to resolve a view name from
 * the method return value. With a REST API, we want JSON — not a view.
 *
 * <p><b>@RequestMapping("/api/v1/auth")</b>: Base URL prefix on all methods.
 * {@code /api/v1/} prefix enables future versioning ({@code /api/v2/}) without
 * breaking existing clients (contracts are promised, not just implemented).
 *
 * <p><b>All endpoints here are PUBLIC</b> (no JWT needed) — configured in:
 * {@link com.smartpg.security.config.SecurityConfig}
 *
 * <p><b>Base URL:</b> {@code /api/v1/auth}
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    /**
     * Constructor injection — explicit dependency declaration.
     * If {@link AuthService} is missing from context, app fails fast at startup.
     * Enables unit testing without Spring by passing mocks directly.
     *
     * @param authService the auth business logic service
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // =========================================================================
    // ① POST /api/v1/auth/register
    // =========================================================================

    /**
     * Registers a new user account and immediately issues JWT tokens.
     *
     * <p><b>Request</b>: {@code POST /api/v1/auth/register}
     * <br><b>Body</b>: {@link RegisterRequest} (email, password, phone?, role)
     * <br><b>Auth</b>: None (public endpoint)
     * <br><b>Response</b>: 201 CREATED with {@link AuthResponse}
     *
     * <p><b>@Valid deep dive:</b>
     * {@code @Valid} activates Jakarta Bean Validation on the {@code @RequestBody}.
     * Before the method body runs, Spring validates ALL constraints on the DTO:
     * <ul>
     *   <li>{@code @NotBlank} on email → fails if blank or null</li>
     *   <li>{@code @Email} → fails if not valid email format</li>
     *   <li>{@code @Pattern} on password → fails if no special char/uppercase/etc.</li>
     * </ul>
     * On failure: Spring throws {@code MethodArgumentNotValidException} automatically.
     * Our {@link com.smartpg.common.exception.handler.GlobalExceptionHandler} catches it
     * and returns a 400 with a map of {@code fieldName → errorMessage}.
     *
     * <p><b>WITHOUT {@code @Valid}</b>: DTO constraints are completely ignored.
     * Someone could register with email="" and password="a". Disaster.
     *
     * <p><b>Why 201 CREATED, not 200 OK?</b>
     * HTTP semantics: 201 = new resource was created on the server.
     * A new User row + UserProfile row + RefreshToken row were created.
     * 201 is semantically correct. Frontend devs and API tools rely on this
     * to distinguish resource creation from reads/updates.
     *
     * <p><b>HttpServletRequest parameter:</b>
     * Spring MVC auto-injects this — no annotation needed. It represents the raw
     * HTTP request, which we use to extract the client's IP and User-Agent string.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Valid body → 201 with accessToken + refreshToken</li>
     *   <li><b>Edge case</b>: Same email registered twice
     *       → DuplicateEmailException → 409 CONFLICT</li>
     *   <li><b>Failure</b>: Missing email field
     *       → MethodArgumentNotValidException → 400 BAD REQUEST</li>
     * </ul>
     *
     * @param request     the validated registration payload from JSON body
     * @param httpRequest the raw HTTP request (for IP + User-Agent extraction)
     * @return 201 CREATED with auth tokens and basic user info
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest) {

        log.info("Registration request received for email: {}", request.email());

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        AuthResponse response = authService.register(request, ip, userAgent);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registration successful. Please verify your email.", response));
    }

    // =========================================================================
    // ② POST /api/v1/auth/login
    // =========================================================================

    /**
     * Authenticates a user with email and password, returning JWT tokens.
     *
     * <p><b>Request</b>: {@code POST /api/v1/auth/login}
     * <br><b>Body</b>: {@link LoginRequest} (email, password)
     * <br><b>Auth</b>: None (public endpoint)
     * <br><b>Response</b>: 200 OK with {@link AuthResponse}
     *
     * <p><b>Why 200 OK here (not 201)?</b>
     * Login authenticates and returns existing data (tokens) — no new
     * "resource" is created from the client's perspective. 200 is correct.
     * (Internally, a RefreshToken row IS created, but that's an implementation detail.)
     *
     * <p><b>Brute-force protection (handled in service, not here):</b>
     * The controller is intentionally unaware of brute-force logic. That's a
     * business rule that lives in {@link AuthService}. If the limit is hit,
     * the service throws {@code InvalidCredentialsException} → GlobalExceptionHandler → 401.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Correct email + password → 200 with tokens</li>
     *   <li><b>Edge case</b>: Correct email, wrong password 5x times
     *       → brute force lockout → 401 (same error as wrong password — intentional)</li>
     *   <li><b>Failure</b>: Account suspended
     *       → AccountStatusException → 403 FORBIDDEN</li>
     * </ul>
     *
     * @param request     login payload (email + password)
     * @param httpRequest for client IP + User-Agent extraction
     * @return 200 OK with access + refresh tokens
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        log.info("Login request received for email: {}", request.email());

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        AuthResponse response = authService.login(request, ip, userAgent);

        return ResponseEntity.ok(ApiResponse.success("Login successful.", response));
    }

    // =========================================================================
    // ③ POST /api/v1/auth/refresh
    // =========================================================================

    /**
     * Exchanges a valid refresh token for a new access token + new refresh token (rotation).
     *
     * <p><b>Request</b>: {@code POST /api/v1/auth/refresh}
     * <br><b>Body</b>: {@link RefreshTokenRequest} (refreshToken)
     * <br><b>Auth</b>: None — the refresh token IS the credential here
     * <br><b>Response</b>: 200 OK with new {@link AuthResponse}
     *
     * <p><b>Refresh Token Rotation explained:</b>
     * Every call here produces:
     * <ol>
     *   <li>OLD refresh token → revoked in DB (dead forever)</li>
     *   <li>NEW access token (fresh 15-min JWT)</li>
     *   <li>NEW refresh token (fresh 30-day opaque string)</li>
     * </ol>
     * If an attacker steals a refresh token and uses it, the real user's
     * next refresh attempt finds theirs already revoked → service revokes ALL
     * sessions → both attacker and user are kicked out → user re-logs in safely.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Valid, non-expired refresh token → 200 with new tokens</li>
     *   <li><b>Edge case</b>: Token reuse (same token used twice)
     *       → ALL sessions revoked → 401 (security escalation)</li>
     *   <li><b>Failure</b>: Expired refresh token → InvalidTokenException → 401</li>
     * </ul>
     *
     * @param request     contains the refresh token to exchange
     * @param httpRequest for IP + User-Agent (new session tracking)
     * @return 200 OK with fresh access and refresh tokens
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @Valid @RequestBody RefreshTokenRequest request,
            HttpServletRequest httpRequest) {

        log.debug("Token refresh request received");

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        AuthResponse response = authService.refresh(request, ip, userAgent);

        return ResponseEntity.ok(ApiResponse.success("Token refreshed successfully.", response));
    }

    // =========================================================================
    // ④ POST /api/v1/auth/logout
    // =========================================================================

    /**
     * Logs out the current user by revoking their specific session's refresh token.
     *
     * <p><b>Request</b>: {@code POST /api/v1/auth/logout}
     * <br><b>Headers</b>: {@code Authorization: Bearer <accessToken>} (required)
     * <br><b>Body</b>: {@link LogoutRequest} (refreshToken to revoke)
     * <br><b>Auth</b>: Required — must be authenticated to log out
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>Why does logout require the JWT (authenticated endpoint)?</b>
     * <ol>
     *   <li>We need to know WHO is logging out for the audit log.</li>
     *   <li>We verify the refresh token belongs to THIS authenticated user,
     *       not someone else's session.</li>
     * </ol>
     *
     * <p><b>@AuthenticationPrincipal UserPrincipal explained:</b>
     * Our {@link com.smartpg.security.filter.JwtAuthenticationFilter} validates the JWT
     * and puts a {@code UsernamePasswordAuthenticationToken} into the {@code SecurityContext}.
     * The principal of that token is our {@link UserPrincipal} object.
     * <br>
     * {@code @AuthenticationPrincipal} tells Spring to extract that principal and inject
     * it directly as a method parameter. No manual {@code SecurityContextHolder.getContext()
     * .getAuthentication().getPrincipal()} needed — clean, testable injection.
     * <br>
     * Since our {@link UserPrincipal} carries {@code userId} directly, we get the UUID
     * for free — zero extra DB calls.
     *
     * <p><b>Idempotent logout:</b>
     * Calling logout twice with the same token is safe — the second call is a no-op
     * (the service logs it but returns normally). This prevents confusing errors
     * on double-click or network retry.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Valid JWT + valid refresh token → token revoked → 200</li>
     *   <li><b>Edge case</b>: Logout called twice → idempotent → still 200</li>
     *   <li><b>Failure</b>: Missing/expired JWT → Spring Security blocks → 401
     *       (never reaches this method)</li>
     * </ul>
     *
     * @param request     the logout payload (refresh token to revoke)
     * @param principal   the authenticated user's principal with userId + email
     * @param httpRequest for IP + User-Agent (audit log)
     * @return 200 OK with logout confirmation
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @Valid @RequestBody LogoutRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Logout request for user: {}", principal.getUserId());

        authService.logout(request.refreshToken(), principal.getUserId(), ip, userAgent);

        return ResponseEntity.ok(ApiResponse.success("Logged out successfully."));
    }

    // =========================================================================
    // ⑤ POST /api/v1/auth/logout-all
    // =========================================================================

    /**
     * Logs out from ALL devices — revokes every active refresh token for this user.
     *
     * <p><b>Request</b>: {@code POST /api/v1/auth/logout-all}
     * <br><b>Headers</b>: {@code Authorization: Bearer <accessToken>} (required)
     * <br><b>Auth</b>: Required
     * <br><b>Response</b>: 200 OK with count of sessions terminated
     *
     * <p><b>Real-world use case:</b>
     * User's phone gets stolen. They open the web browser, log in from a laptop,
     * and hit "Sign out from all devices". This endpoint revokes ALL active
     * refresh tokens (phone, tablet, laptop sessions). The thief's next API
     * call with the stolen token returns 401.
     *
     * <p><b>Why return the session count?</b>
     * UX clarity — "3 active sessions ended" is more reassuring than a blank
     * success. Also useful for security dashboards.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: User logged in from 3 devices → 3 sessions revoked → 200</li>
     *   <li><b>Edge case</b>: No active sessions (already logged out everywhere)
     *       → 0 sessions revoked → still 200 (idempotent)</li>
     *   <li><b>Failure</b>: No JWT → 401 (blocked by Spring Security filter)</li>
     * </ul>
     *
     * @param principal   the authenticated user with their UUID ready to use
     * @param httpRequest for IP + User-Agent (audit log)
     * @return 200 OK with number of sessions terminated
     */
    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Integer>> logoutAll(
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Logout-all request for user: {}", principal.getUserId());

        int sessionsRevoked = authService.logoutAll(principal.getUserId(), ip, userAgent);

        return ResponseEntity.ok(
                ApiResponse.success(
                        sessionsRevoked + " session(s) terminated across all devices.",
                        sessionsRevoked
                )
        );
    }
}
