package com.smartpg.module.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a refresh token is not found, expired, or revoked.
 *
 * <p>Maps to {@code 401 Unauthorized} — the session is dead, the client must
 * log in again. The frontend should intercept this and redirect to /login.
 *
 * <p><b>Why NOT 403?</b> 403 means you're authenticated but not authorized.
 * A bad/expired refresh token means you're NOT authenticated — use 401.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public static InvalidTokenException notFound() {
        return new InvalidTokenException("Refresh token not found. Please login again.");
    }

    public static InvalidTokenException expired() {
        return new InvalidTokenException("Session expired. Please login again.");
    }

    public static InvalidTokenException revoked() {
        return new InvalidTokenException("Session was terminated. Please login again.");
    }
}
