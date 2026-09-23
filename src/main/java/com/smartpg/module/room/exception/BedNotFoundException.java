package com.smartpg.module.room.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class BedNotFoundException extends ResourceNotFoundException {
    public BedNotFoundException(UUID bedId) {
        super("Bed not found with ID: " + bedId);
    }
}
