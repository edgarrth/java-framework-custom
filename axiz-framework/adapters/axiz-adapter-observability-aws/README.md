<!-- generated -->
# axiz-adapter-observability-aws

AWS callback admission remains bounded and rejects saturation without caller-runs. Shared settings
are `axiz.aws.completion.workers` (default 2, range 1..64) and
`axiz.aws.completion.queue-capacity` (default 256, range 1..65536). Each capability owns its executor
and closes it through `AsyncAwsClients`; existing constructors keep their defaults.

When Micrometer core is present, framework-owned pools register `axiz.transport.pending`,
`axiz.transport.queued`, `axiz.transport.active`, `axiz.transport.rejected` and
`axiz.transport.latency` in the global registry, tagged by their stable capability resource name.
Pools sharing a name aggregate their gauges; closing the last owner removes its meters.
Micrometer core remains optional at runtime.

AWS SDK v2 execution interceptor producing Micrometer observations.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-adapter-observability-aws</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `AwsClients` | Applies the framework conventions to any AWS SDK v2 client builder: observation interceptor, optional region and endpoint override (LocalStack in local runs and tests). |
| `AsyncAwsClients` | Owns native Netty clients, warmed credentials and bounded completion resources. |
| `WarmedAwsCredentials` | Startup snapshot and background renewal; request paths never fetch credentials synchronously. |
| `ObservationExecutionInterceptor` | AWS SDK v2 interceptor that wraps every call (DynamoDB, SQS, SNS, S3, KMS, Secrets Manager...) in a Micrometer observation: a CLIENT span with service and operation and the `axiz.aws.sdk` timer. |

The interceptor uses a Micrometer `SenderContext` with `Kind.CLIENT`, allowing the configured
tracing handler to inject propagation headers. It adds those headers before SigV4 signing.
Each execution owns its carrier. The SDK's client-type attribute determines scope handling:
synchronous calls open and close their scope on the caller; asynchronous calls never retain an
open scope across completion threads. This also applies when registering the interceptor directly
on a custom SDK client. Native Reactor facades use Foundation's `ReactiveStages` to propagate
subscriber context through submissions, chained native operations and coalesced cache listeners.

Native completion workers are started at startup. Submission uses a lock-free queue with bounded
admission; exhaustion rejects new tasks rather than growing memory or running work on the caller.
Admission uses the common JDK `BoundedCallbackExecutor`; AWS retains its own limits and thread
names. The SDK's standard SigV4 signer handles the finite in-memory payloads of DynamoDB, KMS,
Secrets Manager, SSM, SQS and SNS, avoiding the shared digest-pool contention tracked in
[AWS issue 6590](https://github.com/aws/aws-sdk-java-v2/issues/6590). Streaming S3 keeps its native
signer. Payload signing and algorithms remain enabled and unchanged.
DNS resolution is non-blocking; this adapter declares `netty-resolver-dns` itself and does not
rely on a Spring/Reactor capability to supply it. AWS channels use an unpooled allocator: Netty's
adaptive allocator can park event loops while expanding magazines under concurrent writes.
Transport concurrency and streaming demand bound the number of live chunks.

XML provider discovery/JDK policy configuration and TLS trust material load during startup.
The transport receives a snapshot of the strict JDK trust managers; certificate and hostname
validation remain enabled. Rebuild the client when changing trust material. A cold TLS test
removes the store file after client construction and still establishes fresh connections, while
untrusted certificates and a wrong hostname fail. Auxiliary presigners use the non-owning,
snapshot-only `credentialsProvider()` view.

 Connection acquisition, connect, TLS negotiation, read and write
have explicit bounds. Connections expire after 5 minutes or 30 seconds idle; configure
`axiz.aws.async.connection-ttl` and `axiz.aws.async.max-idle-time` with positive durations when
needed. Region selection never falls back to metadata lookup on a request.

BlockHound marks completion callbacks as non-blocking. The JDK executor's idle `getTask` wait and
Netty's channel-pool shutdown are the precise lifecycle boundaries allowed; application sleeps
and queue waits on those same workers remain errors. Transport shutdown errors are checked in
the real DynamoDB, S3 and TLS integrations instead of being accepted as successful test runs.

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl adapters/axiz-adapter-observability-aws verify`.
