# coverage

`CapabilityCoverageTest` requires every catalog capability to have declared, existing code or
configuration evidence in a demo service or an executable example. Every referenced project must
be a Maven module included in the normal demo reactor. Example evidence is declared in its
`capabilities.yaml`; service evidence remains in `.axiz/project.yaml`. Paths cannot escape the
selected project. Unknown capabilities, missing declarations and stale evidence fail the gate.

The protected-data example executes against LocalStack and Valkey as part of the same build;
its presence in the evidence map alone does not mark its integration tests as successful.
