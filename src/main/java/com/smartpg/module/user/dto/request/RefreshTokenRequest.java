package com.smartpg.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for {@code POST /api/v1/auth/refresh} — exchange a valid refresh token
 * for a new short-lived access token.
 *
 * <p><b>Token Rotation Strategy</b>:
 * On every successful refresh, the old refresh token is revoked and a brand new
 * one is issued alongside the new access token. This is called "refresh token rotation"
 * and prevents replay attacks — even if a token is stolen, it becomes invalid
 * after first use.
 *
 * <p><b>Why send the refresh token in the body, not a cookie?</b>
 * Both patterns are valid. For this API:
 * <ul>
 *   <li>Body: simpler for mobile clients (React Native, Flutter) and Postman testing.</li>
 *   <li>Cookie (HttpOnly): better for browser clients (XSS protection).</li>
 * </ul>
 * This implementation uses body-based for simplicity. A future enhancement could
 * support HttpOnly cookies as an alternative via request headers.
 *
 * @param refreshToken the opaque refresh token string issued at login or last refresh
 */
public record RefreshTokenRequest(

        @NotBlank(message = "Refresh token is required")
        String refreshToken

) {}
