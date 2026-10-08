package com.smartpg.module.booking.model;

import com.smartpg.module.booking.enums.TenancyStatus;
import com.smartpg.module.room.model.Bed;
import com.smartpg.module.user.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Represents an active (or past) stay of a Tenant in a specific Bed.
 * While 'Booking' is the request phase, 'Tenancy' is the actual execution.
 */
@Entity
@Table(name = "tenancies")
@Getter
@Setter
public class Tenancy {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Relationships
    // -------------------------------------------------------------------------

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private User tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bed_id", nullable = false)
    private Bed bed;

    /**
     * Optional link to the original booking request.
     * Can be null if the owner directly adds a tenant without a prior booking request.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", unique = true)
    private Booking booking;

    // -------------------------------------------------------------------------
    // Tenancy Details
    // -------------------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TenancyStatus status = TenancyStatus.ACTIVE;

    @Column(name = "move_in_date", nullable = false)
    private LocalDate moveInDate;

    @Column(name = "move_out_date")
    private LocalDate moveOutDate;

    /**
     * The rent agreed upon at the time of move-in. 
     * Locks in the price for this specific tenancy regardless of future room price changes.
     */
    @Column(name = "monthly_rent", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyRent;

    @Column(name = "security_deposit", nullable = false, precision = 10, scale = 2)
    private BigDecimal securityDeposit;

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
        if (!(o instanceof Tenancy other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
