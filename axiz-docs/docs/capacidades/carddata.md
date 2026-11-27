<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# carddata

Tipos semánticos de PAN y de datos de autenticación transitorios; enmascarados por defecto, sin bóveda

```bash
axiz add carddata
```

|  |  |
|---|---|
| Starter | `pe.axiz:axiz-core-carddata` |
| Stacks del catálogo | servlet, reactive |
| Agrega también | [`sensitive`](sensitive.md) |
| Incluida por defecto en | ningún arquetipo |

## Uso

```java
try (Pan pan = Pan.of(value)) { /* only reveal at an authorized integration boundary */ }
```
