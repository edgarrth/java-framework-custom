# core · layer 1

Plain Java capabilities (`axiz-core-<capability>[-<technology>]`): JDK, SLF4J, JSpecify and
Micrometer only. Technology libraries (AWS SDK v2, Lettuce, Kafka clients) appear only in the
`-<technology>` adapter modules, never in the capability API. Spring and Quarkus are banned by
`axiz-parent-core`. Each module documents its main types in its own README.
