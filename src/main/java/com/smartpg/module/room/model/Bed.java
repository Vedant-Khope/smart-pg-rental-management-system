package com.smartpg.module.room.model;

import com.smartpg.module.room.enums.BedStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single physical bed (or bunk/cot) inside a Room.
 *
 * <p><b>Real-world analogy:</b>
 * Room 101 (Double) ke andar do beds hain: "Bed A" aur "Bed B".
 * Rahul ne Bed A book kiya. Priya Bed B ke liye search kar rahi hai.
 * Yahi granularity chahiye humein — individual bed level tracking.
 *
 * <p><b>Why Bed is a separate entity from Room:</b>
 * <ul>
 *   <li>Tenants book a SPECIFIC bed, not a room. A booking links to a Bed.</li>
 *   <li>Individual beds can have different states (A=OCCUPIED, B=AVAILABLE, C=MAINTENANCE).</li>
 *   <li>Each bed can have its own amenities or notes (e.g., "window seat", "ground level").</li>
 *   <li>Bed-level tracking enables precise vacancy reports and revenue per bed analytics.</li>
 * </ul>
 *
 * <p><b>The RESERVATION pattern (race condition prevention):</b>
 * Without the RESERVED state, two tenants could simultaneously try to book Bed A:
 * <ol>
 *   <li>Tenant 1 sees Bed A as AVAILABLE → clicks "Book Now"</li>
 *   <li>Tenant 2 sees Bed A as AVAILABLE → clicks "Book Now" (same millisecond)</li>
 *   <li>Both proceed to payment... only one should succeed!</li>
 * </ol>
 * With RESERVED: the first click atomically sets status = RESERVED (using database-level
 * UPDATE WHERE status = 'AVAILABLE'). The second click finds status = RESERVED and fails
 * gracefully with "Sorry, this bed was just taken!".
 *
 * <p><b>Table</b>: {@code beds}
 */
