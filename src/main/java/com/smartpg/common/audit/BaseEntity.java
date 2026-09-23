package com.smartpg.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Abstract base class inherited by ALL JPA entities in this system.
 *
 * <p>Provides 5 common columns automatically:
 *   - id          (primary key, auto-increment)
 *   - created_at  (auto-set on INSERT)
 *   - updated_at  (auto-set on INSERT and UPDATE)
 *   - created_by  (auto-set from SecurityContext on INSERT)
 *   - updated_by  (auto-set from SecurityContext on UPDATE)
 *
 * <p>Usage: public class User extends BaseEntity { ... }
 */
@Getter
@Setter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Auto-populated by Spring Data JPA on INSERT.
     * updatable = false → JPA never includes this column in an UPDATE statement.
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Auto-populated by Spring Data JPA on both INSERT and UPDATE.
     */
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Auto-populated with the current authenticated user's identifier on INSERT.
     * Sourced from AuditorAware bean defined in AuditConfig.
     * updatable = false → never changes after creation.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    /**
     * Auto-populated with the current authenticated user's identifier on UPDATE.
     */
    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}
