package com.smartpg.module.property.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Physical address and geolocation data for a property listing.
 *
 * <p><b>Why a separate table from Property?</b>
 * <ul>
 *   <li><b>Geo-Queries</b>: Future feature - "find PGs within 5km of Indiranagar Metro".
 *       A separate address table with indexed lat/lng columns is the standard approach.
 *       Adding PostGIS extension later is much cleaner with address in its own table.</li>
 *   <li><b>Independent Editability</b>: Address changes (minor: floor number, landmark)
 *       shouldn't require re-approval. But a full street/city change MIGHT need re-approval.
 *       Having address separate lets us implement this rule cleanly in PropertyService.</li>
 *   <li><b>Reusability</b>: If we later add a user's home address or a caretaker's
 *       assignment address, the same address structure can be referenced.</li>
 * </ul>
 *
 * <p><b>Table</b>: {@code property_addresses}
 */
@Entity
@Table(
    name = "property_addresses",
    indexes = {
        // For FK lookups from property side
        @Index(name = "idx_property_addresses_property_id", columnList = "property_id"),
        // For geo-based searches (future: PostGIS or Haversine)
        @Index(name = "idx_property_addresses_city",    columnList = "city"),
        @Index(name = "idx_property_addresses_pincode", columnList = "pincode"),
        // Composite lat/lng for bounding box searches
        @Index(name = "idx_property_addresses_latlng",  columnList = "latitude, longitude")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "property")
public class PropertyAddress {

    // -------------------------------------------------------------------------
    // Primary Key
    // -------------------------------------------------------------------------

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Owning Side of the 1:1 Relationship
    // -------------------------------------------------------------------------

    /**
     * The property this address belongs to.
     *
     * <p>This side of the OneToOne owns the FK column {@code property_id}
     * in the {@code property_addresses} table.
     * The inverse side uses {@code mappedBy = "address"}.
     *
     * <p>{@code updatable = false}: An address is tied to its property for life.
     * Changing which property an address belongs to is nonsensical.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "property_id",
        nullable = false,
        unique = true,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_property_addresses_property_id")
    )
    private Property property;

    // -------------------------------------------------------------------------
    // Street-Level Address
    // -------------------------------------------------------------------------

    /**
     * Full street address: building name, flat number, street name.
     * Example: "Plot 42, 2nd Cross, Koramangala 5th Block".
     * Not shown publicly on listing cards (security) - shown only after booking confirmation.
     */
    @Column(name = "street_address", nullable = false, length = 500)
    private String streetAddress;

    /**
     * Nearby landmark. Critical for navigation in Indian cities where addresses
     * are imprecise. Example: "Near Forum Mall", "Opp. HDFC Bank ATM".
     */
    @Column(name = "landmark", length = 255)
    private String landmark;

    // -------------------------------------------------------------------------
    // Locality / Area
    // -------------------------------------------------------------------------

    /**
     * The neighbourhood or area within the city.
     * This is the key tenant search filter! Tenants search by area, not full address.
     * Example: "Koramangala", "Andheri West", "Sector 62 Noida".
     */
    @Column(name = "locality", nullable = false, length = 100)
    private String locality;

    /**
     * The city this property is in.
     * Used for city-level filtering in search.
     * Example: "Bengaluru", "Mumbai", "Pune".
     */
    @Column(name = "city", nullable = false, length = 100)
    private String city;

    /**
     * State or Union Territory.
     * Example: "Karnataka", "Maharashtra", "Telangana".
     */
    @Column(name = "state", nullable = false, length = 100)
    private String state;

    /**
     * 6-digit Indian postal code.
     * Used for precise locality matching.
     * Example: "560034" (Koramangala, Bengaluru).
     */
    @Column(name = "pincode", nullable = false, length = 6)
    private String pincode;

    // -------------------------------------------------------------------------
    // Geolocation (for Map Display and Proximity Search)
    // -------------------------------------------------------------------------

    /**
     * GPS latitude coordinate of the property.
     * Range: -90.0 to 90.0. India is between 8.4° N to 37.6° N.
     *
     * <p>Precision: 8 decimal places gives ~1mm accuracy.
     * We use BigDecimal (not double/float) to avoid floating-point precision issues
     * when storing and comparing coordinates.
     *
     * <p>Populated when the owner pins their location on the map UI.
     * Nullable: not every owner will pin the map (they might only type the address).
     */
    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    /**
     * GPS longitude coordinate of the property.
     * Range: -180.0 to 180.0. India is between 68.7° E to 97.25° E.
     *
     * <p>Same precision reasoning as latitude above.
     */
    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Lifecycle Hooks
    // -------------------------------------------------------------------------

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
        if (!(o instanceof PropertyAddress other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
