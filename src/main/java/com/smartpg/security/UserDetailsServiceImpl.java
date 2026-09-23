package com.smartpg.security;

import com.smartpg.module.user.model.User;
import com.smartpg.module.user.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security's bridge between your {@link User} entity and its auth framework.
 *
 * <p><b>What is this?</b>
 * Spring Security doesn't know what a "User" is. It has its own interface —
 * {@link UserDetails} — that describes an authenticated principal. This class
 * is the translator: it knows how to load YOUR user from the DB and wrap it
 * in the {@link UserDetails} interface that Spring Security understands.
 *
 * <p>Imagine you're a bouncer at a club (Spring Security). You have a rulebook
 * ({@link UserDetails}) that tells you: is this person allowed in? Are they banned?
 * What areas can they access? But the rulebook is in your format, not the format
 * of the guest list (our database). This class TRANSLATES the guest list entry
 * into bouncer-readable format.
 *
 * <p><b>When is this called?</b>
 * <ol>
 *   <li>By {@link org.springframework.security.authentication.DaoAuthenticationProvider}
 *       during form login or {@code UsernamePasswordAuthenticationToken} validation.</li>
 *   <li>By our JWT filter (indirectly) when we manually build an
 *       {@link org.springframework.security.core.Authentication} for the SecurityContext.</li>
 * </ol>
 *
 * <p><b>Why {@code @Transactional(readOnly = true)}?</b>
 * This method only reads data — it never writes. {@code readOnly = true} hints to
 * Hibernate to skip dirty-checking (reduces CPU overhead) and allows the DB to
 * use read replicas. It also prevents accidental writes if someone adds a setter
 * call here by mistake.
 *
 * <p><b>Why implement {@link UserDetailsService} and not use our own interface?</b>
 * Spring Security wires {@link UserDetailsService} automatically in its
 * {@link org.springframework.security.authentication.AuthenticationManager} configuration.
 * If we implement this interface, we get automatic integration without extra config.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    public UserDetailsServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Loads a user by their email address and wraps it in Spring Security's {@link UserDetails}.
     *
     * <p><b>Why is the parameter called "username"?</b>
     * Spring Security was designed when usernames were the norm. In our system,
     * the "username" IS the email. The parameter name is dictated by the interface —
     * we just treat it as an email internally.
     *
     * <p><b>The ROLE_ prefix convention</b>:
     * Spring Security's {@code hasRole('OWNER')} automatically prepends "ROLE_" when checking.
     * So {@code hasRole('OWNER')} checks for authority "ROLE_OWNER". We must store it with
     * the "ROLE_" prefix in {@link SimpleGrantedAuthority} for this to work correctly.
     * If you forget the prefix, {@code hasRole()} will NEVER match anything — a very
     * subtle, hard-to-debug bug.
     *
     * <p><b>Alternatively</b>: Use {@code hasAuthority('ROLE_OWNER')} or
     * {@code hasAuthority('OWNER')} depending on what you store — just be consistent.
     *
     * <p><b>What if the user is SUSPENDED or DELETED?</b>
     * We still load them here. The ACCOUNT STATUS check happens in
     * {@link com.smartpg.module.user.service.AuthService#login} BEFORE Spring Security
     * calls this method. Or alternatively, we encode the status in the UserDetails
     * returned (via the {@code isEnabled()} / {@code isAccountNonLocked()} flags)
     * and Spring Security enforces it automatically.
     *
     * <p>We use the second approach (UserDetails flags) for maximum Spring Security
     * integration — Spring will reject a disabled/locked user during auth automatically.
     *
     * @param email the email address of the user to load (treated as username)
     * @return a populated {@link UserDetails} for Spring Security to use
     * @throws UsernameNotFoundException if no user with this email exists
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No user found with email: " + email));

        return buildUserDetails(user);
    }

    /**
     * Converts a {@link User} entity into a Spring Security {@link UserDetails} object.
     *
     * <p><b>UserDetails flags mapping</b>:
     * <pre>
     *   isEnabled()           → status == ACTIVE or INACTIVE (user exists and can be reactivated)
     *   isAccountNonLocked()  → status != SUSPENDED (admins suspend by "locking" the account)
     *   isAccountNonExpired() → always true (we don't expire accounts by time)
     *   isCredentialsNonExpired() → always true (password doesn't expire in our system)
     * </pre>
     *
     * <p>Spring Security checks these flags during authentication and throws
     * specific exceptions ({@link org.springframework.security.authentication.DisabledException},
     * {@link org.springframework.security.authentication.LockedException}) if they return false.
     * Our GlobalExceptionHandler maps those to the right HTTP status codes.
     *
     * <p><b>Authorities (roles)</b>: We create ONE {@link SimpleGrantedAuthority} per user
     * because our system has exactly ONE role per user (no multi-role setup). If multi-role
     * were needed, we'd return a list of authorities.
     *
     * Converts a {@link User} entity into our custom {@link UserPrincipal}.
     *
     * <p><b>Why UserPrincipal instead of Spring's built-in User builder?</b>
     * Spring's {@code User.builder()} only stores a String username. Our controllers
     * need the UUID directly (for audit logs, service calls) without an extra DB hit.
     * {@link UserPrincipal} carries the UUID, email, and role all in one object —
     * injected via {@code @AuthenticationPrincipal UserPrincipal} in controllers.
     *
     * @param user the loaded {@link User} entity
     * @return a {@link UserPrincipal} for Spring Security
     */
    public UserDetails buildUserDetails(User user) {
        return new UserPrincipal(user);
    }
}
