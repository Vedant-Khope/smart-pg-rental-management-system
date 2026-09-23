package com.smartpg.module.user.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.common.util.HttpRequestUtil;
import com.smartpg.module.user.dto.request.ChangeRoleRequest;
import com.smartpg.module.user.dto.request.SuspendUserRequest;
import com.smartpg.module.user.dto.response.UserResponse;
import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.service.UserService;
import com.smartpg.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST Controller for admin-level user management operations.
 *
 * <p><b>What is this?</b>
 * The "HR Department" API. While {@link UserController} lets users manage
 * themselves, AdminController lets ADMIN/SUPER_ADMIN manage OTHER users:
 * view all users, suspend accounts, change roles, reactivate, soft-delete.
 *
 * <p><b>Double Authorization — Defense in Depth:</b>
 * <ol>
 *   <li><b>URL-level</b>: SecurityConfig restricts {@code /api/v1/admin/**}
 *       to {@code hasAnyRole("ADMIN", "SUPER_ADMIN")} at the HTTP filter level.
 *       A TENANT or OWNER never even reaches this controller.</li>
 *   <li><b>Method-level</b>: {@code @PreAuthorize} on specific methods adds a
 *       second check. Example: only SUPER_ADMIN can change roles.
 *       Even if the URL rule were misconfigured, the method-level check still holds.</li>
 * </ol>
 * This is "defense in depth" — two independent authorization layers.
 * One misconfiguration doesn't expose the endpoint.
 *
 * <p><b>Why separate AdminController and UserController?</b>
 * <ul>
 *   <li><b>Single Responsibility</b>: Each controller has ONE cohesive purpose.</li>
 *   <li><b>Security clarity</b>: All admin ops are in one place — easy to audit.</li>
 *   <li><b>Testability</b>: You can write admin tests and user tests in isolation.</li>
 *   <li><b>URL structure</b>: {@code /api/v1/admin/...} is instantly recognizable as
 *       admin-only — no ambiguity about who should call what.</li>
 * </ul>
 *
 * <p><b>@PreAuthorize SpEL expressions used here:</b>
 * <ul>
 *   <li>{@code hasRole('ADMIN')} → checks for "ROLE_ADMIN" authority</li>
 *   <li>{@code hasRole('SUPER_ADMIN')} → checks for "ROLE_SUPER_ADMIN" authority</li>
 *   <li>{@code hasAnyRole('ADMIN','SUPER_ADMIN')} → either role works</li>
 * </ul>
 * These work because {@link com.smartpg.security.UserPrincipal} grants
 * "ROLE_" + role.name() as the authority when the user is authenticated.
 *
 * <p><b>Base URL:</b> {@code /api/v1/admin/users}
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")  // class-level guard — applies to ALL methods
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final UserService userService;

    /**
     * Constructor injection — explicit, testable, fail-fast.
     *
     * @param userService the user management service
     */
    public AdminController(UserService userService) {
        this.userService = userService;
    }

    // =========================================================================
    // ① GET /api/v1/admin/users/{id}
    // =========================================================================

    /**
     * Fetches a specific user's account details by their UUID.
     *
     * <p><b>Request</b>: {@code GET /api/v1/admin/users/{id}}
     * <br><b>Auth</b>: ADMIN or SUPER_ADMIN
     * <br><b>Response</b>: 200 OK with {@link UserResponse}
     *
     * <p><b>@PathVariable UUID id explained:</b>
     * Spring MVC automatically converts the {@code {id}} path segment string
     * into a {@code UUID} object. If the string is not a valid UUID format
     * (e.g., "abc" instead of "550e8400-e29b-41d4-a716-446655440000"),
     * Spring throws a {@code MethodArgumentTypeMismatchException}
     * → GlobalExceptionHandler → 400 BAD REQUEST.
     * Zero extra validation code needed for UUID format.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Valid admin JWT + valid UUID → 200 with user data</li>
     *   <li><b>Edge case</b>: Valid UUID but user doesn't exist
     *       → EntityNotFoundException → 404 NOT FOUND</li>
     *   <li><b>Failure</b>: TENANT tries to call this → URL-level block → 403</li>
     * </ul>
     *
     * @param id the UUID of the user to fetch
     * @return 200 OK with the user's safe profile data
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getUserById(@PathVariable UUID id) {
        log.debug("Admin fetching user: {}", id);

        User user = userService.findById(id);
        return ResponseEntity.ok(
                ApiResponse.success("User fetched successfully.", UserResponse.from(user)));
    }

    // =========================================================================
    // ② GET /api/v1/admin/users?role=TENANT&page=0&size=20
    // =========================================================================

    /**
     * Lists users filtered by role with pagination.
     *
     * <p><b>Request</b>: {@code GET /api/v1/admin/users?role=TENANT&page=0&size=20}
     * <br><b>Auth</b>: ADMIN or SUPER_ADMIN
     * <br><b>Response</b>: 200 OK with paginated {@link UserResponse} list
     *
     * <p><b>@PageableDefault explained:</b>
     * Spring Data's {@code Pageable} is auto-resolved from query params:
     * {@code ?page=0&size=20&sort=createdAt,desc}. Without params, defaults kick in:
     * {@code @PageableDefault(size = 20, sort = "createdAt")} means:
     * page 0, 20 items per page, sorted by createdAt ASC.
     * This prevents accidental full-table scans if the client forgets to paginate.
     *
     * <p><b>Why paginate?</b>
     * A production PG platform could have 100,000 users. Loading all of them in
     * one response would: (a) crash the server with OOM, (b) time out the HTTP
     * connection, (c) send 10MB+ JSON to the client for no reason.
     * Pagination is NON-NEGOTIABLE for list endpoints.
     *
     * <p><b>Returning {@code Page<UserResponse>} vs {@code List<UserResponse>}:</b>
     * {@code Page<T>} includes:
     * <ul>
     *   <li>{@code content}: the actual list</li>
     *   <li>{@code totalElements}: total count (for "Showing 1-20 of 450 users")</li>
     *   <li>{@code totalPages}: how many pages exist</li>
     *   <li>{@code number}: current page</li>
     * </ul>
     * The frontend needs all this to render pagination controls.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: {@code ?role=TENANT} → paginated list of tenants</li>
     *   <li><b>Edge case</b>: {@code ?role=INVALID_ROLE} → Spring fails to bind Role enum
     *       → MethodArgumentTypeMismatchException → 400 BAD REQUEST</li>
     *   <li><b>Failure</b>: No role param provided → 400 (role is required)</li>
     * </ul>
     *
     * @param role     the role to filter by (required query param)
     * @param pageable auto-resolved from ?page, ?size, ?sort query params
     * @return 200 OK with a paginated list of users
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getUsersByRole(
            @RequestParam Role role,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        log.debug("Admin listing users with role: {}", role);

        Page<UserResponse> page = userService.findByRole(role, pageable)
                .map(UserResponse::from);  // transform Page<User> → Page<UserResponse>

        return ResponseEntity.ok(
                ApiResponse.success("Users fetched successfully.", page));
    }

    // =========================================================================
    // ③ GET /api/v1/admin/users/by-status?status=SUSPENDED
    // =========================================================================

    /**
     * Lists users filtered by account status with pagination.
     *
     * <p><b>Request</b>: {@code GET /api/v1/admin/users/by-status?status=SUSPENDED}
     * <br><b>Auth</b>: ADMIN or SUPER_ADMIN
     * <br><b>Response</b>: 200 OK with paginated {@link UserResponse} list
     *
     * <p><b>Real-world use case:</b>
     * Admin dashboard has a "Suspended Accounts" tab. It calls this endpoint
     * with {@code ?status=SUSPENDED} to show a list of suspended users that
     * need admin review. Also used for "Pending Verification" queue.
     *
     * <p><b>Why a separate endpoint instead of combining with the role filter?</b>
     * Combining role + status filters in one endpoint makes the service method
     * more complex (dynamic query). For v1, two clean endpoints are simpler.
     * In v2, add a search/filter endpoint with optional params.
     *
     * @param status   the account status to filter by
     * @param pageable pagination params from query string
     * @return 200 OK with paginated list of users with the given status
     */
    @GetMapping("/by-status")
    public ResponseEntity<ApiResponse<Page<UserResponse>>> getUsersByStatus(
            @RequestParam UserStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        log.debug("Admin listing users with status: {}", status);

        Page<UserResponse> page = userService.findByStatus(status, pageable)
                .map(UserResponse::from);

        return ResponseEntity.ok(
                ApiResponse.success("Users fetched successfully.", page));
    }

    // =========================================================================
    // ④ PATCH /api/v1/admin/users/{id}/role
    // =========================================================================

    /**
     * Changes a user's role. SUPER_ADMIN only.
     *
     * <p><b>Request</b>: {@code PATCH /api/v1/admin/users/{id}/role}
     * <br><b>Body</b>: {@link ChangeRoleRequest}
     * <br><b>Auth</b>: SUPER_ADMIN only
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>Why PATCH and not PUT?</b>
     * {@code PUT} replaces the entire resource. {@code PATCH} is a partial update.
     * We're only changing ONE field (role) of the user — PATCH is semantically correct.
     *
     * <p><b>Why SUPER_ADMIN only (not ADMIN)?</b>
     * Role changes are extremely powerful — they can elevate a TENANT to ADMIN.
     * This is a security-critical operation that only the platform owner should do.
     * An ADMIN should not be able to create more ADMINs — privilege escalation risk.
     *
     * <p><b>Method-level @PreAuthorize vs class-level:</b>
     * The class has {@code @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")} which
     * allows both roles. This method OVERRIDES it with SUPER_ADMIN only.
     * Method-level annotations take precedence over class-level ones in Spring Security.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: SUPER_ADMIN changes TENANT → OWNER → 200</li>
     *   <li><b>Edge case</b>: Demoting the last SUPER_ADMIN → IllegalStateException
     *       → GlobalExceptionHandler → 500 (but the message is clear)</li>
     *   <li><b>Failure</b>: ADMIN tries this → @PreAuthorize fails → 403</li>
     * </ul>
     *
     * @param id        the UUID of the user whose role to change
     * @param request   contains the new role to assign
     * @param principal the authenticated admin's principal (for audit log)
     * @param httpRequest for IP + User-Agent (audit log)
     * @return 200 OK confirming role change
     */
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")  // overrides class-level — only SUPER_ADMIN
    public ResponseEntity<ApiResponse<Void>> changeUserRole(
            @PathVariable UUID id,
            @Valid @RequestBody ChangeRoleRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Role change: user {} → {} (by super_admin: {})",
                id, request.newRole(), principal.getUserId());

        userService.changeRole(id, request.newRole(), principal.getUserId(), ip, userAgent);

        return ResponseEntity.ok(
                ApiResponse.success("User role updated to " + request.newRole().name() + " successfully."));
    }

    // =========================================================================
    // ⑤ POST /api/v1/admin/users/{id}/suspend
    // =========================================================================

    /**
     * Suspends a user account. ADMIN or SUPER_ADMIN.
     *
     * <p><b>Request</b>: {@code POST /api/v1/admin/users/{id}/suspend}
     * <br><b>Body</b>: {@link SuspendUserRequest} (reason)
     * <br><b>Auth</b>: ADMIN or SUPER_ADMIN
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>What suspension does:</b>
     * <ol>
     *   <li>Sets {@code user.status = SUSPENDED}</li>
     *   <li>Revokes all active refresh tokens (kicks user off all devices immediately)</li>
     *   <li>The next API call the user makes will fail at the JWT filter
     *       (user is loaded from DB, checked for SUSPENDED status)</li>
     * </ol>
     *
     * <p><b>Why POST for suspend (not PATCH)?</b>
     * "Suspend" is an action, not a partial update. Actions map well to POST.
     * PATCH would imply you're sending a partial user object — but we're triggering
     * a workflow (suspend + revoke sessions + audit log). POST is cleaner for actions.
     *
     * <p><b>Idempotent:</b>
     * Suspending an already-suspended user is a no-op. The service checks and returns
     * without error. This prevents confusion from double-clicks or retry loops.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Active user gets suspended → 200, sessions revoked</li>
     *   <li><b>Edge case</b>: Already suspended → no-op → 200 (idempotent)</li>
     *   <li><b>Failure</b>: Invalid UUID format in path → 400 from Spring type conversion</li>
     * </ul>
     *
     * @param id        the UUID of the user to suspend
     * @param request   contains the suspension reason (required for audit)
     * @param principal the admin performing the action (for audit log)
     * @param httpRequest for IP + User-Agent
     * @return 200 OK confirming suspension
     */
    @PostMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspendUser(
            @PathVariable UUID id,
            @Valid @RequestBody SuspendUserRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Suspending user: {} (reason: {}, by admin: {})",
                id, request.reason(), principal.getUserId());

        userService.suspendUser(id, request.reason(), principal.getUserId(), ip, userAgent);

        return ResponseEntity.ok(ApiResponse.success("User account suspended successfully."));
    }

    // =========================================================================
    // ⑥ POST /api/v1/admin/users/{id}/reactivate
    // =========================================================================

    /**
     * Reactivates a suspended or inactive user account.
     *
     * <p><b>Request</b>: {@code POST /api/v1/admin/users/{id}/reactivate}
     * <br><b>Auth</b>: ADMIN or SUPER_ADMIN
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>Use case:</b>
     * A user was suspended for spam. They contact support, explain themselves,
     * and the admin lifts the suspension. The user can now log in again.
     *
     * <p><b>Note:</b> Reactivating a DELETED account is NOT allowed.
     * The service throws {@code IllegalStateException} if you try.
     * Deleted accounts are permanent — they'd need to register fresh.
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Suspended user gets reactivated → status = ACTIVE → 200</li>
     *   <li><b>Edge case</b>: Already ACTIVE → no-op → 200 (idempotent)</li>
     *   <li><b>Failure</b>: Trying to reactivate DELETED account
     *       → IllegalStateException → GlobalExceptionHandler → 500 with clear message</li>
     * </ul>
     *
     * @param id        the UUID of the user to reactivate
     * @param principal the admin performing the action
     * @param httpRequest for IP + User-Agent
     * @return 200 OK confirming reactivation
     */
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivateUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Reactivating user: {} (by admin: {})", id, principal.getUserId());

        userService.reactivateUser(id, principal.getUserId(), ip, userAgent);

        return ResponseEntity.ok(ApiResponse.success("User account reactivated successfully."));
    }

    // =========================================================================
    // ⑦ DELETE /api/v1/admin/users/{id}
    // =========================================================================

    /**
     * Soft-deletes a user account. ADMIN or SUPER_ADMIN.
     *
     * <p><b>Request</b>: {@code DELETE /api/v1/admin/users/{id}}
     * <br><b>Auth</b>: ADMIN or SUPER_ADMIN
     * <br><b>Response</b>: 200 OK
     *
     * <p><b>When is admin-delete used (vs user self-delete)?</b>
     * <ul>
     *   <li>User violated ToS and must be removed by admin action</li>
     *   <li>Duplicate accounts detected — merge and delete the duplicate</li>
     *   <li>Fraudulent account identified — remove immediately</li>
     *   <li>Legal compliance — GDPR right to erasure (implemented as soft delete in v1)</li>
     * </ul>
     *
     * <p><b>callerIsAdmin = true in the service call:</b>
     * The service uses this flag to set the audit log detail:
     * {@code "isAdminAction": true} — important for compliance reporting
     * (knowing whether the deletion was requested by the user or forced by admin).
     *
     * <p><b>Scenarios:</b>
     * <ul>
     *   <li><b>Happy path</b>: Admin deletes active user → status=DELETED, sessions revoked → 200</li>
     *   <li><b>Edge case</b>: Already deleted → no-op → 200</li>
     *   <li><b>Failure</b>: UUID doesn't exist → EntityNotFoundException → 404</li>
     * </ul>
     *
     * @param id        the UUID of the user to soft-delete
     * @param principal the admin performing the deletion
     * @param httpRequest for IP + User-Agent
     * @return 200 OK confirming soft deletion
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest) {

        String ip        = HttpRequestUtil.getClientIp(httpRequest);
        String userAgent = HttpRequestUtil.getUserAgent(httpRequest);

        log.info("Admin soft-delete: user {} (by admin: {})", id, principal.getUserId());

        userService.softDeleteUser(
                id,
                principal.getUserId(), // callerId = the admin doing the deletion
                true,                  // callerIsAdmin = true
                ip,
                userAgent
        );

        return ResponseEntity.ok(ApiResponse.success("User account deleted successfully."));
    }
}
