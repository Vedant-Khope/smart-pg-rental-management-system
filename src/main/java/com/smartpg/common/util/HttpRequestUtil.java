package com.smartpg.common.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Utility class for extracting useful metadata from incoming HTTP requests.
 *
 * <p><b>What is this and why does it exist?</b>
 * Every auth operation (login, register, logout, refresh) needs to log the
 * client's IP address and User-Agent string for audit trail and brute-force
 * protection purposes. Without this info, audit logs are useless — you can't
 * trace WHO did what from WHERE.
 *
 * <p>This is a pure static utility — no state, no Spring beans, no DI needed.
 * Every controller calls these methods before passing data to the service.
 *
 * <p><b>Why a separate util and not inline in the controller?</b>
 * DRY principle. Multiple controllers (AuthController, UserController) need
 * the same logic. If the IP extraction logic changes (e.g., we add a new
 * trusted proxy header), we update it in ONE place, not 10 controllers.
 *
 * <p><b>Real-world scenario — Proxy Headers:</b>
 * When your app runs behind an AWS ALB (Application Load Balancer) or an
 * Nginx reverse proxy, the raw {@code request.getRemoteAddr()} returns the
 * PROXY's IP (e.g., 10.0.1.5), not the user's real IP. The real IP is in:
 * <ul>
 *   <li>{@code X-Forwarded-For: 203.45.67.89, 10.0.1.5} (chain of IPs)</li>
 *   <li>{@code X-Real-IP: 203.45.67.89} (single IP from Nginx)</li>
 * </ul>
 * We check these headers first, falling back to {@code getRemoteAddr()} if
 * none are present (direct connection, no proxy).
 *
 * <p><b>SECURITY NOTE:</b>
 * In production, ONLY trust X-Forwarded-For from KNOWN trusted proxies.
 * A malicious client can spoof these headers. Validate against your
 * trusted proxy IPs list — out of scope for v1 but important to know.
 */
public final class HttpRequestUtil {

    // Prevent instantiation — this is a static utility class
    private HttpRequestUtil() {}

    // =========================================================================
    // ① IP ADDRESS EXTRACTION
    // =========================================================================

    /**
     * Extracts the real client IP address from the request.
     *
     * <p><b>Header check order (proxy-aware):</b>
     * <ol>
     *   <li>{@code X-Forwarded-For} — set by AWS ALB, Nginx, Cloudflare, etc.
     *       May contain a comma-separated chain: "client, proxy1, proxy2".
     *       We take the FIRST IP (the original client).</li>
     *   <li>{@code X-Real-IP} — set by Nginx directly, single IP only.</li>
     *   <li>{@code Proxy-Client-IP} — used by Apache httpd proxies.</li>
     *   <li>{@code WL-Proxy-Client-IP} — used by WebLogic proxies.</li>
     *   <li>{@code HTTP_CLIENT_IP} — non-standard but used in some setups.</li>
     *   <li>{@code HTTP_X_FORWARDED_FOR} — another common variant.</li>
     *   <li>{@code request.getRemoteAddr()} — the raw socket IP, fallback.</li>
     * </ol>
     *
     * <p><b>With proxy (AWS ALB):</b>
     * X-Forwarded-For: "203.45.67.89, 10.0.1.5" → returns "203.45.67.89"
     *
     * <p><b>Without proxy (local dev):</b>
     * No headers set → returns request.getRemoteAddr() → "127.0.0.1"
     *
     * @param request the incoming HTTP servlet request
     * @return the real client IP address as a string; "unknown" if extraction fails
     */
    public static String getClientIp(HttpServletRequest request) {
        // Check X-Forwarded-For first (most common in cloud environments)
        String ip = request.getHeader("X-Forwarded-For");
        if (isValidIp(ip)) {
            // X-Forwarded-For can be a chain: "client, proxy1, proxy2"
            // The client's real IP is always the FIRST one in the list
            return ip.split(",")[0].trim();
        }

        ip = request.getHeader("X-Real-IP");
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("Proxy-Client-IP");
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("WL-Proxy-Client-IP");
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("HTTP_CLIENT_IP");
        if (isValidIp(ip)) return ip;

        ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        if (isValidIp(ip)) {
            return ip.split(",")[0].trim();
        }

        // Fallback: the direct socket connection IP (no proxy in between)
        String remoteAddr = request.getRemoteAddr();
        return StringUtils.hasText(remoteAddr) ? remoteAddr : "unknown";
    }

    // =========================================================================
    // ② USER-AGENT EXTRACTION
    // =========================================================================

    /**
     * Extracts the User-Agent string from the request header.
     *
     * <p>The User-Agent tells us what client is making the request:
     * <ul>
     *   <li>Browser: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537..."</li>
     *   <li>Mobile app: "SmartPG-Android/2.1.0 (Pixel 7; Android 14)"</li>
     *   <li>Postman: "PostmanRuntime/7.36.0"</li>
     *   <li>curl: "curl/7.88.1"</li>
     * </ul>
     *
     * <p><b>Why we log this:</b>
     * <ul>
     *   <li>Security: A user's account is suddenly being accessed from
     *       "python-requests/2.28" at 3am from a Russian IP → suspicious.</li>
     *   <li>UX: We can show the user "Active sessions: Chrome on Windows".</li>
     *   <li>Debugging: "This 500 error only happens on Safari/iOS" → UA helps reproduce.</li>
     * </ul>
     *
     * @param request the incoming HTTP servlet request
     * @return the User-Agent string, or "unknown" if not present
     */
    public static String getUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        return StringUtils.hasText(userAgent) ? userAgent : "unknown";
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    /**
     * Checks if an IP header value is valid (non-null, non-empty, not "unknown").
     *
     * <p>Some proxies set the header to the literal string "unknown" when they
     * can't determine the IP. We treat that as invalid and fall through to the
     * next header in the chain.
     *
     * @param ip the IP string from a header (may be null or "unknown")
     * @return true if this is a usable IP value
     */
    private static boolean isValidIp(String ip) {
        return StringUtils.hasText(ip) && !"unknown".equalsIgnoreCase(ip);
    }
}
