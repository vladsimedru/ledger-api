#!/usr/bin/env bash
# Prints every announced transaction (ledger.transactions) with its key and headers.
# Pass --dlt to watch dead-lettered payment-settled messages instead.
set -euo pipefail
cd "$(dirname "$0")/.."

topic=ledger.transactions
[ "${1:-}" = "--dlt" ] && topic=payments.settled.DLT

docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic "$topic" --from-beginning \
  --property print.key=true --property print.headers=true
