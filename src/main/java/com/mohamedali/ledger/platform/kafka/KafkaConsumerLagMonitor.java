package com.mohamedali.ledger.platform.kafka;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KafkaConsumerLagMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerLagMonitor.class);

    private final AdminClient adminClient;
    private final String groupId;
    private final KafkaLagTracker lagTracker;

    public KafkaConsumerLagMonitor(AdminClient adminClient,
                                   @Value("${spring.kafka.consumer.group-id}") String groupId,
                                   KafkaLagTracker lagTracker) {
        this.adminClient = adminClient;
        this.groupId = groupId;
        this.lagTracker = lagTracker;
    }

    @Scheduled(fixedDelay = 5000L)
    public void updateLag() {
        try {
            ListConsumerGroupOffsetsResult offsetsResult = adminClient.listConsumerGroupOffsets(groupId);
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed = offsetsResult
                    .partitionsToOffsetAndMetadata().get();
            if (committed.isEmpty()) {
                lagTracker.updateLagRecords(0);
                return;
            }

            Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
            for (TopicPartition tp : committed.keySet()) {
                latestSpecs.put(tp, OffsetSpec.latest());
            }

            Map<TopicPartition, Long> latest = new HashMap<>();
            adminClient.listOffsets(latestSpecs).all().get()
                    .forEach((tp, info) -> latest.put(tp, info.offset()));

            long totalLag = 0;
            for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> e : committed.entrySet()) {
                long committedOffset = e.getValue().offset();
                long endOffset = latest.getOrDefault(e.getKey(), committedOffset);
                totalLag += Math.max(0, endOffset - committedOffset);
            }

            lagTracker.updateLagRecords(totalLag);
        } catch (Exception ex) {
            LOG.debug("Failed to compute kafka consumer lag for group={} error={}", groupId, ex.toString());
        }
    }
}
