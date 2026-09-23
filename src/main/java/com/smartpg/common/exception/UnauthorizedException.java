package com.smartpg.common.exception;

/**
 * Thrown when a user attempts an action they are not authenticated to perform.
 * Maps to HTTP 401 UNAUTHORIZED in GlobalExceptionHandler.
 *
 * <p>Note: This is different from AccessDeniedException (403 FORBIDDEN).
 *   - 401 Unauthorized = "I don't know WHO you are" (not authenticated)
 *   - 403 Forbidden    = "I know who you are but you can't do this" (not authorized)
 *
 * <p>Usage examples:
 *   throw new UnauthorizedException("Invalid or expired token");
 *   throw new UnauthorizedException("Please login to continue");
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
