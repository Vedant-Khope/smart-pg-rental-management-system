package com.smartpg.module.booking.repository;

import com.smartpg.module.booking.enums.BookingStatus;
import com.smartpg.module.booking.model.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /**
     * Find all bookings made by a specific tenant.
     * Useful for the Tenant's "My Bookings" page.
     */
    Page<Booking> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);

    /**
     * Find all bookings for a specific bed with a given status.
     * Useful to check if a bed already has a PENDING or APPROVED booking.
     */
    List<Booking> findByBedIdAndStatus(UUID bedId, BookingStatus status);

    /**
     * Custom JPQL query to check if a tenant already has an active or pending booking.
     * Prevents a tenant from spamming booking requests.
     */
    @Query("SELECT COUNT(b) > 0 FROM Booking b WHERE b.tenant.id = :tenantId AND b.status IN (:statuses)")
    boolean existsByTenantIdAndStatusIn(@Param("tenantId") UUID tenantId, @Param("statuses") List<BookingStatus> statuses);

    /**
     * Bulk update to cancel all pending bookings for a bed.
     * Called when one booking is approved, ensuring no other tenant is left hanging.
     */
    @Query("UPDATE Booking b SET b.status = 'CANCELLED' WHERE b.bed.id = :bedId AND b.status = 'PENDING'")
    @org.springframework.data.jpa.repository.Modifying
    void cancelPendingBookingsForBed(@Param("bedId") UUID bedId);
}
