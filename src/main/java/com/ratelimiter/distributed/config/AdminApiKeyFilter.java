package com.ratelimiter.distributed.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that validates the {@code X-Admin-Api-Key} header on every
 * request reaching the {@code /admin/**} path space.
 *
 * <p>The filter is registered only for {@code /admin/**} via
 * {@link SecurityConfig} — public {@code /api/**} routes never pass through it.
 *
 * <h2>Authentication flow</h2>
 * <ol>
 *   <li>Extract the {@code X-Admin-Api-Key} header value.</li>
 *   <li>Compare it (constant-time via {@link #constantTimeEquals}) to the
 *       configured key from {@code application.yaml}.</li>
 *   <li>On match: populate the {@link SecurityContextHolder} with an
 *       authenticated token and continue the filter chain.</li>
 *   <li>On mismatch / missing key: return a {@code 401} JSON response and
 *       halt the chain — the request never reaches the controller.</li>
 * </ol>
 *
 * <h2>Security note</h2>
 * <p>A constant-time comparison prevents timing-based side-channel attacks
 * where an attacker could infer the key length or prefix by measuring
 * how long the comparison takes.
 */
@Slf4j
public class AdminApiKeyFilter extends OncePerRequestFilter {

    /** HTTP header name the caller must set. */
    public static final String API_KEY_HEADER = "X-Admin-Api-Key";

    /** The role granted to a successfully authenticated admin caller. */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final String expectedApiKey;

    public AdminApiKeyFilter(String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String providedKey = request.getHeader(API_KEY_HEADER);

        if (isValid(providedKey)) {
            // Authenticate — downstream Spring Security checks will see ROLE_ADMIN
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            "admin", null,
                            List.of(new SimpleGrantedAuthority(ROLE_ADMIN)));
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
        } else {
            log.warn("Admin API key rejected — path={} remoteAddr={}",
                    request.getRequestURI(), request.getRemoteAddr());
            sendUnauthorized(response);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private boolean isValid(String provided) {
        if (provided == null || expectedApiKey == null) {
            return false;
        }
        return constantTimeEquals(provided, expectedApiKey);
    }

    /**
     * Constant-time string comparison — always iterates the full length of
     * both strings so execution time does not reveal a partial match.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            // Still iterate to avoid length-based timing leaks,
            // but we know the result is false.
            int dummy = 0;
            for (int i = 0; i < a.length(); i++) {
                dummy |= a.charAt(i) ^ 'x';
            }
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /**
     * Writes a JSON 401 response without leaking internal detail.
     * Mirrors the shape of {@link com.ratelimiter.distributed.api.dto.ErrorResponse}.
     */
    private static void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"status":401,"error":"Unauthorized","message":"Invalid or missing API key"}
                """);
    }
}
