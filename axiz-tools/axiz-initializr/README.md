# axiz-initializr

Web generator and REST API of the axiz platform, behind the corporate SSO. The UI and the API
(`/v1/metadata`, `/v1/projects`, `/v1/projects/validate`, `/v1/contracts/lint`,
`/v1/projects/github`) generate services with the same engine as the CLI (`pe.axiz:axiz-codegen`).

- Contract: `docs/openapi.yaml`.
- Security: OIDC login for the UI (registration `sso`, the only one that signs in), bearer JWT for
  the CLI and automations; GitHub repositories are created with the user's own OAuth authorization
  (registration `github`, callback `/authorized/github`). A GitHub token is held only in the
  authenticated HTTP session for at most five minutes, removed after local validation and before each remote publication attempt
  (success or remote error), and lost on logout/session expiry. SSO access/refresh clients are not retained. Without that
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
java -jar axiz-initializr/target/axiz-initializr-2.0.0.jar --spring.profiles.active=local
# http://localhost:8090, user ana / local-only-ana
```

Creating GitHub repositories also needs a GitHub OAuth App with the callback
`http://localhost:8090/authorized/github` (`AXIZ_GITHUB_CLIENT_ID`, `AXIZ_GITHUB_CLIENT_SECRET`,
optionally `AXIZ_GITHUB_ORGANIZATION`). The project is pushed as one commit, `feat: generate <name> with axiz
initializr`, a Conventional Commit that opens the changelog; the first release is `0.1.0`, the
`initial-version` of the generated `release-please-config.json`.

## Publication and recovery

Send an `Idempotency-Key` UUID with `POST /v1/projects/github`. The same authenticated subject,
operation, repository and exact generated project can resume a partial publication. Keep the same
request and framework version across retries; changed output fails closed with 409. The UI keeps
that operation and request in tab session storage until success. It contains project/contract text,
never OAuth tokens: do not put production data or secrets into generation requests.

GitHub authorization requires both `repo` and `workflow`; the publisher verifies granted scopes
before any write because generated projects include workflows. Authorize again after a failed
attempt or a consumed/expired grant. This removes local credentials; it does not revoke the
provider-side OAuth grant, which the user can revoke in GitHub settings.

A shared deployment secret `axiz.initializr.publication-key` (or
`AXIZ_INITIALIZR_PUBLICATION_KEY`) is required at startup: Base64-encoded random key of at least
32 bytes, mounted through the secret store, never a ConfigMap. Every replica needs the same key.
An HMAC marker in the private repository description binds the operation, subject, repository and
project digest. Do not remove it while recovery is needed; rotating the key requires draining or
reconciling pending operations. It is not a GitHub credential. GitHub descriptions are limited to
160 characters here to leave room for the marker (ZIP generation still accepts 300 characters).

201 confirms the branch. A 503 includes `operationId`, `phase` and `repository` for retry with the
same operation after authorization. A 409 means identity/content conflict and needs inspection.
Recovery verifies the marker and ancestry, never force-pushes, overwrites another user's commit
or deletes a repository. Lost acknowledgements are covered at creation, tree, commit and ref stages.
The generated project is deterministic only for the same generator version and inputs.

## Sessions and quotas

The application uses process-local servlet sessions. Configure browser affinity at the ingress
when it targets pods directly; the chart uses ClientIP affinity for traffic through its Service.
A pod restart intentionally requires SSO/GitHub authorization again. Do not enable a replicated
session store without implementing atomic one-time consumption across replicas as well.
The bearer API is stateless; GitHub publication specifically requires the browser session grant.

Redis quotas use `axiz-security-redis-spring-boot-starter`, shared by every replica, with the fixed
namespace `axiz:initializr:quota:v1:`. Set `spring.data.redis.host/port/username/password` through the
normal config/secret boundary. TLS and hostname verification are required outside local/test/CI.
Connect/command timeouts default to one second. Local execution also needs Redis/Valkey (the demo
provides it); local plaintext is governed by the framework's local environment policy.

