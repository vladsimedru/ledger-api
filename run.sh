#!/usr/bin/env bash
# Builds the service, runs the tests (against real MySQL and Kafka via Testcontainers),
# and starts everything with Docker Compose. Needs only Docker, Git and a JDK 17.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> Building and testing"
./mvnw -B clean verify

echo "==> Building the image"
docker build -t ledger-api:local .

echo "==> Starting MySQL, Kafka, two ledger instances and the gateway"
docker compose up -d --wait

cat <<EOF

Ledger is up:
  API (load-balanced over 2 instances)  http://localhost:${LEDGER_PORT:-8080}
  Instance 1 / 2                        http://localhost:8081  http://localhost:8082
  Kafka UI (topics, messages, DLT)      http://localhost:${KAFKA_UI_PORT:-8085}
  phpMyAdmin (MySQL tables)             http://localhost:${PHPMYADMIN_PORT:-8086}

Send a sample payment-settled message:
  ./scripts/send-payment.sh <merchantAccountId> 125.50 EUR
Watch announced transactions:
  ./scripts/watch-transactions.sh
Stop everything:
  docker compose down -v
EOF
