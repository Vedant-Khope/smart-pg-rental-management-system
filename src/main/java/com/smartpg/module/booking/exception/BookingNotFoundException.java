package com.smartpg.module.booking.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class BookingNotFoundException extends ResourceNotFoundException {
    public BookingNotFoundException(UUID id) {
        super("Booking not found with ID: " + id);
    }
}
