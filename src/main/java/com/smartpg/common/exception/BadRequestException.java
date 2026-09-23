package com.smartpg.common.exception;

/**
 * Thrown when the client sends invalid or logically incorrect input
 * that passes @Valid annotation checks but fails business rule validation.
 * Maps to HTTP 400 BAD REQUEST in GlobalExceptionHandler.
 *
 * <p>Usage examples:
 *   throw new BadRequestException("Password and confirm password do not match");
 *   throw new BadRequestException("Cannot book a room that is already occupied");
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
