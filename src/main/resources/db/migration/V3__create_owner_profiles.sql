-- =============================================================================
-- V3__create_owner_profiles.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create the `owner_profiles` table — business and legal identity
--            data specific to users with role = 'OWNER'.
-- Depends  : V1__create_users.sql (FK → users.id)
-- Author   : Smart PG Dev Team
-- Notes    :
--   • Only created for OWNER-role users (enforced at service layer).
--     Caretakers, Tenants, Admins will never have a row here.
--   • verification_status has a defined state machine enforced by CHECK.
--   • pan_number encrypted at rest (same AES-256 strategy as user_profiles).
--   • total_properties is a denormalized counter cache — updated by PropertyService,
--     NOT derived at query time (avoids expensive JOINs on admin dashboards).
--   • ON DELETE CASCADE mirrors the `user_profiles` strategy. The users table
--     is soft-delete only, but cascade ensures clean DB state regardless.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- Create the `owner_profiles` table
-- -----------------------------------------------------------------------------

CREATE TABLE owner_profiles (

    -- -------------------------------------------------------------------------
    -- Primary Key
    -- -------------------------------------------------------------------------
    id                  UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Foreign Key → users(id)
    -- UNIQUE ensures 1:1 (one owner ↔ one business profile).
    -- NOT NULL: every owner_profile MUST belong to an existing user.
    -- -------------------------------------------------------------------------
    user_id             UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Business Details
    -- business_name: Registered trade name of the PG or rental business.
    --   Shown on property listings and rent receipts.
    --   Nullable: individual landlords (solo owners) may not have a formal name.
    -- gst_number: 15-char GSTIN (format: 22AAAAA0000A1Z5).
    --   Nullable: only required for owners above GST registration threshold (₹20L).
    -- pan_number: 10-char PAN for TDS compliance (format: AAAAA9999A).
    --   Stored AES-256 encrypted. Length 64 = post-encryption Base64 size.
    -- -------------------------------------------------------------------------
    business_name       VARCHAR(255),
    gst_number          VARCHAR(15),
    pan_number          VARCHAR(64),

    -- -------------------------------------------------------------------------
    -- Verification Status — Admin-controlled state machine
    -- Tracks the KYC/document review lifecycle for this owner's business.
    --
    -- State machine transitions (enforced in OwnerVerificationService):
    --   UNVERIFIED → IN_REVIEW   : Owner submits documents → admin picks up
    --   IN_REVIEW  → VERIFIED    : Admin approves → owner can publish listings
    --   IN_REVIEW  → REJECTED    : Admin rejects → owner must resubmit
    --   REJECTED   → IN_REVIEW   : Owner resubmits corrected documents
    --
    -- Default: UNVERIFIED — all new owner accounts start unverified.
    -- An owner CANNOT publish property listings until status = VERIFIED.
    -- -------------------------------------------------------------------------
    verification_status VARCHAR(20)     NOT NULL    DEFAULT 'UNVERIFIED',

    -- -------------------------------------------------------------------------
    -- Denormalized Counter: Total Properties
    -- Cached count of how many properties this owner currently has on the platform.
    --
    -- WHY DENORMALIZE?
    --   Admin dashboard query: "List all owners with their property counts."
    --   Without this: SELECT o.*, COUNT(p.id) FROM owner_profiles o
    --                 LEFT JOIN properties p ON p.owner_id = o.user_id
    --                 GROUP BY o.id → expensive aggregate on large data.
    --   With this   : SELECT * FROM owner_profiles → O(1) per row.
    --
    -- CONSISTENCY: Updated by PropertyService.createProperty() (increment)
    --   and PropertyService.deleteProperty() (decrement). Eventual consistency
    --   is acceptable — this is a display metric, not a billing figure.
    --
    -- DEFAULT 0: Every new owner starts with zero properties.
    -- -------------------------------------------------------------------------
    total_properties    INTEGER         NOT NULL    DEFAULT 0,

    -- -------------------------------------------------------------------------
    -- Audit Timestamps
    -- -------------------------------------------------------------------------
    created_at          TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    -- -------------------------------------------------------------------------
    -- Constraints
    -- -------------------------------------------------------------------------
    CONSTRAINT pk_owner_profiles            PRIMARY KEY (id),

    -- 1:1 enforcement
    CONSTRAINT uk_owner_profiles_user_id    UNIQUE (user_id),

    -- Referential integrity
    CONSTRAINT fk_owner_profiles_user_id
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE,

    -- Enum validation for verification_status
    CONSTRAINT chk_owner_profiles_verification_status
        CHECK (verification_status IN ('UNVERIFIED', 'IN_REVIEW', 'VERIFIED', 'REJECTED')),

    -- Business rule: property count can never go negative
    CONSTRAINT chk_owner_profiles_total_properties
        CHECK (total_properties >= 0),

    -- GST number format validation (basic length check — full regex in service layer)
    -- 15 chars: 2 (state code) + 10 (PAN) + 1 (entity type) + 1 (Z) + 1 (check digit)
    CONSTRAINT chk_owner_profiles_gst_length
        CHECK (gst_number IS NULL OR LENGTH(gst_number) = 15)

);

-- -----------------------------------------------------------------------------
-- Indexes
-- -----------------------------------------------------------------------------

-- idx_owner_profiles_user_id:
--   "Get the business profile for owner userId X" — used in every owner API call.
--   PostgreSQL creates an index for UNIQUE constraint automatically, but
--   explicit naming helps with query plan analysis.
CREATE INDEX idx_owner_profiles_user_id ON owner_profiles (user_id);

-- idx_owner_profiles_verification_status:
--   Admin workflow: "Show me all owners IN_REVIEW so I can start the verification queue."
--   High-value index for admin dashboard — without it, a full scan of owner_profiles
--   table happens every time the admin opens the verification panel.
CREATE INDEX idx_owner_profiles_verification_status ON owner_profiles (verification_status);

-- idx_owner_profiles_total_properties:
--   Admin dashboard: "Sort owners by property count, descending."
--   Without: Seq scan + sort. With: Index scan (already sorted).
CREATE INDEX idx_owner_profiles_total_properties ON owner_profiles (total_properties DESC);

-- -----------------------------------------------------------------------------
-- Comments
-- -----------------------------------------------------------------------------

COMMENT ON TABLE  owner_profiles                            IS 'Business and legal profile for OWNER-role users only. 1:1 with users table.';
COMMENT ON COLUMN owner_profiles.id                        IS 'UUID PK.';
COMMENT ON COLUMN owner_profiles.user_id                   IS 'FK to users(id). UNIQUE — one user has at most one owner profile.';
COMMENT ON COLUMN owner_profiles.business_name             IS 'Registered trade name shown on listings and receipts. Nullable for individual landlords.';
COMMENT ON COLUMN owner_profiles.gst_number                IS '15-char GSTIN. NULL if owner is below registration threshold.';
COMMENT ON COLUMN owner_profiles.pan_number                IS 'AES-256 encrypted 10-char PAN for TDS compliance.';
COMMENT ON COLUMN owner_profiles.verification_status       IS 'Admin KYC state: UNVERIFIED | IN_REVIEW | VERIFIED | REJECTED';
COMMENT ON COLUMN owner_profiles.total_properties          IS 'Denormalized count of active properties. Updated by PropertyService, NOT a live aggregate.';
COMMENT ON COLUMN owner_profiles.created_at                IS 'UTC timestamp of owner profile creation.';
COMMENT ON COLUMN owner_profiles.updated_at                IS 'UTC timestamp of last update.';
