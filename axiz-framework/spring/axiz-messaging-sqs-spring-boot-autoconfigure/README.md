# axiz-messaging-sqs-spring-boot-autoconfigure

SQS integration for CloudEvents messaging.

This module owns the `pe.axiz.spring.messaging.sqs` API.

## Selection

Versions are managed by `axiz-bom`; applications inherit `axiz-parent-service`.

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-messaging-sqs-spring-boot-starter</artifactId>
</dependency>
```

The paired starter selects this provider. Application code uses the capability ports; do not
import auto-configuration classes into controllers, application services or domain models.

## Types

Public types declared by this module:

- `AxizMessagingSqsAutoConfiguration`
- `AxizMessagingSqsClientProperties`
- `AxizMessagingSqsProperties`
- `AxizNativeMessagingSqsAutoConfiguration`
- `SqsEventConsumer`

## Configuration

Owned property prefixes: `axiz.messaging.aws.*`, `axiz.messaging.sqs.*`.

## Catalog usage

```bash
axiz add messaging-sqs
```

Required capabilities: `messaging`.

The catalog supplies the following properties. Environment placeholders must resolve in the
service configuration; they are not credentials or provisioned infrastructure.

```yaml
axiz.messaging.sqs.wait-time: 20s
```

## Operations

Provide region, workload identity, TLS and least-privilege permissions for the selected AWS
resources. Provision production tables, queues, topics, keys and buckets through reviewed IaC.
LocalStack tests exercise behavior but do not attest deployed IAM or service durability.
Never place long-lived credentials in generated YAML.

For an explicit FIFO dead-letter queue, the consumer preserves the source message group and
uses the source SQS message ID as a stable deduplication ID across delivery retries. When a
standard source has no group, the destination uses `axiz-dead-letter`. Acknowledgement of the
original message follows acknowledgement of the dead-letter send. The executable
`axiz-demo/examples/sqs-consumer` recipe covers standard/FIFO consumption, transient retries,
explicit DLQ delivery and native redrive in LocalStack on both web stacks.

FIFO queues receive one message per poll, so a retry/defer cannot process a later message in the
same group from a prefetched batch. This conservative strategy preserves order at the cost of
batch throughput; standard queues still receive up to ten messages. Visibility renewal and
settlement coordinate per receipt, so a heartbeat cannot overwrite retry backoff or redrive.
Startup requires either an explicit dead-letter destination or a native SQS redrive policy.
Grant `sqs:GetQueueAttributes` when relying on native redrive. Missing policy or inaccessible
queue fails startup instead of creating an endless poison-message redelivery loop.

## Verification

From `axiz-framework`:

```bash
./mvnw -pl spring/axiz-messaging-sqs-spring-boot-autoconfigure -am test
```

The full acceptance gate is `./mvnw clean install -Pquality -Daxiz.it.requireDocker=true`.
Provider integration tests live in `quality/axiz-store-integration-tests` and require Docker.
