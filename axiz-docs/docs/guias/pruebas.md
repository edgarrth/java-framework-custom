# Pruebas

Cada servicio nace con tres pruebas que no se borran:

| Prueba | Qué verifica |
|---|---|
| `ArchitectureTest` | reglas del estilo y reglas generales (`AxizArchitecture.verify(paquete, estilo)`) |
| `<Servicio>ApplicationTest` | el servicio arranca contra dependencias reales en contenedores: cableado, configuración, migraciones, health |
| `ContractExamplesTest` | cada ejemplo de request del contrato es una request válida para los modelos generados |

## Pirámide

- **Unitarias** sobre dominio, casos de uso y adaptadores, con fakes en memoria de los puertos
  (`InMemoryIdempotencyStore`, `InMemoryDistributedLock`, `InMemoryObjectStorage`,
  `InMemorySagaStateStore`, `InMemoryCrudService`...). Son la mayoría y corren en milisegundos.
- **De integración** (`*IT`, Failsafe) con Testcontainers y LocalStack:

```java
@SpringBootTest
@Import({PostgresTestConfiguration.class, LocalStackTestConfiguration.class})
class PaymentOrdersIT { ... }
```

- **De contrato** con los ejemplos del OpenAPI, y `HttpStubs` (WireMock) para dependencias HTTP.
- **Reactivas** con `@ExtendWith(BlockHoundExtension.class)`: cualquier llamada bloqueante en un
  hilo del event loop hace fallar la prueba.
- `RequestContexts` arma el contexto de una request (correlación, canal, tenant, principal) para
  probar código que lo lee.

## Umbrales

| Qué | Umbral |
|---|---|
| Cobertura (JaCoCo, por módulo) | al menos 80 % de líneas y 70 % de ramas (`-Pquality`) |
| Mutation testing (PIT, capa 1) | al menos 81 % de mutantes eliminados |
| Nativo | compilación y pruebas nativas del demo y de un servicio por arquetipo, cada noche |

Ninguna prueba puede afirmar un comportamiento defectuoso para subir cobertura: una prueba
documenta el comportamiento esperado.

## End to end

El demo trae una colección de **Bruno** (`bruno/`) con pruebas de cada servicio y un smoke test
que paga un beneficiario por la API de canal y espera el estado `COMPLETED`
(`scripts/demo.sh smoke`), que el workflow nocturno `e2e` corre en kind.
