<!-- GENERADO por scripts/sync_reference.py desde axiz-framework: no editar -->
# Remote tokenization consumer

Aporta `Tokenizer` en la pila imperativa y `ReactiveTokenizer` en la pila reactiva.
El consumidor no depende del motor de la bóveda, del SDK de DynamoDB, de claves criptográficas ni del perfil PAN.
Se selecciona `axiz-tokenization-spring-boot-starter` junto con las capacidades de cliente HTTP y de
seguridad de la aplicación. El servidor verifica la identidad JWT de forma independiente y autoriza cada operación.

```yaml
axiz:
  tokenization:
    mode: remote
    remote:
      client: token-vault
  http-client:
    clients:
      token-vault:
        base-url: https://tokens.example.internal
        auth:
          type: TOKEN_RELAY
        retry: NONE
        max-attempts: 1
        max-concurrent-calls: 32
        max-connections: 32
        connect-timeout: 2s
        read-timeout: 5s
        total-timeout: 10s
```

Para la identidad del servicio se usa `CLIENT_CREDENTIALS`, configurado mediante la capacidad de cliente
HTTP con una URI de token, un ID de cliente y una referencia de secreto explícitos. Las credenciales
reactivas requieren `AsyncSecretResolver`. La confianza TLS o el TLS mutuo usan el `ssl-bundle` del
cliente con nombre; un certificado de cliente complementa la autenticación JWT. Las API keys por sí solas
no pueden establecer los claims JWT del servidor. HTTP sin cifrar requiere la excepción de transporte
compartida explícita en `local`, `test` o `ci`.

`tokenize(type, value, domain, operationId)` envía el valor real mediante un escritor acotado dedicado. No
instala un serializador revelador en el mapper de la aplicación. El `SensitiveValue` del llamador se
cierra después de la finalización; con `Mono`, se retiene hasta la suscripción y la finalización. Cada
suscripción ejecuta su propia operación y lee su propio contexto de seguridad. A este cliente no se le
agregan filtros de logging de cuerpos, trazado de payloads ni caché. Los logs HTTP incorporados contienen
metadatos de la ruta.

`lookup` devuelve un token existente sin crear uno; `describe` devuelve los metadatos permitidos;
`detokenize` devuelve un `SensitiveValue` propio que el llamador debe cerrar. El texto cifrado y el índice
HMAC nunca salen de la bóveda. `delete` devuelve si se eliminó un registro: el protocolo HTTP usa 204 en
ambos casos y la cabecera booleana `X-Axiz-Token-Deleted`, sin cuerpo de respuesta.

Los reintentos y redirecciones automáticos están desactivados. `AXZ-TOKEN-0504` significa que la mutación
pudo haberse confirmado: se concilia antes de decidir repetirla. Las confirmaciones de mutación faltantes
o malformadas también devuelven esa incertidumbre. Un 404 genérico de un proxy no se convierte en un
lookup vacío. Los errores de token usan un catálogo cerrado y descartan los mensajes y causas remotos; 429
conserva un `Retry-After` acotado. Las respuestas se limitan a 64 KiB, exigen el tipo y el esquema de
campos esperados, y las respuestas exitosas exigen `no-store`. Las llamadas nativas tienen un plazo
global. Las lecturas imperativas comprueban el plazo global entre lecturas acotadas; una lectura
bloqueada en curso está acotada por el timeout de lectura del transporte configurado.

Para aplicaciones que usan client credentials, `axiz.tokenization.remote.async-facade=true` publica además
`AsyncTokenizer`. El reenvío del token debe usar `ReactiveTokenizer`, ya que un método `CompletionStage`
no tiene contexto de seguridad del suscriptor. Para esta fachada no se deduce ninguna identidad ligada al hilo.

Los arreglos temporales propios se limpian al completarse, fallar o cancelarse. Esta API no puede garantizar
el borrado de los strings inmutables de Java ni de las copias que retengan las implementaciones HTTP/TLS.

## Simulación local y verificación

`mode: local` requiere `axiz.config.environment: local|test|ci`. Su doble acotado y local a la instancia
acepta solo etiquetas como `synthetic:document_one`, con un máximo de 1024 asociaciones. Devuelve tokens
predecibles exclusivamente para pruebas y nunca acepta valores reales de PAN/DNI ni cifra. No actúa como
respaldo del modo remoto. Para ejercitar el validador PAN, el cifrado, la auditoría o la persistencia se
usa la bóveda real con fixtures numéricos sintéticos.

`RemoteTokenWireTest` ejercita bytes HTTP reales para PAN/DNI, identidades concurrentes de reenvío
reactivo, la ausencia de reintentos y redirecciones, los límites de respuesta y los canarios de log, con
BlockHound. `TokenRemoteResponseTest` comprueba campos malformados o desconocidos, UTF-8, no-store e
incertidumbre; `TokenClientConfigurationTest` comprueba la selección de pila y las políticas de arranque
inválidas. Estas pruebas de protocolo usan un fixture HTTP local; no establecen cumplimiento PCI ni
sustituyen las pruebas de integración del despliegue de la bóveda.
