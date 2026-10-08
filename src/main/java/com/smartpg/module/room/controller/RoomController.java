package com.smartpg.module.room.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.room.dto.request.CreateRoomRequest;
import com.smartpg.module.room.dto.response.RoomResponse;
import com.smartpg.module.room.service.RoomService;
import com.smartpg.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Room endpoints.
 *
 * <p>Handles operations related to Rooms within a Property.
 */
@RestController
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * OWNER API: Creates a new room within a property.
     * Enforces that only users with the OWNER role can access this.
     */
    @PostMapping("/api/v1/properties/{propertyId}/rooms")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<RoomResponse>> createRoom(
            @PathVariable UUID propertyId,
            @Valid @RequestBody CreateRoomRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        RoomResponse response = roomService.createRoom(propertyId, request, currentUser.getUserId());
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Room created successfully", response));
    }

    /**
     * OWNER API: Lists all rooms in a property with pagination.
     */
    @GetMapping("/api/v1/properties/{propertyId}/rooms/owner")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Page<RoomResponse>>> getOwnerRooms(
            @PathVariable UUID propertyId,
            @PageableDefault(size = 10, sort = "roomNumber") Pageable pageable) {

        Page<RoomResponse> rooms = roomService.getRoomsByProperty(propertyId, pageable);
        
        return ResponseEntity.ok(ApiResponse.success("Rooms fetched successfully", rooms));
    }

    /**
     * PUBLIC/TENANT API: Lists all AVAILABLE rooms for a property.
     * Accessible by any authenticated user (tenants).
     */
    @GetMapping("/api/v1/properties/{propertyId}/rooms/available")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getAvailableRooms(
            @PathVariable UUID propertyId) {

        List<RoomResponse> availableRooms = roomService.getAvailableRooms(propertyId);
        
        return ResponseEntity.ok(ApiResponse.success("Available rooms fetched successfully", availableRooms));
    }

    /**
     * PUBLIC/TENANT/OWNER API: Gets full room detail including beds list.
     */
    @GetMapping("/api/v1/rooms/{roomId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<RoomResponse>> getRoomById(
            @PathVariable UUID roomId) {

        RoomResponse response = roomService.getRoomWithBeds(roomId);
        return ResponseEntity.ok(ApiResponse.success("Room details fetched successfully", response));
    }
}