`axiz.initializr.limits` defaults to a one-minute window, 10 generations, 2 publication attempts,
20 validations per authenticated subject, and 4 concurrent expensive requests per replica.
Client identity headers cannot change that subject. 429 includes `Retry-After`; Redis errors deny
work instead of allowing it. An outage may be reported as a denied quota by the shared limiter.
No process-local fallback is configured in production. Tune budgets for the deployed resources.

## Verification

Run `mvn -Pquality -pl axiz-initializr -am test` in `axiz-tools` after installing the framework.
Tests cover scope preflight, phase-by-phase lost responses, conflict/replay rejection, session
expiry/consumption/logout, masked errors, per-subject quotas and bounded generation concurrency.
The GitHub tests use a stateful HTTP fake and create no real repositories.

## Transport and publication audit

Use a classic GitHub OAuth App with `repo` and `workflow` grants. Tokens without the OAuth scope
header (including GitHub App installation tokens) are not supported by this browser flow.
The trusted deployment configuration selects the HTTPS API origin (`github-api` for Enterprise);
request input cannot override it. Credentials, query and fragment are rejected in that URL.
The dedicated client never follows redirects, connects within 3 seconds and bounds each request
at 10 seconds. Initial branch creation gets at most three read attempts on 404/409, with 200/400ms
backoff; remote writes are not retried blindly. A 401/403 in scope preflight requests reauthorization.

Publication emits `project.publish` audit events with a SHA-256 pseudonym of the authenticated
subject, repository, operation ID, phase and outcome. It never audits the OAuth token or generated
content. `project.publish.requested` records accepted intent with unknown operation outcome;
only `CONFIRMED` records a committed remote outcome. Initializr uses the framework's SYNC delivery,
without an in-memory async queue. This is synchronous handoff, not a durable journal or an atomic
transaction with GitHub. The framework's confirmed publisher reports unacknowledged sink delivery
through its fallback and metrics; a returned `publish()` does not prove durable storage. A publisher
exception before the remote work aborts it, while one after a remote write cannot replace that
business outcome. Configure and monitor a persistent collector/sink and its delivery metrics.
Logging-only defaults do not provide crash-proof storage or PCI compliance certification.

Outside the local profile `AXIZ_SSO_ISSUER` is mandatory. Local execution also needs a private
random `AXIZ_INITIALIZR_PUBLICATION_KEY` of at least 32 bytes encoded in Base64; the service refuses
to start without it. Use your local secret manager or `openssl rand -base64 32`, never a checked-in
key. Do not regenerate the key while partial publications need recovery.

Quotas match decoded MVC segments and use canonical operation keys, so percent-encoded equivalent
paths share the same quota. OAuth consumption uses the servlet session mutex across request
facades. If tab storage cannot retain a large generated-project request, the UI reports it before
publication and asks to reduce the contract size or free tab storage.

### Rechazos de GitHub y lectura de auditoría

Un 401 exige renovar la autorización delegada. Un 403 de permisos detiene la publicación con
`CONFLICT`; no invita a repetir una operación que la organización prohíbe. Los 403 que informan
`Retry-After` o `X-RateLimit-Remaining: 0`, y los 429, dejan el contenido pendiente para un reintento
posterior autorizado. No se repiten mutaciones automáticamente dentro de la llamada.

La acción `project.publish.requested` con outcome `SUCCESS` significa que se aceptó y auditó la
solicitud, no que GitHub publicó el proyecto: su `operationOutcome` permanece `UNKNOWN`. Sólo
`project.publish` con fase `CONFIRMED` declara el resultado de publicación `COMMITTED`.
El sujeto SHA-256 es un seudónimo estable, no anonimización ni una protección contra diccionarios.
El operador debe conservar un directorio de identidades autorizado y acceso restringido para
correlacionar ese seudónimo con el principal autenticado cuando investigue eventos de auditoría.

### Request quotas and concurrency

The per-subject/per-operation quota counts authenticated attempts, including attempts rejected
because all generation workers are busy. It is an abuse-control budget, not a billing counter for
accepted jobs. The separate semaphore bounds active work in each replica. A caller receiving 429
must respect Retry-After instead of retrying freely while the service is saturated.
