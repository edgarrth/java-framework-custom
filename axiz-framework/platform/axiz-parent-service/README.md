# axiz-parent-service

Parent of every microservice: Spring Boot repackage, Jib image (non-root, immutable tag; the
`native` profile adds the GraalVM image `<tag>-native`), contract-first model generation from
`src/main/openapi/openapi.yaml`, the `quality` profile (SpotBugs/FindSecBugs, JaCoCo thresholds),
integration tests with Failsafe and the code standards of `axiz-code-standards`.

```xml
<parent>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-parent-service</artifactId>
  <version>1.2.0</version>
  <relativePath/>
</parent>
```

Properties a service may override: `axiz.image.registry`, `axiz.image.base`,
`axiz.image.native-base`, `axiz.image.tag`.
