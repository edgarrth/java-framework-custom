# frameworks: layer 3

One architectural style per service, chosen when it is created: `layered`, `hexagonal`, `cqrs`,
`eda`, `bff` and `batch`. Each style ships an `-api` module (plain Java contracts), a Spring Boot
starter that composes the layer 2 starters, ArchUnit rules (in `axiz-archunit-rules`), generation
templates and a guide.

CQRS builds on the hexagonal API and starter; this is intentional reuse within layer 3.

## Hexagonal dependency policy

The domain admits the JDK, JSpecify annotations and the Axiz value/contract packages explicitly
listed in `HexagonalRules` (commons, domain, money, errors, id, identifiers, sensitive and time).
It never depends on Spring or technology adapters. Application code uses owned ports and Axiz
contracts; SLF4J and Reactor publisher contracts are supported for logging and reactive execution.
Configuration is an outer composition root. Inbound and outbound adapters cannot depend on one
another. Port signatures cannot expose HTTP models, generated clients or persistence entities.
Architecture checks also follow dependencies through Axiz core types to detect hidden infrastructure.

Generated hexagonal and CQRS services own plain application models in `application.model`. HTTP models stay
in `api.model`; conversion occurs in the REST adapter. Contract synchronization creates new
application models and proposes changes to existing models for review. The generator supports
explicit object schemas and Java-compatible enums; composed schemas must first be expressed as
explicit application contracts. Schema validation remains at the HTTP boundary.

CQRS handlers and access policies are registered by the outer `HandlerConfiguration`, keeping
Spring stereotypes out of the application layer.
