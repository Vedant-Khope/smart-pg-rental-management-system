package com.smartpg.security.jwt;

import com.smartpg.module.user.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Stateless utility service for generating, signing, and validating JWT access tokens.
 *
 * <p><b>What is this?</b>
 * Think of JwtService as the "stamp authority" at a concert. When you enter,
 * they stamp your hand with a UV pattern (issue a token). At every subsequent
 * area (API endpoint), a bouncer shines a UV light (validates the token) and
 * lets you through — no need to ask the stamp desk again for every door.
 *
 * <p><b>HS256 (HMAC-SHA256)</b>:
 * Our JWT is signed with a symmetric secret key using HS256. Both signing and
 * verification use the SAME secret key — meaning only our server can create
 * AND verify these tokens. This is simpler than RS256 (which uses a private/public key pair)
 * and sufficient for a single-server architecture. If we ever go microservices,
 * we'd switch to RS256 so each service only needs the public key.
 *
 * <p><b>What is a JWT structure?</b>
 * <pre>
 *   HEADER.PAYLOAD.SIGNATURE
 *   ↓
 *   eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJ1c2VySWQiLCJyb2xlIjoiVEVOQU5UIn0 . SomeHmacSignature
 *   [header:base64]       [payload:base64 — your claims]                    [HMAC-SHA256 signature]
 * </pre>
 * The payload (claims) are BASE64 decoded — NOT encrypted. Anyone with the token
 * can read the claims. But they CANNOT forge a valid signature without the secret key.
 * → NEVER put sensitive data (Aadhaar, PAN, password) in JWT claims.
 *
 * <p><b>Token lifetime strategy</b>:
 * <ul>
 *   <li>Access token: 15 minutes — short so a stolen token quickly becomes useless.</li>
 *   <li>Refresh token: 30 days — stored in DB, revocable on logout.</li>
 * </ul>
 *
 * <p><b>Claims we include</b>:
 * <ul>
 *   <li>{@code sub} (subject) — the user's UUID (standard JWT claim)</li>
 *   <li>{@code role} — the user's role, so the auth filter doesn't need a DB call per request</li>
 *   <li>{@code email} — the user's email for quick display on the frontend</li>
 *   <li>{@code iat} — issued at (standard)</li>
 *   <li>{@code exp} — expiry (standard)</li>
 * </ul>
 *
 * <p><b>@Component vs @Service</b>: We use {@code @Component} because this is a
 * stateless utility, not a business-logic service. No transaction, no repo calls.
 * Either annotation would technically work since both register the bean — this
 * is purely semantic convention.
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    // =========================================================================
    // Custom claim keys — stored in JWT payload
    // =========================================================================

    /** JWT claim key for the user's role. Value: e.g., "TENANT", "OWNER". */
    public static final String CLAIM_ROLE  = "role";

    /** JWT claim key for the user's email. */
    public static final String CLAIM_EMAIL = "email";

    // =========================================================================
    // Configuration from application.properties
    // =========================================================================

    /**
     * The HMAC-SHA256 secret key used to sign all JWTs.
     *
     * <p><b>Minimum length for HS256</b>: 256 bits = 32 characters minimum.
     * In production, use a 64+ character random string from a secrets manager
     * (AWS Secrets Manager, Vault, GCP Secret Manager) — NEVER hardcode here.
     *
     * <p><b>In application.properties</b>:
     * <pre>
     *   jwt.secret=your-super-secret-key-that-is-at-least-256-bits-long-keep-it-safe
     *   jwt.access-token-expiration-ms=900000    # 15 minutes
     * </pre>
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * Access token lifetime in milliseconds.
     * Default: 900000 ms = 15 minutes.
     * Configured via {@code jwt.access-token-expiration-ms} in application.properties.
     */
    @Value("${jwt.access-token-expiration-ms:900000}")
    private long accessTokenExpirationMs;

    // =========================================================================
    // Key Generation
    // =========================================================================

    /**
     * Derives the HMAC-SHA256 signing key from the configured secret string.
     *
     * <p>Called internally on every sign/verify operation.
     * {@code Keys.hmacShaKeyFor()} derives a proper {@link SecretKey} from raw bytes,
     * and JJWT validates that it's long enough for HS256 (32 bytes minimum).
     *
     * <p>Why not cache this as a field? Because {@code jwtSecret} is injected
     * after construction (Spring @Value injection). If we compute the key in the
     * constructor, it would be null. We could use @PostConstruct — but recomputing
     * on each call is negligible performance overhead for a security-critical path.
     * A @PostConstruct-cached version would be the optimized production approach.
     *
     * @return a {@link SecretKey} suitable for HS256 signing
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Token Generation
    // =========================================================================

    /**
     * Generate a signed JWT access token for the given user.
     *
     * <p><b>What it does step by step</b>:
     * <ol>
     *   <li>Builds a claims map: sub=userId, role=TENANT, email=raj@gmail.com</li>
     *   <li>Calculates expiry: now + 15 minutes</li>
     *   <li>Signs with HMAC-SHA256 using our secret key</li>
     *   <li>Serializes to compact JWT string: HEADER.PAYLOAD.SIGNATURE</li>
     * </ol>
     *
     * <p><b>Real-world scenario</b>:
     * Raj logs in at 10:00 AM. This method runs, returns a JWT valid until 10:15 AM.
     * At 10:14, Raj hits the /profile endpoint. The filter calls {@link #validateToken}
     * → valid → request proceeds. At 10:16, Raj hits an endpoint again → filter calls
     * {@link #validateToken} → {@code ExpiredJwtException} → 401 response. Client
     * calls /auth/refresh with the refresh token to get a new access token.
     *
     * @param userId the UUID of the user (stored as JWT subject)
     * @param email  the user's email (stored as a custom claim)
     * @param role   the user's role (stored as a custom claim)
     * @return a compact JWT string ready to be sent to the client
     */
    public String generateAccessToken(UUID userId, String email, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(accessTokenExpirationMs);

        return Jwts.builder()
                .subject(userId.toString())                     // Standard 'sub' claim: who this token is for
                .claim(CLAIM_EMAIL, email)                      // Custom claim: user's email
                .claim(CLAIM_ROLE,  role.name())                // Custom claim: role as string ("TENANT")
                .issuedAt(Date.from(now))                       // Standard 'iat' claim: when issued
                .expiration(Date.from(expiry))                  // Standard 'exp' claim: when it dies
                .signWith(getSigningKey())                       // Signs with HMAC-SHA256 (HS256 by default)
                .compact();                                     // Serializes to HEADER.PAYLOAD.SIGNATURE
    }

    // =========================================================================
    // Token Validation & Parsing
    // =========================================================================

    /**
     * Validates a JWT string and returns all its claims if valid.
     *
     * <p>This method is called by the JWT filter on EVERY incoming request.
     * It verifies:
     * <ol>
     *   <li>The signature is valid (wasn't tampered with)</li>
     *   <li>The token isn't expired (exp claim &gt; now)</li>
     *   <li>The token is structurally valid (3 base64 parts, valid JSON payload)</li>
     * </ol>
     *
     * <p><b>What happens if invalid?</b> Throws {@link JwtException} (parent of
     * {@link ExpiredJwtException}, {@code MalformedJwtException}, {@code SignatureException}).
     * The JWT filter catches this and returns 401.
     *
     * @param token the compact JWT string from the Authorization header
     * @return the parsed {@link Claims} containing sub, role, email, exp, iat
     * @throws JwtException if the token is invalid, expired, or tampered
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())      // Set the key to verify the signature
                .build()
                .parseSignedClaims(token)         // Parse + verify in one step
                .getPayload();                    // Get the claims from the verified token
    }

    /**
     * Validates a JWT token and returns {@code true} if it's valid and not expired.
     *
     * <p>This is a convenience boolean wrapper around {@link #parseToken} for cases
     * where you just need a yes/no answer (e.g., the JWT filter's main check).
     *
     * <p><b>With this method</b>: The filter does {@code if (jwtService.isValid(token)) {...}}
     * <br><b>Without it</b>: The filter would need a try/catch in the middle of its flow.
     * Extracting the try/catch here keeps the filter clean.
     *
     * @param token the JWT string to validate
     * @return {@code true} if the token is structurally valid and not expired
     */
    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return false;
        } catch (JwtException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Claim Extractors — called by the JWT filter to populate SecurityContext
    // =========================================================================

    /**
     * Extract the user UUID from the 'sub' (subject) claim.
     *
     * <p>Used by the JWT filter to identify the authenticated user without
     * making a database call on every request. The UUID is then stored in
     * the {@link org.springframework.security.core.Authentication} principal.
     *
     * @param token the validated JWT string
     * @return the user's UUID parsed from the subject claim
     */
    public UUID extractUserId(String token) {
        return UUID.fromString(parseToken(token).getSubject());
    }

    /**
     * Extract the user's email from the 'email' custom claim.
     *
     * @param token the validated JWT string
     * @return the user's email address
     */
    public String extractEmail(String token) {
        return parseToken(token).get(CLAIM_EMAIL, String.class);
    }

    /**
     * Extract the user's role from the 'role' custom claim.
     *
     * @param token the validated JWT string
     * @return the user's {@link Role}
     */
    public Role extractRole(String token) {
        String roleStr = parseToken(token).get(CLAIM_ROLE, String.class);
        return Role.valueOf(roleStr);
    }

    /**
     * Get the configured access token expiration in seconds (for the AuthResponse payload).
     *
     * <p>The frontend uses this for countdown timers or to know when to proactively
     * refresh the token (e.g., refresh at expiresIn - 60 seconds).
     *
     * @return token expiry duration in seconds
     */
    public long getExpiresInSeconds() {
        return accessTokenExpirationMs / 1000;
    }
}
