package com.smartpg.module.booking.exception;

import com.smartpg.common.exception.BadRequestException;

public class BedUnavailableException extends BadRequestException {
    public BedUnavailableException(String message) {
        super(message);
    }
}
