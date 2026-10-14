# Capas

| Capa | Carpeta | Artefactos | Regla |
|---|---|---|---|
| Plataforma | `platform/` | `axiz-parent-*`, `axiz-dependencies`, `axiz-bom` | Versiones y gates de build |
| 1 · core | `core/` | `axiz-core-<capacidad>[-<tecnología>]` | Solo JDK, SLF4J, JSpecify y Micrometer; las librerías de tecnología solo en los adaptadores |
| 2 · Spring | `spring/` | `axiz-<capacidad>-spring-boot-autoconfigure` + `-starter` | Solo cableado; un starter nunca depende de otro |
| 3 · frameworks | `frameworks/` | `axiz-framework-<estilo>-api` + `-spring-boot-starter` | Un estilo por servicio |
| Generación | `codegen/` | `axiz-codegen`, plantillas, arquetipos | Compartido por la CLI y el initializr |
| Calidad | `quality/` | `axiz-code-standards`, `axiz-archunit-rules`, pruebas de arquitectura | Reglas verificadas por máquina |

## Capa 1 · core

Java puro: la lógica de cada capacidad (errores, identificadores, cifrado, outbox, sagas...) sin
Spring ni Quarkus, para probarla con pruebas unitarias simples y poder cablearla con otro runtime.
Una capacidad expone **puertos** (`OutboxStore`, `IdempotencyStore`, `DistributedLock`,
`SecretResolver`...) y los módulos `-<tecnología>` los implementan: `axiz-core-lock-redis`,
`axiz-core-lock-dynamodb`, `axiz-core-secrets-aws`. El AWS SDK v2, Lettuce o los clientes de Kafka
solo aparecen en esos módulos.

## Capa 2 · Spring Boot

Cada capacidad tiene su auto-configuración y su starter. Las auto-configuraciones:

- se registran en `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  (nunca `spring.factories` ni escaneo de paquetes);
- declaran cada bean con `@ConditionalOnMissingBean`, así el servicio puede reemplazarlo;
- personalizan los beans de Boot con customizers en lugar de reemplazarlos (`ObjectMapper`,
  `RestClient.Builder`, `WebClient.Builder`);
- leen propiedades tipadas e inmutables bajo `axiz.<capacidad>.*`, validadas al arrancar;
- activan la variante servlet o reactiva con `@ConditionalOnWebApplication`.

Un servicio declara solo starters; las versiones vienen del BOM.

## Capa 3 · frameworks

Un estilo define cómo se estructura **todo** el servicio y se elige una vez. Cada estilo entrega un
módulo `-api` (contratos en Java puro), un starter que compone los starters de la capa 2, reglas
ArchUnit, plantillas de código y su guía. Ver [Estilos](../estilos/index.md).

## Cómo se verifica

- `maven-enforcer`: `bannedDependencies` por parent (la capa 1 prohíbe Spring, Quarkus, AWS SDK v1,
  librerías de GCP, Hazelcast, Vault, agentes de OpenTelemetry, Retrofit, Feign), convergencia de
  dependencias, versiones de plugins fijadas y README obligatorio por módulo.
- `axiz-architecture-tests`: la capa 1 no conoce Spring ni capas superiores, la capa 2 no conoce la
  3, los `-api` de los frameworks solo dependen de la capa 1, cada starter usa solo su capacidad (o
  la fundación) y no hay ciclos entre capacidades ni entre estilos.
- `axiz-archunit-rules`: las reglas de cada estilo, que cada servicio corre en su `ArchitectureTest`
  (ver [Reglas de arquitectura](../calidad/arquitectura.md)).

