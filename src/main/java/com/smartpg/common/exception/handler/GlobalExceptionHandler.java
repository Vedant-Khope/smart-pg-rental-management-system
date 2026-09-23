package com.smartpg.common.exception.handler;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.common.exception.ConflictException;
import com.smartpg.common.exception.ResourceNotFoundException;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.user.exception.AccountStatusException;
import com.smartpg.module.user.exception.DuplicateEmailException;
import com.smartpg.module.user.exception.InvalidCredentialsException;
import com.smartpg.module.user.exception.InvalidTokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Central exception handling for the entire application.
 *
 * <p>@RestControllerAdvice intercepts exceptions thrown from ANY
 * @RestController in the application and converts them into clean
 * ApiResponse JSON objects with appropriate HTTP status codes.
 *
 * <p>This is the ONLY place in the codebase where exceptions are
 * converted to HTTP responses. No try-catch in controllers needed.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ──────────────────────────────────────────────
    // Our Custom Business Exceptions
    // ──────────────────────────────────────────────

    /**
     * Handles: throw new ResourceNotFoundException("User", "id", 42L)
     * Returns: 404 NOT FOUND
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.error("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: throw new BadRequestException("Passwords do not match")
     * Returns: 400 BAD REQUEST
     */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(BadRequestException ex) {
        log.error("Bad request: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: throw new ConflictException("Email already in use")
     * Returns: 409 CONFLICT
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Object>> handleConflict(ConflictException ex) {
        log.error("Conflict: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: throw new UnauthorizedException("Invalid or expired token")
     * Returns: 401 UNAUTHORIZED
     */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnauthorized(UnauthorizedException ex) {
        log.error("Unauthorized access: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // ──────────────────────────────────────────────
    // Domain / Business Exceptions
    // ──────────────────────────────────────────────

    /**
     * Handles: Login with wrong email or password.
     * Security: Generic message — never reveals which field was wrong.
     * Returns: 401 UNAUTHORIZED
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidCredentials(InvalidCredentialsException ex) {
        log.warn("Authentication failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: Login attempt with a blocked account status (SUSPENDED, DELETED, etc.).
     * Returns: 403 FORBIDDEN — credentials were valid, but account state blocks access.
     */
    @ExceptionHandler(AccountStatusException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccountStatus(AccountStatusException ex) {
        log.warn("Account status blocked login: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: Refresh token not found, expired, or revoked.
     * Returns: 401 UNAUTHORIZED — session is dead, client must re-login.
     */
    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiResponse<Object>> handleInvalidToken(InvalidTokenException ex) {
        log.warn("Invalid token: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: Registration with an email already in the system.
     * Returns: 409 CONFLICT — the resource (user with this email) already exists.
     */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiResponse<Object>> handleDuplicateEmail(DuplicateEmailException ex) {
        log.warn("Duplicate email registration attempt: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: Business rule violations (e.g., demoting last SUPER_ADMIN,
     * reactivating a DELETED account).
     * Returns: 422 UNPROCESSABLE ENTITY — the request was well-formed but
     * cannot be processed due to business logic constraints.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalState(IllegalStateException ex) {
        log.error("Business rule violation: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Handles: Self-registration attempt with a restricted role (ADMIN, SUPER_ADMIN).
     * Returns: 400 BAD REQUEST.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ex.getMessage()));
    }

    // ──────────────────────────────────────────────
    // Spring / Jakarta Validation Exception
    // ──────────────────────────────────────────────

    /**
     * Handles: @Valid on a @RequestBody fails (e.g., blank email, short password)
     * Returns: 400 BAD REQUEST with a map of field → error message
     *
     * <p>Example response body:
     * {
     *   "success": false,
     *   "message": "Validation failed",
     *   "data": {
     *     "email": "must not be blank",
     *     "password": "size must be between 8 and 50"
     *   }
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> fieldErrors = new HashMap<>();

        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            fieldErrors.put(fieldName, errorMessage);
        });

        log.error("Validation failed for fields: {}", fieldErrors.keySet());

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .message("Validation failed")
                        .data(fieldErrors)
                        .build());
    }

    // ──────────────────────────────────────────────
    // Spring Security Exceptions
    // ──────────────────────────────────────────────

    /**
     * Handles: User is authenticated but lacks the required role/permission.
     * Example: TENANT tries to call an OWNER-only endpoint.
     * Returns: 403 FORBIDDEN
     *
     * <p>Note: Spring Security throws AccessDeniedException automatically
     * when @PreAuthorize("hasRole('OWNER')") fails.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action"));
    }

    /**
     * Handles: Authentication failures from Spring Security.
     * Returns: 401 UNAUTHORIZED
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Object>> handleAuthenticationException(
            AuthenticationException ex) {
        log.error("Authentication failed: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("Authentication failed: " + ex.getMessage()));
    }

    // ──────────────────────────────────────────────
    // Catch-All — NEVER expose stack traces to clients
    // ──────────────────────────────────────────────

    /**
     * Handles: Any unhandled exception not caught by the specific handlers above.
     * Returns: 500 INTERNAL SERVER ERROR with a generic, safe message.
     *
     * <p>CRITICAL: We log the full exception here for debugging,
     * but we NEVER send the stack trace or internal details to the client.
     * This prevents information leakage in production.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error occurred: ", ex);  // Full stack trace in server logs only
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred. Please try again later."));
    }
}
