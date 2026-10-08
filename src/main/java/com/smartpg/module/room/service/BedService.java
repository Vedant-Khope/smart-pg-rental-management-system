package com.smartpg.module.room.service;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.room.dto.request.CreateBedRequest;
import com.smartpg.module.room.dto.response.BedResponse;
import com.smartpg.module.room.enums.BedStatus;
import com.smartpg.module.room.enums.RoomStatus;
import com.smartpg.module.room.exception.BedNotFoundException;
import com.smartpg.module.room.exception.RoomNotFoundException;
import com.smartpg.module.room.model.Bed;
import com.smartpg.module.room.model.Room;
import com.smartpg.module.room.repository.BedRepository;
import com.smartpg.module.room.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing Beds and reservation workflows.
 *
 * <p><b>Critical design: Atomic Reservation Pattern.</b>
 * The most important method here is {@link #reserveBed}. It uses a conditional DB UPDATE
 * (WHERE status = 'AVAILABLE') to prevent race conditions when two tenants
 * try to book the same bed at the same millisecond. This is a database-level lock —
 * it works correctly even across multiple server instances (horizontal scaling).
 *
 * <p><b>Room status auto-sync:</b>
 * Whenever a bed changes state (reserved, occupied, released), we check if the parent
 * room's status needs to be updated (AVAILABLE ↔ FULLY_OCCUPIED). This keeps the
 * room's status accurate for tenant search results without a scheduled job.
 */
@Service
public class BedService {

    private static final Logger log = LoggerFactory.getLogger(BedService.class);

    /** How long (in minutes) a bed remains RESERVED before auto-release. */
    private static final long RESERVATION_WINDOW_MINUTES = 15L;

    private final BedRepository bedRepository;
    private final RoomRepository roomRepository;

    public BedService(BedRepository bedRepository, RoomRepository roomRepository) {
        this.bedRepository = bedRepository;
        this.roomRepository = roomRepository;
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    /**
     * Adds a new bed to a room.
     *
     * <p><b>Business rules enforced:</b>
     * <ol>
     *   <li>Requesting user must own the room's property (checked via ownership chain query).</li>
     *   <li>Room must not be at capacity (current bed count must be less than room.capacity).</li>
     *   <li>Bed label must be unique within the room (e.g., can't have two "Bed A").</li>
     * </ol>
     *
     * <p><b>Room status side-effect:</b>
     * If the room was FULLY_OCCUPIED (edge case: all existing beds were occupied, owner adds a new one),
     * adding a new available bed should flip room back to AVAILABLE. We handle that here.
     *
     * @param roomId   UUID of the room to add a bed to
     * @param request  validated DTO (label + optional notes)
     * @param ownerId  UUID from JWT — verified via ownership chain
     * @return newly created {@link BedResponse}
     */
    @Transactional
    public BedResponse addBedToRoom(UUID roomId, CreateBedRequest request, UUID ownerId) {
        log.info("Owner {} adding bed '{}' to room {}", ownerId, request.bedLabel(), roomId);

        Room room = roomRepository.findByIdAndOwnerId(roomId, ownerId)
                .orElseThrow(() -> new UnauthorizedException(
                        "You do not own this room or the room does not exist"));

        long currentBedCount = bedRepository.countByRoomId(roomId);
        if (currentBedCount >= room.getCapacity()) {
            throw new BadRequestException(
                    "Room capacity (" + room.getCapacity() + ") reached. Cannot add more beds.");
        }

        if (bedRepository.existsByRoomIdAndBedLabel(roomId, request.bedLabel())) {
            throw new BadRequestException(
                    "Bed label '" + request.bedLabel() + "' already exists in this room.");
        }

        Bed bed = new Bed();
        bed.setRoom(room);
        bed.setBedLabel(request.bedLabel());
        bed.setNotes(request.notes());

        Bed saved = bedRepository.save(bed);
        log.info("Bed '{}' added with ID: {}", saved.getBedLabel(), saved.getId());

        // If this room was FULLY_OCCUPIED, adding a new AVAILABLE bed reopens it
        if (room.getStatus() == RoomStatus.FULLY_OCCUPIED) {
            roomRepository.updateRoomStatus(roomId, RoomStatus.AVAILABLE);
            log.info("Room {} status auto-updated to AVAILABLE (new bed added)", roomId);
        }

        return BedResponse.from(saved);
    }

    // =========================================================================
    // READ
    // =========================================================================

    /**
     * Fetches all beds in a room, ordered A → Z by bed label.
     * Used on the room detail page by both owners and tenants.
     *
     * @param roomId UUID of the room
     * @return ordered list of {@link BedResponse} (may be empty, never null)
     */
    @Transactional(readOnly = true)
    public List<BedResponse> getBedsForRoom(UUID roomId) {
        return bedRepository.findByRoomIdOrderByBedLabelAsc(roomId)
                .stream()
                .map(BedResponse::from)
                .toList();
    }

    // =========================================================================
    // RESERVATION FLOW (Tenant Booking Steps)
    // =========================================================================

    /**
     * Step 1 of the booking flow: Atomically reserves a bed for a tenant.
     *
     * <p><b>How race condition prevention works:</b>
     * <pre>
     *   UPDATE beds SET status='RESERVED', reserved_by=:userId, reserved_until=:expiry
     *   WHERE id = :bedId AND status = 'AVAILABLE'   ← conditional!
     * </pre>
     * If Thread A executes this 5ms before Thread B, Thread A gets rows=1 (success).
     * Thread B's WHERE clause no longer matches (status is now RESERVED), so rows=0 → exception.
     * This is all handled at the PostgreSQL level — no application-level locking needed.
     *
     * <p><b>Room status check after reservation:</b>
     * If this was the last available bed in the room, we auto-flip the room to FULLY_OCCUPIED
     * so it disappears from tenant search results immediately.
     *
     * @param bedId    UUID of the bed the tenant wants to reserve
     * @param tenantId UUID from JWT — the reserving tenant
     * @throws BadRequestException if the bed is not AVAILABLE (already taken by another tenant)
     */
    @Transactional
    public void reserveBed(UUID bedId, UUID tenantId) {
        log.info("Tenant {} attempting to reserve bed {}", tenantId, bedId);

        Instant reservedUntil = Instant.now().plus(RESERVATION_WINDOW_MINUTES, ChronoUnit.MINUTES);
        int rowsUpdated = bedRepository.atomicReserveBed(bedId, tenantId, reservedUntil);

        if (rowsUpdated == 0) {
            throw new BadRequestException("Sorry, this bed is no longer available.");
        }

        log.info("Bed {} successfully reserved by tenant {} until {}", bedId, tenantId, reservedUntil);

        // Check if this was the last available bed — if so, mark room FULLY_OCCUPIED
        syncRoomStatusAfterBedChange(bedId);
    }

    /**
     * Step 2 of the booking flow: Confirm reservation → OCCUPIED.
     * Called after payment is confirmed. Clears reservation metadata.
     *
     * <p>The double-check (status=RESERVED AND reservedBy=tenantId) is a security guard:
     * prevents one tenant from accidentally confirming another tenant's reservation.
     *
     * @param bedId    UUID of the bed being confirmed
     * @param tenantId UUID of the tenant who holds the reservation
     * @throws BadRequestException if reservation doesn't match (expired or wrong tenant)
     */
    @Transactional
    public void confirmReservation(UUID bedId, UUID tenantId) {
        log.info("Confirming reservation: bed {} for tenant {}", bedId, tenantId);

        int rowsUpdated = bedRepository.confirmReservation(bedId, tenantId);

        if (rowsUpdated == 0) {
            throw new BadRequestException(
                    "Reservation could not be confirmed. It may have expired or belongs to another tenant.");
        }

        log.info("Bed {} is now OCCUPIED by tenant {}", bedId, tenantId);
    }

    /**
     * Releases a bed back to AVAILABLE when a tenant vacates.
     * Called by BookingService when a tenancy ends (natural end or early termination).
     *
     * <p>Also auto-updates the parent room status: if the room was FULLY_OCCUPIED,
     * releasing one bed flips it back to AVAILABLE (visible in tenant search again).
     *
     * @param bedId   UUID of the bed being vacated
     * @param ownerId UUID from JWT — only the property owner can trigger this
     * @throws BedNotFoundException if no OCCUPIED bed exists with this ID under the given owner
     */
    @Transactional
    public void releaseBed(UUID bedId, UUID ownerId) {
        log.info("Owner {} releasing bed {}", ownerId, bedId);

        // Ownership check via chain: Bed → Room → Property → Owner
        bedRepository.findByIdAndOwnerId(bedId, ownerId)
                .orElseThrow(() -> new UnauthorizedException(
                        "You do not own this bed or it does not exist"));

        int rowsUpdated = bedRepository.releaseBed(bedId);

        if (rowsUpdated == 0) {
            throw new BadRequestException("Bed is not currently OCCUPIED and cannot be released.");
        }

        log.info("Bed {} released back to AVAILABLE", bedId);

        // If room was FULLY_OCCUPIED, this vacant bed makes it AVAILABLE again
        syncRoomStatusAfterBedChange(bedId);
    }

    // =========================================================================
    // MAINTENANCE
    // =========================================================================

    /**
     * Flags a bed as UNDER_MAINTENANCE (e.g., mattress replaced, bunk broken).
     * Only the property owner can do this.
     *
     * <p>A bed UNDER_MAINTENANCE is hidden from tenant booking UI.
     * It stays in this state until the owner explicitly marks it as restored.
     *
     * @param bedId   UUID of the bed to flag
     * @param ownerId UUID from JWT — only the property owner can do maintenance
     * @throws BadRequestException if the bed is OCCUPIED (can't flag an occupied bed for maintenance)
     */
    @Transactional
    public void markUnderMaintenance(UUID bedId, UUID ownerId) {
        log.info("Owner {} marking bed {} as UNDER_MAINTENANCE", ownerId, bedId);

        Bed bed = bedRepository.findByIdAndOwnerId(bedId, ownerId)
                .orElseThrow(() -> new UnauthorizedException(
                        "You do not own this bed or it does not exist"));

        if (bed.getStatus() == BedStatus.OCCUPIED) {
            throw new BadRequestException(
                    "Cannot mark an OCCUPIED bed as under maintenance. The tenant must vacate first.");
        }

        bed.setStatus(BedStatus.UNDER_MAINTENANCE);
        bed.setReservedByUserId(null);
        bed.setReservedUntil(null);
        bedRepository.save(bed);

        log.info("Bed {} is now UNDER_MAINTENANCE", bedId);
        syncRoomStatusAfterBedChange(bedId);
    }

    /**
     * Restores a bed from UNDER_MAINTENANCE back to AVAILABLE.
     *
     * @param bedId   UUID of the bed to restore
     * @param ownerId UUID from JWT
     * @throws BadRequestException if the bed is not currently UNDER_MAINTENANCE
     */
    @Transactional
    public void restoreFromMaintenance(UUID bedId, UUID ownerId) {
        log.info("Owner {} restoring bed {} from maintenance", ownerId, bedId);

        Bed bed = bedRepository.findByIdAndOwnerId(bedId, ownerId)
                .orElseThrow(() -> new UnauthorizedException(
                        "You do not own this bed or it does not exist"));

        if (bed.getStatus() != BedStatus.UNDER_MAINTENANCE) {
            throw new BadRequestException("Bed is not currently under maintenance.");
        }

        bed.setStatus(BedStatus.AVAILABLE);
        bedRepository.save(bed);

        log.info("Bed {} restored to AVAILABLE", bedId);
        syncRoomStatusAfterBedChange(bedId);
    }

    // =========================================================================
    // SCHEDULED CLEANUP (Expired Reservations)
    // =========================================================================

    /**
     * Scheduled job to automatically release expired reservations every 5 minutes.
     *
     * <p><b>Scenario this solves:</b>
     * Tenant Priya clicks "Book Bed A" → bed = RESERVED → Priya closes the browser.
     * Without this job, Bed A would be stuck as RESERVED forever, blocking others.
     * This job runs every 5 minutes, finds stale RESERVED beds, and releases them.
     *
     * <p><b>Why {@code fixedRate} vs {@code @Scheduled(cron = ...)}?</b>
     * {@code fixedRate = 300_000} (every 5 min from app start) is simpler for a cleanup job.
     * A cron expression (e.g., "0 */5 * * * ?") is better for jobs that must run at specific
     * wall-clock times (like monthly billing). For this continuous cleanup, fixedRate is fine.
     *
     * <p><b>Distributed Note:</b>
     * If running multiple app instances, this job will fire on EACH instance.
     * The bulk UPDATE is idempotent (only matches RESERVED beds with expired timestamp),
     * so concurrent runs are safe — they'll just update 0 rows on the second instance.
     * For more control, use ShedLock or Quartz.
     */
    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void releaseExpiredReservations() {
        Instant now = Instant.now();
        List<Bed> expiredBeds = bedRepository.findExpiredReservations(now);

        if (!expiredBeds.isEmpty()) {
            int releasedCount = bedRepository.releaseExpiredReservations(now);
            log.info("Cleanup Job: Released {} expired bed reservation(s)", releasedCount);

            // Future: loop over expiredBeds and publish a Spring ApplicationEvent
            // to notify the tenant "Your reservation for Bed A has expired"
        }
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Checks if the parent room's status needs to be updated after any bed status change.
     *
     * <p><b>FULLY_OCCUPIED logic:</b>
     * If there are zero AVAILABLE beds in the room → set room = FULLY_OCCUPIED.
     *
     * <p><b>AVAILABLE logic:</b>
     * If there is at least one AVAILABLE bed → set room = AVAILABLE.
     *
     * <p>We only flip between AVAILABLE and FULLY_OCCUPIED. We never override
     * UNDER_MAINTENANCE or INACTIVE via this method (those are owner-initiated states).
     *
     * @param bedId UUID of the bed that just changed state
     */
    private void syncRoomStatusAfterBedChange(UUID bedId) {
        bedRepository.findById(bedId).ifPresent(bed -> {
            UUID roomId = bed.getRoom().getId();
            Room room = bed.getRoom();

            // Only auto-sync if the room is in an auto-managed state
            if (room.getStatus() == RoomStatus.UNDER_MAINTENANCE
                    || room.getStatus() == RoomStatus.INACTIVE) {
                return;
            }

            long availableCount = bedRepository.countByRoomIdAndStatus(roomId, BedStatus.AVAILABLE);

            if (availableCount == 0 && room.getStatus() == RoomStatus.AVAILABLE) {
                roomRepository.updateRoomStatus(roomId, RoomStatus.FULLY_OCCUPIED);
                log.info("Room {} auto-updated to FULLY_OCCUPIED", roomId);
            } else if (availableCount > 0 && room.getStatus() == RoomStatus.FULLY_OCCUPIED) {
                roomRepository.updateRoomStatus(roomId, RoomStatus.AVAILABLE);
                log.info("Room {} auto-updated to AVAILABLE", roomId);
            }
        });
    }
}
