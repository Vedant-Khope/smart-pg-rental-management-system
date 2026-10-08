package com.smartpg.module.booking.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class TenancyNotFoundException extends ResourceNotFoundException {
    public TenancyNotFoundException(UUID tenancyId) {
        super("Tenancy not found with ID: " + tenancyId);
    }
}
