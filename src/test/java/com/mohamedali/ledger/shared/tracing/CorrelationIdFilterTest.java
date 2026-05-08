package com.mohamedali.ledger.shared.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesCorrelationIdWhenMissing_andCleansMdcAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/platform/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcSeenInsideChain = new AtomicReference<>();

        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            mdcSeenInsideChain.set(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID));
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilter(request, response, chain);

        String responseCorrelationId = response.getHeader(TracingHeaders.CORRELATION_ID);
        assertThat(responseCorrelationId).isNotBlank();
        assertThat(mdcSeenInsideChain.get()).isEqualTo(responseCorrelationId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void propagatesIncomingCorrelationAndTraceHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/platform/ping");
        request.addHeader(TracingHeaders.CORRELATION_ID, "corr-123");
        request.addHeader(TracingHeaders.TRACEPARENT, "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        request.addHeader(TracingHeaders.TRACESTATE, "vendor=value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TracingHeaders.CORRELATION_ID)).isEqualTo("corr-123");
        assertThat(response.getHeader(TracingHeaders.TRACEPARENT))
                .isEqualTo("00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        assertThat(response.getHeader(TracingHeaders.TRACESTATE)).isEqualTo("vendor=value");
    }
}

