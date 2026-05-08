package com.mohamedali.ledger.platform.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class KafkaLagTracker {

    private final AtomicLong lagRecords = new AtomicLong(0);
    private final AtomicLong messageStalenessSeconds = new AtomicLong(0);

    public KafkaLagTracker(MeterRegistry meterRegistry) {
        meterRegistry.gauge("kafka_consumer_lag_records", lagRecords);
        meterRegistry.gauge("kafka_consumer_message_staleness_seconds", messageStalenessSeconds);
    }

    public void updateLagRecords(long records) {
        lagRecords.set(Math.max(0, records));
    }

    public void updateMessageStalenessSeconds(long seconds) {
        messageStalenessSeconds.set(Math.max(0, seconds));
    }
}
