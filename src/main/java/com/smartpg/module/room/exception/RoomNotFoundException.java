package com.smartpg.module.room.exception;

import com.smartpg.common.exception.ResourceNotFoundException;
import java.util.UUID;

public class RoomNotFoundException extends ResourceNotFoundException {
    public RoomNotFoundException(UUID roomId) {
        super("Room not found with ID: " + roomId);
    }
}
