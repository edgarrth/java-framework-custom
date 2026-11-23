#!/bin/bash
# Tópicos de la demo local; producción aprovisiona sus tópicos de forma independiente.
set -euo pipefail
k=/opt/kafka/bin/kafka-topics.sh
bootstrap=${KAFKA_BOOTSTRAP_SERVERS:-kafka:29092}
for t in payments payments.dlq audit; do
  $k --bootstrap-server "$bootstrap" --create --if-not-exists --topic "$t" --partitions 3 --replication-factor 1
done
$k --bootstrap-server "$bootstrap" --list
