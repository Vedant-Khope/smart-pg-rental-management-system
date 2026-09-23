-- =============================================================================
-- V7__create_property_addresses.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create the `property_addresses` table — physical location + GPS
--            coordinates for each property listing.
-- Depends  : V6__create_properties.sql (FK to `properties.id`)
-- Notes    :
--   • 1:1 relationship with properties (UNIQUE constraint on property_id).
--   • Address is kept in a separate table (not embedded in properties) for:
--       (a) Geo-query optimization: lat/lng indexed separately for proximity search.
--       (b) Independent editability: owner can update landmark without re-approval.
--       (c) Future extensibility: PostGIS extension can be added to this table only.
--   • `street_address` is NOT indexed — it's never used as a search filter
--     (tenants search by locality/city, not by exact street address).
--   • `locality` IS indexed — it's the most common tenant search field.
--   • Lat/lng stored as NUMERIC(precision, scale) not FLOAT — avoids floating-point
--     representation errors that break coordinate comparisons.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- STEP 1: Create the `property_addresses` table
-- -----------------------------------------------------------------------------

CREATE TABLE property_addresses (

    -- -------------------------------------------------------------------------
    -- Primary Key
    -- -------------------------------------------------------------------------
    id              UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Parent Relationship
    -- 1:1 with properties. UNIQUE enforces "one property, one address".
    -- updatable = false in JPA: address is permanently tied to this property.
    -- ON DELETE CASCADE: if the property is hard-deleted (future edge case),
    --   its address row is automatically removed. In practice, we soft-delete,
    --   but this prevents orphan address rows if a physical delete ever happens.
    -- -------------------------------------------------------------------------
    property_id     UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Street-Level Address (private — not shown in public search results)
    -- Tenants only see this AFTER a booking is confirmed, for privacy + security.
    -- "Plot 42, 2nd Cross, Koramangala 5th Block" — detailed enough for navigation.
    -- -------------------------------------------------------------------------
    street_address  VARCHAR(500)    NOT NULL,

    -- -------------------------------------------------------------------------
    -- Landmark (critical for Indian cities)
    -- Addresses in Bengaluru, Mumbai, etc. are notoriously imprecise.
    -- "Near Forum Mall" or "Opp. HDFC Bank ATM" is more useful than GPS for
    -- first-visit navigation. Nullable — not all owners provide this.
    -- -------------------------------------------------------------------------
    landmark        VARCHAR(255),

    -- -------------------------------------------------------------------------
    -- Searchable Location Hierarchy
    -- locality : neighbourhood/area — the PRIMARY tenant search filter.
    --   "Koramangala" / "Andheri West" / "Sector 62 Noida"
    --   Indexed because tenant search is: WHERE locality = ? AND city = ?
    -- city     : city-level filter for multi-city platform.
    --   "Bengaluru" / "Mumbai" / "Pune"
    -- state    : state for regional reporting.
    --   "Karnataka" / "Maharashtra"
    -- pincode  : 6-digit postal code. Used for precise locality matching.
    --   Indexed for exact-match lookups.
    -- -------------------------------------------------------------------------
    locality        VARCHAR(100)    NOT NULL,
    city            VARCHAR(100)    NOT NULL,
    state           VARCHAR(100)    NOT NULL,
    pincode         VARCHAR(6)      NOT NULL,

    -- -------------------------------------------------------------------------
    -- Geolocation Coordinates (for map display + future proximity search)
    -- latitude  : NUMERIC(10,8) → 8 decimal places → ~1.1mm precision.
    --   India range: 8.4°N to 37.6°N. Values are positive (North of equator).
    --   NUMERIC avoids floating-point representation errors (unlike FLOAT/DOUBLE).
    -- longitude : NUMERIC(11,8) → 11 total digits, 8 decimal places.
    --   India range: 68.7°E to 97.25°E. Extra digit for 3-digit integer part.
    -- Both nullable — owner may submit only the text address without pinning a map.
    -- -------------------------------------------------------------------------
    latitude        NUMERIC(10, 8),
    longitude       NUMERIC(11, 8),

    -- -------------------------------------------------------------------------
    -- Audit Timestamps
    -- -------------------------------------------------------------------------
    created_at      TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    -- =========================================================================
    -- CONSTRAINTS
    -- =========================================================================

    CONSTRAINT pk_property_addresses
        PRIMARY KEY (id),

    -- 1:1 enforcement: one property can only have one address row.
    CONSTRAINT uk_property_addresses_property_id
        UNIQUE (property_id),

    CONSTRAINT fk_property_addresses_property_id
        FOREIGN KEY (property_id) REFERENCES properties (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Latitude sanity check: global range is -90 to 90.
    -- India's range is ~8.4 to 37.6 but we keep the global range for flexibility.
    CONSTRAINT chk_property_addresses_latitude
        CHECK (latitude IS NULL OR (latitude BETWEEN -90 AND 90)),

    -- Longitude sanity check: global range is -180 to 180.
    CONSTRAINT chk_property_addresses_longitude
        CHECK (longitude IS NULL OR (longitude BETWEEN -180 AND 180)),

    -- Pincode format: exactly 6 digits (Indian postal codes).
    CONSTRAINT chk_property_addresses_pincode
        CHECK (pincode ~ '^[0-9]{6}$')

);


-- -----------------------------------------------------------------------------
-- STEP 2: Indexes
-- -----------------------------------------------------------------------------

-- FK lookup: Hibernate uses this when loading address for a given property.
-- Also used by the 1:1 relationship navigation (property.getAddress()).
CREATE INDEX idx_property_addresses_property_id
    ON property_addresses (property_id);

-- Tenant search: the most common filter.
-- "Show me PGs in Koramangala" → WHERE locality = 'Koramangala'
CREATE INDEX idx_property_addresses_locality
    ON property_addresses (locality);

-- City-level filter: "Show me listings in Bengaluru"
CREATE INDEX idx_property_addresses_city
    ON property_addresses (city);

-- Pincode search: precise locality matching for users who know their target pincode.
CREATE INDEX idx_property_addresses_pincode
    ON property_addresses (pincode);

-- Composite lat/lng: for future bounding-box proximity queries.
-- "Find all properties where lat BETWEEN ? AND ? AND lng BETWEEN ? AND ?"
-- This is the Haversine formula query pattern — requires both columns indexed together.
CREATE INDEX idx_property_addresses_latlng
    ON property_addresses (latitude, longitude);


-- -----------------------------------------------------------------------------
-- STEP 3: Table and Column Comments
-- -----------------------------------------------------------------------------

COMMENT ON TABLE  property_addresses IS
    'Physical location + GPS coordinates for each property. '
    'Kept separate from properties for geo-query optimization and independent editability.';

COMMENT ON COLUMN property_addresses.id IS
    'UUID primary key. Generated by Hibernate @UuidGenerator.';

COMMENT ON COLUMN property_addresses.property_id IS
    'FK to properties.id. 1:1 — each property has exactly one address.';

COMMENT ON COLUMN property_addresses.street_address IS
    'Full street address (building, flat number, street). '
    'Private: shown to tenants only after booking confirmation.';

COMMENT ON COLUMN property_addresses.landmark IS
    'Nearby landmark for navigation. E.g. "Near Forum Mall". '
    'Critical for Indian cities where GPS addresses are imprecise.';

COMMENT ON COLUMN property_addresses.locality IS
    'Neighbourhood or area. Primary tenant search filter. E.g. "Koramangala".';

COMMENT ON COLUMN property_addresses.city IS
    'City. E.g. "Bengaluru", "Mumbai". Used for city-level search filtering.';

COMMENT ON COLUMN property_addresses.state IS
    'State or Union Territory. E.g. "Karnataka", "Maharashtra".';

COMMENT ON COLUMN property_addresses.pincode IS
    '6-digit Indian postal code. E.g. "560034". Used for precise locality matching.';

COMMENT ON COLUMN property_addresses.latitude IS
    'GPS latitude. NUMERIC(10,8) = 8 decimal places = ~1mm precision. '
    'NULL if owner did not pin location on map. India range: 8.4N to 37.6N.';

COMMENT ON COLUMN property_addresses.longitude IS
    'GPS longitude. NUMERIC(11,8). '
    'NULL if owner did not pin location on map. India range: 68.7E to 97.25E.';

COMMENT ON COLUMN property_addresses.created_at IS
    'UTC timestamp of address creation. Immutable.';

COMMENT ON COLUMN property_addresses.updated_at IS
    'UTC timestamp of last update. E.g. when owner corrects a typo in the landmark.';
