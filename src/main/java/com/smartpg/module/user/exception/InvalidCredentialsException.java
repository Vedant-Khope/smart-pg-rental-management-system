package com.smartpg.module.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a login attempt is made with a non-existent email or wrong password.
 *
 * <p><b>Security-critical design</b>:
 * This exception message is GENERIC — it NEVER reveals whether the email exists
 * or whether the password is wrong. "Invalid credentials" is the only message.
 *
 * <p>Why? If you say "User not found" → attacker knows the email isn't registered.
 * If you say "Wrong password" → attacker knows the email IS registered and can target it.
 * Generic message = zero information leakage = account enumeration attack prevented.
 *
 * <p>Maps to: {@code 401 Unauthorized} — the caller tried to authenticate but failed.
 * Do NOT use 403 (Forbidden) here — that's for authenticated-but-not-authorized.
 */
@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials. Please check your email and password.");
    }

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
