# axiz-adapter-saga-jdbc

Saga persistence and schema resources over JDBC.

Java/JDBC technology belongs here; business ports remain in `axiz-core-saga`.
Use through the matching Spring capability, or provide a transaction-aware `ConnectionProvider`.

Run `./mvnw -pl adapters/axiz-adapter-saga-jdbc -am verify` from the framework root.

## Schema upgrade and transaction boundary

Before running the new orchestrator, migrate existing `axiz_saga` tables with nullable
`execution_owner VARCHAR(36)` and `execution_until TIMESTAMP WITH TIME ZONE` columns. Bootstrap
schemas include them; existing databases need a new migration, never an edited Flyway history.
The demo supplies `V2__saga_execution_ownership.sql`. Old rows have no owner and can be recovered.

State and lease writes must commit before a participant is called. The adapter rejects connections
bound to an active caller transaction. Supply an independent auto-commit connection provider or
invoke the orchestrator outside that transaction. Participant transactions remain local to their
services. `CURRENT_TIMESTAMP` is read from the database for lease acquisition/renewal; live owner
and version predicates fence state updates. Run the schema migration before upgrading all workers.
