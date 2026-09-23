package com.smartpg.module.user.dto.response;

import com.smartpg.module.user.enums.Role;
import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.model.User;

import java.time.Instant;
import java.util.UUID;

/**
 * A safe, serializable representation of a {@link User} entity for API responses.
 *
 * <p><b>What is this and why does it exist?</b>
 * We NEVER return the raw JPA {@link User} entity from a controller. Why?
 * <ul>
 *   <li><b>Security</b>: The entity has {@code passwordHash}, {@code emailVerified},
 *       lazy-loaded {@code profile} collections — not meant for external consumption.</li>
 *   <li><b>Control</b>: A DTO gives you precise control over which fields are exposed.
 *       If tomorrow you add an {@code internalFlag} to the entity, it won't accidentally
 *       leak into API responses.</li>
 *   <li><b>API stability</b>: Entity structure can evolve (new columns, renamed fields)
 *       without breaking the public API contract defined by this DTO.</li>
 *   <li><b>Lazy loading</b>: If Jackson tries to serialize the entity with lazy-loaded
 *       collections, you get a {@code LazyInitializationException}. DTOs sidestep this.</li>
 * </ul>
 *
 * <p><b>Analogy</b>: The entity is your internal passport file at the government office
 * (contains everything — biometrics, criminal record, medical history). The DTO is
 * the passport book you hand to border security — only the fields they need to see.
 *
 * <p><b>Hinglish note</b>: Entity ek raw database row hai, lekin UserResponse ek
 * "filtered, safe export" hai jo client ko bhejte hain. Password hash kabhi nahi!
 *
 * @param id            the user's UUID — frontend needs this for routing and cache keys
 * @param email         the user's email address
 * @param role          the role determining access level
 * @param status        the account status (ACTIVE, SUSPENDED, etc.)
 * @param emailVerified whether the email address has been verified
 * @param phoneVerified whether the phone number has been verified
 * @param phone         the phone number (may be null if not provided at registration)
 * @param lastLoginAt   the timestamp of the last successful login (null = never logged in)
 * @param createdAt     when the account was created
 * @param updatedAt     when the account was last updated
 */
public record UserResponse(
        UUID userId,
        String email,
        Role role,
        UserStatus status,
        boolean emailVerified,
        boolean phoneVerified,
        String phone,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Static factory method — builds a {@code UserResponse} from a {@link User} entity.
     *
     * <p><b>Why static factory instead of constructor?</b>
     * Cleaner call site in the controller:
     * <pre>
     *   return UserResponse.from(user);  // readable
     *   // vs
     *   return new UserResponse(user.getId(), user.getEmail(), user.getRole(), ...); // fragile
     * </pre>
     * With positional constructor args you can accidentally swap {@code createdAt} and
     * {@code updatedAt} and the compiler won't catch it. Static factory with named
     * field mapping is safer.
     *
     * @param user the {@link User} entity fetched from the DB
     * @return a fully populated {@code UserResponse}
     */
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isEmailVerified(),
                user.isPhoneVerified(),
                user.getPhone(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
