package com.smartpg.module.user.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class UserNotFoundException extends ResourceNotFoundException {
    public UserNotFoundException(UUID userId) {
        super("User not found with ID: " + userId);
    }
}
