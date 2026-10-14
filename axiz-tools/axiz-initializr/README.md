# axiz-initializr

Web generator and REST API of the axiz platform, behind the corporate SSO. The UI and the API
(`/v1/metadata`, `/v1/projects`, `/v1/projects/validate`, `/v1/contracts/lint`,
`/v1/projects/github`) generate services with the same engine as the CLI (`pe.axiz:axiz-codegen`).

- Contract: `docs/openapi.yaml`.
- Security: OIDC login for the UI, bearer JWT for the CLI and automations; GitHub repositories
  are created with the user's own OAuth authorization and tokens are never stored.
- Generation happens in memory with size limits; every generation is audited and counted
  (`axiz.initializr.generations`).
- Deployment: chart in `deploy/helm`, configuration in ConfigMaps, client secrets from the
  ExternalSecret.

```bash
mvn -pl axiz-initializr -am spring-boot:run   # needs an OIDC issuer (see application.yaml)
```
