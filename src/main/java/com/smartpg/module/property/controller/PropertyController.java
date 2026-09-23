package com.smartpg.module.property.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.property.dto.request.CreatePropertyRequest;
import com.smartpg.module.property.dto.response.PropertyResponse;
import com.smartpg.module.property.service.PropertyService;
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

import java.util.UUID;

/**
 * REST Controller for Property endpoints.
 *
 * <p><b>Design Notes:</b>
 * <ul>
 *   <li><b>Role Based Access Control</b>: Uses @PreAuthorize to ensure only OWNERs can create listings.</li>
 *   <li><b>Security Principal</b>: Injects UserPrincipal to get the authenticated owner's ID safely
 *       from the JWT token, rather than trusting a client-provided ID in the payload.</li>
 *   <li><b>Standardized Responses</b>: Every endpoint wraps its return data in {@link ApiResponse}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {
    
    private final PropertyService propertyService;
    
    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }
    
    /**
     * Creates a new property listing.
     * Automatically assigned PENDING_APPROVAL status.
     *
     * @param request the property details
     * @param principal the authenticated owner
     * @return 201 Created with the created property details
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<PropertyResponse>> createProperty(
            @Valid @RequestBody CreatePropertyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        PropertyResponse response = propertyService.createProperty(request, principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Property listing created and is pending approval", response));
    }
    
    /**
     * Fetches all properties owned by the authenticated user.
     * Supports pagination via query params (?page=0&size=10).
     */
    @GetMapping("/my-properties")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Page<PropertyResponse>>> getMyProperties(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 10) Pageable pageable) {
            
        Page<PropertyResponse> properties = propertyService.getOwnerProperties(principal.getUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.success("Properties fetched successfully", properties));
    }
    
    /**
     * Sets the cover photo for a specific property.
     * Only the owner of the property can perform this action.
     */
    @PatchMapping("/{propertyId}/cover-photo/{photoId}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> setCoverPhoto(
            @PathVariable UUID propertyId,
            @PathVariable UUID photoId,
            @AuthenticationPrincipal UserPrincipal principal) {
            
        propertyService.setCoverPhoto(propertyId, photoId, principal.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Cover photo updated successfully"));
    }
}
