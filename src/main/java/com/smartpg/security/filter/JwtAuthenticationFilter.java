package com.smartpg.security.filter;

import com.smartpg.module.user.enums.Role;
import com.smartpg.security.jwt.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The CORE of our JWT-based authentication — runs once per HTTP request.
 *
 * <p><b>What is this?</b>
 * Imagine a concert where each area has a bouncer. This filter IS that bouncer
 * for every single HTTP request. Before the request reaches your controller,
 * this filter intercepts it, checks the JWT "wristband" in the Authorization
 * header, and either:
 * <ul>
 *   <li>✅ <b>Valid JWT</b> → Stamps the SecurityContext ("this person is authenticated as OWNER") so the rest of the application trusts this request.</li>
 *   <li>❌ <b>No JWT / invalid JWT</b> → Does nothing — the SecurityContext remains empty, and Spring Security will reject the request when it hits a protected endpoint (returning 401 via {@link AuthEntryPointJwt}).</li>
 * </ul>
 *
 * <p><b>Why extend OncePerRequestFilter and not GenericFilterBean?</b>
 * {@link OncePerRequestFilter} guarantees this filter runs EXACTLY ONCE per request,
 * even in cases where Spring internally forwards/includes requests (which could
 * trigger other filters multiple times). This is critical — you don't want to
 * do JWT parsing/DB calls multiple times for the same request.
 *
 * <p><b>Why NOT load the user from the database on every request?</b>
 * Our JWT contains ALL the information we need:
 * <ul>
 *   <li>{@code sub} → userId (UUID)</li>
 *   <li>{@code role} → OWNER / TENANT / ADMIN etc.</li>
 *   <li>{@code email} → user's email</li>
 * </ul>
 * We trust this because the JWT is cryptographically signed. If the signature is
 * valid, the claims inside are authentic. Loading from DB would add a round-trip
 * to every single API call — for a high-traffic app, that's unacceptable overhead.
 *
 * <p><b>NO Database Call architecture</b>:
 * <pre>
 * Request → JwtAuthenticationFilter → Validate JWT signature → Extract claims from JWT
 *         → Set SecurityContext → Controller → (if needed) DB lookup
 * </pre>
 * This makes our auth O(1) — constant time regardless of user count.
 *
 * <p><b>Filter Chain position</b>: Configured in {@link com.smartpg.security.config.SecurityConfig}
 * to run BEFORE {@link org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter}.
 * This ensures our JWT check happens before any Spring Security default auth mechanisms.
 *
 * <p><b>@Component</b>: Spring auto-detects this as a filter bean. We explicitly add
 * it to the security filter chain in {@link com.smartpg.security.config.SecurityConfig}
 * via {@code .addFilterBefore()} to control its position precisely.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /**
     * The standard HTTP Authorization header name.
     * Requests from clients look like:
     * <pre>
     *   Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VySWQiLCJyb2xlIjoiVEVOQU5UIn0.SomeHmacSignature
     * </pre>
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Every Bearer token must start with this prefix.
     * RFC 6750 defines "Bearer" as the token type for OAuth2 / JWT.
     * We strip this prefix before parsing the actual JWT string.
     */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    /**
     * Constructor injection — preferred over @Autowired.
     * Explicit, testable, fails fast if JwtService is not registered.
     *
     * @param jwtService the JWT utility for validation and claim extraction
     */
    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * The core filter logic — called exactly once per HTTP request.
     *
     * <p><b>Step-by-step flow</b>:
     * <ol>
     *   <li><b>Extract token</b>: Read the Authorization header, strip "Bearer " prefix.</li>
     *   <li><b>Validate</b>: Check token signature and expiry via JwtService.</li>
     *   <li><b>Extract claims</b>: Get userId, email, role from the JWT payload.</li>
     *   <li><b>Build authentication</b>: Wrap claims in a UsernamePasswordAuthenticationToken.</li>
     *   <li><b>Set SecurityContext</b>: Tell Spring Security this request is authenticated.</li>
     *   <li><b>Continue</b>: Call filterChain.doFilter() to pass to the next filter/controller.</li>
     * </ol>
     *
     * <p><b>Why check SecurityContextHolder.getContext().getAuthentication() == null?</b>
     * If Spring Security's session management or another filter has ALREADY set the
     * authentication, we skip our JWT check. This prevents accidentally overwriting
     * a valid authentication that was set by another mechanism.
     *
     * <p><b>What happens if validation fails?</b>
     * We log the failure and call {@code filterChain.doFilter()} WITHOUT setting
     * SecurityContext. Spring Security then sees an unauthenticated request and:
     * <ul>
     *   <li>For protected endpoints → calls {@link AuthEntryPointJwt} → returns 401</li>
     *   <li>For public endpoints → lets the request through (public is public)</li>
     * </ul>
     *
     * @param request     the incoming HTTP request
     * @param response    the outgoing HTTP response
     * @param filterChain the chain of filters after this one
     * @throws ServletException if a downstream filter throws it
     * @throws IOException      if a downstream filter or controller throws it
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // ── Step 1: Extract the JWT from the Authorization header ─────────────
        String token = extractToken(request);

        if (token == null) {
            // No JWT in the request — pass through without setting SecurityContext.
            // This is NORMAL for public endpoints (login, register, health check).
            // For protected endpoints, Spring Security will reject it via AuthEntryPointJwt.
            filterChain.doFilter(request, response);
            return;
        }

        // ── Step 2: Skip if SecurityContext already has authentication ─────────
        // Another filter or session management may have already authenticated this request.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            log.debug("SecurityContext already populated — skipping JWT filter for: {}", request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        // ── Step 3: Validate the JWT ──────────────────────────────────────────
        try {
            if (!jwtService.isTokenValid(token)) {
                // Token is invalid (bad signature, expired, malformed).
                // Log it and continue WITHOUT setting SecurityContext.
                log.debug("Invalid JWT token for request: {}", request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            // ── Step 4: Extract claims from the validated token ───────────────
            UUID   userId = jwtService.extractUserId(token);
            String email  = jwtService.extractEmail(token);
            Role   role   = jwtService.extractRole(token);

            log.debug("JWT valid — userId: {}, role: {}, path: {}", userId, role, request.getRequestURI());

            // ── Step 5: Build the Authentication object ───────────────────────
            // UsernamePasswordAuthenticationToken is Spring Security's standard
            // representation of an authenticated principal.
            //
            // Constructor: (principal, credentials, authorities)
            //   - principal: The "who" — we use userId (UUID) as principal.
            //                This is what SecurityContextHolder.getContext()
            //                .getAuthentication().getPrincipal() returns in controllers.
            //   - credentials: null after authentication (no need to hold the password).
            //   - authorities: The list of roles/permissions. We create ONE authority
            //                  with "ROLE_" prefix so @PreAuthorize("hasRole('OWNER')") works.
            //
            // IMPORTANT: The 3-argument constructor (with authorities) is what marks
            // this token as "authenticated = true". The 2-argument constructor WITHOUT
            // authorities creates an UNAUTHENTICATED token (used before verification).
            
            com.smartpg.security.UserPrincipal principal = com.smartpg.security.UserPrincipal.fromJwtClaims(userId, email, role);
            
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    principal,                                                   // principal (the full object)
                    null,                                                        // credentials (null after auth)
                    principal.getAuthorities()                                   // authorities
            );

            // ── Step 6: Attach request details ───────────────────────────────
            // WebAuthenticationDetailsSource builds an object containing the IP address
            // and session ID of the request. This is stored on the authentication token
            // and is useful for:
            //   a) Audit logging inside controllers (get IP without parsing headers manually)
            //   b) Spring Security's built-in security event publishing
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            // ── Step 7: Register in SecurityContext ───────────────────────────
            // This is the KEY STEP. Setting this tells the entire Spring Security framework:
            // "This request is authenticated. Let it through."
            // From this point, @PreAuthorize, @Secured, and principal-injection in
            // controllers all work because they read from this context.
            SecurityContextHolder.getContext().setAuthentication(authToken);

        } catch (JwtException e) {
            // This catches any JWT library exceptions that slipped past isTokenValid()
            // (e.g., parsing edge cases). We log and continue without auth.
            log.warn("JWT exception during filter processing for [{}]: {}", request.getRequestURI(), e.getMessage());
            // Intentionally NOT setting SecurityContext — request will be treated as unauthenticated
        }

        // ── Step 8: Continue the filter chain ────────────────────────────────
        // ALWAYS call this — even on failure. The filter chain must continue
        // so Spring Security can properly handle the (possibly unauthenticated) request.
        // If we forget this, the request hangs forever — the response is never completed.
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the raw JWT string from the Authorization header.
     *
     * <p>Expected header format: {@code Authorization: Bearer <token>}
     *
     * <p><b>StringUtils.hasText()</b>: Unlike {@code != null && !isEmpty()},
     * this also handles strings with only whitespace (e.g., "  ") — treating
     * them as blank. Safer than {@code != null} alone.
     *
     * @param request the incoming HTTP request
     * @return the raw JWT string (without "Bearer " prefix), or {@code null}
     *         if the Authorization header is absent or not a Bearer token
     */
    private String extractToken(HttpServletRequest request) {
        String headerValue = request.getHeader(AUTHORIZATION_HEADER);

        if (StringUtils.hasText(headerValue) && headerValue.startsWith(BEARER_PREFIX)) {
            // Substring from index 7 — "Bearer " is 7 characters
            return headerValue.substring(BEARER_PREFIX.length());
        }

        // No Authorization header, or it's not a Bearer token (could be Basic, Digest, etc.)
        return null;
    }
}
