# Coverage of framework capabilities

The [consumer matrix](CAPABILITIES.md) maps every catalog capability and required provider/stack
variant to a runnable example. The demo remains a consumer: no demo business logic belongs to the framework.

`CapabilityCoverageTest` requires a declared capability, a source file and an exact JUnit
`fully.qualified.Class#method`. The corresponding Surefire or Failsafe XML must contain a successful,
non-skipped test case and be newer than the consumer's sources, resources and POM. Missing,
failed, skipped or stale execution fails the build. `ExecutionEvidenceTest` verifies those
rejection paths, including unsafe XML. Each successful operation publishes its capability and
actual variants with JUnit `TestReporter`; standard Open Test Reporting binds those entries to
the same successful class/method. Catalog starters and provider dependencies must exist on that
test's recorded runtime classpath. Service stack labels must match the consumer manifest.
Each plugin writes its event report in its own directory, so integration tests cannot overwrite
unit-test evidence. Missing or ambiguous reports fail the gate. Variant evidence is published
after each provider/stack operation has checked its result.

The gate also checks the resolved framework JARs against the reports and, when an adjacent
framework checkout exists, its source/resources/POM against those JARs. Install changed framework
modules and rerun the demo: old reports or an older installed framework cannot prove a new change.
These are reproducibility checks for local/CI builds, not signed tamper-proof attestations.
A comment, dependency or configuration property alone cannot satisfy this gate.

The coverage module depends on each consumer's POM for reactor ordering, with all transitive
dependencies excluded. It runs after the services and examples even in a parallel Maven build.
Run the complete reactor from `axiz-demo`:

```bash
./mvnw clean verify -Pquality -Daxiz.it.requireDocker=true
```

`coverage.yaml` names the evidence and its scope:

- `integration`: actual infrastructure or transport and checked effects/results.
- `consumer-contract`: executable consumer behavior with a controlled collaborator or protocol fixture.
- `local-identity-contract`: local IAM signing/configuration checks; these do not prove AWS authorization.

`variants.yaml` adds required provider operations. Both stack and persistence alternatives are read
directly from the framework catalog, so adding a new catalog option cannot bypass the gate by
omitting it from the demo's variant file. These checks prove the named examples, not every possible
combination of all framework options or PCI DSS certification.

`provider-recipes` demonstrates alternate providers and cross-cutting capabilities through the
public starters. `protected-data` demonstrates both native asynchronous and imperative vaults,
independent encryption/index operations, administrative rotation and retention. Services retain
the payment business flows and their concurrency/integrity tests. All examples use local
containers; IAM recipes never contact remote AWS.

`sqs-consumer` supplies an independent, minimal starter classpath. Its automatic discovery test
executes standard and FIFO queues, retries, explicit DLQ and broker redrive on both stacks. These
operations are mandatory entries in `variants.yaml`; publisher-only evidence cannot replace them.
