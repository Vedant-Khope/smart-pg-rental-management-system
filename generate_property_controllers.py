import os

base_dir = r'C:\Users\vedan\.gemini\antigravity\scratch\smart-pg-backend\src\main\java\com\smartpg\module\property'
os.makedirs(os.path.join(base_dir, 'controller'), exist_ok=True)
os.makedirs(os.path.join(base_dir, 'dto', 'request'), exist_ok=True)

files = {}

files[r'dto\request\ReviewPropertyRequest.java'] = '''package com.smartpg.module.property.dto.request;

import jakarta.validation.constraints.NotNull;

public record ReviewPropertyRequest(
    @NotNull(message = "Approval decision is required") Boolean isApproved,
    String reason
) {}
'''

files[r'controller\PropertyController.java'] = '''package com.smartpg.module.property.controller;

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
        
        PropertyResponse response = propertyService.createProperty(request, principal.getId());
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
            
        Page<PropertyResponse> properties = propertyService.getOwnerProperties(principal.getId(), pageable);
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
            
        propertyService.setCoverPhoto(propertyId, photoId, principal.getId());
        return ResponseEntity.ok(ApiResponse.success("Cover photo updated successfully"));
    }
}
'''

files[r'controller\AdminPropertyController.java'] = '''package com.smartpg.module.property.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.property.dto.request.ReviewPropertyRequest;
import com.smartpg.module.property.service.PropertyService;
import com.smartpg.security.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST Controller for Admin-specific Property operations.
 *
 * <p>Separating Admin APIs from standard user APIs ensures clean security boundaries
 * and prevents accidental exposure of privileged endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/properties")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AdminPropertyController {

    private final PropertyService propertyService;

    public AdminPropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    /**
     * Approves or rejects a property listing.
     *
     * <p>If rejected, a reason must be provided in the request payload.
     *
     * @param id the UUID of the property to review
     * @param request the approval decision and optional rejection reason
     * @param principal the admin performing the review
     * @return 200 OK on success
     */
    @PatchMapping("/{id}/review")
    public ResponseEntity<ApiResponse<Void>> reviewProperty(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewPropertyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        
        // Manual validation: If rejecting, a reason MUST be provided.
        // We do this in code rather than annotations because it's a conditional rule.
        if (!request.isApproved() && (request.reason() == null || request.reason().isBlank())) {
            throw new IllegalArgumentException("Rejection reason is required when rejecting a property");
        }

        propertyService.reviewProperty(id, request.isApproved(), request.reason(), principal.getId());
        
        String message = request.isApproved() ? "Property approved successfully" : "Property rejected successfully";
        return ResponseEntity.ok(ApiResponse.success(message));
    }
}
'''

for path, content in files.items():
    full_path = os.path.join(base_dir, path.replace('\\', '/'))
    with open(full_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f'Written: {path}')
print('Done!')
