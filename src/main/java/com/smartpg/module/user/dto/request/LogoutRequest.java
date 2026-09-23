package com.smartpg.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/auth/logout} — revoking a specific session.
 *
 * <p><b>Why send the refresh token in the body for logout?</b>
 * The access token (JWT) is stateless — we can't revoke it. But the refresh
 * token IS stored in the DB. By sending the refresh token on logout, we revoke
 * that specific session row. This is the correct, token-rotation-compatible approach.
 *
 * <p><b>Why not just use the Authorization header token for logout?</b>
 * The Authorization header carries the ACCESS token (short-lived JWT). Revoking
 * that is impossible without a blocklist. The REFRESH token is what gives
 * persistent access — revoking it effectively ends the session.
 *
 * @param refreshToken the opaque refresh token identifying the session to terminate
 */
public record LogoutRequest(

        @NotBlank(message = "Refresh token is required for logout")
        @Size(max = 512, message = "Invalid token format")
        String refreshToken

) {}
