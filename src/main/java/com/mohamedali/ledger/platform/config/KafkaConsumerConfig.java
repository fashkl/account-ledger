package com.mohamedali.ledger.platform.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> ledgerKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setPollTimeout(Duration.ofSeconds(2).toMillis());
        return factory;
    }

    @Bean
    public CommonErrorHandler commonErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                 @Value("${ledger.kafka.topic.dlq:ledger-events.v1.dlq}") String dlqTopic,
                                                 MeterRegistry meterRegistry,
                                                 ObjectMapper objectMapper) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> {
                    meterRegistry.counter("kafka_dlq_published_total").increment();
                    return new TopicPartition(dlqTopic, record.partition());
                });
        recoverer.setHeadersFunction((record, exception) -> {
            RecordHeaders headers = new RecordHeaders();
            headers.add(new RecordHeader("x-dlq-reason",
                    exception.getClass().getSimpleName().getBytes(StandardCharsets.UTF_8)));
            headers.add(new RecordHeader("x-dlq-message",
                    String.valueOf(exception.getMessage()).getBytes(StandardCharsets.UTF_8)));
            headers.add(new RecordHeader("x-dlq-stack-hash",
                    stackHash(exception).getBytes(StandardCharsets.UTF_8)));
            String eventId = resolveEventId(record, objectMapper);
            if (eventId != null) {
                headers.add(new RecordHeader("x-event-id", eventId.getBytes(StandardCharsets.UTF_8)));
            }
            return headers;
        });

        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(3);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                LOG.warn("Kafka processing retry attempt={} topic={} partition={} offset={} error={}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(), ex.toString()));
        return errorHandler;
    }

    private static String resolveEventId(ConsumerRecord<?, ?> record, ObjectMapper objectMapper) {
        Object value = record.value();
        if (!(value instanceof String json)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode eventId = root.get("eventId");
            return eventId == null || eventId.isNull() ? null : eventId.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stackHash(Exception exception) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = exception.getClass().getName() + ":" + String.valueOf(exception.getMessage());
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return "sha256-unavailable";
        }
    }
}
