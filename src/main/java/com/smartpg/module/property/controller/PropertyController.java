package com.smartpg.module.property.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.property.dto.request.CreatePropertyRequest;
import com.smartpg.module.property.dto.request.UpdatePropertyRequest;
import com.smartpg.module.property.dto.response.PropertyDetailResponse;
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
 * REST Controller for Owner-facing Property endpoints.
 *
 * <p><b>Base URL:</b> {@code /api/v1/properties}
 *
 * <p><b>Design decisions:</b>
 * <ul>
 *   <li><b>@PreAuthorize at method level</b> for fine-grained control — the GET
 *       endpoint is intentionally accessible to all authenticated roles (tenant
 *       needs to view detail), while write operations are OWNER-only.</li>
 *   <li><b>@AuthenticationPrincipal</b> to extract the owner's UUID directly from
 *       the Spring Security context (set by JwtAuthenticationFilter from the token).
 *       Never trust a user-supplied ID in the request body for ownership.</li>
 *   <li><b>ApiResponse wrapper</b> gives the React frontend a consistent envelope:
 *       {@code { "status": "success", "message": "...", "data": {...} }}</li>
 * </ul>
 *
 * <p><b>Endpoints summary:</b>
 * <pre>
 *   POST   /api/v1/properties                         → createProperty  [OWNER]
 *   GET    /api/v1/properties/{id}                    → getPropertyById [ALL AUTH]
 *   GET    /api/v1/properties/my-properties           → getMyProperties  [OWNER]
 *   PUT    /api/v1/properties/{id}                    → updateProperty  [OWNER]
 *   PATCH  /api/v1/properties/{id}/deactivate         → deactivate      [OWNER]
 *   PATCH  /api/v1/properties/{id}/cover-photo/{pid}  → setCoverPhoto   [OWNER]
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    private final PropertyService propertyService;

    public PropertyController(PropertyService propertyService) {
        this.propertyService = propertyService;
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    /**
     * Owner submits a new property listing.
     *
     * <p>Returns 201 Created (not 200 OK) — semantically correct: a new resource
     * was created, and its ID is in the response body for the frontend to redirect to.
     */
    @PostMapping
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<PropertyResponse>> createProperty(
            @Valid @RequestBody CreatePropertyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        PropertyResponse response = propertyService.createProperty(request, principal.getUserId());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Property listing created and is pending admin approval", response));
    }

    // =========================================================================
    // READ
    // =========================================================================

    /**
     * Fetches full property detail for the listing page (US-207).
     * Accessible to all authenticated users — tenants need to read listings.
     *
     * <p>Returns the rich {@link PropertyDetailResponse} with all amenities, photos,
     * and address data. Tenant search cards use the leaner list endpoint.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PropertyDetailResponse>> getPropertyById(@PathVariable UUID id) {
        PropertyDetailResponse response = propertyService.getPropertyById(id);
        return ResponseEntity.ok(ApiResponse.success("Property fetched successfully", response));
    }

    /**
     * Owner's own property list — paginated dashboard view.
     *
     * <p>Supports standard Spring Pageable query params:
     * {@code ?page=0&size=10&sort=createdAt,desc}
     */
    @GetMapping("/my-properties")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Page<PropertyResponse>>> getMyProperties(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable) {

        Page<PropertyResponse> properties = propertyService.getOwnerProperties(principal.getUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.success("Properties fetched successfully", properties));
    }

    // =========================================================================
    // UPDATE
    // =========================================================================

    /**
     * Owner updates a property's details (name, description, amenities, address, policies).
     *
     * <p>Uses HTTP PUT (full replacement semantics): the client sends the complete
     * updated state of editable fields. Immutable fields (type, owner) are ignored.
     *
     * <p>Ownership is verified inside {@link PropertyService#updateProperty} to guard
     * against IDOR attacks — a malicious OWNER cannot update another OWNER's property.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<PropertyResponse>> updateProperty(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePropertyRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {

        PropertyResponse response = propertyService.updateProperty(id, request, principal.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Property updated successfully", response));
    }

    // =========================================================================
    // DEACTIVATE
    // =========================================================================

    /**
     * Owner hides a property from tenant search (INACTIVE status).
     * Existing tenants are unaffected.
     *
     * <p>Uses PATCH (partial state change) not DELETE, because the resource still
     * exists — it's just hidden. A DELETE endpoint would imply hard deletion,
     * which we never do (soft delete only).
     */
    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('OWNER')")
    public ResponseEntity<ApiResponse<Void>> deactivateProperty(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {

        propertyService.deactivateProperty(id, principal.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Property deactivated successfully"));
    }

    // =========================================================================
    // PHOTO MANAGEMENT
    // =========================================================================

    /**
     * Sets which photo is shown as the cover on search result cards.
     * Only the property owner can do this.
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
