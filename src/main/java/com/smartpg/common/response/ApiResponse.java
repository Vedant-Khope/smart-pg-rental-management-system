package com.smartpg.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Standard API response wrapper for ALL endpoints in the system.
 * Every controller method returns ResponseEntity<ApiResponse<T>>.
 *
 * <p>Generic type T allows this to wrap any data type:
 * ApiResponse<UserResponse>, ApiResponse<List<PropertyResponse>>, etc.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    // true = request succeeded, false = request failed
    private boolean success;

    // Human-readable message for the frontend
    private String message;

    // The actual payload. Null on error responses — hidden by @JsonInclude
    private T data;

    // Auto-populated timestamp. @Builder.Default ensures this runs even when using builder()
    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

    // ─────────────────────────────────────────────
    // Static factory methods — cleaner than calling builder() everywhere
    // ─────────────────────────────────────────────

    /**
     * Use when the operation succeeds and returns data.
     * Example: GET /users/me → success("Profile fetched", userResponse)
     */
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * Use when the operation succeeds but returns no data.
     * Example: DELETE /users/{id} → success("User deleted")
     */
    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .build();
    }

    /**
     * Use when the operation fails.
     * Example: POST /auth/login with wrong password → error("Invalid credentials")
     */
    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }
}
