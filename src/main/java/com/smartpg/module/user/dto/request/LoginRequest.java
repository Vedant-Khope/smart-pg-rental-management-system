package com.smartpg.module.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Incoming payload for the {@code POST /api/v1/auth/login} endpoint.
 *
 * <p><b>Design note</b>: We intentionally keep login as simple as possible —
 * just email and password. Phone-based OTP login is a separate endpoint/flow
 * handled by a different DTO. Keeping them separate avoids a "god DTO" with
 * 10 optional fields, half of which are null on every call.
 *
 * <p><b>Security contract</b>:
 * <ul>
 *   <li>This DTO is NEVER logged. The password field must be masked in any
 *       log output (configure logback pattern to exclude request body on this path).</li>
 *   <li>Regardless of whether email or password is wrong, AuthService returns
 *       the same generic "Invalid credentials" message — never reveal WHICH
 *       field was wrong (prevents account enumeration attacks).</li>
 * </ul>
 *
 * @param email    the registered email address (case-insensitive at service layer)
 * @param password the plaintext password (compared via BCrypt.matches — never stored)
 */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 72, message = "Password must not exceed 72 characters")
        String password

) {}
