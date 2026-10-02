package com.tradingbot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * H6: bearer-token authentication for mutating {@code /api/**} endpoints (POST/PUT/PATCH/DELETE).
 * Safe methods (GET/HEAD/OPTIONS) — health checks, dashboards — stay open.
 *
 * <p>Auth is enabled by setting {@code trading-bot.api.auth-token} (or env {@code API_AUTH_TOKEN}).
 * While the token is unset every mutating endpoint is unauthenticated; a startup WARN makes that
 * explicit rather than accidental. Accepted headers: {@code Authorization: Bearer <token>} or
 * {@code X-Api-Token: <token>}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Value("${trading-bot.api.auth-token:}")
    private String authToken;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean mutating = !SAFE_METHODS.contains(request.getMethod().toUpperCase());
        if (!mutating || !path.startsWith("/api/") || authToken == null || authToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String authorization = request.getHeader("Authorization");
        String apiToken = request.getHeader("X-Api-Token");
        boolean authorized =
                (authorization != null && ("Bearer " + authToken).equals(authorization))
                        || authToken.equals(apiToken);
        if (!authorized) {
            log.warn(
                    "[API-AUTH] Rejected {} {} from {} (missing/invalid bearer token)",
                    request.getMethod(),
                    path,
                    request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter()
                    .write(
                            "{\"error\":\"unauthorized\",\"message\":\"Missing or invalid bearer"
                                    + " token. Supply Authorization: Bearer <token> or X-Api-Token.\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    @jakarta.annotation.PostConstruct
    public void logAuthMode() {
        if (authToken == null || authToken.isBlank()) {
            log.warn(
                    "[API-AUTH] ⚠️ trading-bot.api.auth-token is NOT set — mutating /api/**"
                            + " endpoints (POST/PUT/DELETE, incl. /reset and /toggle) are"
                            + " UNAUTHENTICATED (H6). Set the property or API_AUTH_TOKEN env var"
                            + " to enforce bearer-token auth.");
        } else {
            log.info(
                    "[API-AUTH] Bearer-token auth ENABLED for mutating /api/** endpoints"
                            + " (GET endpoints remain open).");
        }
    }

    /** Visible for tests. */
    void setAuthToken(String token) {
        this.authToken = token;
    }
}
