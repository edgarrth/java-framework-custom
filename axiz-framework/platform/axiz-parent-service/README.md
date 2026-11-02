# axiz-parent-service

Parent of every microservice: Spring Boot repackage, Jib image (non-root, immutable tag; the
`native` profile adds the GraalVM image `<tag>-native`), contract-first model generation from
`src/main/openapi/openapi.yaml`, the `quality` profile (SpotBugs/FindSecBugs, JaCoCo thresholds),
integration tests with Failsafe and the code standards of `axiz-code-standards`.

```xml
<parent>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-parent-service</artifactId>
  <version>2.0.0</version>
  <relativePath/>
</parent>
```

Properties a service may override: `axiz.image.registry`, `axiz.image.base`,
`axiz.image.native-base`, `axiz.image.tag`.

## Native compilation

With GraalVM JDK 25 selected, run `./mvnw -Pnative package native:compile`.
The native compiler reads the AOT classes from `target/classes`, while Spring Boot keeps the
executable JAR layout for JVM execution. The profile selects the JDK DRBG during image analysis;
reactive infrastructure checks that default and warms it at application startup.
