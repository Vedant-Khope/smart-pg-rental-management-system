package com.smartpg.module.booking.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.booking.dto.request.MoveInRequest;
import com.smartpg.module.booking.dto.response.TenancyResponse;
import com.smartpg.module.booking.model.Tenancy;
import com.smartpg.module.booking.service.TenancyService;
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
 * REST Controller for Tenancy endpoints.
 * Handles the actual move-in and move-out operations.
 */
@RestController
@RequestMapping("/api/v1/tenancies")
public class TenancyController {

    private final TenancyService tenancyService;

    public TenancyController(TenancyService tenancyService) {
        this.tenancyService = tenancyService;
    }

    /**
     * Marks a tenant as officially moved in, generating the active tenancy record.
     */
    @PostMapping("/move-in")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TenancyResponse>> moveInTenant(
            @Valid @RequestBody MoveInRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        
        Tenancy tenancy = tenancyService.moveInTenant(
                request.bookingId(), 
                request.monthlyRent(), 
                request.securityDeposit()
        );
        
        TenancyResponse response = TenancyResponse.fromEntity(tenancy);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tenant successfully moved in", response));
    }

    /**
     * Records a tenant moving out, freeing the bed.
     */
    @PutMapping("/{id}/move-out")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<TenancyResponse>> moveOutTenant(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        
        Tenancy tenancy = tenancyService.moveOutTenant(id);
        TenancyResponse response = TenancyResponse.fromEntity(tenancy);
        return ResponseEntity.ok(ApiResponse.success("Tenant successfully moved out", response));
    }

    /**
     * Tenant fetches their tenancy history.
     */
    @GetMapping("/my-tenancies")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<ApiResponse<List<TenancyResponse>>> getMyTenancies(
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        List<TenancyResponse> tenancies = tenancyService.getTenantTenancies(userPrincipal.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Tenancies fetched successfully", tenancies));
    }

    /**
     * Owner views active tenancies in a specific property.
     */
    @GetMapping("/property/{propertyId}")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<TenancyResponse>>> getPropertyTenancies(
            @PathVariable UUID propertyId,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {

        List<TenancyResponse> tenancies = tenancyService.getActiveTenanciesByProperty(propertyId, userPrincipal.getUserId());
        return ResponseEntity.ok(ApiResponse.success("Active tenancies fetched successfully", tenancies));
    }
}
