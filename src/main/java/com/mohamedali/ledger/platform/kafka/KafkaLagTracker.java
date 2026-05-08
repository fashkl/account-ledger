package com.mohamedali.ledger.platform.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class KafkaLagTracker {

    private final AtomicLong lagSeconds = new AtomicLong(0);

    public KafkaLagTracker(MeterRegistry meterRegistry) {
        meterRegistry.gauge("kafka_consumer_lag_seconds", lagSeconds);
    }

    public void updateLagSeconds(long seconds) {
        lagSeconds.set(Math.max(0, seconds));
    }
}
