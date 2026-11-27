<!-- generated -->
# axiz-core-crypto

AES-256-GCM, cifrado de sobre, RSA-OAEP, HMAC, hashes, hash de contraseñas, índice ciego y rotación de claves.

## Dependencia

```xml
<dependency>
  <groupId>pe.axiz</groupId>
  <artifactId>axiz-core-crypto</artifactId>
</dependency>
```

La versión la gestiona `axiz-bom` (los servicios heredan `axiz-parent-service`).

## Tipos principales

| Tipo | Propósito |
|---|---|
| `AsyncDataKeyProvider` | Generación y descifrado nativos de claves de sobre con CompletionStage. |
| `AsyncEnvelopeEncryptor` | Formato compartido con cachés de claves acotadas y agrupadas; la cancelación del último llamador llega al proveedor. |
| `AesGcm` | Cifrado autenticado AES-256-GCM con un IV aleatorio de 96 bits por mensaje y datos autenticados adicionales (AAD) opcionales. |
| `BlindIndex` | Índice ciego para buscar por igualdad en columnas cifradas: HMAC-SHA256 del valor normalizado con una clave dedicada (nunca la de cifrado), truncado para limitar la filtración. |
| `CryptoException` | Falló una operación criptográfica (clave incorrecta, datos alterados o proveedor no disponible). |
| `DataKeyProvider` | Puerto de claves de datos para cifrado de sobre (AWS KMS en producción). |
| `EnvelopeEncryptor` | Cifrado de sobre: los datos se cifran localmente con una clave de datos que a su vez cifra la clave maestra (KMS). |
| `FieldEncryptor` | Cifra valores individuales (campos de base de datos) con AES-256-GCM. |
| `Hashing` | Hashes SHA-2 y HMAC con verificación en tiempo constante. |
| `KeyProvider` | Proporciona claves simétricas por identificador. |
| `PasswordHasher` | Puerto de hash de contraseñas. |
| `Pbkdf2PasswordHasher` | Hash de contraseñas PBKDF2-HMAC-SHA256 disponible en cualquier JDK (compatible con FIPS). |
| `Rsa` | RSA con cifrado OAEP-SHA256 y firmas RSASSA-PSS-SHA256. |
| `StaticKeyProvider` | Proveedor sobre claves cargadas al arrancar (por ejemplo desde Secrets Manager). |

## Pruebas

Las pruebas unitarias están en `src/test/java` y documentan el comportamiento esperado; se ejecutan con `./mvnw -pl core/axiz-core-crypto verify`.
