package com.smartpg.module.room.service;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.property.exception.PropertyNotFoundException;
import com.smartpg.module.property.model.Property;
import com.smartpg.module.property.repository.PropertyRepository;
import com.smartpg.module.room.dto.request.CreateRoomRequest;
import com.smartpg.module.room.dto.response.RoomResponse;
import com.smartpg.module.room.exception.RoomNotFoundException;
import com.smartpg.module.room.model.Room;
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
 * Service for managing Rooms.
 * 
 * <p>Handles room creation, validation, and fetching.
 */
@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepository roomRepository;
    private final PropertyRepository propertyRepository;

    public RoomService(RoomRepository roomRepository, PropertyRepository propertyRepository) {
        this.roomRepository = roomRepository;
        this.propertyRepository = propertyRepository;
    }

    /**
     * Creates a new room within a property.
     * Enforces that the room number is unique within the property.
     */
    @Transactional
    public RoomResponse createRoom(UUID propertyId, CreateRoomRequest request, UUID ownerId) {
        log.info("Owner {} creating room {} in property {}", ownerId, request.roomNumber(), propertyId);

        Property property = propertyRepository.findById(propertyId)
                .orElseThrow(() -> new PropertyNotFoundException(propertyId));

        if (!property.getOwner().getId().equals(ownerId)) {
            throw new UnauthorizedException("You do not own this property");
        }

        if (roomRepository.existsByPropertyIdAndRoomNumber(propertyId, request.roomNumber())) {
            throw new BadRequestException("Room number '" + request.roomNumber() + "' already exists in this property");
        }

        Room room = new Room();
        room.setProperty(property);
        room.setRoomNumber(request.roomNumber());
        room.setFloorNumber(request.floorNumber());
        room.setRoomType(request.roomType());
        room.setCapacity(request.capacity());
        room.setMonthlyRent(request.monthlyRent());
        room.setDescription(request.description());
        room.setHasAttachedBathroom(request.hasAttachedBathroom() != null ? request.hasAttachedBathroom() : false);
        room.setHasBalcony(request.hasBalcony() != null ? request.hasBalcony() : false);
        room.setHasAc(request.hasAc() != null ? request.hasAc() : false);

        Room savedRoom = roomRepository.save(room);
        log.info("Room created successfully: {}", savedRoom.getId());

        return RoomResponse.from(savedRoom);
    }

    /**
     * Fetches paginated rooms for a property (Owner View).
     */
    @Transactional(readOnly = true)
    public Page<RoomResponse> getRoomsByProperty(UUID propertyId, Pageable pageable) {
        return roomRepository.findByPropertyId(propertyId, pageable)
                .map(RoomResponse::from);
    }

    /**
     * Fetches all available rooms with available beds (Tenant Search View).
     */
    @Transactional(readOnly = true)
    public List<RoomResponse> getAvailableRooms(UUID propertyId) {
        return roomRepository.findAvailableRoomsInProperty(propertyId).stream()
                .map(RoomResponse::from) // summary
                .toList();
    }
}
