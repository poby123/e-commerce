-- W3C trace context of the request/message that wrote the row; Debezium copies it into a Kafka header so the
-- consumer's span continues the same trace. '' when there was no span.
ALTER TABLE outbox ADD COLUMN traceparent VARCHAR(64) NOT NULL DEFAULT '' AFTER payload;
