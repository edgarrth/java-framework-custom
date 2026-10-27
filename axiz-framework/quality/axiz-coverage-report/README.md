# axiz-coverage-report

Aggregates the JaCoCo reports of every module with production code (`./mvnw -Pquality verify`,
report in `target/site/jacoco-aggregate`). Not published.

- Every module with code is a `provided` dependency: JaCoCo's `report-aggregate` takes classes and
  sources only from compile, runtime and provided dependencies. A `test` dependency adds its
  execution data and nothing to measure it against, which produced an empty report.
- `.github/scripts/check-coverage-aggregate.py .` (framework CI) fails when a module with code is
  missing or has another scope; with `-Pquality` the build fails when the report holds no classes.
  `-DskipTests` skips that check, since nothing ran under the agent.
