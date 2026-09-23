package com.smartpg.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/users/me/change-password}.
 *
 * <p><b>Why require both oldPassword and newPassword?</b>
 * This is the "authenticated change" flow (user is already logged in and
 * knows their current password). It's different from "forgot password" where
 * you reset via OTP/email link without knowing the old password.
 *
 * <p>Requiring the old password prevents an attacker who grabs an unlocked
 * phone or a session token from silently changing the victim's password.
 *
 * <p><b>Why confirmNewPassword?</b>
 * Classic UX safety net — the user types the new password twice to catch
 * typos. If they don't match, the backend returns a 400 before touching the DB.
 * The check is done in the controller (fail fast at the HTTP layer).
 *
 * @param oldPassword        the current password for verification
 * @param newPassword        the desired new password (will be BCrypt-hashed)
 * @param confirmNewPassword must match newPassword exactly
 */
public record ChangePasswordRequest(

        @NotBlank(message = "Current password is required")
        @Size(max = 72, message = "Password must not exceed 72 characters")
        String oldPassword,

        @NotBlank(message = "New password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String newPassword,

        @NotBlank(message = "Please confirm your new password")
        @Size(max = 72)
        String confirmNewPassword

) {}
