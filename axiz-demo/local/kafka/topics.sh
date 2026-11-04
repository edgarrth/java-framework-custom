#!/bin/bash
# Topics of the demo (Terraform creates them in MSK): order events, their dead-letter topics and
# the audit trail of payment-orders (topic "audit").
set -euo pipefail
k=/opt/kafka/bin/kafka-topics.sh
for t in payments payments.dlq audit; do
  $k --bootstrap-server kafka:29092 --create --if-not-exists --topic "$t" --partitions 3 --replication-factor 1
done
$k --bootstrap-server kafka:29092 --list
