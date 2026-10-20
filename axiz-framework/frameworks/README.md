# frameworks: layer 3

One architectural style per service, chosen when it is created: `layered`, `hexagonal`, `cqrs`,
`eda`, `bff` and `batch`. Each style ships an `-api` module (plain Java contracts), a Spring Boot
starter that composes the layer 2 starters, ArchUnit rules (in `axiz-archunit-rules`), generation
templates and a guide.
