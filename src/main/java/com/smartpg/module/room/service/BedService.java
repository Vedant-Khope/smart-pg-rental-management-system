package com.smartpg.module.room.service;

import com.smartpg.common.exception.BadRequestException;
import com.smartpg.common.exception.UnauthorizedException;
import com.smartpg.module.room.dto.request.CreateBedRequest;
import com.smartpg.module.room.dto.response.BedResponse;
import com.smartpg.module.room.enums.RoomStatus;
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
 * Service for managing Beds and Reservations.
 *
 * <p>Handles bed creation, fetching, atomic reservations, and background cleanup jobs.
 */
@Service
public class BedService {

    private static final Logger log = LoggerFactory.getLogger(BedService.class);

    private final BedRepository bedRepository;
    private final RoomRepository roomRepository;

    public BedService(BedRepository bedRepository, RoomRepository roomRepository) {
        this.bedRepository = bedRepository;
        this.roomRepository = roomRepository;
    }

    /**
     * Adds a bed to a room.
     */
    @Transactional
    public BedResponse addBedToRoom(UUID roomId, CreateBedRequest request, UUID ownerId) {
        log.info("Owner {} adding bed {} to room {}", ownerId, request.bedLabel(), roomId);

        Room room = roomRepository.findByIdAndOwnerId(roomId, ownerId)
                .orElseThrow(() -> new UnauthorizedException("You do not own this room or room does not exist"));

        long currentBedCount = bedRepository.countByRoomId(roomId);
        if (currentBedCount >= room.getCapacity()) {
            throw new BadRequestException("Room capacity (" + room.getCapacity() + ") reached. Cannot add more beds.");
        }

        if (bedRepository.existsByRoomIdAndBedLabel(roomId, request.bedLabel())) {
            throw new BadRequestException("Bed label '" + request.bedLabel() + "' already exists in this room.");
        }

        Bed bed = new Bed();
        bed.setRoom(room);
        bed.setBedLabel(request.bedLabel());
        bed.setNotes(request.notes());

        Bed savedBed = bedRepository.save(bed);
        
        // If the room was fully occupied but now has a new available bed, reset its status
        if (room.getStatus() == RoomStatus.FULLY_OCCUPIED) {
            roomRepository.updateRoomStatus(roomId, RoomStatus.AVAILABLE);
        }

        return BedResponse.from(savedBed);
    }

    /**
     * Fetches all beds in a room.
     */
    @Transactional(readOnly = true)
    public List<BedResponse> getBedsForRoom(UUID roomId) {
        return bedRepository.findByRoomIdOrderByBedLabelAsc(roomId).stream()
                .map(BedResponse::from)
                .toList();
    }

    /**
     * Atomically reserves a bed for a tenant.
     * Prevents race conditions during booking.
     */
    @Transactional
    public void reserveBed(UUID bedId, UUID tenantId) {
        log.info("Tenant {} attempting to reserve bed {}", tenantId, bedId);
        
        // 15 minute reservation window
        Instant reservedUntil = Instant.now().plus(15, ChronoUnit.MINUTES);
        
        int rowsUpdated = bedRepository.atomicReserveBed(bedId, tenantId, reservedUntil);
        
        if (rowsUpdated == 0) {
            // Bed was not AVAILABLE (either already reserved, occupied, or maintenance)
            throw new BadRequestException("Sorry, this bed is no longer available.");
        }
        log.info("Bed {} successfully reserved by tenant {}", bedId, tenantId);
    }

    /**
     * Scheduled job to automatically release expired reservations.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedRate = 300000)
    @Transactional
    public void releaseExpiredReservations() {
        Instant now = Instant.now();
        List<Bed> expiredBeds = bedRepository.findExpiredReservations(now);
        
        if (!expiredBeds.isEmpty()) {
            int releasedCount = bedRepository.releaseExpiredReservations(now);
            log.info("Cleanup Job: Released {} expired bed reservations", releasedCount);
            
            // Optionally, we could loop over expiredBeds and publish events to notify tenants
        }
    }
}
