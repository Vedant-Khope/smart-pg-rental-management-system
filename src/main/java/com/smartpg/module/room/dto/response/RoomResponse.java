package com.smartpg.module.room.dto.response;

import com.smartpg.module.room.enums.RoomStatus;
import com.smartpg.module.room.enums.RoomType;
import com.smartpg.module.room.model.Room;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a Room — what the API returns to clients.
 *
 * <p><b>Two variants via static factory methods:</b>
 * <ul>
 *   <li>{@link #from(Room)} — Room summary without beds (for listing pages).
 *       Used when you just need the room card info — no need to load all beds.</li>
 *   <li>{@link #withBeds(Room, List)} — Room detail WITH beds (for the room detail page).
 *       Used when a tenant clicks "View Room" and needs to see individual bed availability.</li>
 * </ul>
 *
 * <p><b>Why two factory methods instead of one?</b>
 * Performance! For an owner listing all 20 rooms, we don't need to load all beds for all rooms.
 * But for the "View Room" detail page, we need beds. Two methods = right data at right time.
 *
 * @param id                   Room UUID
 * @param propertyId           Parent property UUID
 * @param roomNumber           Human-readable room identifier (e.g., "101")
 * @param floorNumber          Floor location (0 = ground floor)
 * @param roomType             SINGLE | DOUBLE | TRIPLE | QUAD
 * @param capacity             Max allowed beds
 * @param monthlyRent          Monthly rent per bed in INR
 * @param status               AVAILABLE | FULLY_OCCUPIED | UNDER_MAINTENANCE | INACTIVE
 * @param description          Owner's description of the room
 * @param hasAttachedBathroom  Bathroom type flag
 * @param hasBalcony           Balcony availability flag
 * @param hasAc                AC availability flag
 * @param availableBedCount    How many beds are currently AVAILABLE (tenant sees this prominently)
 * @param beds                 List of beds (populated only in detail view, null in summary)
 * @param createdAt            When this room was added
 * @param updatedAt            Last modification timestamp
 */
public record RoomResponse(
        UUID id,
        UUID propertyId,
        String roomNumber,
        Integer floorNumber,
        RoomType roomType,
        Integer capacity,
        BigDecimal monthlyRent,
        RoomStatus status,
        String description,
        boolean hasAttachedBathroom,
        boolean hasBalcony,
        boolean hasAc,
        long availableBedCount,
        List<BedResponse> beds,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Creates a room summary WITHOUT beds.
     * Used for: property listing pages, owner dashboard room list.
     * The {@code beds} field will be null — frontend should NOT show beds in summary view.
     *
     * <p>availableBedCount is set to 0 here because we haven't loaded beds
     * (LAZY fetch). For accurate count, use the detail variant or a COUNT query.
     *
     * <p>In production, prefer passing the count from a repository COUNT query
     * rather than relying on loaded beds — more efficient.
     */
    public static RoomResponse from(Room room) {
        return new RoomResponse(
                room.getId(),
                room.getProperty().getId(),
                room.getRoomNumber(),
                room.getFloorNumber(),
                room.getRoomType(),
                room.getCapacity(),
                room.getMonthlyRent(),
                room.getStatus(),
                room.getDescription(),
                room.isHasAttachedBathroom(),
                room.isHasBalcony(),
                room.isHasAc(),
                0L,  // beds not loaded in summary — use COUNT query for accurate value
                null,  // beds list not included in summary
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }

    /**
     * Creates a room detail WITH full beds list and accurate available bed count.
     * Used for: room detail page when tenant/owner wants to see individual beds.
     * Beds are passed in (already fetched) so we can map them to BedResponse.
     */
    public static RoomResponse withBeds(Room room, List<BedResponse> beds) {
        long availableCount = beds.stream()
                .filter(b -> b.status() == com.smartpg.module.room.enums.BedStatus.AVAILABLE)
                .count();

        return new RoomResponse(
                room.getId(),
                room.getProperty().getId(),
                room.getRoomNumber(),
                room.getFloorNumber(),
                room.getRoomType(),
                room.getCapacity(),
                room.getMonthlyRent(),
                room.getStatus(),
                room.getDescription(),
                room.isHasAttachedBathroom(),
                room.isHasBalcony(),
                room.isHasAc(),
                availableCount,
                beds,
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }
}
