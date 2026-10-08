package com.smartpg.module.booking.model;

import com.smartpg.module.booking.enums.BookingStatus;
import com.smartpg.module.room.model.Bed;
import com.smartpg.module.user.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents a request by a Tenant to book a specific Bed.
 * Acts as the precursor to a full Tenancy.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
public class Booking {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Relationships
    // -------------------------------------------------------------------------

    /**
     * The tenant requesting the booking.
     * FetchType.LAZY to avoid N+1 queries when loading multiple bookings.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private User tenant;

    /**
     * The specific bed being booked.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bed_id", nullable = false)
    private Bed bed;

    // -------------------------------------------------------------------------
    // Booking Details
    // -------------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private BookingStatus status = BookingStatus.PENDING;

    /**
     * When the tenant expects to move in.
     */
    @Column(name = "expected_move_in_date", nullable = false)
    private LocalDate expectedMoveInDate;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Booking other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
