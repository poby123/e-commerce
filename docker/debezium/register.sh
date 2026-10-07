#!/bin/sh
# PUT .../config creates the connector or updates it, so re-running is harmless.
set -e
curl -fsS -X PUT -H 'Content-Type: application/json' \
  --data @/debezium/outbox-connector.json \
  http://connect:8083/connectors/outbox-connector/config
echo
curl -fsS http://connect:8083/connectors/outbox-connector/status
echo
