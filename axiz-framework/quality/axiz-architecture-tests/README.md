# axiz-architecture-tests

ArchUnit tests of the framework itself (`FrameworkArchitectureTest`): layer 1 is plain Java (no
Spring, Quarkus or upper layers), layer 2 never depends on layer 3, the `-api` modules of the
frameworks depend only on layer 1, each starter uses only its own capability or the foundation
starter, and there are no cycles between core capabilities or between styles. Not published.
