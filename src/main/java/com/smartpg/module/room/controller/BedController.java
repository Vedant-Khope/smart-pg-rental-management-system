package com.smartpg.module.room.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.room.dto.request.CreateBedRequest;
import com.smartpg.module.room.dto.response.BedResponse;
import com.smartpg.module.room.service.BedService;
import com.smartpg.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Bed endpoints.
 *
 * <p>Handles operations like adding beds, atomic bed reservations, releases, and maintenance.
 */
@RestController
public class BedController {

    private final BedService bedService;

    public BedController(BedService bedService) {
        this.bedService = bedService;
    }

    /**
     * OWNER API: Adds a new bed to a room.
     */
    @PostMapping("/api/v1/rooms/{roomId}/beds")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<BedResponse>> addBed(
            @PathVariable UUID roomId,
            @Valid @RequestBody CreateBedRequest request,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        BedResponse response = bedService.addBedToRoom(roomId, request, currentUser.getUserId());
        
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Bed added successfully", response));
    }

    /**
     * PUBLIC/TENANT API: Lists all beds in a room.
     */
    @GetMapping("/api/v1/rooms/{roomId}/beds")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<BedResponse>>> getBeds(
            @PathVariable UUID roomId) {

        List<BedResponse> beds = bedService.getBedsForRoom(roomId);
        return ResponseEntity.ok(ApiResponse.success("Beds fetched successfully", beds));
    }

    /**
     * TENANT API: Reserves a bed temporarily during the booking flow (15-minute window).
     */
    @PostMapping("/api/v1/rooms/{roomId}/beds/{bedId}/reserve")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<ApiResponse<Void>> reserveBed(
            @PathVariable UUID roomId,
            @PathVariable UUID bedId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        bedService.reserveBed(bedId, currentUser.getUserId());
        
        return ResponseEntity.ok(ApiResponse.success("Bed reserved successfully for 15 minutes", null));
    }

    /**
     * OWNER API: Releases a bed back to AVAILABLE when a tenant vacates.
     */
    @PatchMapping("/api/v1/beds/{bedId}/release")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> releaseBed(
            @PathVariable UUID bedId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        bedService.releaseBed(bedId, currentUser.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Bed released back to AVAILABLE successfully"));
    }

    /**
     * OWNER API: Marks a bed as UNDER_MAINTENANCE.
     */
    @PatchMapping("/api/v1/beds/{bedId}/maintenance")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> markUnderMaintenance(
            @PathVariable UUID bedId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        bedService.markUnderMaintenance(bedId, currentUser.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Bed marked as UNDER_MAINTENANCE"));
    }

    /**
     * OWNER API: Restores a bed from UNDER_MAINTENANCE back to AVAILABLE.
     */
    @PatchMapping("/api/v1/beds/{bedId}/restore")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> restoreFromMaintenance(
            @PathVariable UUID bedId,
            @AuthenticationPrincipal UserPrincipal currentUser) {

        bedService.restoreFromMaintenance(bedId, currentUser.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Bed restored to AVAILABLE"));
    }
}
