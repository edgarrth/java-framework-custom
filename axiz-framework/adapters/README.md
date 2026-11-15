# adapters

Technology adapters implement core ports or the documented technology contracts. Services select them at runtime; their code imports ports and persistence kits instead of adapter implementations.

`axiz-adapter-database-auth-aws` implements the R2DBC kit's neutral `DatabasePasswordProvider`
contract. Its documented dependency exception keeps the RDS SDK in the adapter, outside the kit.

Build from the framework root using `./mvnw verify`. See the framework architecture guide for dependency rules.
