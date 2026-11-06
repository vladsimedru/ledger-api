#!/usr/bin/env bash
# Publishes a payment-settled message to the running stack.
#   ./scripts/send-payment.sh <merchantAccountId> <amount> <currency> [paymentId] [eventId]
# Re-run with the same paymentId (and a new or the same eventId) to see redelivery deduplicated.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ $# -lt 3 ]; then
  echo "usage: $0 <merchantAccountId> <amount> <currency> [paymentId] [eventId]" >&2
  exit 1
fi

new_id() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || date +%s%N; }

account_id=$1
amount=$2
currency=$3
payment_id=${4:-pay-$(new_id)}
event_id=${5:-$(new_id)}

message=$(printf '{"eventId":"%s","paymentId":"%s","merchantAccountId":"%s","amount":"%s","currency":"%s"}' \
  "$event_id" "$payment_id" "$account_id" "$amount" "$currency")

# Keyed by paymentId, so redeliveries of one payment stay in order on one partition.
printf '%s|%s\n' "$payment_id" "$message" | docker compose exec -T kafka \
  /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic payments.settled \
  --property parse.key=true --property key.separator='|'

echo "sent: $message"
