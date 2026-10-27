# axiz-initializr

Web generator and REST API of the axiz platform, behind the corporate SSO. The UI and the API
(`/v1/metadata`, `/v1/projects`, `/v1/projects/validate`, `/v1/contracts/lint`,
`/v1/projects/github`) generate services with the same engine as the CLI (`pe.axiz:axiz-codegen`).

- Contract: `docs/openapi.yaml`.
- Security: OIDC login for the UI (registration `sso`, the only one that signs in), bearer JWT for
  the CLI and automations; GitHub repositories are created with the user's own OAuth authorization
  (registration `github`, callback `/authorized/github`) and tokens are never stored. Without that
  authorization `POST /v1/projects/github` answers 403 with the `authorize` URL; the API never
  redirects. Bearer tokens must come from the SSO issuer and carry the audience
  `axiz.initializr.audience` (`axiz-initializr` by default, `AXIZ_INITIALIZR_AUDIENCE`): a token of
  the same identity provider issued for another API gets 401. In the identity provider, the client
  that the CLI uses needs an audience mapper with that value.
- Generation happens in memory with size limits; every generation is audited and counted
  (`axiz.initializr.generations`).
- Deployment: chart in `deploy/helm`, configuration in ConfigMaps, client secrets from the
  ExternalSecret.

Local run against the Keycloak of the demo (`axiz-demo`: `scripts/demo.sh up`; realm `axiz`,
client `axiz-initializr`), on port 8090:

```bash
./mvnw -pl axiz-initializr -am clean package -DskipTests
java -jar axiz-initializr/target/axiz-initializr-1.0.0.jar --spring.profiles.active=local
# http://localhost:8090, user ana / local-only-ana
```

Creating GitHub repositories also needs a GitHub OAuth App with the callback
`http://localhost:8090/authorized/github` (`AXIZ_GITHUB_CLIENT_ID`, `AXIZ_GITHUB_CLIENT_SECRET`,
optionally `AXIZ_GITHUB_ORGANIZATION`). The project is pushed as one commit, `feat: generate <name> with axiz
initializr`, a Conventional Commit that opens the changelog; the first release is `0.1.0`, the
`initial-version` of the generated `release-please-config.json`.
