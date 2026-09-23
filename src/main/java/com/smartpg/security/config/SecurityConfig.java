package com.smartpg.security.config;

import com.smartpg.security.filter.AuthEntryPointJwt;
import com.smartpg.security.filter.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Central Spring Security configuration for the Smart PG backend.
 *
 * <p><b>What is this?</b>
 * Think of this as the "Security Command Center" — everything security-related
 * is wired together here. This class tells Spring Security:
 * <ul>
 *   <li>Which endpoints are public (no token needed)?</li>
 *   <li>Which endpoints require authentication?</li>
 *   <li>Which endpoints are role-restricted?</li>
 *   <li>How should sessions work? (We use STATELESS — no server-side sessions)</li>
 *   <li>What happens when someone hits a protected endpoint without a token?</li>
 *   <li>Where does our JWT filter sit in the chain?</li>
 * </ul>
 *
 * <p><b>@Configuration</b>: Tells Spring this class contains @Bean definitions
 * that should be loaded into the ApplicationContext. Without this, @Bean methods
 * won't work.
 *
 * <p><b>@EnableWebSecurity</b>: Activates Spring Security's web security support.
 * In Spring Boot 3+, this is mostly needed to enable customization via
 * SecurityFilterChain beans. Without it, Spring Security still auto-configures,
 * but you lose the ability to fully customize the filter chain.
 *
 * <p><b>@EnableMethodSecurity</b>: Activates method-level security annotations.
 * Without this:
 * <ul>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} on controller methods → does NOTHING</li>
 *   <li>{@code @PreAuthorize("hasRole('OWNER')")} → does NOTHING</li>
 * </ul>
 * With this enabled, Spring generates AOP proxies around each annotated method
 * and enforces the security expressions before the method body executes.
 *
 * <p><b>prePostEnabled = true (default in EnableMethodSecurity)</b>:
 * Enables {@code @PreAuthorize} and {@code @PostAuthorize}. We primarily use
 * {@code @PreAuthorize} which checks BEFORE the method runs — the right default.
 *
 * <p><b>Session Strategy: STATELESS</b>
 * We tell Spring Security to NEVER create or use an HTTP session. JWTs ARE the
 * session. Every request carries the token — no need to store session state on
 * the server. This makes the app horizontally scalable: any server instance can
 * handle any request without shared session storage.
 *
 * <p><b>CSRF disabled — why?</b>
 * CSRF (Cross-Site Request Forgery) attacks exploit browser cookies. Since we
 * use JWTs in the Authorization header (not cookies), CSRF attacks are not
 * applicable. The browser won't automatically include the JWT header — the
 * client must explicitly set it. Disabling CSRF removes unnecessary overhead.
 * <b>NOTE</b>: If you ever switch to cookie-based auth, re-enable CSRF.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthEntryPointJwt       authEntryPointJwt;

    /**
     * Constructor injection for the two security components we need.
     *
     * <p>Both {@link JwtAuthenticationFilter} and {@link AuthEntryPointJwt} are
     * @Component beans — Spring injects them automatically.
     *
     * @param jwtAuthenticationFilter the JWT filter to add to the chain
     * @param authEntryPointJwt       the 401 error handler for unauthenticated requests
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          AuthEntryPointJwt authEntryPointJwt) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authEntryPointJwt       = authEntryPointJwt;
    }

    // =========================================================================
    // ① MAIN SECURITY FILTER CHAIN
    // =========================================================================

    /**
     * Defines the security filter chain — the complete security pipeline for HTTP requests.
     *
     * <p><b>Order of rules matters in Spring Security</b>:
     * Rules are evaluated top-to-bottom. The FIRST matching rule wins.
     * Put more specific rules before more general ones. Example:
     * <pre>
     *   .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")  ← specific first
     *   .anyRequest().authenticated()                           ← general last
     * </pre>
     *
     * <p><b>Public endpoints (permitAll)</b>:
     * These don't require a JWT at all:
     * <ul>
     *   <li>/api/v1/auth/register — new user signup</li>
     *   <li>/api/v1/auth/login — credential-based login</li>
     *   <li>/api/v1/auth/refresh — exchange refresh token for new access token</li>
     *   <li>/actuator/health — health check for load balancers / Kubernetes probes</li>
     *   <li>/swagger-ui/** — API documentation (disable in prod if desired)</li>
     * </ul>
     *
     * <p><b>Admin-only endpoints</b>:
     * Even with a valid JWT, the role must be ADMIN or SUPER_ADMIN.
     * URL-based role checks here act as the FIRST line of defense.
     * Method-level {@code @PreAuthorize} in controllers acts as a SECOND check.
     * Defense in depth — two layers of authorization.
     *
     * <p><b>Filter position</b>:
     * We add our {@link JwtAuthenticationFilter} BEFORE Spring's built-in
     * {@link UsernamePasswordAuthenticationFilter}. This ensures our JWT check
     * runs first — if the JWT is valid, Spring's form login filter sees an
     * already-authenticated request and does nothing.
     *
     * @param http the HttpSecurity builder — provided by Spring Security
     * @return the configured SecurityFilterChain
     * @throws Exception if any configuration step fails (Spring Security API requirement)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ── 1. Disable CSRF (not needed for stateless JWT REST APIs) ──────
            .csrf(AbstractHttpConfigurer::disable)

            // ── 2. Configure CORS ─────────────────────────────────────────────
            // Uses the CorsConfigurationSource bean defined below.
            // CORS is enforced before security checks — preflight OPTIONS requests
            // must pass through without requiring a JWT.
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // ── 3. Session Management: STATELESS ──────────────────────────────
            // Spring Security will NEVER create or use an HttpSession.
            // Each request is fully self-contained via the JWT token.
            // No session = no session fixation vulnerabilities.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // ── 4. Custom 401 Error Handler ───────────────────────────────────
            // Tell Spring Security to use our AuthEntryPointJwt for 401 responses
            // instead of the default HTML error page.
            .exceptionHandling(exceptions ->
                exceptions.authenticationEntryPoint(authEntryPointJwt))

            // ── 5. Authorization Rules ────────────────────────────────────────
            .authorizeHttpRequests(auth -> auth

                // ─────────── PUBLIC ENDPOINTS (no token required) ─────────────

                // Auth endpoints — must be public or login/register would require
                // a token to GET a token (chicken-and-egg problem)
                .requestMatchers(HttpMethod.POST,
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh"
                ).permitAll()

                // Health check — for load balancers, Kubernetes liveness probes,
                // monitoring tools. Must be publicly accessible without auth.
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                // Swagger / OpenAPI docs — for development.
                // In production, you may want to restrict these to internal IPs.
                .requestMatchers(
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/v3/api-docs/**",
                    "/v3/api-docs"
                ).permitAll()

                // ─────────── ROLE-RESTRICTED ENDPOINTS ────────────────────────

                // Super Admin only — role management, platform config
                // hasRole('SUPER_ADMIN') checks for authority "ROLE_SUPER_ADMIN"
                .requestMatchers("/api/v1/admin/super/**")
                    .hasRole("SUPER_ADMIN")

                // Admin and above — user management, property approval, etc.
                // hasAnyRole() checks if the user has ANY of the listed roles.
                .requestMatchers("/api/v1/admin/**")
                    .hasAnyRole("ADMIN", "SUPER_ADMIN")

                // Owner-specific endpoints — property management, room management
                // NOTE: Additional fine-grained authorization (e.g., "only owner of
                // THIS property can manage it") is done in service layer or @PreAuthorize
                .requestMatchers("/api/v1/owner/**")
                    .hasAnyRole("OWNER", "ADMIN", "SUPER_ADMIN")

                // ─────────── CATCH-ALL: AUTHENTICATE EVERYTHING ELSE ──────────

                // Any other request MUST have a valid JWT.
                // This is a security-first default: if you forget to add an endpoint
                // to the public list, it defaults to PROTECTED — not open.
                .anyRequest().authenticated()
            )

            // ── 6. Insert JWT filter BEFORE Spring's default auth filter ──────
            // This is the critical wiring: our filter runs first.
            // If our filter sets the SecurityContext, Spring's default filter
            // (UsernamePasswordAuthenticationFilter) sees an authenticated request
            // and skips itself. Clean, no conflicts.
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // =========================================================================
    // ② PASSWORD ENCODER
    // =========================================================================

    /**
     * BCrypt password encoder — the industry standard for password hashing.
     *
     * <p><b>Why BCrypt?</b>
     * <ul>
     *   <li><b>Adaptive work factor</b>: The default cost factor (10) means hashing
     *       takes ~100ms on modern hardware. An attacker can only try ~10 passwords/second
     *       per CPU core — not millions.</li>
     *   <li><b>Built-in salt</b>: BCrypt generates a random 22-character salt per hash.
     *       Two users with the same password get DIFFERENT hashes — rainbow tables are useless.</li>
     *   <li><b>Spring native support</b>: Spring Security's AuthenticationManager
     *       uses this bean automatically for password verification.</li>
     * </ul>
     *
     * <p><b>Why strength 12 vs default 10?</b>
     * Each increment of the strength doubles the computation time:
     * <ul>
     *   <li>strength 10: ~100ms (Spring default)</li>
     *   <li>strength 12: ~400ms (better security, acceptable UX)</li>
     * </ul>
     * For a PG management system handling 100-200 logins/minute, 400ms is fine.
     * For a high-traffic app with 10,000+ logins/minute, you'd use async login + strength 10.
     *
     * <p><b>Without this bean</b>: Spring Security has no idea how to encode
     * or verify passwords. Every {@code passwordEncoder.encode()} call in
     * AuthService would throw a {@code NoSuchBeanDefinitionException}.
     *
     * @return a BCryptPasswordEncoder with strength 12
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    // =========================================================================
    // ③ AUTHENTICATION MANAGER
    // =========================================================================

    /**
     * Exposes Spring Security's AuthenticationManager as a bean.
     *
     * <p><b>What is AuthenticationManager?</b>
     * The central hub for authentication. When you call
     * {@code authManager.authenticate(token)}, it delegates to the appropriate
     * {@code AuthenticationProvider} (in our case, {@code DaoAuthenticationProvider},
     * which uses {@link com.smartpg.security.UserDetailsServiceImpl} + BCrypt).
     *
     * <p><b>Why expose it as a @Bean?</b>
     * Spring Security creates an AuthenticationManager internally, but it's not
     * a Spring bean by default — you can't inject it with @Autowired.
     * We expose it here so that {@link com.smartpg.module.user.service.AuthService}
     * can inject and use it directly if needed (e.g., when switching to
     * AuthenticationManager-based login in the future).
     *
     * <p><b>Current usage</b>: In our AuthService, we do manual credential verification
     * (userRepo.findByEmail + passwordEncoder.matches). This bean is here for
     * completeness and future-proofing. If you refactor to use
     * {@code authManager.authenticate()}, you won't need a config change.
     *
     * @param authConfig Spring's auto-configured AuthenticationConfiguration
     * @return the application's AuthenticationManager
     * @throws Exception if the AuthenticationManager can't be built
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig)
            throws Exception {
        return authConfig.getAuthenticationManager();
    }

    // =========================================================================
    // ④ CORS CONFIGURATION
    // =========================================================================

    /**
     * CORS (Cross-Origin Resource Sharing) configuration.
     *
     * <p><b>What is CORS?</b>
     * Browsers enforce the "Same-Origin Policy" — JavaScript on http://localhost:3000
     * cannot call http://localhost:8080 unless the SERVER explicitly allows it
     * via CORS headers.
     *
     * <p><b>Real-world scenario</b>:
     * Your React frontend runs at http://localhost:5173 (Vite dev server).
     * Your Spring Boot backend runs at http://localhost:8080.
     * Without CORS config:
     * <pre>
     *   ❌ Access to fetch at 'http://localhost:8080/api/v1/auth/login' from origin
     *      'http://localhost:5173' has been blocked by CORS policy
     * </pre>
     * With CORS config:
     * <pre>
     *   ✅ Request passes — server sends: Access-Control-Allow-Origin: http://localhost:5173
     * </pre>
     *
     * <p><b>Production CRITICAL</b>: NEVER use {@code allowedOrigins("*")} in production.
     * Use specific frontend domain URLs. Wildcard origins disable cookie-based auth and
     * are a security risk.
     *
     * <p><b>Preflight requests</b>: Browsers send an HTTP OPTIONS request before any
     * cross-origin POST/PUT/DELETE to "ask permission". We set {@code allowedMethods}
     * to include OPTIONS so these preflight requests are allowed through.
     *
     * @return the CORS configuration source for the entire application
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // ── Allowed Origins ────────────────────────────────────────────────────
        // List every URL where your frontend runs.
        // In production: replace with your actual deployed frontend URLs.
        // NEVER use "*" with allowCredentials(true) — that combination is rejected
        // by browsers per the CORS spec.
        config.setAllowedOrigins(List.of(
            "http://localhost:3000",   // Create React App default
            "http://localhost:5173",   // Vite dev server default
            "http://localhost:4200"    // Angular CLI default (in case of future migration)
        ));

        // ── Allowed HTTP Methods ───────────────────────────────────────────────
        // All standard REST methods + OPTIONS for CORS preflight.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        // ── Allowed Headers ────────────────────────────────────────────────────
        // "*" here means "allow any header the client sends".
        // Critical headers we use:
        //   - Authorization: Bearer <token> (JWT)
        //   - Content-Type: application/json
        config.setAllowedHeaders(List.of("*"));

        // ── Expose Headers ────────────────────────────────────────────────────
        // Headers the browser JavaScript can read from the response.
        // By default, browsers hide most response headers from JS.
        // We expose Authorization so clients can read tokens from response headers
        // if we ever use that pattern.
        config.setExposedHeaders(List.of("Authorization"));

        // ── Allow Credentials ─────────────────────────────────────────────────
        // Required if you need to send cookies across origins.
        // For JWT in Authorization headers, this is technically not needed,
        // but if you ever add cookie-based refresh tokens, you'll need it.
        config.setAllowCredentials(true);

        // ── Preflight Cache Duration ──────────────────────────────────────────
        // How long (seconds) the browser caches the preflight response.
        // 3600 = 1 hour. Browser won't send OPTIONS every time for 1 hour.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply this CORS config to ALL routes ("/**")
        source.registerCorsConfiguration("/**", config);

        return source;
    }
}
