package com.smartpg.module.user.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.common.util.HttpRequestUtil;
import com.smartpg.module.user.dto.request.ChangePasswordRequest;
import com.smartpg.module.user.dto.response.UserResponse;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.service.UserService;
import com.smartpg.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for the currently authenticated user's own account operations.
 *
 * <p><b>What is this?</b>
 * While {@link AdminController} manages OTHER users (admin operations),
 * UserController is for users managing THEMSELVES:
 * <ul>
 *   <li>Viewing their own profile</li>
 *   <li>Changing their password</li>
 *   <li>Deleting their own account (soft delete)</li>
 * </ul>
 *
 * <p><b>Who can access this?</b>
 * ANY authenticated user — TENANT, OWNER, CARETAKER, ADMIN, SUPER_ADMIN.
 * Every logged-in user should be able to manage their own account.
 * The "me" in the URL path makes this self-evident: {@code /users/me} = "about me".
 *
 * <p><b>Design pattern: "me" endpoints</b>
 * Instead of {@code GET /users/{id}} where the user passes their own UUID,
 * we use {@code GET /users/me}. Why?
 * <ol>
 *   <li>The client already has their UUID from the login response — but we
 *       can't trust the client to send the right UUID. A malicious user
 *       could send someone else's UUID to fetch their data.</li>
 *   <li>With {@code /me}, the UUID comes from the JWT (server-validated) —
 *       it's impossible to impersonate another user this way.</li>
 *   <li>Cleaner API: no ID needed in the URL path, fewer error possibilities.</li>
 * </ol>
 *
 * <p><b>Security model:</b>
 * These endpoints require authentication but are not role-restricted.
 * The {@code anyRequest().authenticated()} rule in SecurityConfig covers them.
 * Any valid JWT gets access — but only to your OWN data (enforced via the
 * JWT-extracted userId, not a client-provided one).
 *
 * <p><b>Base URL:</b> {@code /api/v1/users}
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserService userService;

    /**
     * Constructor injection of {@link UserService}.
     * All user account management operations are delegated here.
     *
     * @param userService the service handling user account operations
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    // =========================================================================
    // ① GET /api/v1/users/me
    // =========================================================================

    /**
     * Fetches the currently authenticated user's own account information.
     *
     * <p><b>Request</b>: {@code GET /api/v1/users/me}
     * <br><b>Headers</b>: {@code Authorization: Bearer <accessToken>} (required)
     * <br><b>Auth</b>: Any authenticated user
     * <br><b>Response</b>: 200 OK with {@link UserResponse}
     *
     * <p><b>Why {@link UserResponse} and not the raw {@link User} entity?</b>
     * The {@link User} entity has {@code passwordHash}, lazy-loaded collections,
     * and JPA metadata — NONE of which should go to the client. {@link UserResponse}
     * is a safe, controlled projection. Even if a developer accidentally adds a
     * sensitive field to the entity, the DTO acts as a firewall.
     *
     * <p><b>No DB query for the UUID:</b>
     * {@link UserPrincipal} already carries the UUID from the JWT filter.
     * The JWT filter ran before this method, loaded the user from DB once, and
     * stored the principal in the SecurityContext. {@code @AuthenticationPrincipal}
     * extracts it — zero extra DB calls for the user ID.
     *
     * <p><b>Hinglish note:</b>
     * "GET /users/me" ek mirror ki tarah hai — apna chehra dekho bina kisi ID ke.
     * JWT mein ID already hai, wahan se uthao, database mein verify karo, return karo.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Valid JWT → 200 with email, role, status, lastLoginAt...</li>
     *   <li><b>Edge case</b>: JWT valid but user deleted in DB (race condition)
     *       → EntityNotFoundException → GlobalExceptionHandler → 404</li>
     *   <li><b>Failure</b>: No JWT → Spring Security blocks → 401
     *       (never reaches this method)</li>
     * </ul>
     *
     * @param principal the JWT-authenticated user's principal (carries userId)
     * @return 200 OK with the user's safe profile data
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getMyProfile(
            @AuthenticationPrincipal UserPrincipal principal) {

        log.debug("Profile fetch for user: {}", principal.getUserId());

        User user     = userService.findById(principal.getUserId());
        UserResponse response = UserResponse.from(user);

        return ResponseEntity.ok(ApiResponse.success("Profile fetched successfully.", response));
    }

    // =========================================================================
    // ② POST /api/v1/users/me/change-password
    // =========================================================================

    /**
     * Changes the currently authenticated user's password.
     *
     * <p><b>Request</b>: {@code POST /api/v1/users/me/change-password}
     * <br><b>Headers</b>: {@code Authorization: Bearer <accessToken>}
     * <br><b>Body</b>: {@link ChangePasswordRequest}
     * <br><b>Auth</b>: Required
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>Why POST and not PATCH?</b>
     * Technically, changing a password is a partial update (PATCH) of the user resource.
     * But this is a security-sensitive action with its own payload (oldPassword, newPassword,
     * confirm). Using a dedicated POST endpoint makes the intent crystal-clear and
     * easier to audit and rate-limit separately from regular profile updates.
     * Industry practice (GitHub, Google) uses POST for password changes.
     *
     * <p><b>Confirm password check in controller:</b>
     * We compare {@code newPassword} and {@code confirmNewPassword} HERE in the controller
     * (not the service) because it's a presentation-layer concern. The service receives
     * already-validated inputs. If passwords don't match, we throw
     * {@link com.smartpg.common.exception.BadRequestException} → 400.
     *
     * <p><b>What happens after password change?</b>
     * ALL refresh tokens for this user are revoked ({@code revokeAllByUserId()}).
     * This forces re-login on all devices. The current access token is still valid
     * (JWTs are stateless) until it expires in ~15 minutes — acceptable trade-off.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Correct old password, new passwords match → 200,
     *       all sessions revoked</li>
     *   <li><b>Edge case</b>: New password same as old → service accepts it
     *       (no policy against reuse in v1 — can add later)</li>
     *   <li><b>Failure</b>: Wrong old password → InvalidCredentialsException → 401</li>
     * </ul>
     *
     * @param request     contains old password, new password, confirmation
     * @param principal   the authenticated user's principal
     * @param httpRequest for IP + User-Agent (audit log)
     * @return 200 OK confirming password was changed
     */
    @PostMapping("/me/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        // Fail fast: confirm passwords match before touching the service
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw new com.smartpg.common.exception.BadRequestException(
                    "New password and confirmation password do not match.");
        }

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Password change request for user: {}", principal.getUserId());

        userService.changePassword(
                principal.getUserId(),
                request.oldPassword(),
                request.newPassword(),
                ip,
                userAgent
        );

        return ResponseEntity.ok(
                ApiResponse.success("Password changed successfully. Please log in again on your other devices."));
    }

    // =========================================================================
    // ③ DELETE /api/v1/users/me
    // =========================================================================

    /**
     * Soft-deletes the currently authenticated user's own account.
     *
     * <p><b>Request</b>: {@code DELETE /api/v1/users/me}
     * <br><b>Headers</b>: {@code Authorization: Bearer <accessToken>}
     * <br><b>Auth</b>: Required
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>Soft delete — why not hard delete?</b>
     * <ul>
     *   <li><b>Referential integrity</b>: If a tenant deletes their account, their
     *       Booking rows, Payment rows, and Complaint rows still reference their userId.
     *       A hard DELETE would either fail (FK constraint) or cascade-delete financial
     *       records — illegal in many countries for accounting purposes.</li>
     *   <li><b>Audit trail</b>: We must keep the record that this user existed and
     *       what they did. {@code status = DELETED} marks them as gone without losing history.</li>
     *   <li><b>Recovery</b>: If the user changes their mind or was deleted by mistake,
     *       an admin can reactivate a soft-deleted account. Hard delete is irreversible.</li>
     * </ul>
     *
     * <p><b>What happens on soft delete?</b>
     * <ol>
     *   <li>{@code user.status} set to {@code DELETED}</li>
     *   <li>All active refresh tokens revoked (all sessions terminated)</li>
     *   <li>Audit log written with details of who deleted and when</li>
     *   <li>The current access token still works for ~15 minutes (JWT is stateless)
     *       but the user can't log in again — acceptable for v1</li>
     * </ol>
     *
     * <p><b>HTTP status choice:</b>
     * We use 200 OK with a message instead of 204 No Content because the message
     * ("Account deleted successfully") is useful UX for the frontend to display.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Valid JWT, account not already deleted → 200,
     *       status=DELETED, all sessions revoked</li>
     *   <li><b>Edge case</b>: Account already deleted → service returns without error
     *       (idempotent — deleting deleted account is a no-op)</li>
     *   <li><b>Failure</b>: No JWT → 401 (Spring Security blocks before this runs)</li>
     * </ul>
     *
     * @param principal   the authenticated user's principal (source of userId)
     * @param httpRequest for IP + User-Agent (audit log)
     * @return 200 OK confirming soft deletion
     */
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Void>> deleteMyAccount(
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Account deletion request for user: {}", principal.getUserId());

        userService.softDeleteUser(
                principal.getUserId(),
                principal.getUserId(), // caller is the user themselves
                false,                 // callerIsAdmin = false
                ip,
                userAgent
        );

        return ResponseEntity.ok(
                ApiResponse.success("Account deleted successfully. We hope to see you again."));
    }
}
