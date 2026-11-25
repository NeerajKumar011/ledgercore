package com.ledgercore.api.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet filter managing request identification and correlation.
 *
 * <p>Validates incoming X-Request-Id to prevent log injection (^[\w.-]{1,64}$),
 * binds to MDC and request attributes, echoes back in response headers, and
 * records a single structured access log entry upon completion without logging bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";

    private static final Pattern VALID_REQUEST_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId;

        if (incoming != null && VALID_REQUEST_ID_PATTERN.matcher(incoming).matches()) {
            requestId = incoming;
        } else {
            requestId = UUID.randomUUID().toString();
        }

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms) [requestId={}]",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs, requestId);
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }
}
