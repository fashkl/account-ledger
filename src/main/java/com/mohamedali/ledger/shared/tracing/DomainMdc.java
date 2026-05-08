package com.mohamedali.ledger.shared.tracing;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

public final class DomainMdc {

    public static final String EVENT_ID = "eventId";
    public static final String REFERENCE_ID = "referenceId";
    public static final String ENTRY_GROUP_ID = "entryGroupId";
    public static final String CUSTOMER_ID = "customerId";

    private DomainMdc() {
    }

    public static void putIfPresent(String key, UUID value) {
        if (value != null) {
            MDC.put(key, value.toString());
        }
    }

    public static void putIfPresent(String key, String value) {
        if (StringUtils.hasText(value)) {
            MDC.put(key, value);
        }
    }

    public static void clearDomainKeys() {
        MDC.remove(EVENT_ID);
        MDC.remove(REFERENCE_ID);
        MDC.remove(ENTRY_GROUP_ID);
        MDC.remove(CUSTOMER_ID);
    }
}

