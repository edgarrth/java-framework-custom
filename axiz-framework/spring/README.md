# spring · layer 2

Spring Boot 4.1 wiring of each capability: `axiz-<capability>-spring-boot-autoconfigure` (beans
registered through `AutoConfiguration.imports`, all `@ConditionalOnMissingBean`, typed
`axiz.<capability>.*` properties) and `axiz-<capability>-spring-boot-starter` (the only dependency
services declare). Starters with web behavior activate the servlet or reactive variant on their own;
a starter never depends on another starter.
