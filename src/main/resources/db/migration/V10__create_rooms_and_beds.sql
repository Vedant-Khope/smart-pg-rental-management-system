-- =============================================================================
-- V10__create_rooms_and_beds.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create `rooms` and `beds` tables.
-- Depends  : V6__create_properties.sql
-- Notes    :
--   • Rooms belong to a Property. Beds belong to a Room.
--   • Room capacity is validated via CHECK constraints where possible.
--   • All status columns use VARCHAR with CHECK constraints.
--   • Beds implement a RESERVED status with a timeout for race-condition-free booking.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- STEP 1: Create `rooms` table
-- -----------------------------------------------------------------------------

CREATE TABLE rooms (
    id                      UUID            NOT NULL,
    property_id             UUID            NOT NULL,
    room_number             VARCHAR(20)     NOT NULL,
    floor_number            INTEGER         NOT NULL,
    room_type               VARCHAR(20)     NOT NULL,
    capacity                INTEGER         NOT NULL,
    monthly_rent            NUMERIC(10,2)   NOT NULL,
    description             TEXT,
    has_attached_bathroom   BOOLEAN         NOT NULL    DEFAULT FALSE,
    has_balcony             BOOLEAN         NOT NULL    DEFAULT FALSE,
    has_ac                  BOOLEAN         NOT NULL    DEFAULT FALSE,
    status                  VARCHAR(25)     NOT NULL    DEFAULT 'AVAILABLE',
    created_at              TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    CONSTRAINT pk_rooms
        PRIMARY KEY (id),

    -- FK to properties. RESTRICT prevents deleting a property that has rooms.
    CONSTRAINT fk_rooms_property_id
        FOREIGN KEY (property_id) REFERENCES properties (id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    -- Enum guards
    CONSTRAINT chk_rooms_type
        CHECK (room_type IN ('SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD')),

    CONSTRAINT chk_rooms_status
        CHECK (status IN ('AVAILABLE', 'FULLY_OCCUPIED', 'UNDER_MAINTENANCE', 'INACTIVE')),

    -- Business rule validations
    CONSTRAINT chk_rooms_capacity
        CHECK (capacity >= 1 AND capacity <= 20),

    CONSTRAINT chk_rooms_rent
        CHECK (monthly_rent >= 0)
);

-- Indexes for Rooms
CREATE INDEX idx_rooms_property_id ON rooms (property_id);
CREATE INDEX idx_rooms_status ON rooms (status);
CREATE INDEX idx_rooms_type ON rooms (room_type);
CREATE INDEX idx_rooms_property_status ON rooms (property_id, status);
CREATE INDEX idx_rooms_monthly_rent ON rooms (monthly_rent);
CREATE INDEX idx_rooms_floor_number ON rooms (floor_number);

-- -----------------------------------------------------------------------------
-- STEP 2: Create `beds` table
-- -----------------------------------------------------------------------------

CREATE TABLE beds (
    id                      UUID            NOT NULL,
    room_id                 UUID            NOT NULL,
    bed_label               VARCHAR(10)     NOT NULL,
    notes                   TEXT,
    status                  VARCHAR(20)     NOT NULL    DEFAULT 'AVAILABLE',
    reserved_by_user_id     UUID,
    reserved_until          TIMESTAMPTZ,
    created_at              TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),
    updated_at              TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    CONSTRAINT pk_beds
        PRIMARY KEY (id),

    -- FK to rooms.
    CONSTRAINT fk_beds_room_id
        FOREIGN KEY (room_id) REFERENCES rooms (id)
        ON DELETE RESTRICT
        ON UPDATE CASCADE,

    -- Enum guard
    CONSTRAINT chk_beds_status
        CHECK (status IN ('AVAILABLE', 'RESERVED', 'OCCUPIED', 'UNDER_MAINTENANCE')),

    -- Reservation consistency checks
    -- If RESERVED, then reserved_by and reserved_until must be set
    CONSTRAINT chk_beds_reservation_consistency
        CHECK (
            (status = 'RESERVED' AND reserved_by_user_id IS NOT NULL AND reserved_until IS NOT NULL)
            OR
            (status != 'RESERVED' AND reserved_until IS NULL) -- we allow reserved_by to stick around or be null, but until must be null
        )
);

-- Indexes for Beds
CREATE INDEX idx_beds_room_id ON beds (room_id);
CREATE INDEX idx_beds_status ON beds (status);
CREATE INDEX idx_beds_room_status ON beds (room_id, status);
CREATE INDEX idx_beds_reserved_until ON beds (reserved_until);

-- Comments
COMMENT ON TABLE rooms IS 'Physical rooms inside a property. A room contains beds.';
COMMENT ON TABLE beds IS 'Individual beds within a room. This is the unit that tenants actually book.';
