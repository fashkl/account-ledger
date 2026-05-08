package com.mohamedali.ledger.shared.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_CORRELATION_ID = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(TracingHeaders.CORRELATION_ID);
        if (!StringUtils.hasText(correlationId)) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_CORRELATION_ID, correlationId);
        response.setHeader(TracingHeaders.CORRELATION_ID, correlationId);

        copyHeaderIfPresent(request, response, TracingHeaders.TRACEPARENT);
        copyHeaderIfPresent(request, response, TracingHeaders.TRACESTATE);
        copyHeaderIfPresent(request, response, TracingHeaders.EVENT_ID);
        copyHeaderIfPresent(request, response, TracingHeaders.REFERENCE_ID);
        copyHeaderIfPresent(request, response, TracingHeaders.CUSTOMER_ID);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private static void copyHeaderIfPresent(HttpServletRequest request,
                                            HttpServletResponse response,
                                            String headerName) {
        String value = request.getHeader(headerName);
        if (StringUtils.hasText(value)) {
            response.setHeader(headerName, value);
        }
    }
}

