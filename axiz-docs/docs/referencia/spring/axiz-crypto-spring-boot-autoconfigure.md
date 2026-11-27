<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# axiz-crypto-spring-boot-autoconfigure

Cifrado de campos con rotación de claves, cifrado de sobre con KMS, índice ciego, hash de contraseñas y JOSE (autoconfiguración).

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-crypto-spring-boot-autoconfigure</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `ReactiveEnvelopeEncryption` | Cifrado de sobre KMS nativo y diferido para aplicaciones reactivas; sin respaldo en un cliente síncrono. |
| `AxizCryptoAutoConfiguration` | Cifrado de campos con claves del almacén de secretos, índices ciegos y hash de contraseñas (Argon2id por defecto). |
| `AxizCryptoProperties` | Criptografía (`axiz.crypto.*`). |
| `SpringPasswordHasher` | Argon2id y BCrypt mediante los encoders de Spring Security. |

Tipos relacionados: `AxizCryptoKmsAutoConfiguration` están en `axiz-crypto-kms-spring-boot-autoconfigure`.

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl spring/axiz-crypto-spring-boot-autoconfigure verify`.
