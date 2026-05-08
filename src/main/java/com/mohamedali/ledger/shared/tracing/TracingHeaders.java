package com.mohamedali.ledger.shared.tracing;

/**
 * Canonical tracing/correlation headers used by this service.
 *
 * <p>Contract:
 * - {@code X-Correlation-Id} must be propagated across all synchronous and asynchronous hops.
 * - W3C trace context headers are preserved when present.
 * - Event metadata headers are optional but recommended for message-driven flows.
 */
public final class TracingHeaders {

    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String EVENT_ID = "X-Event-Id";
    public static final String REFERENCE_ID = "X-Reference-Id";
    public static final String CUSTOMER_ID = "X-Customer-Id";

    private TracingHeaders() {
    }
}

