package com.smartpg.module.room.repository;

import com.smartpg.module.room.enums.BedStatus;
import com.smartpg.module.room.model.Bed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access layer for {@link Bed} entities.
 *
 * <p><b>Critical note on the ATOMIC RESERVATION pattern:</b>
 * The most important method here is {@link #atomicReserveBed}. It uses a conditional
 * UPDATE (WHERE status = 'AVAILABLE') to prevent race conditions when two tenants
 * try to book the same bed simultaneously. This is a database-level lock, not
 * an application-level lock — so it works correctly even across multiple server instances.
 */
@Repository
public interface BedRepository extends JpaRepository<Bed, UUID> {

    // =========================================================================
    // BASIC LOOKUPS
    // =========================================================================

    /**
     * Fetches all beds in a specific room, ordered by bed label alphabetically.
     * Used on: Room detail page → "Show all beds in Room 101".
     * Result: [Bed A, Bed B, Bed C] sorted alphabetically.
     */
    List<Bed> findByRoomIdOrderByBedLabelAsc(UUID roomId);

    /**
     * Fetches all AVAILABLE beds in a specific room.
     * Used by: Booking service → "Which beds can this tenant book in Room 101?".
     * Frontend displays only AVAILABLE beds in the booking UI.
     */
    List<Bed> findByRoomIdAndStatus(UUID roomId, BedStatus status);

    /**
     * Checks if a bed label is already taken within the same room.
     * Used in BedService before adding a new bed:
     * "Does Room 101 already have a 'Bed A'?"
     * If yes → reject with: "Bed label 'Bed A' already exists in this room."
     */
    boolean existsByRoomIdAndBedLabel(UUID roomId, String bedLabel);

    // =========================================================================
    // ATOMIC RESERVATION QUERY (Race Condition Prevention)
    // =========================================================================

    /**
     * <b>CRITICAL METHOD — The heart of our concurrency safety.</b>
     *
     * <p>Atomically reserves a bed for a tenant.
     * This UPDATE only fires if the bed is currently AVAILABLE.
     * If another tenant reserved it a millisecond earlier, the WHERE clause won't match
     * and the UPDATE returns 0 rows — the service layer treats this as "already taken".
     *
     * <p><b>How this prevents race conditions:</b>
     * <pre>
     *   Thread 1 (Rahul): UPDATE beds SET status='RESERVED', reserved_by=rahul, reserved_until=T+15min
     *                      WHERE id = bed-uuid AND status = 'AVAILABLE'
     *                      → Returns 1 ✅ Rahul gets the bed!
     *
     *   Thread 2 (Priya, 5ms later): UPDATE beds SET status='RESERVED', reserved_by=priya...
     *                      WHERE id = bed-uuid AND status = 'AVAILABLE'
     *                      → Returns 0 ❌ Bed is already RESERVED, not AVAILABLE anymore.
     *                      → Service throws: "Sorry, this bed was just taken!"
     * </pre>
     *
     * <p><b>Why @Modifying?</b> This is a DML (UPDATE) statement, not a SELECT.
     * Spring Data JPA requires @Modifying for any @Query that mutates data.
     *
     * <p><b>Why clearAutomatically = true?</b>
     * After this UPDATE, Hibernate's EntityManager cache might hold a stale
     * Bed entity with status=AVAILABLE. clearAutomatically forces a cache flush,
     * so the next findById() returns the correct RESERVED status.
     *
     * @param bedId             The bed to reserve
     * @param reservedByUserId  The tenant attempting the reservation
     * @param reservedUntil     When the reservation expires (typically now + 15 min)
     * @return 1 if successfully reserved, 0 if bed was not AVAILABLE (already taken)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Bed b
        SET b.status = 'RESERVED',
            b.reservedByUserId = :userId,
            b.reservedUntil = :reservedUntil,
            b.updatedAt = CURRENT_TIMESTAMP
        WHERE b.id = :bedId
          AND b.status = 'AVAILABLE'
        """)
    int atomicReserveBed(
        @Param("bedId") UUID bedId,
        @Param("userId") UUID reservedByUserId,
        @Param("reservedUntil") Instant reservedUntil
    );

    // =========================================================================
    // BOOKING CONFIRMATION QUERY
    // =========================================================================

    /**
     * Confirms a reservation by transitioning RESERVED → OCCUPIED.
     * Only fires if the bed is still RESERVED by the SAME user.
     *
     * <p>Why check reservedByUserId? Prevents a malicious actor from confirming
     * someone else's reservation by knowing the bed UUID.
     * The double-check (status=RESERVED AND reservedBy=userId) ensures integrity.
     *
     * @return 1 if confirmed successfully, 0 if reservation doesn't match (expired or wrong user)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Bed b
        SET b.status = 'OCCUPIED',
            b.reservedByUserId = NULL,
            b.reservedUntil = NULL,
            b.updatedAt = CURRENT_TIMESTAMP
        WHERE b.id = :bedId
          AND b.status = 'RESERVED'
          AND b.reservedByUserId = :userId
        """)
    int confirmReservation(@Param("bedId") UUID bedId, @Param("userId") UUID userId);

    // =========================================================================
    // RELEASE QUERIES (Vacating / Cancellation)
    // =========================================================================

    /**
     * Releases a bed back to AVAILABLE when a tenant vacates.
     * Called by BookingService when a tenancy ends (natural end or early termination).
     *
     * <p>Why take the bed ID and a status check? Prevents accidentally releasing
     * a bed that's somehow in a different state (e.g., already UNDER_MAINTENANCE).
     * Only releases if currently OCCUPIED.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Bed b
        SET b.status = 'AVAILABLE',
            b.updatedAt = CURRENT_TIMESTAMP
        WHERE b.id = :bedId
          AND b.status = 'OCCUPIED'
        """)
    int releaseBed(@Param("bedId") UUID bedId);

    // =========================================================================
    // SCHEDULED CLEANUP JOB QUERY (Expired Reservations)
    // =========================================================================

    /**
     * Finds all RESERVED beds whose reservation time has expired.
     * Called by a {@code @Scheduled} job every 5 minutes to clean up abandoned bookings.
     *
     * <p>Flow: Tenant starts booking → bed = RESERVED → tenant abandons payment page
     * → 15 minutes later, this job runs → finds stale RESERVED beds → sets AVAILABLE again.
     * This prevents beds from being "ghost-reserved" forever.
     *
     * @param now  Current timestamp. All beds with reservedUntil BEFORE this are expired.
     */
    @Query("""
        SELECT b FROM Bed b
        WHERE b.status = 'RESERVED'
          AND b.reservedUntil < :now
        """)
    List<Bed> findExpiredReservations(@Param("now") Instant now);

    /**
     * Bulk-releases all expired reservations back to AVAILABLE.
     * More efficient than loading entities and saving one by one.
     * Called immediately after {@link #findExpiredReservations} for notification, then this cleans up.
     *
     * <p>Returns count of beds released — logged for monitoring ("Cleaned 3 expired reservations").
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Bed b
        SET b.status = 'AVAILABLE',
            b.reservedByUserId = NULL,
            b.reservedUntil = NULL,
            b.updatedAt = CURRENT_TIMESTAMP
        WHERE b.status = 'RESERVED'
          AND b.reservedUntil < :now
        """)
    int releaseExpiredReservations(@Param("now") Instant now);

    // =========================================================================
    // ANALYTICS / COUNT QUERIES
    // =========================================================================

    /**
     * Counts beds by status within a specific room.
     * Used for: Room detail card → "2 Available, 1 Occupied, 0 Maintenance".
     */
    long countByRoomIdAndStatus(UUID roomId, BedStatus status);

    /**
     * Counts total beds in a room (all statuses).
     * Used to validate capacity: total beds should not exceed room.capacity.
     */
    long countByRoomId(UUID roomId);

    // =========================================================================
    // OWNER AUTHORIZATION QUERY
    // =========================================================================

    /**
     * Finds a bed and verifies ownership chain: Bed → Room → Property → Owner.
     * Used before any owner-only operations (update bed notes, maintenance flag, etc.).
     * If this returns empty, the owner doesn't own this bed → 403 Forbidden.
     */
    @Query("""
        SELECT b FROM Bed b
        WHERE b.id = :bedId
          AND b.room.property.owner.id = :ownerId
        """)
    Optional<Bed> findByIdAndOwnerId(@Param("bedId") UUID bedId, @Param("ownerId") UUID ownerId);
}
