#!/bin/sh
# PUT .../config creates the connector or updates it, so re-running is harmless.
# Retried: right after a restart Kafka Connect may still be rebalancing and refuse requests (409/500)
# even though its REST endpoint already answers the health check.
set -e
for i in $(seq 1 30); do
  if curl -fsS -X PUT -H 'Content-Type: application/json' \
      --data @/debezium/outbox-connector.json \
      http://connect:8083/connectors/outbox-connector/config > /dev/null; then
    for j in $(seq 1 30); do
      if curl -fsS http://connect:8083/connectors/outbox-connector/status | grep -q '"tasks":\[{"id":0,"state":"RUNNING"'; then
        echo "outbox-connector RUNNING"
        exit 0
      fi
      sleep 2
    done
  fi
  echo "connector not ready (attempt $i), retrying"
  sleep 2
done
exit 1