@Entity
@Table(
    name = "beds",
    indexes = {
        // Load all beds in a room: WHERE room_id = ? (most common query)
        @Index(name = "idx_beds_room_id",           columnList = "room_id"),
        // Availability search: WHERE status = 'AVAILABLE'
        @Index(name = "idx_beds_status",            columnList = "status"),
        // Combined: available beds in a specific room (bed selection UI query)
        @Index(name = "idx_beds_room_status",       columnList = "room_id, status"),
        // Find RESERVED beds for cleanup job (auto-expire reservations)
        @Index(name = "idx_beds_reserved_until",    columnList = "reserved_until")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = {"room"})
public class Bed {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    /**
     * Non-sequential UUID primary key.
     * Like Room and Property — UUIDs everywhere for consistent security posture.
     * Prevents enumeration: a tenant can't guess bed IDs by iterating integers.
     */
    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Parent Room Relationship
    // -------------------------------------------------------------------------

    /**
     * The Room this bed belongs to.
     *
     * <p><b>ManyToOne</b>: Many beds belong to one room. FK lives in THIS (beds) table.
     *
     * <p><b>FetchType.LAZY</b>: When listing all available beds, we DON'T need to load
     * the full Room entity (which would then JOIN to Property, which JOIN to Owner...).
     * LAZY stops that cascade of unnecessary database JOINs.
     *
     * <p><b>updatable = false</b>: A bed's parent room can NEVER change. You can't
     * "move" Bed A from Room 101 to Room 102. If you need to reassign, you soft-delete
     * this bed and create a new one in the target room — with a full audit trail.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "room_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_beds_room_id")
    )
    private Room room;

    // -------------------------------------------------------------------------
    // Bed Identity
    // -------------------------------------------------------------------------

    /**
     * Human-readable label for this bed within the room.
     * Assigned by the owner. Examples: "Bed A", "Bed B", "Lower Bunk", "Upper Bunk",
     * "Window Side", "Left", "Right".
     *
     * <p>Unique within the same room (service layer enforces this — not a DB UNIQUE
     * constraint because uniqueness is scoped to a room, not globally).
     * Displayed in tenant search results and tenancy agreements.
     */
    @Column(name = "bed_label", nullable = false, length = 10)
    private String bedLabel;

    /**
     * Optional notes about this specific bed.
     * Owner-written. Shown to tenants when they view the bed details.
     * Example: "Upper bunk. Not recommended if you are uncomfortable with heights.",
     * "Ground level. Good for elderly tenants."
     * Nullable — most beds won't need special notes.
     */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // -------------------------------------------------------------------------
    // Lifecycle State
    // -------------------------------------------------------------------------

    /**
     * Current availability state of this bed.
     *
     * <p>Default: AVAILABLE — a newly added bed is immediately open for booking.
     * (Assuming the property is ACTIVE and the room is AVAILABLE.)
     *
     * <p><b>State transitions:</b>
     * <ul>
     *   <li>AVAILABLE → RESERVED: Tenant initiates booking (atomic DB UPDATE)</li>
     *   <li>RESERVED → OCCUPIED: Booking confirmed and tenancy created</li>
     *   <li>RESERVED → AVAILABLE: Booking abandoned or reservation expired (cleanup job)</li>
     *   <li>OCCUPIED → AVAILABLE: Tenant vacates (tenancy end date reached)</li>
     *   <li>AVAILABLE → UNDER_MAINTENANCE: Owner flags bed for repair</li>
     *   <li>UNDER_MAINTENANCE → AVAILABLE: Owner marks maintenance complete</li>
     * </ul>
     *
     * @see BedStatus
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BedStatus status = BedStatus.AVAILABLE;

    // -------------------------------------------------------------------------
    // Reservation Tracking (For Race Condition Prevention)
    // -------------------------------------------------------------------------

    /**
     * The UUID of the tenant who currently has this bed reserved.
     * Set when status transitions to RESERVED. Cleared when OCCUPIED or AVAILABLE.
     *
     * <p>Why store this? Two reasons:
     * <ol>
     *   <li>The system can verify "Is this the same tenant trying to confirm their reservation?"
     *       — prevents one tenant from hijacking another tenant's reserved bed.</li>
     *   <li>The cleanup job can notify the specific tenant: "Your reservation for Bed A
     *       in Room 101 has expired."</li>
     * </ol>
     *
     * <p>Stored as a raw UUID (not a FK to users) to avoid a JOIN on every bed status check.
     * The application layer can look up the user when needed (reservation confirmation flow).
     */
    @Column(name = "reserved_by_user_id")
    private UUID reservedByUserId;

    /**
     * Timestamp until which this bed is reserved.
     * When {@link Instant#now()} > {@code reservedUntil}, the reservation has expired.
     *
     * <p>Used by:
     * <ul>
     *   <li><b>Booking service</b>: "Is this reservation still valid?" check</li>
     *   <li><b>Scheduled cleanup job</b>: {@code @Scheduled} task runs every 5 minutes,
     *       finds all beds where status='RESERVED' AND reserved_until < NOW(),
     *       and sets them back to AVAILABLE. This auto-releases abandoned bookings.</li>
     * </ul>
     *
     * <p>Null when status is not RESERVED.
     */
    @Column(name = "reserved_until")
    private Instant reservedUntil;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    /**
     * UTC timestamp of when this bed was first created by the owner.
     * Set ONCE on INSERT via @PrePersist. Immutable. Useful for: "When was this
     * bed added to the property?" queries in admin analytics.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * UTC timestamp of the last modification.
     * Auto-refreshed on every UPDATE via @PreUpdate.
     * Useful for: "When was this bed's status last changed?"
     * Critical for debugging stuck RESERVED states.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Lifecycle Hooks
    // -------------------------------------------------------------------------

    /**
     * Called by Hibernate BEFORE the first INSERT.
     * Guarantees createdAt and updatedAt are always populated — defensive programming.
     */
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Called by Hibernate BEFORE every UPDATE.
     * Automatically refreshes updatedAt. No manual timestamp management needed in services.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    /**
     * Equality based solely on {@code id}.
     * Same reasoning as Room, Property, and User entities.
     * Avoids infinite loops in bidirectional JPA relationships and HashSet corruption.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bed other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
