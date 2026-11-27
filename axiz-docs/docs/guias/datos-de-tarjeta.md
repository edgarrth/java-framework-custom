# Datos de tarjeta

Usar los tipos de `axiz-core-carddata` para expresar dónde se permite procesar cada dato.
Enmascarar, cifrar y tokenizar tienen responsabilidades distintas. El dato de autenticación
sensible no se vuelve almacenable por aplicarle cifrado o tokenización.

| Tipo | Contrato |
| --- | --- |
| `Pan` | Valida dígitos y Luhn; representación habitual enmascarada; `reveal()` explícito |
| `TruncatedPan` | Representación truncada, sin recuperar el PAN original |
| `CardExpiry` | Mes/año; vencimiento contra `YearMonth` explícito |
| `CardVerificationCode` | CVV/CVC de vida corta; acceso con `use`, cierre obligatorio |
| `Pin` | Valor de vida corta y cerrable; nunca persistencia ni diagnóstico |
| `SensitiveAuthenticationData` | Marcador que impide tratar esos valores como datos persistibles |

`Pan.brand()` es una pista de presentación, no una tabla vigente para enrutar pagos. El
consumidor aporta un `CardSchemeResolver` gobernado por su fuente de rangos. La validación Luhn
no demuestra existencia de cuenta, titularidad ni autorización.

```java
Pan pan = Pan.parse("4111111111111111"); // exclusivamente un ejemplo sintético
String display = pan.masked();
try (var code = CardVerificationCode.parse("987")) {
  processor.authorize(pan, code);
}
```

Los serializers ordinarios de tarjetas enmascaran. Solo el cliente HTTP saliente seleccionado
con `reveal-card-data: true` usa un mapper revelador privado. No registrar un serializer revelador
en el mapper global de la aplicación, en eventos, auditoría o respuestas. Seleccionar el cliente
no autoriza un destino: mantener TLS, autenticación, configuración controlada y prohibición de
captura de cuerpos en proxies/APM. El cliente de tokenización usa su propio protocolo acotado.

## Alcance de arquitectura

`CardDataScope.NONE` prohíbe los tipos completos; `EDGE` permite la entrada/salida técnica
controlada y `VAULT` corresponde al servicio de custodia. La selección es explícita en las
reglas del servicio, independiente de `TokenizationScope.NONE`, `CLIENT` o `VAULT`.
Los arquetipos y las estructuras determinan los paquetes válidos; las excepciones no vuelven
persistibles CVV/PIN ni permiten PAN en eventos o dominio general.

ArchUnit inspecciona los grafos de entidades y los mapeos efectivos JPA/DynamoDB, incluidos
getters y conversores. No basta con renombrar una propiedad o ocultarla dentro de otro objeto.
Las reglas estáticas tienen límites ante reflexión, strings y agentes externos; se complementan
con las pruebas de ejecución y revisión del modelo.

## Evitar filtraciones

`SelfMasking` controla representaciones de diagnóstico; las claves sensibles y el detector PAN
son defensas adicionales. El detector no cubre representaciones arbitrarias ni sustituye tipos,
clasificación o autorización. No concatenar valores crudos en mensajes de error, nombres de
métrica, MDC o URLs. No registrar `Throwable` con mensajes remotos sin sanear.

La protección independiente de DNI u otros campos está en [Protección de campos](proteccion-de-campos.md).
La custodia central de PAN y otros tipos está en [Tokenización](tokenizacion.md). El cifrado del
almacenamiento, la gestión de claves, la segregación de permisos, el monitoreo y las pruebas del
despliegue siguen siendo necesarios; estos tipos no acreditan cumplimiento PCI DSS.

Para ejecutar la validación local de tipos, serialización y salida seleccionada:

```sh
mvn -Pquality -pl spring/axiz-sensitive-spring-boot-autoconfigure,spring/axiz-http-client-spring-boot-autoconfigure \
  -am test
```
