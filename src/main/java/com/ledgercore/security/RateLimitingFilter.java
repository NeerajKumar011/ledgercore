package com.ledgercore.security;

import com.ledgercore.api.filter.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory, bounded-memory per-IP rate limiter protecting /auth/login, /auth/register, and /auth/refresh.
 *
 * <p>Runs early in the filter chain before Spring Security and any BCrypt hashing to prevent CPU exhaustion.
 * Uses request.getRemoteAddr() only; X-Forwarded-For is explicitly ignored as it can be spoofed by untrusted clients.
 * Memory is strictly bounded to MAX_TRACKED_ENTRIES with automatic eviction of expired 60-second windows.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class RateLimitingFilter extends OncePerRequestFilter {

    public static final int MAX_TRACKED_ENTRIES = 10_000;
    public static final long WINDOW_SECONDS = 60;

    private int loginLimit;
    private int registerLimit;
    private int refreshLimit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger();

    public record Window(long windowStartEpochSec, int count) {}

    public RateLimitingFilter(
            @Value("${ledgercore.security.rate-limit.login:10}") int loginLimit,
            @Value("${ledgercore.security.rate-limit.register:5}") int registerLimit,
            @Value("${ledgercore.security.rate-limit.refresh:20}") int refreshLimit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.loginLimit = loginLimit;
        this.registerLimit = registerLimit;
        this.refreshLimit = refreshLimit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        String method = request.getMethod();

        if (!"POST".equalsIgnoreCase(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        int limit;
        if ("/api/v1/auth/login".equals(uri)) {
            limit = loginLimit;
        } else if ("/api/v1/auth/register".equals(uri)) {
            limit = registerLimit;
        } else if ("/api/v1/auth/refresh".equals(uri)) {
            limit = refreshLimit;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        long nowSec = clock.instant().getEpochSecond();

        // Evict expired entries every 100 requests or when approaching capacity
        if (requestCounter.incrementAndGet() % 100 == 0 || windows.size() >= MAX_TRACKED_ENTRIES) {
            evictExpired(nowSec);
        }

        // Strictly use getRemoteAddr(); never trust X-Forwarded-For directly
        String clientIp = request.getRemoteAddr();
        String key = uri + ":" + clientIp;

        // If map is still full after eviction, prevent memory growth by dropping oldest
        if (windows.size() >= MAX_TRACKED_ENTRIES && !windows.containsKey(key)) {
            evictOldest();
        }

        Window currentWindow = windows.compute(key, (k, existing) -> {
            if (existing == null || nowSec - existing.windowStartEpochSec() >= WINDOW_SECONDS) {
                return new Window(nowSec, 1);
            }
            return new Window(existing.windowStartEpochSec(), existing.count() + 1);
        });

        if (currentWindow.count() > limit) {
            long retryAfter = Math.max(1, WINDOW_SECONDS - (nowSec - currentWindow.windowStartEpochSec()));
            send429(request, response, retryAfter);
            return;
        }

        filterChain.doFilter(request, response);
    }

    public void evictExpired(long nowSec) {
        windows.entrySet().removeIf(entry -> nowSec - entry.getValue().windowStartEpochSec() >= WINDOW_SECONDS);
    }

    private void evictOldest() {
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public int getTrackedEntriesCount() {
        return windows.size();
    }

    public void setLoginLimit(int loginLimit) {
        this.loginLimit = loginLimit;
    }

    public void setRegisterLimit(int registerLimit) {
        this.registerLimit = registerLimit;
    }

    public void setRefreshLimit(int refreshLimit) {
        this.refreshLimit = refreshLimit;
    }

    public void clear() {
        windows.clear();
    }

    private void send429(HttpServletRequest request, HttpServletResponse response, long retryAfter) throws IOException {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Please try again later."
        );
        problem.setTitle("Too Many Requests");
        problem.setInstance(URI.create(request.getRequestURI()));
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
