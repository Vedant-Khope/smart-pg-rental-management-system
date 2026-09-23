package com.smartpg.module.property.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class PropertyNotFoundException extends ResourceNotFoundException {
    public PropertyNotFoundException(UUID id) {
        super("Property not found with ID: " + id);
    }
}
