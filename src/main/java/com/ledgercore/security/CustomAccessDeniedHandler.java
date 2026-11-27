package com.ledgercore.security;

import com.ledgercore.api.filter.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Returns RFC 9457 ProblemDetail on 403 Forbidden errors.
 */
@Component
public class CustomAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ledgercore.audit.AuditService auditService;

    public CustomAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String requestId = (String) request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        if (requestId == null) {
            requestId = MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY);
        }

        if (auditService != null) {
            java.util.UUID actorUserId = null;
            String actorRole = null;
            org.springframework.security.core.Authentication auth =
                    org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof org.springframework.security.oauth2.jwt.Jwt jwt) {
                try {
                    actorUserId = java.util.UUID.fromString(jwt.getSubject());
                    actorRole = jwt.getClaimAsString("role");
                } catch (Exception ignored) {}
            }
            auditService.recordAuth(
                    "ACCESS_DENIED",
                    "DENIED",
                    actorUserId,
                    actorRole,
                    "ENDPOINT",
                    request.getRequestURI(),
                    null
            );
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Access denied: insufficient permissions to access this resource");
        problem.setTitle("Forbidden");
        problem.setInstance(URI.create(request.getRequestURI()));
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
