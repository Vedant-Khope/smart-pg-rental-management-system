package com.smartpg.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartpg.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Handles unauthenticated requests — fires when someone hits a protected
 * endpoint WITHOUT a valid JWT (or with NO JWT at all).
 *
 * <p><b>What is this?</b>
 * Think of it as the "GATE CLOSED" sign at a concert venue. When someone tries
 * to walk in without a ticket (JWT), instead of just slamming the door, this
 * class sends a polite but firm JSON response explaining WHY they were denied —
 * with the correct HTTP status (401 Unauthorized).
 *
 * <p><b>Why is this needed?</b>
 * By default, Spring Security returns an HTML error page for unauthorized access.
 * Our frontend (React) expects JSON — not HTML. Without this class, your
 * frontend would receive an HTML blob and crash trying to parse it as JSON.
 *
 * <p><b>401 vs 403 — the critical difference:</b>
 * <ul>
 *   <li><b>401 Unauthorized</b> → "I don't know who you are." (No/invalid token)
 *       → Handled HERE by {@link AuthEntryPointJwt}</li>
 *   <li><b>403 Forbidden</b> → "I know who you are, but you don't have permission."
 *       (Valid token but wrong role) → Handled by Spring's default AccessDeniedHandler
 *       (which we customize in SecurityConfig)</li>
 * </ul>
 *
 * <p><b>When is this called?</b>
 * Spring Security calls {@code commence()} when:
 * <ol>
 *   <li>The request has no Authorization header at all.</li>
 *   <li>The JWT filter sets NO SecurityContext (token was missing or invalid).</li>
 *   <li>Spring Security tries to authenticate the request and fails with
 *       {@link AuthenticationException}.</li>
 * </ol>
 *
 * <p><b>Flow:</b>
 * <pre>
 *   Request (no JWT) → JwtAuthenticationFilter (skips) → Security checks →
 *   AuthenticationException → AuthEntryPointJwt.commence() → 401 JSON response
 * </pre>
 *
 * <p><b>@Component</b>: Registers this as a Spring bean so we can inject it
 * into {@link SecurityConfig} via constructor injection.
 */
@Component
public class AuthEntryPointJwt implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(AuthEntryPointJwt.class);

    // ObjectMapper created directly — not injected via constructor.
    // Why? spring-boot-starter-webmvc does not auto-register ObjectMapper as a
    // named Spring bean the same way spring-boot-starter-web does.
    // For this class's single responsibility (writing a 401 JSON response),
    // a plain new ObjectMapper() is perfectly correct and avoids bean wiring issues.
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Writes a clean 401 JSON response when authentication is missing or invalid.
     *
     * <p><b>Why not just throw an exception here?</b>
     * By the time this is called, we're past the controller layer. Exceptions
     * thrown here won't be caught by {@code @ControllerAdvice} — they'd result
     * in an unhandled error. We must write the response directly to
     * {@link HttpServletResponse}.
     *
     * <p><b>Without this method</b>: Spring Security returns a default HTML page
     * saying "Full authentication is required to access this resource" — useless
     * for a REST API client.
     *
     * <p><b>With this method</b>: The client receives:
     * <pre>
     *   HTTP 401 Unauthorized
     *   Content-Type: application/json
     *   {
     *     "success": false,
     *     "message": "Authentication required. Please log in.",
     *     "data": null,
     *     "timestamp": "2026-07-31T18:00:00Z"
     *   }
     * </pre>
     *
     * @param request       the incoming HTTP request that triggered the auth failure
     * @param response      the outgoing HTTP response — we write the JSON to this
     * @param authException the exception explaining why auth failed (message often
     *                      contains "Full authentication is required to access this resource")
     * @throws IOException if writing to the response stream fails
     */
    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        // Log at WARN level — not ERROR — because this is a client-side auth issue,
        // not a server-side bug. ERROR logs should only be for unexpected server failures.
        log.warn("Unauthorized request to [{}]: {}", request.getRequestURI(), authException.getMessage());

        // Set the HTTP status code
        response.setStatus(HttpStatus.UNAUTHORIZED.value());

        // Tell the client we're sending JSON — not HTML
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        // Build a consistent ApiResponse (same format as every other API response)
        // We use the static error factory method from our shared ApiResponse class
        ApiResponse<Void> body = ApiResponse.error("Authentication required. Please provide a valid JWT token.");

        // Write the JSON body directly to the HTTP response stream
        // ObjectMapper serializes the ApiResponse object to JSON string
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
