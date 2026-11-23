# Colección Bruno de la demo de axiz

Peticiones contra la demo local (`scripts/demo.sh up`). Se abre esta carpeta en Bruno, se selecciona el
ambiente `local` y se configuran sus dos variables secretas (no se guardan en el repositorio):

| variable | valor local |
|---|---|
| `password` | `local-only-ana` (usuario demo de Keycloak `ana`, cliente `C-1001`) |
| `clientSecret` | `local-only-payment-process` (cliente `payment-process`) |

Primero se ejecuta `00 auth`: guarda el token del cliente (`token`) y el token de máquina (`m2mToken`).
Cada petición tiene pruebas, por lo que toda la colección puede ejecutarse con `bru run --env local`.
