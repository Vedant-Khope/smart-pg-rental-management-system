package com.smartpg.module.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a user account is found but blocked from logging in.
 *
 * <p>Used when authentication is fine (correct email + password) but the
 * account state prevents access:
 * <ul>
 *   <li>{@code PENDING_VERIFICATION} — email not yet verified</li>
 *   <li>{@code SUSPENDED} — admin suspended the account</li>
 *   <li>{@code DELETED} — soft-deleted account</li>
 *   <li>{@code INACTIVE} — user self-deactivated</li>
 * </ul>
 *
 * <p><b>Why 403 and not 401?</b>
 * The credentials were VALID (user authenticated), but the account state
 * FORBIDS access. That's exactly what 403 Forbidden means.
 *
 * <p>The message can be specific here (unlike {@link InvalidCredentialsException})
 * because we've already authenticated the user — telling them "your account is
 * suspended" doesn't reveal any information about other users.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class AccountStatusException extends RuntimeException {

    public AccountStatusException(String message) {
        super(message);
    }

    public static AccountStatusException pendingVerification() {
        return new AccountStatusException("Your email is not yet verified. Please check your inbox for the verification link.");
    }

    public static AccountStatusException suspended() {
        return new AccountStatusException("Your account has been suspended. Please contact support for assistance.");
    }

    public static AccountStatusException deleted() {
        return new AccountStatusException("This account no longer exists. Please register again.");
    }

    public static AccountStatusException inactive() {
        return new AccountStatusException("Your account is inactive. Please contact support to reactivate.");
    }
}
