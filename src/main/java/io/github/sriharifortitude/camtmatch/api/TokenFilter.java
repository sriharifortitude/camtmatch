package io.github.sriharifortitude.camtmatch.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One bearer token for the whole API, from the environment. This service
 * sits inside a finance team's network behind their SSO proxy; the token
 * is the second lock, not the first. Compared in constant time. The
 * application refuses to start without one (see application.properties).
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    private final byte[] expected;

    public TokenFilter(@Value("${camtmatch.api-token}") String token) {
        if (token == null || token.length() < 32) {
            throw new IllegalStateException("camtmatch.api-token (CAMTMATCH_API_TOKEN) must be at least 32 characters");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        byte[] presented = header != null && header.startsWith("Bearer ") ? header.substring(7).getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!MessageDigest.isEqual(presented, expected)) {
            response.setStatus(401);
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"title\":\"Unauthorized\",\"status\":401}");
            return;
        }
        chain.doFilter(request, response);
    }
}
