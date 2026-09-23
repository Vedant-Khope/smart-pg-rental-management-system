-- =============================================================================
-- V9__create_property_amenities.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create the `property_amenities` table — one row per amenity offered
--            by a property (WIFI, AC, MEALS_PROVIDED, etc.)
-- Depends  : V6__create_properties.sql (FK to `properties.id`)
-- Notes    :
--   • WHY a table instead of JSONB or bitmask?
--       Option A — JSONB array: ["WIFI","AC","MEALS_PROVIDED"]
--         Pro: flexible. Con: GIN index needed; "find all WIFI properties" is slower.
--       Option B — Bitmask integer: bit 1 = WIFI, bit 2 = AC, etc.
--         Pro: compact. Con: max 64 amenities forever; queries are unreadable.
--       Option C — THIS TABLE (one row per amenity):
--         Pro: standard relational; clean filterable JOIN; extensible metadata;
--              interview-friendly. Con: slightly more rows, but trivially optimized.
--   • UNIQUE constraint on (property_id, amenity_type): a property cannot list
--     WIFI twice. Last-resort DB enforcement (service layer checks first to give
--     better error messages than raw constraint violations).
--   • `notes` field: enriches enum label with context.
--     WIFI note → "100 Mbps fiber, unlimited". MEALS note → "Veg only, thali".
--   • Amenity rows are IMMUTABLE — no updated_at. To change notes, service
--     deletes the old row and inserts a new one.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- STEP 1: Create the `property_amenities` table
-- -----------------------------------------------------------------------------

CREATE TABLE property_amenities (

    -- -------------------------------------------------------------------------
    -- Primary Key
    -- -------------------------------------------------------------------------
    id              UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Parent Relationship
    -- ManyToOne: one property, many amenity rows.
    -- ON DELETE CASCADE: deleting a property removes all its amenity rows.
    -- -------------------------------------------------------------------------
    property_id     UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Amenity Type (Enum stored as VARCHAR)
    -- Corresponds to the Java AmenityType enum.
    -- Valid values enforced by CHECK constraint below + application-level enum.
    -- Length 30: longest current value is "NEAR_PUBLIC_TRANSPORT" (20 chars).
    --   30 gives buffer for future additions.
    -- -------------------------------------------------------------------------
    amenity_type    VARCHAR(30)     NOT NULL,

    -- -------------------------------------------------------------------------
    -- Optional Descriptive Notes
    -- Owner can add context beyond the bare enum label.
    -- Examples:
    --   WIFI          → "100 Mbps Jio Fiber, unlimited, works in all rooms"
    --   MEALS_PROVIDED→ "Veg only, breakfast + dinner, Jain meals on request"
    --   PARKING       → "2-wheeler only, covered, Rs. 200/month extra"
    --   GYM           → "Nearby Cult.fit membership included in rent"
    -- Nullable: most owners will just tick the checkbox without adding notes.
    -- -------------------------------------------------------------------------
    notes           VARCHAR(255),

    -- -------------------------------------------------------------------------
    -- Audit Timestamp
    -- Amenities are immutable once added (delete + re-add to change notes).
    -- Only created_at needed.
    -- -------------------------------------------------------------------------
    created_at      TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    -- =========================================================================
    -- CONSTRAINTS
    -- =========================================================================

    CONSTRAINT pk_property_amenities
        PRIMARY KEY (id),

    CONSTRAINT fk_property_amenities_property_id
        FOREIGN KEY (property_id) REFERENCES properties (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- The critical constraint: a property cannot list the same amenity twice.
    -- DB is the last line of defence. Service layer checks first and throws
    -- a clean exception before this fires. If service has a bug, this catches it.
    CONSTRAINT uk_property_amenities_property_amenity
        UNIQUE (property_id, amenity_type),

    -- All valid AmenityType enum values.
    -- Update this CHECK whenever a new amenity is added to the Java enum.
    CONSTRAINT chk_property_amenities_type
        CHECK (amenity_type IN (
            'WIFI',
            'MEALS_PROVIDED',
            'KITCHEN',
            'AC',
            'POWER_BACKUP',
            'HOT_WATER',
            'LAUNDRY',
            'PARKING',
            'SECURITY',
            'GYM',
            'PET_FRIENDLY',
            'HOUSEKEEPING',
            'NEAR_PUBLIC_TRANSPORT',
            'FURNISHED'
        ))

);


-- -----------------------------------------------------------------------------
-- STEP 2: Indexes
-- -----------------------------------------------------------------------------

-- FK lookup + most common query: "get all amenities for property X"
-- Fires every time a property detail page is loaded.
CREATE INDEX idx_property_amenities_property_id
    ON property_amenities (property_id);

-- Reverse search: "find all properties offering WIFI"
-- The tenant filter query:
--   SELECT p.* FROM properties p
--   WHERE EXISTS (
--     SELECT 1 FROM property_amenities pa
--     WHERE pa.property_id = p.id AND pa.amenity_type = 'WIFI'
--   )
-- This index makes the EXISTS subquery hit only the index, not the full table.
CREATE INDEX idx_property_amenities_amenity_type
    ON property_amenities (amenity_type);

-- Composite: "find all properties in Koramangala with WIFI AND AC"
-- Allows a JOIN between property_addresses + property_amenities efficiently.
CREATE INDEX idx_property_amenities_property_type
    ON property_amenities (property_id, amenity_type);


-- -----------------------------------------------------------------------------
-- STEP 3: Table and Column Comments
-- -----------------------------------------------------------------------------

COMMENT ON TABLE  property_amenities IS
    'One row per amenity offered by a property. '
    'Enables clean, indexed filtering: "find all PGs with WIFI and AC in Koramangala". '
    'Alternative designs (JSONB, bitmask) were rejected for filterability reasons.';

COMMENT ON COLUMN property_amenities.id IS
    'UUID primary key.';

COMMENT ON COLUMN property_amenities.property_id IS
    'FK to properties.id. Many amenity rows per property.';

COMMENT ON COLUMN property_amenities.amenity_type IS
    'Which amenity this row represents. Maps to Java AmenityType enum. '
    'E.g. WIFI | AC | MEALS_PROVIDED | PARKING. '
    'Composite UNIQUE with property_id prevents duplicates.';

COMMENT ON COLUMN property_amenities.notes IS
    'Optional owner-written detail about this amenity. '
    'E.g. for WIFI: "100 Mbps Jio Fiber, unlimited". Nullable.';

COMMENT ON COLUMN property_amenities.created_at IS
    'Insertion timestamp. Amenities are immutable: delete + re-add to change notes.';
