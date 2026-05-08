# Kafka Consumer Lag Escalation Runbook

1. Check `kafka_consumer_lag_seconds` and partition skew.
2. Verify circuit breaker state and listener paused status.
3. If paused due to breaker, remediate root cause and resume listener.
4. Scale consumers and rebalance partitions if lag persists.
5. Confirm DLQ growth is stable and no duplicate postings observed.
