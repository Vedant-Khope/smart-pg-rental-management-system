package com.smartpg.common.exception;

/**
 * Thrown when an operation would create a duplicate / conflicting state in the database.
 * Maps to HTTP 409 CONFLICT in GlobalExceptionHandler.
 *
 * <p>Usage examples:
 *   throw new ConflictException("A user with this email already exists");
 *   throw new ConflictException("A property with this name already exists for this owner");
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
