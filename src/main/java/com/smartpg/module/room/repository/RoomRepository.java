package com.smartpg.module.room.repository;

import com.smartpg.module.room.enums.RoomStatus;
import com.smartpg.module.room.model.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Data access layer for {@link Room} entities.
 *
 * <p><b>Why custom queries instead of just findAll()?</b>
 * Spring Data JPA auto-generates findAll(), but:
 * <ul>
 *   <li>findAll() fetches ALL rows — terrible for paginated owner dashboards</li>
 *   <li>We need filtered queries (by property, status, type) — method names would be
 *       unreadably long, so @Query is cleaner</li>
 *   <li>Bulk status updates use @Modifying + @Query for performance —
 *       no need to load entities just to update a status column</li>
 * </ul>
 *
 * <p><b>Extends JpaRepository&lt;Room, UUID&gt;</b>:
 * UUID is the primary key type. JpaRepository gives us:
 * save(), findById(), findAll(), deleteById(), existsById(), count(), etc. for free.
 */
@Repository
public interface RoomRepository extends JpaRepository<Room, UUID> {

    // =========================================================================
    // OWNER DASHBOARD QUERIES
    // =========================================================================

    /**
     * Finds all rooms belonging to a specific property, paginated.
     * Used on: Owner's property detail page → "View all rooms in Sunrise PG".
     *
     * <p>Pageable lets the frontend request specific pages: page=0&size=10&sort=roomNumber,asc
     */
    Page<Room> findByPropertyId(UUID propertyId, Pageable pageable);

    /**
     * Finds all rooms in a property with a specific status.
     * Used on: Admin dashboard → "Show all AVAILABLE rooms in Property XYZ".
     * Also used internally to count available rooms for vacancy reports.
     */
    List<Room> findByPropertyIdAndStatus(UUID propertyId, RoomStatus status);

    /**
     * Checks if a room number is already taken within the same property.
     * Used in RoomService before creating a new room: prevent duplicate room numbers.
     * Example: Owner tries to create "Room 101" when 101 already exists → service rejects.
     */
    boolean existsByPropertyIdAndRoomNumber(UUID propertyId, String roomNumber);

    // =========================================================================
    // TENANT SEARCH QUERIES
    // =========================================================================

    /**
     * Finds all AVAILABLE rooms in a property with at least one available bed.
     *
     * <p><b>Why JPQL instead of method name?</b>
     * The method-name equivalent would be:
     * {@code findByPropertyIdAndStatusAndBeds_StatusIn(...)} — which generates
     * a confusing JOIN. The @Query makes intent explicit and lets us add the
     * COUNT subquery for available beds.
     *
     * <p>The subquery counts only AVAILABLE beds to ensure we don't show rooms
     * where all beds are, e.g., RESERVED or UNDER_MAINTENANCE.
     */
    @Query("""
        SELECT r FROM Room r
        WHERE r.property.id = :propertyId
          AND r.status = 'AVAILABLE'
          AND (SELECT COUNT(b) FROM Bed b
               WHERE b.room = r
                 AND b.status = 'AVAILABLE') > 0
        """)
    List<Room> findAvailableRoomsInProperty(@Param("propertyId") UUID propertyId);

    // =========================================================================
    // STATUS UPDATE QUERIES (Bulk / Atomic)
    // =========================================================================

    /**
     * Atomically updates a single room's status.
     *
     * <p><b>Why @Modifying?</b>
     * @Modifying tells Spring Data that this is a write operation (UPDATE/DELETE),
     * not a read. Without it, Spring Data treats the @Query as a SELECT and throws
     * an exception at runtime.
     *
     * <p><b>Why clearAutomatically = true?</b>
     * After a bulk UPDATE, the Hibernate 1st-level cache (EntityManager) might still
     * hold old entity states. {@code clearAutomatically = true} forces a cache clear,
     * so any subsequent findById() returns the fresh, updated value from DB.
     *
     * <p><b>Why not just load the Room and call setStatus()?</b>
     * That approach requires: (1) SELECT to load entity, (2) UPDATE to save it = 2 DB round trips.
     * This @Modifying query does it in 1 round trip. At scale, this matters.
     *
     * <p>Used by: RoomService when auto-transitioning AVAILABLE ↔ FULLY_OCCUPIED.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE Room r
        SET r.status = :status, r.updatedAt = CURRENT_TIMESTAMP
        WHERE r.id = :roomId
        """)
    int updateRoomStatus(@Param("roomId") UUID roomId, @Param("status") RoomStatus status);

    // =========================================================================
    // OWNER AUTHORIZATION QUERY
    // =========================================================================

    /**
     * Finds a room by ID and verifies it belongs to a specific owner.
     * Used for authorization: "Does this owner own this room?"
     *
     * <p>Why traverse property.owner instead of a direct owner_id on rooms?
     * Rooms don't have a direct owner FK — they belong to a Property which has an owner.
     * This JPQL follows the JPA relationship chain correctly.
     *
     * <p>Used in RoomService: before allowing an owner to update/delete a room,
     * we confirm they own the property that contains the room.
     */
    @Query("""
        SELECT r FROM Room r
        WHERE r.id = :roomId
          AND r.property.owner.id = :ownerId
        """)
    Optional<Room> findByIdAndOwnerId(@Param("roomId") UUID roomId, @Param("ownerId") UUID ownerId);

    // =========================================================================
    // ANALYTICS / ADMIN QUERIES
    // =========================================================================

    /**
     * Counts rooms by status for a specific property.
     * Used on: Admin property overview → "Sunrise PG: 5 AVAILABLE, 3 FULLY_OCCUPIED, 1 MAINTENANCE".
     */
    long countByPropertyIdAndStatus(UUID propertyId, RoomStatus status);

    /**
     * Counts total rooms belonging to a property (all statuses).
     * Used for property statistics cards in admin/owner dashboards.
     */
    long countByPropertyId(UUID propertyId);
}
