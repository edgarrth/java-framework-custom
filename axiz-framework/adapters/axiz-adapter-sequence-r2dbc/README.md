# axiz-adapter-sequence-r2dbc

Native PostgreSQL counter reservations for reactive services. Implements `ReactiveSequenceStore`
with Reactor cancellation and context propagation. Each reservation uses its own connection and
transaction, commits before emitting the value, and survives a later business rollback.

This is a technology adapter, not a general persistence kit. Consumers select it at runtime through
the sequence capability with R2DBC; the generic sequence starter does not impose this backend.

Run `./mvnw --bootstrap -pl adapters/axiz-adapter-sequence-r2dbc -am verify -Daxiz.it.requireDocker=true`
from the framework root. Public API is reviewed with every other published module by the v2 signature gate.