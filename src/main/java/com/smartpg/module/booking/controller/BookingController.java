package com.smartpg.module.booking.controller;

import com.smartpg.common.response.ApiResponse;
import com.smartpg.module.booking.dto.request.CreateBookingRequest;
import com.smartpg.module.booking.dto.response.BookingResponse;
import com.smartpg.module.booking.service.BookingService;
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
 * REST Controller for Booking endpoints.
 * Handles tenant applications and owner approvals/rejections.
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Tenant applies for a bed.
     */
    @PostMapping
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<ApiResponse<BookingResponse>> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        
        BookingResponse response = bookingService.createBooking(request, userPrincipal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Booking request submitted successfully", response));
    }

    /**
     * Tenant fetches their own bookings list.
     */
    @GetMapping("/my-bookings")
    @PreAuthorize("hasRole('TENANT')")
    public ResponseEntity<ApiResponse<Page<BookingResponse>>> getMyBookings(
            @AuthenticationPrincipal UserPrincipal userPrincipal,
            @PageableDefault(size = 10) Pageable pageable) {

        Page<BookingResponse> response = bookingService.getTenantBookings(userPrincipal.getUserId(), pageable);
        return ResponseEntity.ok(ApiResponse.success("Bookings fetched successfully", response));
    }

    /**
     * Owner approves a pending booking.
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BookingResponse>> approveBooking(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        
        BookingResponse response = bookingService.approveBooking(id);
        return ResponseEntity.ok(ApiResponse.success("Booking approved successfully", response));
    }

    /**
     * Owner rejects a pending booking.
     */
    @PutMapping("/{id}/reject")
    @PreAuthorize("hasRole('OWNER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<BookingResponse>> rejectBooking(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal userPrincipal) {
        
        BookingResponse response = bookingService.rejectBooking(id);
        return ResponseEntity.ok(ApiResponse.success("Booking rejected successfully", response));
    }
}
