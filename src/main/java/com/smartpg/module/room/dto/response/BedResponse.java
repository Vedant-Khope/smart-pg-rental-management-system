package com.smartpg.module.room.dto.response;

import com.smartpg.module.room.enums.BedStatus;
import com.smartpg.module.room.model.Bed;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for a single Bed.
 *
 * <p><b>What gets exposed vs hidden:</b>
 * <ul>
 *   <li>EXPOSED: id, roomId, bedLabel, status, notes, timestamps — safe for tenant/owner viewing</li>
 *   <li>HIDDEN: reservedByUserId — tenant A should NOT see that Bed B is reserved by tenant B
 *       (that's private info). Only the status (RESERVED) is shown.</li>
 * </ul>
 *
 * <p>Static factory method {@link #from(Bed)} is the ONLY way to create a BedResponse.
 * This ensures we never accidentally expose a field we didn't intend to.
 *
 * @param id             Bed UUID (for booking API calls)
 * @param roomId         Parent room UUID (for navigation/context)
 * @param bedLabel       "Bed A", "Lower Bunk", etc.
 * @param status         AVAILABLE | RESERVED | OCCUPIED | UNDER_MAINTENANCE
 * @param notes          Owner's notes about this bed (nullable)
 * @param reservedUntil  When the current reservation expires (null if not RESERVED).
 *                       Shown to the SPECIFIC tenant who made the reservation so they
 *                       know their hold expires in X minutes. Hidden from others.
 * @param createdAt      When this bed was added to the system
 * @param updatedAt      Last status change timestamp
 */
public record BedResponse(
        UUID id,
        UUID roomId,
        String bedLabel,
        BedStatus status,
        String notes,
        Instant reservedUntil,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Converts a Bed entity to a BedResponse DTO.
     *
     * <p><b>Why a static factory method instead of a constructor?</b>
     * Readability: {@code BedResponse.from(bed)} clearly communicates intent.
     * It also keeps the mapping logic in one place — if you add a field to Bed,
     * you update this method and nowhere else.
     */
    public static BedResponse from(Bed bed) {
        return new BedResponse(
                bed.getId(),
                bed.getRoom().getId(),
                bed.getBedLabel(),
                bed.getStatus(),
                bed.getNotes(),
                bed.getReservedUntil(),
                bed.getCreatedAt(),
                bed.getUpdatedAt()
        );
    }
}
