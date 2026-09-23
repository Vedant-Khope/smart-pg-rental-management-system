package com.smartpg.module.property.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a single uploaded photo for a property listing.
 *
 * <p><b>Why a separate table for photos instead of a String[] or JSON column?</b>
 * <ul>
 *   <li><b>Ordered Display</b>: We track {@code displayOrder} so owners can set a
 *       cover photo and arrange gallery order. Impossible with a plain URL list.</li>
 *   <li><b>Cover Photo</b>: The first photo (or the marked cover) is shown in search
 *       result cards. {@code isCoverPhoto} flag makes querying the cover trivially easy.</li>
 *   <li><b>Individual Delete</b>: Owner can remove a specific photo. With a JSON array,
 *       you'd have to deserialize, filter, and re-serialize. With a table, it's a
 *       single DELETE WHERE id = ?</li>
 *   <li><b>Metadata Rich</b>: We can add alt text, captions, file size, dimensions
 *       later without a schema change - just add columns.</li>
 *   <li><b>Orphan Removal</b>: When property is deleted, CascadeType.ALL on the parent
 *       auto-deletes all photo rows. Clean referential integrity.</li>
 * </ul>
 *
 * <p><b>Storage</b>: Photos are NOT stored in the DB. Only the URL is stored here.
 * The actual image file lives in cloud storage (S3/GCS). The URL points to it.
 * This keeps the DB lean and images served via CDN.
 *
 * <p><b>Table</b>: {@code property_photos}
 */
@Entity
@Table(
    name = "property_photos",
    indexes = {
        // Most common query: "get all photos for property X, ordered by displayOrder"
        @Index(name = "idx_property_photos_property_id", columnList = "property_id"),
        // Quick lookup of cover photo for a property (shown in search result cards)
        @Index(name = "idx_property_photos_cover",
               columnList = "property_id, is_cover_photo")
    }
)
@Getter
@Setter
@NoArgsConstructor
@ToString(exclude = "property")
public class PropertyPhoto {

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
     * The property this photo belongs to.
     *
     * <p>This side owns the FK. {@code nullable = false}: every photo MUST
     * belong to a property. Orphaned photos in storage are a different concern
     * (handled by a periodic cleanup job), but DB orphans are impossible.
     *
     * <p>{@code updatable = false}: A photo's property never changes.
     * If an owner wants to move a photo to another property, they delete and re-upload.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "property_id",
        nullable = false,
        updatable = false,
        foreignKey = @ForeignKey(name = "fk_property_photos_property_id")
    )
    private Property property;

    // -------------------------------------------------------------------------
    // Photo Metadata
    // -------------------------------------------------------------------------

    /**
     * Full URL to the uploaded image in cloud storage (S3/GCS CDN URL).
     * Example: "https://cdn.smartpg.in/properties/uuid/photo-001.jpg"
     *
     * <p>Length 500: CDN URLs with paths can be long. We store the permanent
     * object URL (not a pre-signed URL which expires).
     *
     * <p>This URL is what the React frontend renders in an <img> tag.
     */
    @Column(name = "photo_url", nullable = false, length = 500)
    private String photoUrl;

    /**
     * Alt text / caption for the photo.
     * Doubles as accessibility text (WCAG compliance) and caption in the gallery.
     * Example: "Living room with sofa and TV", "Well-lit private room with attached bath".
     * Optional - owners may not always provide meaningful captions.
     */
    @Column(name = "caption", length = 255)
    private String caption;

    /**
     * Display order index (0-based) for gallery ordering.
     * Smaller number = shown first. The cover photo is typically displayOrder = 0.
     *
     * <p>When the owner reorders photos in the UI, we update these integers.
     * The {@code @OrderBy("displayOrder ASC")} on the parent collection uses this.
     *
     * <p>Default 0 means newly uploaded photos go to the front until reordered.
     */
    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    /**
     * Whether this is the primary/cover photo shown in search result cards.
     * Only ONE photo per property should have this as true.
     *
     * <p>Enforced at service layer (not DB constraint): when setting a new
     * cover photo, PropertyService sets isCoverPhoto=false on all others first,
     * then sets isCoverPhoto=true on the chosen one.
     *
     * <p>The {@code idx_property_photos_cover} index makes the query
     * "get cover photo for property X" O(log n) instead of a full table scan.
     */
    @Column(name = "is_cover_photo", nullable = false)
    private boolean coverPhoto = false;

    // -------------------------------------------------------------------------
    // Audit Timestamps
    // -------------------------------------------------------------------------

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // -------------------------------------------------------------------------
    // Lifecycle Hooks
    // -------------------------------------------------------------------------

    /**
     * Photos are immutable once uploaded (no updates, only delete + re-upload).
     * So we only need @PrePersist, not @PreUpdate.
     * We still include updatedAt for consistency and potential future use.
     */
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
        if (!(o instanceof PropertyPhoto other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
