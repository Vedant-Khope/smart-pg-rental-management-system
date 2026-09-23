package com.smartpg.module.property.model;

import com.smartpg.module.property.enums.AmenityType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single amenity offered by a property listing.
 *
 * <p><b>Why a table for amenities instead of a JSONB array or bitmask?</b>
 *
 * <p><b>Option A - Bitmask (bit flags stored in an INT)</b>
 * {@code amenities = 00001101} = WIFI + AC + PARKING.
 * Problem: You can only have 32 or 64 amenities max. Adding the 65th requires
 * migrating all rows. Querying is unreadable. Absolutely not.
 *
 * <p><b>Option B - JSONB array</b> (PostgreSQL supports this)
 * {@code amenities = ["WIFI", "AC", "MEALS_PROVIDED"]}
 * Advantage: flexible. Disadvantage: indexing individual elements needs a GIN index,
 * and querying "all properties with WIFI" is harder to optimize than a simple JOIN.
 *
 * <p><b>Option C - Separate table (this approach)</b>
 * Each amenity is a row: (property_id, amenity_type).
 * Advantages:
 * <ul>
 *   <li>Trivially filterable: {@code SELECT p.* FROM properties p
 *       JOIN property_amenities pa ON p.id = pa.property_id
 *       WHERE pa.amenity_type IN ('WIFI', 'AC')}</li>
 *   <li>Can add metadata per amenity (notes, is_paid, etc.) without schema changes.</li>
 *   <li>Standard relational design. Easy to explain in interviews.</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code property_amenities}
 */
@Entity
@Table(
    name = "property_amenities",
    // Composite unique constraint: one property can't list the same amenity twice
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_property_amenities_property_amenity",
            columnNames = {"property_id", "amenity_type"}
        )
    },
    indexes = {
        // For loading all amenities of a specific property
        @Index(name = "idx_property_amenities_property_id",  columnList = "property_id"),
        // For reverse search: "all properties offering WIFI"
        @Index(name = "idx_property_amenities_amenity_type", columnList = "amenity_type")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "property")
public class PropertyAmenity {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Parent Relationship
    // -------------------------------------------------------------------------

    /**
     * The property this amenity belongs to.
     * Many amenities per property (OneToMany on the Property side).
     * FK column lives in this table.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "property_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_property_amenities_property_id")
    )
    private Property property;

    // -------------------------------------------------------------------------
    // Amenity Data
    // -------------------------------------------------------------------------

    /**
     * Which amenity this row represents.
     * Stored as EnumType.STRING: "WIFI", "AC", "MEALS_PROVIDED", etc.
     * The composite unique constraint ensures no duplicate amenities per property.
     *
     * <p>The DB-level unique constraint is the last line of defence.
     * The service layer should also check before inserting to give a
     * better error message than a DB constraint violation.
     *
     * @see AmenityType
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "amenity_type", nullable = false, length = 30)
    private AmenityType amenityType;

    /**
     * Optional descriptive note about this specific amenity.
     * Lets owners add context beyond the enum label.
     * Examples:
     * - WIFI -> "100 Mbps fiber, unlimited"
     * - MEALS_PROVIDED -> "Veg only, breakfast and dinner"
     * - PARKING -> "2-wheeler parking only, covered"
     * - GYM -> "Nearby gym membership included in rent"
     */
    @Column(name = "notes", length = 255)
    private String notes;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    /**
     * When this amenity was added to the property listing.
     * Amenities can be added at any time (owner edits listing).
     * No updatedAt: amenity rows are immutable - delete and re-add to change notes.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // -------------------------------------------------------------------------
    // Lifecycle Hooks
    // -------------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // equals & hashCode
    // -------------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PropertyAmenity other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
