-- =============================================================================
-- V8__create_property_photos.sql
-- Smart PG & Rental Management System
-- =============================================================================
-- Purpose  : Create the `property_photos` table — one row per uploaded photo.
-- Depends  : V6__create_properties.sql (FK to `properties.id`)
-- Notes    :
--   • Photos are NOT stored in PostgreSQL — only the CDN URL is stored here.
--     Actual image files live in cloud storage (S3/GCS). DB stays lean.
--   • A separate table (vs. JSONB array in properties) gives us:
--       (a) Individual delete: DELETE WHERE id = ? (not deserialize-filter-reserialize)
--       (b) Ordered display via display_order column.
--       (c) Cover photo flag for search result card thumbnail.
--       (d) Future extensibility: add dimensions, file_size, alt_text without schema change.
--   • `is_cover_photo` uniqueness (one cover per property) is enforced at the
--     SERVICE LAYER, not DB — because a partial unique index on a boolean is
--     complex and unnecessary for this scale. Service does: reset all → set one.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- STEP 1: Create the `property_photos` table
-- -----------------------------------------------------------------------------

CREATE TABLE property_photos (

    -- -------------------------------------------------------------------------
    -- Primary Key
    -- -------------------------------------------------------------------------
    id              UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Parent Relationship
    -- ManyToOne: one property, many photos.
    -- ON DELETE CASCADE: if the property is deleted, all its photos are deleted too.
    -- In practice, we soft-delete properties (status = DELETED), so CASCADE is
    -- a safety net for physical deletes that shouldn't happen but might in dev/test.
    -- -------------------------------------------------------------------------
    property_id     UUID            NOT NULL,

    -- -------------------------------------------------------------------------
    -- Photo URL
    -- The full CDN URL pointing to the image in cloud storage.
    -- Example: "https://cdn.smartpg.in/properties/uuid/photo-001.webp"
    -- Length 500: CDN URLs with path segments can be long; 500 is safe.
    -- We store the PERMANENT object URL (not a pre-signed URL which expires).
    -- The React frontend renders: <img src={photo.photoUrl} />
    -- -------------------------------------------------------------------------
    photo_url       VARCHAR(500)    NOT NULL,

    -- -------------------------------------------------------------------------
    -- Caption / Alt Text
    -- Owner-written description of what's in the photo.
    -- Doubles as HTML alt text for accessibility (WCAG screen readers).
    -- Example: "Well-lit private room with attached bathroom and study desk"
    -- Nullable: owners often don't write captions.
    -- -------------------------------------------------------------------------
    caption         VARCHAR(255),

    -- -------------------------------------------------------------------------
    -- Display Order
    -- 0-based integer. Smaller number = shown first in the gallery.
    -- @OrderBy("displayOrder ASC, createdAt ASC") on the Property.photos collection
    -- means every load of property.getPhotos() returns them in this order automatically.
    -- When owner reorders gallery, service updates these integers.
    -- Default 0: new photos go to the front until explicitly reordered.
    -- -------------------------------------------------------------------------
    display_order   INTEGER         NOT NULL    DEFAULT 0,

    -- -------------------------------------------------------------------------
    -- Cover Photo Flag
    -- TRUE = this is the "hero" image shown in search result listing cards.
    -- Only ONE photo per property should have this TRUE.
    -- Enforced at service layer (PropertyService.setCoverPhoto):
    --   1. UPDATE property_photos SET is_cover_photo = FALSE WHERE property_id = ?
    --   2. UPDATE property_photos SET is_cover_photo = TRUE  WHERE id = ?
    -- Not enforced by DB constraint because partial unique index on booleans
    -- adds complexity without meaningful safety gain at this scale.
    -- -------------------------------------------------------------------------
    is_cover_photo  BOOLEAN         NOT NULL    DEFAULT FALSE,

    -- -------------------------------------------------------------------------
    -- Audit Timestamp
    -- Photos are IMMUTABLE once uploaded — owners delete and re-upload to change.
    -- So only created_at is needed; no updated_at.
    -- -------------------------------------------------------------------------
    created_at      TIMESTAMPTZ     NOT NULL    DEFAULT NOW(),

    -- =========================================================================
    -- CONSTRAINTS
    -- =========================================================================

    CONSTRAINT pk_property_photos
        PRIMARY KEY (id),

    CONSTRAINT fk_property_photos_property_id
        FOREIGN KEY (property_id) REFERENCES properties (id)
        ON DELETE CASCADE
        ON UPDATE CASCADE,

    -- Basic URL sanity: must start with http:// or https://
    CONSTRAINT chk_property_photos_url
        CHECK (photo_url LIKE 'http://%' OR photo_url LIKE 'https://%')

);


-- -----------------------------------------------------------------------------
-- STEP 2: Indexes
-- -----------------------------------------------------------------------------

-- The most common query: "get all photos for property X, ordered by display_order"
-- Powers: property detail page photo gallery, owner's edit-photos view.
CREATE INDEX idx_property_photos_property_id
    ON property_photos (property_id);

-- Cover photo lookup for search result cards.
-- Query: SELECT * FROM property_photos WHERE property_id = ? AND is_cover_photo = TRUE
-- Composite index so this specific query hits only the index, not the table.
CREATE INDEX idx_property_photos_cover
    ON property_photos (property_id, is_cover_photo);

-- Display order index: when fetching all photos for a property in order.
-- Used in conjunction with property_id in ORDER BY queries.
CREATE INDEX idx_property_photos_display_order
    ON property_photos (property_id, display_order);


-- -----------------------------------------------------------------------------
-- STEP 3: Table and Column Comments
-- -----------------------------------------------------------------------------

COMMENT ON TABLE  property_photos IS
    'One row per uploaded photo for a property listing. '
    'Stores CDN URL only — actual images live in cloud storage (S3/GCS).';

COMMENT ON COLUMN property_photos.id IS
    'UUID primary key.';

COMMENT ON COLUMN property_photos.property_id IS
    'FK to properties.id. Many photos per property.';

COMMENT ON COLUMN property_photos.photo_url IS
    'Full CDN URL to the image. E.g. "https://cdn.smartpg.in/properties/uuid/photo.jpg". '
    'Not a pre-signed URL — must be a permanent access URL.';

COMMENT ON COLUMN property_photos.caption IS
    'Owner-written photo description. Doubles as accessibility alt text. Nullable.';

COMMENT ON COLUMN property_photos.display_order IS
    '0-based gallery order. Smaller = shown first. Default 0. '
    'Updated when owner reorders gallery via drag-and-drop.';

COMMENT ON COLUMN property_photos.is_cover_photo IS
    'TRUE = hero image shown in search result cards. '
    'Only one photo per property should be TRUE. Enforced at service layer.';

COMMENT ON COLUMN property_photos.created_at IS
    'Upload timestamp. Photos are immutable — no updated_at needed.';
