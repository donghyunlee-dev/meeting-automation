package com.meetingautomation.api.config;

import com.meetingautomation.api.error.ApiError;
import com.meetingautomation.api.error.ApiErrorResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Reject setup mutation origins before MVC's CORS processor, retaining the common error envelope. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class DocumentSetupOriginFilter extends OncePerRequestFilter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Set<String> origins;
    public DocumentSetupOriginFilter(@Value("${ALLOWED_ORIGINS:}") String origins) {
        this.origins = new HashSet<>(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if ("POST".equals(request.getMethod()) && path.startsWith("/api/v1/document-setup/")
                && !origins.contains(request.getHeader("Origin"))) {
            String trace = request.getHeader("X-Request-Id");
            if (trace == null || trace.isBlank()) trace = "tr_" + UUID.randomUUID();
            response.setStatus(403);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(JSON.writeValueAsString(new ApiErrorResponse(new ApiError(
                "DOCUMENT_SETUP_ORIGIN_REJECTED", "문서 연결 설정을 확인해 주세요.", "CONFLICT", false, trace, Map.of()))));
            return;
        }
        chain.doFilter(request, response);
    }
}
