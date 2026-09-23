package com.smartpg.security;

import com.smartpg.module.user.enums.UserStatus;
import com.smartpg.module.user.model.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Custom Spring Security principal that wraps our {@link User} entity.
 *
 * <p><b>What is this and why does it exist?</b>
 * Spring Security's default {@link UserDetails} interface only exposes a "username" string.
 * But in our controllers, we need the authenticated user's <b>UUID</b> directly —
 * not just their email string. Without this class, we'd have to do an extra DB call
 * in every controller method just to get the UUID from the email:
 * <pre>
 *   // BAD (extra DB hit on every request):
 *   User user = userRepository.findByEmail(userDetails.getUsername()).get();
 *   UUID userId = user.getId();
 * </pre>
 *
 * <p><b>With {@code UserPrincipal}, the controller can just do:</b>
 * <pre>
 *   @AuthenticationPrincipal UserPrincipal principal = ...;
 *   UUID userId = principal.getUserId();  // ← no DB query, already in SecurityContext
 * </pre>
 *
 * <p><b>Analogy (Hinglish style):</b>
 * Default UserDetails ek plain visiting card hai — sirf naam (email) hai uspe.
 * UserPrincipal ek smart ID card hai — naam, UUID, role, sab kuch ek jagah!
 * Har baar naam se employee dhundne ki zaroorat nahi — ID card se directly pata chal jata hai.
 *
 * <p><b>Why implement UserDetails instead of extending it?</b>
 * {@code UserDetails} is an interface — you must implement it from scratch.
 * Spring Security checks for {@code UserDetails} via {@code instanceof} internally,
 * so implementing the interface is the correct contract.
 *
 * <p><b>@Getter (Lombok)</b>: Auto-generates getters for all fields.
 * Only applied on non-UserDetails fields (userId, email, role).
 * The {@code UserDetails} interface methods are manually implemented below.
 */
@Getter
public class UserPrincipal implements UserDetails {

    /**
     * The user's UUID — the primary key we use everywhere in the system.
     * Pre-loaded from the {@link User} entity so controllers don't need an extra DB call.
     */
    private final UUID userId;

    /**
     * The user's email — doubles as the "username" in Spring Security's model.
     */
    private final String email;

    /**
     * BCrypt-hashed password — Spring Security uses this internally for credential checks.
     * Controllers never access this field.
     */
    private final String passwordHash;

    /**
     * The single GrantedAuthority (e.g., "ROLE_TENANT") for this user.
     */
    private final Collection<? extends GrantedAuthority> authorities;

    /**
     * Whether the account is enabled — false for DELETED accounts.
     * Spring Security throws DisabledException on login if false.
     */
    private final boolean enabled;

    /**
     * Whether the account is not locked — false for SUSPENDED accounts.
     * Spring Security throws LockedException on login if false.
     */
    private final boolean accountNonLocked;

    /**
     * Builds a {@code UserPrincipal} from a fully loaded {@link User} entity.
     *
     * <p>Called from {@link UserDetailsServiceImpl#loadUserByUsername(String)}.
     *
     * @param user the authenticated user entity from the DB
     */
    public UserPrincipal(User user) {
        this.userId       = user.getId();
        this.email        = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        // ROLE_ prefix is required for hasRole('OWNER') to match authority "ROLE_OWNER"
        this.authorities  = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        // DELETED accounts are "disabled" in Spring Security terms
        this.enabled         = user.getStatus() != UserStatus.DELETED;
        // SUSPENDED accounts are "locked" in Spring Security terms
        this.accountNonLocked = user.getStatus() != UserStatus.SUSPENDED;
    }

    /**
     * Internal constructor for building from JWT claims without hitting the DB.
     */
    private UserPrincipal(UUID userId, String email, com.smartpg.module.user.enums.Role role) {
        this.userId       = userId;
        this.email        = email;
        this.passwordHash = null; // Not needed/available when hydrating from JWT
        this.authorities  = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
        this.enabled      = true; // If JWT is valid and not revoked, we trust it
        this.accountNonLocked = true; 
    }

    /**
     * Builds a {@code UserPrincipal} from JWT claims (stateless mode).
     *
     * <p>Used by {@link com.smartpg.security.filter.JwtAuthenticationFilter} so
     * controllers can safely receive a full UserPrincipal instead of just a UUID.
     */
    public static UserPrincipal fromJwtClaims(UUID userId, String email, com.smartpg.module.user.enums.Role role) {
        return new UserPrincipal(userId, email, role);
    }

    // =========================================================================
    // UserDetails interface implementation
    // =========================================================================

    /**
     * Returns the user's email as the "username" in Spring Security's model.
     * This is the identifier used in:
     * <ul>
     *   <li>{@code SecurityContext.getAuthentication().getName()}</li>
     *   <li>{@code @AuthenticationPrincipal UserDetails#getUsername()}</li>
     *   <li>JWT filter: the subject of the JWT token.</li>
     * </ul>
     */
    @Override
    public String getUsername() {
        return email;
    }

    /** Returns the BCrypt-hashed password — used internally by Spring Security. */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * Returns the list of granted authorities (roles) for this user.
     * Used by {@code @PreAuthorize("hasRole('OWNER')")} and SecurityConfig matchers.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** Accounts don't expire by time in our system. Always true. */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /** false = account is SUSPENDED → Spring Security throws LockedException. */
    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    /** Passwords don't expire in our system. Always true. */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /** false = account is DELETED → Spring Security throws DisabledException. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
