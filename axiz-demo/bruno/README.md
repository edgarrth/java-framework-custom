# Bruno collection of the axiz demo

Requests against the local demo (`scripts/demo.sh up`). Open this folder in Bruno, select the
`local` environment and set its two secret variables (they are not stored in the repository):

| variable | local value |
|---|---|
| `password` | `local-only-ana` (Keycloak demo user `ana`, customer `C-1001`) |
| `clientSecret` | `local-only-payment-process` (client `payment-process`) |

Run `00 auth` first: it stores the customer token (`token`) and the machine token (`m2mToken`).
Every request has tests, so the whole collection can run with `bru run --env local`.
