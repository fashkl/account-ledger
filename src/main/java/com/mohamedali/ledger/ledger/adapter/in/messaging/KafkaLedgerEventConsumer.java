package com.mohamedali.ledger.ledger.adapter.in.messaging;

import com.mohamedali.ledger.shared.infra.CircuitBreakerLifecycleListener;
import com.mohamedali.ledger.shared.tracing.DomainMdc;
import io.micrometer.core.instrument.MeterRegistry;
import com.mohamedali.ledger.platform.kafka.KafkaLagTracker;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class KafkaLedgerEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaLedgerEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final LedgerEventProcessor processor;
    private final MeterRegistry meterRegistry;
    private final KafkaLagTracker lagTracker;

    public KafkaLedgerEventConsumer(ObjectMapper objectMapper,
                                    LedgerEventProcessor processor,
                                    MeterRegistry meterRegistry,
                                    KafkaLagTracker lagTracker) {
        this.objectMapper = objectMapper;
        this.processor = processor;
        this.meterRegistry = meterRegistry;
        this.lagTracker = lagTracker;
    }

    @KafkaListener(
            id = CircuitBreakerLifecycleListener.LEDGER_CONSUMER_LISTENER_ID,
            topics = "${ledger.kafka.topic.events}",
            containerFactory = "ledgerKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record,
                        Acknowledgment acknowledgment,
                        @Header(name = "kafka_receivedTopic", required = false) String topic) {
        ExternalLedgerEvent event = objectMapper.readValue(record.value(), ExternalLedgerEvent.class);
        try {
            DomainMdc.putIfPresent(DomainMdc.EVENT_ID, event.eventId());
            DomainMdc.putIfPresent(DomainMdc.REFERENCE_ID, event.referenceId());
            DomainMdc.putIfPresent(DomainMdc.CUSTOMER_ID, event.customerId());
            validatePartitionKey(record, event);

            if (event.occurredAtEpochMs() != null) {
                long lagMs = Instant.now().toEpochMilli() - event.occurredAtEpochMs();
                lagTracker.updateLagSeconds(lagMs / 1000);
            }

            processor.process(event);
            acknowledgment.acknowledge();
            meterRegistry.counter("kafka_events_processed_total", "topic", String.valueOf(topic), "type", String.valueOf(event.eventType())).increment();
        } catch (Exception ex) {
            meterRegistry.counter("kafka_events_failed_total", "topic", String.valueOf(topic)).increment();
            LOG.error("Kafka event processing failed topic={} partition={} offset={} eventId={} error={}",
                    record.topic(), record.partition(), record.offset(), event.eventId(), ex.toString(), ex);
            throw ex;
        } finally {
            DomainMdc.clearDomainKeys();
        }
    }

    private void validatePartitionKey(ConsumerRecord<String, String> record, ExternalLedgerEvent event) {
        if (event.customerId() == null) {
            throw new IllegalArgumentException("customerId is required for kafka event ordering");
        }
        if (record.key() == null) {
            throw new IllegalArgumentException("kafka key must be customerId for strict ordering");
        }
        String expected = event.customerId().toString();
        if (!expected.equals(record.key())) {
            throw new IllegalArgumentException("kafka key/customerId mismatch");
        }
    }
}
