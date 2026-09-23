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
@RequestMapping("/api/v1/properties/{propertyId}/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /**
     * OWNER API: Creates a new room within a property.
     * Enforces that only users with the OWNER role can access this.
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<RoomResponse>> createRoom(
            @PathVariable UUID propertyId,
            @Valid @RequestBody CreateRoomRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        RoomResponse response = roomService.createRoom(propertyId, request, currentUser.getId());
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Room created successfully", response));
    }

    /**
     * OWNER API: Lists all rooms in a property with pagination.
     */
    @GetMapping("/owner")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Page<RoomResponse>>> getOwnerRooms(
            @PathVariable UUID propertyId,
            @PageableDefault(size = 10, sort = "roomNumber") Pageable pageable) {

        // Ideally, we'd also verify the owner owns this property inside the service layer here.
        Page<RoomResponse> rooms = roomService.getRoomsByProperty(propertyId, pageable);
        
        return ResponseEntity.ok(ApiResponse.success("Rooms fetched successfully", rooms));
    }

    /**
     * PUBLIC/TENANT API: Lists all AVAILABLE rooms for a property.
     * Accessible by any authenticated user (tenants).
     */
    @GetMapping("/available")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<RoomResponse>>> getAvailableRooms(
            @PathVariable UUID propertyId) {

        List<RoomResponse> availableRooms = roomService.getAvailableRooms(propertyId);
        
        return ResponseEntity.ok(ApiResponse.success("Available rooms fetched successfully", availableRooms));
    }
}
