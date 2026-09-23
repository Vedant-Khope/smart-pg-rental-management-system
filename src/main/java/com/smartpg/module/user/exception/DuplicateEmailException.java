package com.smartpg.module.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a registration attempt uses an email that already exists.
 *
 * <p><b>Maps to 409 Conflict</b> — the resource (user with this email) already exists.
 * Unlike 400 Bad Request (which is "you sent bad data"), 409 means "the data is
 * technically valid, but it conflicts with an existing resource state".
 *
 * <p>This is one of the rare cases where it's OK to reveal existence —
 * at registration time, telling the user "this email is already taken" is
 * helpful UX, not a security risk (it's a public registration form).
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String email) {
        super("An account with email '" + email + "' already exists. Please login or use a different email.");
    }
}
