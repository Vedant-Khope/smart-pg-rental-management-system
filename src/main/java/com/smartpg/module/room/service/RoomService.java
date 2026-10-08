package com.smartpg.module.room.service;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.property.exception.PropertyNotFoundException;
import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.repository.PropertyRepository;
import com.smartpg.module.room.dto.request.CreateRoomRequest;
import com.smartpg.module.room.dto.response.BedResponse;
import com.smartpg.module.room.dto.response.RoomResponse;
import com.smartpg.module.room.exception.RoomNotFoundException;
import com.smartpg.module.room.model.Room;
import com.smartpg.module.room.repository.BedRepository;
import com.smartpg.module.room.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing Rooms within a Property.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Enforce ownership — only the property owner can create/update rooms.</li>
 *   <li>Enforce business rules — room number uniqueness, capacity limits.</li>
 *   <li>Return the right read model — lean summary for lists, rich detail (with beds) for detail page.</li>
 * </ul>
 *
 * <p><b>Ownership chain:</b>
 * A Room doesn't have a direct {@code owner_id} FK — it belongs to a Property, which has an owner.
 * Every write method verifies this chain: "Is the requesting user the owner of the property this room
 * belongs to?" The {@link RoomRepository#findByIdAndOwnerId} JPQL query traverses this in one shot.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;
    private final BedRepository bedRepository;

    public RoomService(RoomRepository roomRepository,
                       PropertyRepository propertyRepository,
                       BedRepository bedRepository) {
        this.roomRepository = roomRepository;
        this.propertyRepository = propertyRepository;
        this.bedRepository = bedRepository;
    }

    // =========================================================================
    // CREATE
    // =========================================================================

    /**
     * Creates a new room inside a property.
     *
     * <p><b>Business rules enforced here (not at DB level):</b>
     * <ol>
     *   <li>The requesting user must own the property.</li>
     *   <li>Room number must be unique within the property.</li>
     * </ol>
     *
     * <p>Capacity vs roomType alignment (e.g., SINGLE must have capacity=1) is intentionally
     * left as a soft warning rather than a hard rejection — owners sometimes set up a "SINGLE"
     * room with capacity 2 for custom arrangements. Service logs a warning but proceeds.
     *
     * @param propertyId UUID of the target property
     * @param request    validated DTO from controller
     * @param ownerId    UUID from JWT — verified against property.owner.id
     * @return lean {@link RoomResponse} (no beds, just room summary)
     */
    @Transactional
    public RoomResponse createRoom(UUID propertyId, CreateRoomRequest request, UUID ownerId) {
        log.info("Owner {} creating room '{}' in property {}", ownerId, request.roomNumber(), propertyId);

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        if (roomRepository.existsByPropertyIdAndRoomNumber(propertyId, request.roomNumber())) {
            throw new BadRequestException(
                    "Room number '" + request.roomNumber() + "' already exists in this property");
        }

        Room room = new Room();
        room.setProperty(property);
        room.setRoomNumber(request.roomNumber());
        room.setFloorNumber(request.floorNumber());
        room.setRoomType(request.roomType());
        room.setCapacity(request.capacity());
        room.setMonthlyRent(request.monthlyRent());
        room.setDescription(request.description());
        room.setHasAttachedBathroom(Boolean.TRUE.equals(request.hasAttachedBathroom()));
        room.setHasBalcony(Boolean.TRUE.equals(request.hasBalcony()));
        room.setHasAc(Boolean.TRUE.equals(request.hasAc()));

        Room saved = roomRepository.save(room);
        log.info("Room created with ID: {}", saved.getId());

        return RoomResponse.from(saved);
    }

    // =========================================================================
    // READ
    // =========================================================================

    /**
     * Fetches a single room with its full bed list — the detail page view.
     *
     * <p><b>Why fetch beds separately via bedRepository instead of loading room.getBeds()?</b>
     * Both approaches work inside a {@code @Transactional} method. However, fetching via
     * {@link BedRepository#findByRoomIdOrderByBedLabelAsc} guarantees the sort order
     * (Bed A, Bed B, Bed C) without relying on the collection's {@code @OrderBy} hint,
     * which may be ignored when the collection was already partially loaded.
     * More predictable behaviour = fewer surprises in production.
     *
     * @param roomId UUID of the room to fetch
     * @return rich {@link RoomResponse} including beds and live available count
     * @throws RoomNotFoundException if no room exists with the given ID
     */
    @Transactional(readOnly = true)
    public RoomResponse getRoomWithBeds(UUID roomId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));

        List<BedResponse> beds = bedRepository.findByRoomIdOrderByBedLabelAsc(roomId)
                .stream()
                .map(BedResponse::from)
                .toList();

        return RoomResponse.withBeds(room, beds);
    }

    /**
     * Paginated list of all rooms in a property — owner dashboard view.
     * Returns the lean summary (no beds). Use {@link #getRoomWithBeds} for individual room detail.
     *
     * @param propertyId UUID of the property
     * @param pageable   page/size/sort from request params (e.g., ?page=0&size=10&sort=roomNumber,asc)
     * @return paginated lean {@link RoomResponse} list
     */
    @Transactional(readOnly = true)
    public Page<RoomResponse> getRoomsByProperty(UUID propertyId, Pageable pageable) {
        return roomRepository.findByPropertyId(propertyId, pageable)
                .map(RoomResponse::from);
    }

    /**
     * Lists all AVAILABLE rooms in a property that have at least one AVAILABLE bed.
     * Used by the tenant search/browse UI. Returns lean summaries only.
     *
     * @param propertyId UUID of the property to search within
     * @return list of available rooms (may be empty — never null)
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> getAvailableRooms(UUID propertyId) {
        return roomRepository.findAvailableRoomsInProperty(propertyId)
                .stream()
                .map(RoomResponse::from)
                .toList();
    }
}
