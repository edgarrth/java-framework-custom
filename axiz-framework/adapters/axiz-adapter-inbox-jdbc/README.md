# axiz-adapter-inbox-jdbc

Inbox persistence and schema resources over JDBC.

Java/JDBC technology belongs here; business ports remain in `axiz-core-inbox`.
Use through the matching Spring capability, or provide a transaction-aware `ConnectionProvider`.

Run `./mvnw -pl adapters/axiz-adapter-inbox-jdbc -am verify` from the framework root.
