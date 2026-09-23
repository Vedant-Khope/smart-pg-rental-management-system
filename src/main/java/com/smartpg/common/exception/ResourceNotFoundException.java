package com.smartpg.common.exception;

/**
 * Thrown when a requested resource does not exist in the database.
 * Maps to HTTP 404 NOT FOUND in GlobalExceptionHandler.
 *
 * <p>Usage examples:
 *   throw new ResourceNotFoundException("User", "id", 42L);
 *   → "User not found with id: '42'"
 *
 *   throw new ResourceNotFoundException("Property with that name does not exist");
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Use for simple, custom messages.
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * Use for standardized "entity not found by field" messages.
     * Produces: "User not found with email: 'john@gmail.com'"
     *
     * @param resourceName  The entity name (e.g., "User", "Property")
     * @param fieldName     The field used for lookup (e.g., "id", "email")
     * @param fieldValue    The actual value that was searched
     */
    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
