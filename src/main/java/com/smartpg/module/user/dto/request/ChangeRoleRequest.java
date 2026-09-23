package com.smartpg.module.user.dto.request;

import com.smartpg.module.user.enums.Role;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for {@code PATCH /api/v1/admin/users/{id}/role} — changing a user's role.
 *
 * <p><b>Who can call this?</b> Only ADMIN or SUPER_ADMIN (enforced via
 * {@code @PreAuthorize} in the controller).
 *
 * <p><b>Business rule enforced in service:</b>
 * You cannot demote the last SUPER_ADMIN — the system must always have
 * at least one. The service checks this and throws an IllegalStateException.
 *
 * @param newRole the role to assign to the target user
 */
public record ChangeRoleRequest(

        @NotNull(message = "New role is required")
        Role newRole

) {}
