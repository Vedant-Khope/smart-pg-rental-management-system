package com.smartpg.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/admin/users/{id}/suspend} — suspending a user account.
 *
 * <p><b>Why require a reason?</b>
 * Suspension must be auditable. If a landlord complains "why am I suspended?",
 * the support team needs a clear reason stored in the audit log. "Spam behavior"
 * is actionable; a suspend with no reason is legally and operationally risky.
 *
 * <p>The reason is stored in the {@code UserAuditLog.details} JSON field.
 *
 * @param reason human-readable explanation of why the account is being suspended
 */
public record SuspendUserRequest(

        @NotBlank(message = "Suspension reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason

) {}
