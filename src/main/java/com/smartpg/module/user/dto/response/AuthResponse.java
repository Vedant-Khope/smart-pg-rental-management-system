package com.smartpg.module.user.dto.response;

import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload returned after a successful login or registration.
 *
 * <p><b>What goes in here?</b>
 * <ul>
 *   <li>The <b>access token</b> (JWT) — used in {@code Authorization: Bearer <token>} header
 *       for every subsequent API call. Short-lived (15 min).</li>
 *   <li>The <b>refresh token</b> — used ONLY at {@code POST /auth/refresh} to get
 *       a new access token. Long-lived (30 days). Stored in DB ({@link com.smartpg.module.user.model.RefreshToken}).</li>
 *   <li>Basic <b>user info</b> — so the client doesn't have to make a second API call
 *       just to know who logged in. Role helps the frontend decide which UI to show.</li>
 * </ul>
 *
 * <p><b>What does NOT go in here?</b>
 * <ul>
 *   <li>Password hash — obvious. Never.</li>
 *   <li>Aadhaar / PAN — sensitive PII, never in a generic auth response.</li>
 *   <li>Internal fields like createdAt, updatedAt — not needed at login time.</li>
 * </ul>
 *
 * <p><b>Why a record?</b> Immutable, zero boilerplate, auto-serialized to JSON by Jackson.
 * Jackson serializes record components just like regular bean properties.
 *
 * @param accessToken   the JWT access token — put in Authorization header on every request
 * @param refreshToken  the opaque refresh token — store securely, use only at /auth/refresh
 * @param tokenType     always "Bearer" — the scheme used in the Authorization header
 * @param expiresIn     number of seconds until the access token expires (for client-side countdown)
 * @param userId        the UUID of the logged-in user — frontend needs this for routing/caching
 * @param email         the email address of the logged-in user
 * @param role          the user's role — determines which dashboard/UI the frontend shows
 * @param status        the account status — client can detect PENDING_VERIFICATION and redirect
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UUID userId,
        String email,
        Role role,
        UserStatus status
) {

    /**
     * Convenience factory method — builds an AuthResponse from a User entity plus tokens.
     *
     * <p>Using a static factory instead of a constructor keeps the service clean:
     * <pre>
     *   return AuthResponse.from(user, accessToken, refreshToken, expiresIn);
     * </pre>
     *
     * <p>This pattern avoids constructing AuthResponse with 8 positional args scattered
     * across the service method, which is error-prone and hard to read.
     *
     * @param user         the authenticated user entity
     * @param accessToken  the generated JWT access token
     * @param refreshToken the issued refresh token string
     * @param expiresIn    seconds until the access token expires
     * @return a fully populated AuthResponse
     */
    public static AuthResponse from(User user, String accessToken,
                                    String refreshToken, long expiresIn) {
        return new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                expiresIn,
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus()
        );
    }
}
