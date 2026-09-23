package com.smartpg.module.property.controller;

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

        propertyService.reviewProperty(id, request.isApproved(), request.reason(), principal.getUserId());
        
        String message = request.isApproved() ? "Property approved successfully" : "Property rejected successfully";
        return ResponseEntity.ok(ApiResponse.success(message));
    }
}
