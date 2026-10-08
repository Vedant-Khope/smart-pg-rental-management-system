package com.smartpg.module.booking.service;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.module.booking.dto.request.CreateBookingRequest;
import com.smartpg.module.booking.dto.response.BookingResponse;
import com.smartpg.module.booking.enums.BookingStatus;
import com.smartpg.module.booking.exception.BedUnavailableException;
import com.smartpg.module.booking.exception.BookingNotFoundException;
import com.smartpg.module.booking.model.Booking;
import com.smartpg.module.booking.repository.BookingRepository;
import com.smartpg.module.room.enums.BedStatus;
import com.smartpg.module.room.exception.BedNotFoundException;
import com.smartpg.module.room.model.Bed;
import com.smartpg.module.room.repository.BedRepository;
import com.smartpg.module.user.exception.UserNotFoundException;
import com.smartpg.module.user.model.User;
import com.smartpg.module.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BedRepository bedRepository;
    private final UserRepository userRepository;

    public BookingService(BookingRepository bookingRepository, BedRepository bedRepository, UserRepository userRepository) {
        this.bookingRepository = bookingRepository;
        this.bedRepository = bedRepository;
        this.userRepository = userRepository;
    }

    /**
     * Tenant requests a booking.
     * Transactional: Ensures that if anything fails, we don't save a partial booking.
     */
    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request, UUID tenantId) {
        // 1. Fetch Tenant
        User tenant = userRepository.findById(tenantId)
                .orElseThrow(() -> new UserNotFoundException(tenantId));

        // 2. Prevent spam: Ensure tenant doesn't already have a pending or approved booking
        boolean hasActiveBooking = bookingRepository.existsByTenantIdAndStatusIn(
                tenantId, List.of(BookingStatus.PENDING, BookingStatus.APPROVED)
        );
        if (hasActiveBooking) {
            throw new BadRequestException("You already have an active or pending booking.");
        }

        // 3. Fetch Bed
        Bed bed = bedRepository.findById(request.bedId())
                .orElseThrow(() -> new BedNotFoundException(request.bedId()));

        // 4. Check if Bed is currently AVAILABLE
        if (bed.getStatus() != BedStatus.AVAILABLE) {
            throw new BedUnavailableException("This bed is no longer available.");
        }

        // 5. Create Booking
        Booking booking = new Booking();
        booking.setTenant(tenant);
        booking.setBed(bed);
        booking.setExpectedMoveInDate(request.expectedMoveInDate());
        booking.setStatus(BookingStatus.PENDING);

        Booking savedBooking = bookingRepository.save(booking);
        return BookingResponse.fromEntity(savedBooking);
    }

    /**
     * Owner approves a booking.
     */
    @Transactional
    public BookingResponse approveBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BadRequestException("Only PENDING bookings can be approved.");
        }

        Bed bed = booking.getBed();
        if (bed.getStatus() != BedStatus.AVAILABLE) {
            throw new BedUnavailableException("This bed was already booked by someone else.");
        }

        // Update booking status
        booking.setStatus(BookingStatus.APPROVED);
        Booking savedBooking = bookingRepository.save(booking);

        // Cancel all other pending requests for this bed
        bookingRepository.cancelPendingBookingsForBed(bed.getId());

        return BookingResponse.fromEntity(savedBooking);
    }

    /**
     * Owner rejects a booking.
     */
    @Transactional
    public BookingResponse rejectBooking(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new BadRequestException("Only PENDING bookings can be rejected.");
        }

        booking.setStatus(BookingStatus.REJECTED);
        return BookingResponse.fromEntity(bookingRepository.save(booking));
    }

    /**
     * Get all bookings for a specific tenant (Tenant Dashboard / My Bookings).
     */
    @Transactional(readOnly = true)
    public Page<BookingResponse> getTenantBookings(UUID tenantId, Pageable pageable) {
        return bookingRepository.findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(BookingResponse::fromEntity);
    }
}
