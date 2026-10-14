<!-- generated -->
# axiz-codegen

Generation engine shared by axiz-initializr and axiz-cli: OpenAPI model and lint, compatibility rules, templates and safe output.

## Dependency

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-codegen</artifactId>
</dependency>
```

The version is managed by `axiz-bom` (services inherit `axiz-parent-service`).

## Main types

| Type | Purpose |
|---|---|
| `ArtifactGenerator` | `axiz generate <type> <name>`: code coherent with the project's style, stack and persistence. |
| `ContractSync` | `axiz contract sync`: after editing `src/main/openapi/openapi.yaml`, generates the skeletons of new operations, proposes updated controllers (`.axiz-new` files next to the existing ones, never overwriting code), reports removed operations and checks backward compatibility against the snapshot of the last synced contract. |
| `GenerationException` | The request cannot be generated; carries every problem found, not only the first one. |
| `HttpClientGenerator` | `axiz generate http-client <name> --contract <openapi>`: a declarative client (`@HttpExchange` interface, one method per operation) of a third-party or downstream API, its models as records and its registration in the HTTP service group `<name>`, which the http-client capability configures from `axiz.http-client.clients.<name>.*` (base URL, timeouts, retries, circuit breaker, OAuth2 client credentials, observations). |
| `Names` | Naming conventions for generated code. |
| `ProjectGenerator` | Generates a complete project (service, library or starter) from a request: validation, context and templates. |
| `RequestValidator` | Compatibility rules of archetype × framework × stack × persistence × capabilities. |
| `Archetype` | Role of a service in the ecosystem and the defaults it preselects. |
| `Capability` | A capability that the initializr and `axiz add` can add to a service. |
| `Catalog` | Archetypes and capabilities shipped with the framework (`axiz/archetypes/catalog.yaml` and `axiz/archetypes/capabilities.yaml`). |
| `Framework` | Architectural style (layer 3) and the stacks it supports in v1. |
| `ProjectRequest` | What to generate. |
| `Stack` | Web stack of a service. |
| `ApiOperation` | One operation of the contract. |
| `ContractDiff` | Compatibility check between two versions of a contract, from the point of view of existing consumers: removed operations, new required inputs, removed or retyped response fields and narrowed enums are breaking; new operations, optional inputs and response fields are not. |
| `LintFinding` | A contract rule violation. |
| `OpenApiDocument` | Minimal, safe reader of OpenAPI 3.x documents (YAML or JSON): operations, tags, schemas and examples needed by the generators. |
| `OpenApiLinter` | Corporate contract rules, the same as the Spectral ruleset in axiz-code-standards, applied by the initializr and the CLI before generating code. |
| `SafeYaml` | YAML loader for untrusted contracts: safe constructor (no arbitrary types), alias and size limits, no duplicate keys, and dates kept as text so examples are not rewritten. |
| `JavaFormatter` | Formats generated Java sources with google-java-format (and removes unused imports) when it is on the classpath, so generated projects pass `spotless:check` from the first build. |
| `TemplateSet` | A set of Mustache templates described by `axiz/templates/<set>/manifest.yaml`. |
| `YamlProperties` | Spring Boot properties as YAML: flat keys (`a.b[0].c`) are nested into maps and lists and written in block style, so capability properties can be merged without duplicate root keys. |
| `CapabilityAdder` | `axiz add <capability>`: adds the starter and its adapters to the pom, its properties to `application.yaml` (and the local profile), the ConfigMap keys to the Helm values of every environment, its secrets to the ExternalSecret, the tables it needs as a new Flyway migration and the capability to the manifest. |
| `Migrations` | Flyway versions of new migrations: the next integer after the highest `V<n>__` file, so generated migrations continue the project's own numbering (`V1__init`, `V2__...`). |
| `PomEditor` | Minimal, formatting-preserving edits of a `pom.xml`: adds dependencies before the closing `</dependencies>` of the project (never inside dependencyManagement or profiles) and updates the parent version. |
| `ProjectManifest` | The `.axiz/project.yaml` manifest of a generated project: what the CLI needs to generate code coherent with the project (framework, stack, packages, capabilities and contract). |
| `GeneratedProject` | Generated files kept in memory until written. |
| `SafePaths` | Validation of generated relative paths and safe writes under a base directory: no absolute paths, no `..`, and no symbolic link followed below the base (a link planted in the destination, such as `src -> /etc`, would otherwise redirect the write outside it). |
| `SampleProjects` | Generates sample projects so CI can build them as a team would: `defaults` creates one project per archetype with its default style, stack and persistence; `all` creates every valid combination. |

## Tests

Unit tests live in `src/test/java` and document the expected behavior; run them with `./mvnw -pl codegen/axiz-codegen verify`.
