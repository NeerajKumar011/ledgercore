package com.ledgercore.api.filter;

import com.ledgercore.api.error.PayloadTooLargeException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Early filter rejecting request bodies exceeding 16 KB (16,384 bytes) with HTTP 413.
 * Rejects either by declared Content-Length or during streaming without buffering the payload.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 16 * 1024; // 16 KB

    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            send413(request, response);
            return;
        }

        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
            BoundedRequestWrapper wrappedRequest = new BoundedRequestWrapper(request, MAX_BODY_BYTES);
            try {
                filterChain.doFilter(wrappedRequest, response);
            } catch (Exception e) {
                if (hasPayloadTooLargeCause(e)) {
                    if (!response.isCommitted()) {
                        send413(request, response);
                        return;
                    }
                }
                if (e instanceof ServletException se) {
                    throw se;
                } else if (e instanceof IOException ioe) {
                    throw ioe;
                } else if (e instanceof RuntimeException re) {
                    throw re;
                } else {
                    throw new ServletException(e);
                }
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private static boolean hasPayloadTooLargeCause(Throwable t) {
        while (t != null) {
            if (t instanceof PayloadTooLargeException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void send413(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Request payload exceeds maximum permitted size of " + MAX_BODY_BYTES + " bytes"
        );
        problem.setTitle("Payload Too Large");
        problem.setInstance(URI.create(request.getRequestURI()));
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }

        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private static class BoundedRequestWrapper extends HttpServletRequestWrapper {
        private final long maxBytes;
        private BoundedServletInputStream boundedInputStream;
        private BufferedReader reader;

        public BoundedRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (boundedInputStream == null) {
                boundedInputStream = new BoundedServletInputStream(super.getInputStream(), maxBytes);
            }
            return boundedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
            return reader;
        }
    }

    private static class BoundedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long bytesRead = 0;

        public BoundedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                bytesRead++;
                if (bytesRead > maxBytes) {
                    throw new PayloadTooLargeException("Request body exceeds maximum permitted size of " + maxBytes + " bytes");
                }
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read != -1) {
                bytesRead += read;
                if (bytesRead > maxBytes) {
                    throw new PayloadTooLargeException("Request body exceeds maximum permitted size of " + maxBytes + " bytes");
                }
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
