package com.smartpg.module.user.dto.request;

import com.smartpg.module.user.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Incoming payload for the {@code POST /api/v1/auth/register} endpoint.
 *
 * <p><b>Why a dedicated DTO (not the User entity directly)?</b>
 * <ul>
 *   <li><b>Security</b>: The {@link com.smartpg.module.user.model.User} entity has
 *       fields like {@code status}, {@code emailVerified}, {@code createdAt} that
 *       the client must NEVER be allowed to set. If you bind the entity directly,
 *       a malicious client could send {@code "status":"ACTIVE","emailVerified":true}
 *       and bypass verification. A DTO is your firewall.</li>
 *   <li><b>Validation</b>: Bean Validation annotations ({@code @Email}, {@code @NotBlank},
 *       etc.) belong on input DTOs, not on JPA entities. Mixing them causes
 *       Hibernate to run validations at the wrong time (flush time, not bind time).</li>
 *   <li><b>API contract stability</b>: The entity can change its internal structure
 *       (adding columns, renaming fields) without breaking the public API contract
 *       defined by this DTO.</li>
 * </ul>
 *
 * <p><b>Validation chain</b>: When the controller method has {@code @Valid},
 * Spring runs all constraint annotations here before the method body executes.
 * Any violation throws {@code MethodArgumentNotValidException} which the
 * {@code GlobalExceptionHandler} turns into a structured 400 response.
 *
 * <p><b>Why a Java record?</b> Records are immutable by design — no setters —
 * which is exactly what you want for a request payload. Once bound from JSON,
 * it cannot be accidentally mutated.
 *
 * @param email    the user's email — must be valid format and unique in the system
 * @param password raw plaintext password — service will BCrypt-hash it; never stored as-is
 * @param phone    optional 10-digit phone number — validated against digit-only pattern
 * @param role     the role the user is registering as (TENANT or OWNER only from public API)
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email must be a valid email address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        /**
         * Plaintext password supplied by the user.
         * Constraints:
         * <ul>
         *   <li>Min 8 characters — prevents trivially guessable passwords</li>
         *   <li>Max 72 characters — BCrypt silently truncates beyond 72 chars,
         *       so we enforce the limit here to avoid user confusion</li>
         *   <li>At least one uppercase, one lowercase, one digit, one special char
         *       — enforced by regex for real-world password strength</li>
         * </ul>
         */
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@#$%^&+=!]).+$",
            message = "Password must contain at least one uppercase, one lowercase, one digit, and one special character (@#$%^&+=!)"
        )
        String password,

        @Pattern(regexp = "^[6-9]\\d{9}$", message = "Phone must be a valid 10-digit Indian mobile number")
        String phone,

        /**
         * Role the user is self-registering as.
         * The controller/service should validate this isn't ADMIN or SUPER_ADMIN —
         * those roles can only be assigned internally, never via public registration.
         */
        @NotNull(message = "Role is required")
        Role role

) {}
